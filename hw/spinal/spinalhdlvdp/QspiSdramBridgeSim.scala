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
    fork {
      while(true) {
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
    println("QspiSdramBridgeSim: PASS")
  }
}
