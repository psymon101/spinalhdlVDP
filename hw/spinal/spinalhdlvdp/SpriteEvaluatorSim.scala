package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 28 — Two-Pass Sprite Evaluator validation sim.
  *
  * Parameters match the Task 28 landing: `descCount=32`, `visiblePerLine=8`,
  * `patternSelBits=4`, `legacyIoCount=4`. The sequential Pass-1 FSM takes
  * `descCount` cycles to complete; tests wait on that before reading
  * active-list outputs.
  *
  * Cases:
  *   1. on-line / off-line selection via legacy IO slots
  *   2. empty line — zero active, no overflow
  *   3. overflow — 9 enabled sprites on one line → only first 8 retained,
  *      `overflowFlag` asserts, slot ordering is lowest-idx-first
  *   4. disabled-skip — disabling an earlier descriptor shifts later ones
  *      forward in the active list
  *   5. stability — active list stays constant through >800 cycles without
  *      a new evalStart strobe
  *   6. Y-boundary — [Y, Y+16) inclusive/exclusive bounds
  *   7. bus programming — Reg-backed slots 4..31 drive active selections
  *      when legacy slots 0..3 are disabled
  */
object SpriteEvaluatorSim extends App {
  val D = 32
  val V = 8
  val P = 4
  val L = 4

  Config.sim.compile(SpriteEvaluator(
      descCount = D, visiblePerLine = V, patternSelBits = P, legacyIoCount = L
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def setLegacy(idx: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit = {
      require(idx >= 0 && idx < L, s"setLegacy idx=$idx out of legacy range [0..$L)")
      dut.io.descX(idx)          #= x
      dut.io.descY(idx)          #= y
      dut.io.descEnabled(idx)    #= enabled
      dut.io.descPatternIdx(idx) #= patIdx
    }

    def pulseBus(slot: Int, word: Int, data: Int): Unit = {
      dut.io.busSlot #= slot
      dut.io.busWord #= word
      dut.io.busData #= data
      dut.io.busWr   #= true
      dut.clockDomain.waitSampling()
      dut.io.busWr   #= false
    }

    def setBusDesc(slot: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit = {
      require(slot >= L && slot < D, s"setBusDesc slot=$slot not in bus range [$L..$D)")
      val word0 = ((if (enabled) 1 else 0) << 15) | ((patIdx & 0xF) << 11) | (y & 0x3FF)
      val word1 = x & 0x3FF
      pulseBus(slot, 0, word0)
      pulseBus(slot, 1, word1)
    }

    def pulseEval(line: Int): Unit = {
      dut.io.evalLine  #= line
      dut.io.evalStart #= true
      dut.clockDomain.waitSampling()
      dut.io.evalStart #= false
      // Sequential scan takes descCount cycles; wait a safety margin.
      dut.clockDomain.waitSampling(D + 4)
    }

    def activeSet(): Seq[(Boolean, Int, Int, Int, Int)] =
      (0 until V).map { s =>
        (dut.io.activeValid(s).toBoolean,
         dut.io.activeX(s).toInt,
         dut.io.activeRow(s).toInt,
         dut.io.activePatternIdx(s).toInt,
         s)
      }

    // Defaults.
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    dut.io.evalLine  #= 0
    dut.io.evalStart #= false
    dut.io.busSlot   #= 0
    dut.io.busWord   #= 0
    dut.io.busData   #= 0
    dut.io.busWr     #= false
    // Initialize all bus slots to disabled state.
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    dut.clockDomain.waitSampling(5)

    // --- Case 1: on/off line selection via legacy IO slots ---
    setLegacy(0, 100,  50, enabled = true, patIdx = 0)
    setLegacy(1, 200,  50, enabled = true, patIdx = 1)
    setLegacy(2, 300, 200, enabled = true, patIdx = 0)
    setLegacy(3, 400, 200, enabled = true, patIdx = 1)
    pulseEval(55)
    val c1 = activeSet()
    assert(c1(0)._1 && c1(0)._2 == 100 && c1(0)._3 == 5 && c1(0)._4 == 0,
           s"Case 1 slot 0: got $c1")
    assert(c1(1)._1 && c1(1)._2 == 200 && c1(1)._3 == 5 && c1(1)._4 == 1,
           s"Case 1 slot 1: got $c1")
    assert(!c1(2)._1 && !c1(3)._1, s"Case 1 unused slots must be invalid: $c1")
    assert(!dut.io.overflowFlag.toBoolean, "Case 1: unexpected overflow")
    println("[sim] Case 1 on-line select via legacy IO — OK")

    // --- Case 2: empty line ---
    pulseEval(130)
    val c2 = activeSet()
    assert(c2.forall(!_._1), s"Case 2: expected all slots invalid, got $c2")
    assert(!dut.io.overflowFlag.toBoolean, "Case 2: unexpected overflow")
    println("[sim] Case 2 empty line — OK")

    // --- Case 3: overflow — 9 enabled sprites on line 100, only 8 retained ---
    for (d <- 0 until L) setLegacy(d, 10 + 50*d, 100, enabled = true, patIdx = d % 2)
    // Bus slots 4..8 also on line 100.
    for (s <- L until 9) setBusDesc(s, 10 + 50*s, 100, enabled = true, patIdx = s % 2)
    pulseEval(110)
    val c3 = activeSet()
    val validCount = c3.count(_._1)
    assert(validCount == V, s"Case 3: expected $V valid slots, got $validCount in $c3")
    assert(dut.io.overflowFlag.toBoolean, "Case 3: overflow flag must be set")
    // Slot 0 = lowest descriptor index = legacy 0 @ x=10.
    assert(c3(0)._2 == 10, s"Case 3 slot 0 should be legacy desc 0 (x=10): $c3")
    // Slot 7 = 8th-lowest active = bus slot 7 @ x=10+50*7=360.
    assert(c3(7)._2 == 360, s"Case 3 slot 7 should be bus desc 7 (x=360): $c3")
    println(f"[sim] Case 3 overflow: ${validCount}/${V} valid, overflow flag set, ordering lowest-idx-first — OK")

    // --- Case 4: disable legacy 0 → slot 0 should become legacy 1 ---
    setLegacy(0, 10, 100, enabled = false)
    pulseEval(110)
    val c4 = activeSet()
    assert(c4(0)._1 && c4(0)._2 == 60, s"Case 4 slot 0 must be legacy 1 (x=60): $c4")
    println("[sim] Case 4 disable-skip — OK")

    // --- Case 5: stability — no new evalStart for >800 cycles ---
    val snap = activeSet()
    dut.clockDomain.waitSampling(900)
    val later = activeSet()
    assert(snap == later, s"Case 5 stability: snap=$snap later=$later")
    println("[sim] Case 5 active list stable without new evalStart — OK")

    // --- Case 6: Y boundary ---
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    setLegacy(0, 77, 50, enabled = true, patIdx = 0)
    pulseEval(50)
    assert(dut.io.activeValid(0).toBoolean && dut.io.activeRow(0).toInt == 0,
           "Case 6 Y=50 row=0")
    pulseEval(65)
    assert(dut.io.activeValid(0).toBoolean && dut.io.activeRow(0).toInt == 15,
           "Case 6 Y=65 row=15")
    pulseEval(66)
    assert(!dut.io.activeValid(0).toBoolean, "Case 6 Y=66 off-line")
    pulseEval(49)
    assert(!dut.io.activeValid(0).toBoolean, "Case 6 Y=49 off-line")
    println("[sim] Case 6 Y-boundary [Y..Y+16) — OK")

    // --- Case 7: bus programming of slot 10 with legacy disabled ---
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    setBusDesc(10, x = 222, y = 80, enabled = true, patIdx = 3)
    pulseEval(88)
    val c7 = activeSet()
    assert(c7(0)._1 && c7(0)._2 == 222 && c7(0)._4 == 3,
           s"Case 7: slot 10 bus-programmed descriptor should drive slot 0: $c7")
    println("[sim] Case 7 bus-programmed slot selection — OK")

    println("[sim] SpriteEvaluatorSim: PASS")
  }
}
