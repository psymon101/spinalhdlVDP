package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** WHOLE-VDP-134 — targeted refutation of the "stale high-byte latch race"
  * hypothesis (CyanPeak #12458) for I80HostInterface.
  *
  * Claim: in sDataHi, `dataReg(15:8)` and `regWrR` are assigned the same cycle,
  * so `regBus.enable` could be high while `regBus.data` still holds the PREVIOUS
  * transaction's high byte → every non-repeating-high-byte write corrupted.
  *
  * Test: drive back-to-back i80 register writes with DELIBERATELY distinct,
  * non-repeating high AND low bytes, and assert each captured regBus write equals
  * exactly what was sent. If a stale-high-byte race existed, write N would carry
  * write N-1's high byte and these asserts would fail.
  */
object I80DataLatchSim extends App {
  Config.sim.compile(I80HostInterface(8)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.cs #= true; dut.io.wr #= true; dut.io.rd #= true; dut.io.dc #= false
    dut.io.dIn #= 0; dut.io.readData #= 0; dut.io.blockWr.ready #= true
    dut.clockDomain.waitSampling(5)

    val captured = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    dut.clockDomain.onSamplings {
      if (dut.io.regBus.enable.toBoolean)
        captured += ((dut.io.regBus.addr.toInt, dut.io.regBus.data.toInt))
    }

    def wrByte(dcv: Boolean, b: Int): Unit = {
      dut.io.cs #= false; dut.io.dc #= dcv; dut.io.dIn #= b
      dut.io.wr #= false; dut.clockDomain.waitSampling(4)
      dut.io.wr #= true;  dut.clockDomain.waitSampling(4)
    }
    def regWrite(addr: Int, data: Int): Unit = {
      wrByte(false, 0x00)
      wrByte(false, addr & 0xFF); wrByte(false, (addr >> 8) & 0xFF)
      wrByte(true,  data & 0xFF); wrByte(true,  (data >> 8) & 0xFF)
      dut.io.cs #= true; dut.clockDomain.waitSampling(3)
    }

    // Back-to-back writes: every high byte differs from the previous (the exact
    // condition CyanPeak says corrupts), plus the real copper words.
    val seq = Seq(
      0x0400 -> 0x000A, 0x0401 -> 0x4347, 0x0402 -> 0x1801, 0x0403 -> 0xC000,
      0x0347 -> 0xAA55, 0x0300 -> 0x1234, 0x0351 -> 0x5678, 0x0352 -> 0x9ABC,
      0x0310 -> 0x0001
    )
    seq.foreach { case (a, d) => regWrite(a, d) }
    dut.clockDomain.waitSampling(10)

    println("[sim] captured regBus writes:")
    captured.foreach { case (a, d) => println(f"[sim]   0x$a%04X = 0x$d%04X") }

    var fails = 0
    if (captured.size != seq.size) { println(f"[sim] FAIL: expected ${seq.size} writes, captured ${captured.size}"); fails += 1 }
    captured.zip(seq).zipWithIndex.foreach { case (((ga, gd), (ea, ed)), i) =>
      if (ga != ea || gd != ed) { println(f"[sim] FAIL #$i: got 0x$ga%04X=0x$gd%04X expected 0x$ea%04X=0x$ed%04X"); fails += 1 }
    }
    if (fails == 0)
      println("[sim] I80DataLatchSim: PASS — every write (distinct non-repeating high bytes) captured correctly; NO stale-high-byte race")
    else
      println(s"[sim] I80DataLatchSim: FAIL — $fails mismatch(es); stale-data race reproduced")
  }
}
