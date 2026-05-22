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
    dut.io.bankSwapNow #= false
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

    /** Populate channel `ch` at entry slot `ent` with {valid, line, data}.
      * Per BH-3, the line field is 9 bits (covers 0..511). */
    def writeEntry(ch: Int, ent: Int, line: Int, data: Int): Unit = {
      val slot = 0x0A + ch * 16 + ent * 2
      hdmaWrite(slot,     (1 << 15) | (line & 0x1FF))  // valid + line[8:0]
      hdmaWrite(slot + 1, data & 0xFFFF)               // data
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

    // -- Case 6 (BH-3): 9-bit line compare reaches lines 256..479. Aliased
    // pre-BH-3 (8-bit line compare) would mistakenly fire at line 0/100
    // because 256 & 0xFF = 0, 356 & 0xFF = 100. Verify those low-byte-
    // aliased lines produce NO writes (anti-alias check), and the true
    // high-line targets DO fire. --
    hdmaWrite(0x06, 0x4000)               // chAddr2 retargeted to 0x4000
    hdmaWrite(0x00, 0x0000)               // disable HDMA while reprogramming
    // Disable previous ch2 entry by clearing its valid bit at slot 0.
    hdmaWrite(0x0A + 2 * 16 + 0 * 2, 0x0000)
    writeEntry(2, 1, line = 256, data = 0x9100)
    writeEntry(2, 2, line = 356, data = 0x9164)
    hdmaWrite(0x00, 0x0001 | (0x7 << 1))  // re-enable, mask = ch0+ch1+ch2

    val observed2 = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int, Int)]
    fork {
      var frame = 0
      while (frame < 2) {
        var line = 0
        while (line < 480) {
          dut.io.vCounter #= line
          var h = 0
          while (h < hTotal) {
            dut.io.hCounter #= h
            dut.clockDomain.waitSampling()
            if (dut.io.regWr.toBoolean) {
              observed2.append((frame, line, dut.io.regAddr.toInt, dut.io.regData.toInt))
            }
            h += 1
          }
          line += 1
        }
        frame += 1
      }
    }.join()

    val ch2Targets = Map(256 -> 0x9100, 356 -> 0x9164)
    val ch2Writes2 = observed2.filter(_._3 == 0x4000)
    val aliasLines = Set(0, 100)
    for (frame <- 0 until 2; (line, expData) <- ch2Targets.toSeq.sortBy(_._1)) {
      val hits = ch2Writes2.filter(w => w._1 == frame && w._2 == line)
      assert(hits.exists(_._4 == expData),
        f"Case 6 FAIL: frame $frame line $line expected data=0x$expData%04X — 9-bit line compare did not fire")
    }
    val aliasHits = ch2Writes2.filter(w => aliasLines.contains(w._2))
    assert(aliasHits.isEmpty,
      s"Case 6 FAIL: 8-bit-aliased lines fired (would indicate compare still using vCounter[7:0]): $aliasHits")
    println(f"Case 6 PASS: 9-bit line compare fires at 256/356 and does NOT alias to 0/100")

    // -- Case 7 (BH-4): HDMA indirect mode ---------------------------------
    // In indirect mode, the entry's `data` field is interpreted as a
    // pointer into the new 256x16 data array; the actual register
    // value comes from dataArray[ptr]. This case loads three sentinels
    // into the data array, programs ch3 entries that point at them by
    // pointer (NOT by value), enables indirect mode, and verifies the
    // sweep writes the dereferenced sentinel values to chAddr3 — proving
    // both the pointer dereference and that the data array is gated
    // correctly behind hdmaIndirect (the same entries in direct mode
    // would write the literal pointers, not the sentinels).
    hdmaWrite(0x00, 0x0000)               // disable HDMA while reprogramming
    // Clear all ch2 entries so they don't fire during this case.
    for (e <- 0 until 8) hdmaWrite(0x0A + 2 * 16 + e * 2, 0x0000)
    // Retarget chAddr3 = 0x5000.
    hdmaWrite(0x08, 0x5000)
    // BH-4 data array: HDMA_DATA_PTR (0x50), HDMA_DATA_WRITE (0x51).
    hdmaWrite(0x50, 0x10)                 // pointer = 0x10
    hdmaWrite(0x51, 0xDA10)               // dataArray[0x10] = 0xDA10, ptr -> 0x11
    hdmaWrite(0x51, 0xDA11)               // dataArray[0x11] = 0xDA11, ptr -> 0x12
    hdmaWrite(0x51, 0xDA12)               // dataArray[0x12] = 0xDA12, ptr -> 0x13
    // Program ch3 entries: each entry's data field is the POINTER, not
    // the value. Lines kept inside 0..255 so they fire on every frame
    // regardless of vCounter wrap behavior in this synthetic harness.
    writeEntry(3, 0, line = 80,  data = 0x10)   // ptr → dataArray[0x10] = 0xDA10
    writeEntry(3, 1, line = 82,  data = 0x11)   // ptr → dataArray[0x11] = 0xDA11
    writeEntry(3, 2, line = 84,  data = 0x12)   // ptr → dataArray[0x12] = 0xDA12
    // Enable HDMA with INDIRECT mode (CTRL bit[5]=1) and ch3 mask bit
    // (mask[3]=1 → CTRL bits[4:1] = 0b1000).
    hdmaWrite(0x00, 0x0001 | (0x8 << 1) | (1 << 5))

    val observed3 = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int, Int)]
    fork {
      var frame = 0
      while (frame < 1) {
        var line = 0
        while (line < 100) {
          dut.io.vCounter #= line
          var h = 0
          while (h < hTotal) {
            dut.io.hCounter #= h
            dut.clockDomain.waitSampling()
            if (dut.io.regWr.toBoolean) {
              observed3.append((frame, line, dut.io.regAddr.toInt, dut.io.regData.toInt))
            }
            h += 1
          }
          line += 1
        }
        frame += 1
      }
    }.join()

    val ch3Writes = observed3.filter(_._3 == 0x5000)
    val expectedIndirect = Map(80 -> 0xDA10, 82 -> 0xDA11, 84 -> 0xDA12)
    for ((line, expVal) <- expectedIndirect.toSeq.sortBy(_._1)) {
      val hits = ch3Writes.filter(_._2 == line)
      assert(hits.exists(_._4 == expVal),
        f"Case 7 FAIL: indirect-mode line $line expected dereferenced data=0x$expVal%04X, got hits=$hits")
    }
    // Verify the LITERAL pointer values (0x10/0x11/0x12) did NOT slip
    // through — that would indicate the indirect mux failed.
    val literalHits = ch3Writes.filter(w => Set(0x10, 0x11, 0x12).contains(w._4))
    assert(literalHits.isEmpty,
      s"Case 7 FAIL: indirect mode wrote literal pointer values: $literalHits")
    println(f"Case 7 PASS: indirect mode dereferences pointers (lines 80/82/84 -> 0xDA10/0xDA11/0xDA12)")

    println(s"CopperHdmaSim: all 7 cases PASS — HDMA 9-bit line + indirect mode verified")
  }
}
