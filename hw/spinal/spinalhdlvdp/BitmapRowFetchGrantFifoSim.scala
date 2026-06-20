package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** BUG1-GRANT-FIFO-174 — proves the BitmapRowFetch grant queue is a true depth-2 FIFO.
  *
  * Drives grant A, lets the FSM start fetching it, then issues grants B, C, D back-to-back
  * WHILE the A fetch is still in flight (FSM busy, not in sIdle). With the depth-2 FIFO:
  *   - A is popped + fetched immediately, B and C are buffered (not lost), D overflows
  *     (FSM >2 rows behind) → grantOverflow == 1, and rows A,B,C are ALL fetched.
  * The OLD depth-1 latch would OVERWRITE B with C (B lost): rows A,C only, grantOverflow == 2.
  *
  * Observed via `dut.sd.lineReg` (the row currently being fetched) + `dut.sd.grantOverflow`.
  * Each granted line L is displayed-doubled, so the fetched source row = L>>1.
  *
  * Run: sbt "runMain spinalhdlvdp.BitmapRowFetchGrantFifoSim"
  */
object BitmapRowFetchGrantFifoSim extends App {
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(84000000 Hz))
    BitmapRowFetch(sdramCd, skipSdramInit = true)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= false
    dut.io.fetchGrant #= false; dut.io.fetchLine #= 0; dut.io.col #= 0
    dut.io.enable #= false; dut.io.directColor #= false; dut.io.tileBootDone #= false
    dut.io.bitmapBase #= 0x1000; dut.io.attrBase #= 0x4000
    dut.io.bitmapStride #= 512; dut.io.attrStride #= 512; dut.io.bitmapHeight #= 240

    // Reactive SDRAM model (matches BitmapRowFetchDirectColorSim): busy held false,
    // 5-cycle latency, burst out `sdramBurstLen` words. Keeps each row fetch long enough
    // that the B/C/D grants land while the A fetch is still in flight.
    fork {
      while (true) {
        if (dut.io.sdramRd.toBoolean) {
          val addr = dut.io.sdramAddr.toLong
          val n = math.max(1, dut.io.sdramBurstLen.toInt)
          dut.sdramCd.waitSampling(5)
          for (k <- 0 until n) {
            val wa = addr + k * 4
            dut.io.sdramDout32 #= BigInt((wa & 0xFF) | (((wa+1)&0xFF)<<8) | (((wa+2)&0xFF)<<16) | (((wa+3)&0xFF)<<24))
            dut.io.sdramDout #= (wa & 0xFF).toInt
            dut.io.sdramDataReady #= true; dut.sdramCd.waitSampling()
          }
          dut.io.sdramDataReady #= false
        } else dut.sdramCd.waitSampling()
      }
    }

    dut.sdramCd.waitSampling(10); dut.clockDomain.waitSampling(10)
    dut.io.enable #= true; dut.io.directColor #= true; dut.io.tileBootDone #= true
    var timeout = 2000
    while (!dut.io.bootDone.toBoolean && timeout > 0) { dut.sdramCd.waitSampling(); timeout -= 1 }
    assert(timeout > 0, "timed out waiting for bootDone")
    println("[sim] bootDone reached")

    // Continuously record every row the FSM actually fetches (lineReg changes on each pop).
    val fetchedRows = mutable.Set[Int]()
    fork { while (true) { fetchedRows += dut.sd.lineReg.toInt; dut.clockDomain.waitSampling() } }

    def grant(line: Int): Unit = {
      dut.io.fetchLine #= line
      dut.io.fetchGrant #= true;  dut.clockDomain.waitSampling(3)
      dut.io.fetchGrant #= false; dut.clockDomain.waitSampling(3)
    }

    // A: grant, then let the FSM pop it and start fetching (now BUSY).
    grant(4)                       // line 4 -> row 2
    dut.clockDomain.waitSampling(12)
    // B, C, D arrive WHILE A is still fetching (FSM busy).
    grant(8)                       // line 8 -> row 4  (buffered)
    grant(12)                      // line 12 -> row 6 (buffered; FIFO now full)
    grant(16)                      // line 16 -> row 8 (FIFO full -> overflow, dropped)
    // Let all in-flight + queued fetches drain.
    dut.clockDomain.waitSampling(1200)

    val ovf = dut.sd.grantOverflow.toInt
    val rows = fetchedRows.toSeq.sorted
    println(s"[sim] fetched source rows = $rows ; grantOverflow = $ovf")
    // Depth-2 FIFO proof: rows A(2), B(4), C(6) ALL fetched (B not lost); only D(8) dropped.
    assert(rows.contains(2), "row A (2) not fetched")
    assert(rows.contains(4), "row B (4) LOST — depth-1 overwrite bug, FIFO not depth-2")
    assert(rows.contains(6), "row C (6) not fetched")
    assert(!rows.contains(8), s"row D (8) should have overflowed (dropped), but was fetched: $rows")
    assert(ovf == 1, s"expected exactly 1 overflow (D), got $ovf — depth-2 FIFO should buffer B,C")
    println("[sim] BitmapRowFetchGrantFifoSim: PASS — depth-2 FIFO buffered B,C (no loss); only D overflowed")
  }
}
