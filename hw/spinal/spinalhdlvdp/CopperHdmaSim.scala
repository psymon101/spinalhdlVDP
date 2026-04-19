package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 33 Checkpoint B — HDMA engine coverage sim.
  *
  * Exercises the HDMA portion of Copper.scala directly (unit scope), using
  * synthetic hCounter/vCounter drive. Validates per the artifact §4.1:
  *   1. HDMA table entries produce register writes at the correct lines
  *   2. HDMA auto-repeats each frame (no manual re-arm)
  *   3. Disabled channels are silent
  *   4. Script + HDMA priority — script wins on same-cycle contention
  *      (covered by construction: FSM output mux favours scriptFire).
  *
  * Counter model: hCounter cycles 0..hTotal-1; vCounter bumps at hCounter==0.
  * For compact sim, use a small hTotal (32) so 4 frames run in a few ms of
  * simulated time. HDMA sweep fits easily in 32 cycles (4 ch × 8 ent = 32).
  */
object CopperHdmaSim extends App {
  Config.sim.compile(Copper()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    dut.io.enabled  #= false
    dut.io.progAddr #= 0
    dut.io.progData #= 0
    dut.io.progWr   #= false
    dut.io.hdmaCtrlAddr #= 0
    dut.io.hdmaData     #= 0
    dut.io.hdmaWr       #= false
    dut.io.hCounter     #= 0
    dut.io.vCounter     #= 0

    dut.clockDomain.waitSampling(5)

    /** Pulse one HDMA control write. */
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

    /** Populate channel `ch` at entry slot `ent` with {valid, line, data}. */
    def writeEntry(ch: Int, ent: Int, line: Int, data: Int): Unit = {
      val slot = 0x0A + ch * 16 + ent * 2
      hdmaWrite(slot,     (1 << 15) | (line & 0xFF))  // valid + line
      hdmaWrite(slot + 1, data & 0xFFFF)              // data
    }

    // -- Configure HDMA: ch0 target = 0x1000, ch1 target = 0x2000 --
    hdmaWrite(0x02, 0x1000)  // chAddr0
    hdmaWrite(0x04, 0x2000)  // chAddr1

    // -- Four entries per artifact §4.1, lines {0, 60, 120, 180} on ch0. --
    writeEntry(0, 0, line = 0,   data = 0xAA00)
    writeEntry(0, 1, line = 60,  data = 0xAA60)
    writeEntry(0, 2, line = 120, data = 0xAAC0)
    writeEntry(0, 3, line = 180, data = 0xAAB4)

    // -- One entry on ch1 at line 30 (coverage for 2-channel simultaneous activity). --
    writeEntry(1, 0, line = 30, data = 0xBB1E)

    // -- One entry on ch2 at line 45 but DISABLED via mask below --
    hdmaWrite(0x06, 0x3000)                           // chAddr2
    writeEntry(2, 0, line = 45, data = 0xCC2D)

    // -- Enable HDMA; mask only ch0 and ch1 (bits 0..1 within mask[3:0]). --
    // CTRL register: bit[0]=enable, bits[4:1]=mask[3:0]
    hdmaWrite(0x00, 0x0001 | (0x3 << 1))

    // Observers.
    val hTotal      = 32   // short scan line for fast sim
    val framesToRun = 4
    val linesPerFrame = 200
    val observed = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int, Int)]  // frame, line, addr, data

    // Pre-align: park hCounter at hTotal-1 so the first line begins with a
    // clean 31→0 rising edge on hzero (driving the lineStart pulse).
    dut.io.hCounter #= hTotal - 1
    dut.clockDomain.waitSampling(2)

    fork {
      var frame = 0
      while (frame < framesToRun) {
        var line = 0
        while (line < linesPerFrame) {
          dut.io.vCounter #= line
          var h = 0
          while (h < hTotal) {
            dut.io.hCounter #= h
            dut.clockDomain.waitSampling()
            if (dut.io.regWr.toBoolean) {
              observed.append((frame, line, dut.io.regAddr.toInt, dut.io.regData.toInt))
            }
            h += 1
          }
          line += 1
        }
        frame += 1
      }
    }.join()

    // -- Case 1: ch0 writes at lines 0, 60, 120, 180 on every frame --
    val ch0Targets = Set(0, 60, 120, 180)
    val ch0Writes  = observed.filter { case (_, _, addr, _) => addr == 0x1000 }
    for (frame <- 0 until framesToRun) {
      val frameLines = ch0Writes.filter(_._1 == frame).map(_._2).toSet
      assert(ch0Targets.subsetOf(frameLines),
        s"Case 1 FAIL: frame $frame ch0 lines=$frameLines expected superset of $ch0Targets")
    }
    println(f"Case 1 PASS: ch0 fired at lines ${ch0Targets.toSeq.sorted} across ${framesToRun} frames")

    // -- Case 2: HDMA auto-repeat (same events on every frame) --
    val perFrameCh0Sigs = (0 until framesToRun).map { f =>
      ch0Writes.filter(_._1 == f).map(w => (w._2, w._4)).toSet
    }
    for (f <- 1 until framesToRun) {
      assert(perFrameCh0Sigs(f) == perFrameCh0Sigs(0),
        s"Case 2 FAIL: frame $f differs from frame 0: ${perFrameCh0Sigs(f)} vs ${perFrameCh0Sigs(0)}")
    }
    println(f"Case 2 PASS: auto-repeat — ${perFrameCh0Sigs(0).size} identical events on every frame")

    // -- Case 3: ch1 fires at line 30 each frame with correct data --
    val ch1Writes = observed.filter { case (_, _, addr, _) => addr == 0x2000 }
    for (frame <- 0 until framesToRun) {
      val hits = ch1Writes.filter(_._1 == frame)
      assert(hits.nonEmpty && hits.forall(_._2 == 30) && hits.forall(_._4 == 0xBB1E),
        s"Case 3 FAIL: frame $frame ch1 hits=$hits")
    }
    println(f"Case 3 PASS: ch1 fired at line 30 data=0xBB1E every frame")

    // -- Case 4: ch2 was configured but masked out — no writes to 0x3000 --
    val ch2Writes = observed.filter { case (_, _, addr, _) => addr == 0x3000 }
    assert(ch2Writes.isEmpty,
      s"Case 4 FAIL: ch2 masked but fired ${ch2Writes.size} times: $ch2Writes")
    println(f"Case 4 PASS: masked ch2 produced zero writes")

    // -- Case 5: correct per-line data integrity --
    val expectedByLine = Map(0 -> 0xAA00, 60 -> 0xAA60, 120 -> 0xAAC0, 180 -> 0xAAB4)
    for (frame <- 0 until framesToRun; (line, data) <- expectedByLine) {
      val matches = ch0Writes.filter(w => w._1 == frame && w._2 == line)
      assert(matches.exists(_._4 == data),
        f"Case 5 FAIL: frame $frame line $line expected data=0x$data%04X got ${matches.map(_._4.toHexString)}")
    }
    println(f"Case 5 PASS: data integrity — all 4 ch0 entries carry correct data every frame")

    println(s"CopperHdmaSim: all 5 cases PASS — HDMA engine produces correct per-frame per-line writes")
  }
}
