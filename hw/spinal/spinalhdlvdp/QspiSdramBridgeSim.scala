package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 3 host-upload repair (#9360 / audit PASS #9362) — burst-under-
  * gating verification for `QspiSdramBridge`'s 16-byte FIFO replacement
  * of the prior single-byte latch.
  *
  * Drives a 40-byte burst (one Task 3 plane row's worth) at the bench
  * QSPI rate while toggling `allowUpload` low/high to mimic active
  * video / H-blank gating. Asserts that all 40 bytes land at SDRAM
  * `addrInit + n` in order with no drops.
  *
  * The pre-fix bridge would have dropped ~12 of every 13 bytes that
  * arrived during the active-video low window because of the single-byte
  * latch overrun. The 16-byte FIFO must absorb the per-window backlog
  * and drain it during the next blanking window.
  */
object QspiSdramBridgeSim extends App {

  Config.sim.compile(QspiSdramBridge()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Defaults — quiescent.
    dut.io.headerValid #= false
    dut.io.addrInit    #= 0
    dut.io.lenBytes    #= 0
    dut.io.byteIn      #= 0
    dut.io.byteValid   #= false
    dut.io.allowUpload #= true
    dut.io.wrCmd.ready #= true   // #11123 FIX 1: downstream (CC FIFO) always ready here
    dut.clockDomain.waitSampling(5)

    // Capture every committed write from the bridge into a list so we
    // can verify the byte ordering and address ordering after the burst.
    case class Wrt(addr: BigInt, data: BigInt)
    val writes = scala.collection.mutable.ArrayBuffer[Wrt]()
    fork {
      while(true) {
        dut.clockDomain.waitSampling()
        if (dut.io.wrCmd.valid.toBoolean && dut.io.wrCmd.ready.toBoolean) {
          val p = dut.io.wrCmd.payload.toBigInt   // addr(23) ## din(8)
          writes += Wrt(p >> 8, p & 0xFF)
        }
      }
    }

    // Active-video gating mimic at the real 4:1 active:blank ratio
    // (640 active px vs 160 blank px). Scaled-down 80:20 cycles for
    // sim runtime; that's what the hardware actually presents.
    var gaterRun = true
    fork {
      while(gaterRun) {
        dut.io.allowUpload #= false
        dut.clockDomain.waitSampling(80)
        dut.io.allowUpload #= true
        dut.clockDomain.waitSampling(20)
      }
    }

    // Fire the SDRAM_WRITE header for a 40-byte burst at SDRAM addr 0x1000.
    val addrInit = 0x1000
    val nBytes   = 40
    dut.io.addrInit    #= addrInit
    dut.io.lenBytes    #= nBytes
    dut.io.headerValid #= true
    dut.clockDomain.waitSampling()
    dut.io.headerValid #= false

    // Stream the 40 payload bytes at the realistic ESP8266 ~500 kHz QSPI
    // rate: one byte every ~32 pixel cycles (matches the bench FQ8266
    // sketches' 16 nibble half-periods × 2 µs ÷ 39.7 ns pixel period).
    // Even with 4:1 active:blank gating, the FIFO drains faster than
    // bytes arrive at this rate — pre-fix single-byte latch would
    // still drop bytes whenever two arrived within the same active
    // window.
    val expected = (0 until nBytes).map(i => 0xA0 + i)
    for (i <- 0 until nBytes) {
      dut.io.byteIn    #= expected(i)
      dut.io.byteValid #= true
      dut.clockDomain.waitSampling()
      dut.io.byteValid #= false
      dut.clockDomain.waitSampling(31)
    }

    // Wait long enough for the bridge FSM to drain.
    dut.clockDomain.waitSampling(2000)

    println(s"[sim] saw ${writes.size} sdramWr pulses (expected $nBytes)")
    assert(writes.size == nBytes,
      s"FIFO must commit exactly $nBytes bytes, got ${writes.size}")

    for (i <- 0 until nBytes) {
      val w = writes(i)
      val expAddr = (addrInit + i) & ((1L << 23) - 1)
      val expData = expected(i)
      assert(w.addr.toLong == expAddr,
        f"write $i: addr 0x${w.addr.toLong}%X expected 0x$expAddr%X")
      assert(w.data.toInt == expData,
        f"write $i: data 0x${w.data.toInt}%X expected 0x$expData%X")
    }
    println("[sim] all 40 bytes written in order under active-video gating PASS")

    // ---- #11321: two back-to-back SDRAM_WRITE transactions, NO idle gap ----
    // The 2nd header arrives while the bridge may still be draining the 1st. With
    // the header FIFO, txn2 must re-anchor at its OWN address (0x5000), not continue
    // txn1's (0x2004) — the pre-fix bug that corrupted burst/back-to-back uploads.
    writes.clear()
    def fireTxn(addr: Int, data: Seq[Int]): Unit = {
      dut.io.addrInit    #= addr
      dut.io.lenBytes    #= data.size
      dut.io.headerValid #= true
      dut.clockDomain.waitSampling()
      dut.io.headerValid #= false
      for (b <- data) {
        dut.io.byteIn    #= b
        dut.io.byteValid #= true
        dut.clockDomain.waitSampling()
        dut.io.byteValid #= false      // no inter-byte gap — stress the FIFO path
      }
    }
    fireTxn(0x2000, Seq(0x11, 0x22, 0x33, 0x44))
    fireTxn(0x5000, Seq(0x55, 0x66, 0x77, 0x88))   // immediately after, no idle gap
    dut.clockDomain.waitSampling(3000)             // drain through active-video gating
    assert(writes.size == 8, s"back-to-back: expected 8 writes, got ${writes.size}")
    val b2bExp = Seq(
      (0x2000, 0x11), (0x2001, 0x22), (0x2002, 0x33), (0x2003, 0x44),
      (0x5000, 0x55), (0x5001, 0x66), (0x5002, 0x77), (0x5003, 0x88))
    for (i <- 0 until 8) {
      assert(writes(i).addr.toLong == b2bExp(i)._1,
        f"b2b write $i: addr 0x${writes(i).addr.toLong}%X exp 0x${b2bExp(i)._1}%X")
      assert(writes(i).data.toInt == b2bExp(i)._2,
        f"b2b write $i: data 0x${writes(i).data.toInt}%X exp 0x${b2bExp(i)._2}%X")
    }
    println("[sim] back-to-back: txn2 re-anchored at 0x5000 (not 0x2004) — header FIFO PASS")

    // ---- #11330: 32 back-to-back transactions (tile matrix) under gating ----
    // Reproduce the HW deadlock: many headers arriving faster than the gated drain
    // could overflow the depth-8 hdrFifo -> dropped headers desync the byte stream
    // -> bridge stuck (uploadBusy never releases) / dropped writes. Asserts the
    // bridge NEVER leaves uploadBusy stuck and lands every byte.
    // Production config: F5 sets allowUpload := True (continuous drain). Stop the
    // legacy 80/20 gater so this matches the shipped bridge, not the old gating.
    gaterRun = false
    dut.clockDomain.waitSampling(120)
    dut.io.allowUpload #= true
    dut.clockDomain.waitSampling(5)
    writes.clear()
    val nTxn = 32
    for (t <- 0 until nTxn) {
      dut.io.addrInit    #= 0xA000 + t * 4
      dut.io.lenBytes    #= 4
      dut.io.headerValid #= true
      dut.clockDomain.waitSampling()
      dut.io.headerValid #= false
      for (k <- 0 until 4) {
        dut.io.byteIn    #= (0x10 * (k + 1) + t) & 0xFF
        dut.io.byteValid #= true
        dut.clockDomain.waitSampling()
        dut.io.byteValid #= false
      }
      // NO gap between transactions — stress hdrFifo vs the gated drain
    }
    dut.clockDomain.waitSampling(12000)            // ample drain
    println(s"[sim] 32-txn: ${writes.size} writes (expected ${nTxn * 4}), uploadBusy=${dut.io.uploadBusy.toBoolean}")
    assert(!dut.io.uploadBusy.toBoolean, "DEADLOCK: uploadBusy stuck high after 32 back-to-back txns")
    assert(writes.size == nTxn * 4, s"32-txn: expected ${nTxn * 4} writes, got ${writes.size} (dropped headers -> desync)")
    for (t <- 0 until nTxn; k <- 0 until 4) {
      val w = writes(t * 4 + k)
      val expAddr = (0xA000 + t * 4 + k) & ((1L << 23) - 1)
      val expData = (0x10 * (k + 1) + t) & 0xFF
      assert(w.addr.toLong == expAddr && w.data.toInt == expData,
        f"32-txn write t=$t k=$k: addr 0x${w.addr.toLong}%X/data 0x${w.data.toInt}%X exp 0x$expAddr%X/0x$expData%X")
    }
    println("[sim] 32-txn back-to-back: all 128 bytes landed, no deadlock — PASS")
    println("QspiSdramBridgeSim: PASS")
  }
}
