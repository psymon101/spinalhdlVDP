package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** CopperW4Sim — empirical proof of the W4 HDMA >=512 alias guard (#12128).
  *
  * The HDMA hit compare is `entLine === io.vCounter(8 downto 0)`, which truncates
  * vCounter to 9 bits. Without the guard `&& !io.vCounter(9)`, a valid entry for
  * line L (0..511) ALSO matches at vCounter = L+512 (vblank in 480p / active video
  * in 720p) — a spurious extra fire. This sim programs a single ch0 entry at line 0
  * and sweeps vCounter 0..519, past the alias point 512 (=0+512), asserting the
  * entry fires EXACTLY at line 0 and NEVER at 512. Verified as a true discriminator:
  * with the guard removed it fires at [0, 512] (FAIL); with the guard, [0] (PASS).
  *
  * Standalone (fresh DUT) rather than wedged into CopperHdmaSim, which accumulates
  * multi-case CTRL/entry state that makes a clean post-hoc reprogram unreliable.
  */
object CopperW4Sim extends App {
  Config.sim.compile(Copper()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    dut.io.enabled  #= false
    dut.io.progAddr #= 0
    dut.io.progData #= 0
    dut.io.progWr   #= false
    dut.io.bankSwapNow  #= false
    dut.io.hdmaCtrlAddr #= 0
    dut.io.hdmaData     #= 0
    dut.io.hdmaWr       #= false
    dut.io.hCounter     #= 0
    dut.io.vCounter     #= 0
    dut.clockDomain.waitSampling(5)

    def hdmaWrite(off: Int, data: Int): Unit = {
      dut.io.hdmaCtrlAddr #= off
      dut.io.hdmaData     #= data
      dut.io.hdmaWr       #= true
      dut.clockDomain.waitSampling()
      dut.io.hdmaWr       #= false
      dut.io.hdmaCtrlAddr #= 0
      dut.io.hdmaData     #= 0
      dut.clockDomain.waitSampling()
    }
    def writeEntry(ch: Int, ent: Int, line: Int, data: Int): Unit = {
      val slot = 0x0A + ch * 16 + ent * 2
      hdmaWrite(slot,     (1 << 15) | (line & 0x1FF))  // valid + line[8:0]
      hdmaWrite(slot + 1, data & 0xFFFF)               // data
    }

    // Single ch0 entry at line 0; enable HDMA, mask ch0 only.
    hdmaWrite(0x02, 0x1000)                  // chAddr0
    writeEntry(0, 0, line = 0, data = 0xDD05)
    hdmaWrite(0x00, 0x0001 | (0x1 << 1))     // enable + mask ch0 (mask[0] -> bit1)

    val hTotal = 32
    val fires  = scala.collection.mutable.ArrayBuffer.empty[Int]
    // Pre-align hCounter so the first line gives a clean hzero rising edge.
    dut.io.hCounter #= hTotal - 1
    dut.clockDomain.waitSampling(2)
    fork {
      var frame = 0
      while (frame < 2) {                      // 2 frames (HDMA auto-repeats per frame)
        var line = 0
        while (line < 520) {                   // covers line 0 and the alias 512 (=0+512)
          dut.io.vCounter #= line
          var h = 0
          while (h < hTotal) {
            dut.io.hCounter #= h
            dut.clockDomain.waitSampling()
            if (dut.io.regWr.toBoolean && dut.io.regAddr.toInt == 0x1000) fires.append(line)
            h += 1
          }
          line += 1
        }
        frame += 1
      }
    }.join()

    val fired = fires.toSeq.distinct.sorted
    println(s"[sim] ch0(line0) fired at vCounter $fired  (alias point would be 512 = 0+512)")
    assert(fired.contains(0),    s"W4 FAIL: line-0 entry did not fire at line 0: $fired")
    assert(!fired.contains(512), s"W4 FAIL: line-0 entry ALIASED to vCounter 512 (=0+512) — guard missing: $fired")
    assert(fired == Seq(0),      s"W4 FAIL: line-0 entry fired at unexpected vCounter(s): $fired")
    println("CopperW4Sim: PASS — line-0 entry fires only at vCounter 0, NOT at aliased 512 (W4 >=512 guard)")
  }
}
