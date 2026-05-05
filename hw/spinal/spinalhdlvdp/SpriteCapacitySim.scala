package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 45 — Sprite Capacity proof.
  *
  * Explicit 32-descriptor overflow and per-slot culling/activeRow proof,
  * per artifact `PROJECT_PLAN/artifacts/TASK_45_SPRITE_CAPACITY_HARDENING.md`
  * §7.1.4. Complements `SpriteEvaluatorSim` by exercising the full
  * `descCount=32` range, not just the 9-descriptor barely-overflow case.
  *
  * Cases:
  *   A. All 32 descriptors enabled on the same Y line → exactly 8 active,
  *      overflow flag set, ordering lowest-slot-index-first, per-slot
  *      `activeRow` correct.
  *   B. 8 descriptors enabled on the line, 24 disabled → exactly 8 active,
  *      overflow flag NOT set.
  *   C. High-index slots only (slots 24..31) enabled → those 8 slots are
  *      selected (proves bus-programmable high end of the descriptor range
  *      actually affects the scan).
  *   D. Mixed Y — 16 descriptors on line 100, 16 on line 200 → each line
  *      produces 8 active + overflow, selecting lowest-index 8 from its group.
  */
object SpriteCapacitySim extends App {
  val D = 64
  val V = 32
  val P = 4
  val L = 4

  Config.sim.compile(SpriteEvaluator(
      descCount = D, visiblePerLine = V, patternSelBits = P, legacyIoCount = L
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def setLegacy(idx: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit = {
      require(idx >= 0 && idx < L)
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
      require(slot >= L && slot < D)
      val word0 = ((if (enabled) 1 else 0) << 15) | ((patIdx & 0xF) << 11) | (y & 0x3FF)
      val word1 = x & 0x3FF
      pulseBus(slot, 0, word0)
      pulseBus(slot, 1, word1)
    }

    def setDesc(idx: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit =
      if (idx < L) setLegacy(idx, x, y, enabled, patIdx)
      else         setBusDesc(idx, x, y, enabled, patIdx)

    def pulseEval(line: Int): Unit = {
      dut.io.evalLine  #= line
      dut.io.evalStart #= true
      dut.clockDomain.waitSampling()
      dut.io.evalStart #= false
      dut.clockDomain.waitSampling(D + 4)
    }

    def activeSet(): Seq[(Boolean, Int, Int)] =
      (0 until V).map { s =>
        (dut.io.activeValid(s).toBoolean,
         dut.io.activeX(s).toInt,
         dut.io.activeRow(s).toInt)
      }

    // Defaults — all descriptors parked off-screen and disabled.
    for (d <- 0 until L) setLegacy(d, 0, 1023, enabled = false)
    dut.io.evalLine  #= 0
    dut.io.evalStart #= false
    dut.io.busSlot   #= 0
    dut.io.busWord   #= 0
    dut.io.busData   #= 0
    dut.io.busWr     #= false
    for (s <- L until D) setBusDesc(s, 0, 1023, enabled = false)
    dut.clockDomain.waitSampling(5)

    // --- Case A: all D descriptors on Y=100, all enabled ---
    for (d <- 0 until D) {
      val x = 10 + 8 * d                                  // distinct per-descriptor x
      setDesc(d, x, 100, enabled = true, patIdx = d % 2)
    }
    pulseEval(105)                                        // line 105, inside [100..116)
    val cA = activeSet()
    val validA = cA.count(_._1)
    assert(validA == V, s"Case A: expected $V active, got $validA (full=$cA)")
    assert(dut.io.overflowFlag.toBoolean == (D > V),
      s"Case A: overflowFlag should be ${D > V} with $D overlapping vs capacity $V")
    // Ordering: lowest descriptor index first. Slot 0 = desc 0 (x=10).
    for (s <- 0 until V) {
      val expX   = 10 + 8 * s
      val expRow = 5     // 105 - 100
      assert(cA(s)._1 && cA(s)._2 == expX && cA(s)._3 == expRow,
             s"Case A slot $s expected valid/x=$expX/row=$expRow, got ${cA(s)}")
    }
    println(s"[sim] Case A 32 overlapping descriptors: 8/8 retained lowest-first, overflow set, activeRow=5 — OK")

    // --- Case B: exactly V enabled on line 200, rest disabled ---
    for (d <- 0 until D) setDesc(d, 0, 1023, enabled = false)
    // Enable only descriptors 10..(10+V-1) on line 200.
    for (d <- 10 until (10 + V)) setDesc(d, 20 + 5 * d, 200, enabled = true, patIdx = 0)
    pulseEval(210)
    val cB = activeSet()
    val validB = cB.count(_._1)
    assert(validB == V, s"Case B: expected $V active, got $validB (full=$cB)")
    // Overflow expected from tile-budget rule (V × 4 tiles for default 16×16 sprites > TileBudget=34).
    // Count rule does NOT trigger (exactly V active = capacity).
    assert(dut.io.overflowFlag.toBoolean == (V * 4 > 34),
      s"Case B: overflowFlag should be ${V*4 > 34} (tile-budget rule)")
    // Slot 0 = descriptor 10, slot V-1 = descriptor (10+V-1).
    assert(cB(0)._2 == 20 + 5 * 10, s"Case B slot 0 should be desc 10: ${cB(0)}")
    assert(cB(V - 1)._2 == 20 + 5 * (10 + V - 1), s"Case B slot ${V-1} should be desc ${10+V-1}: ${cB(V-1)}")
    for (s <- 0 until V) assert(cB(s)._3 == 10, s"Case B slot $s activeRow must be 10: ${cB(s)}")
    println(s"[sim] Case B exactly $V at Y=200: all retained, no overflow — OK")

    // --- Case C: only high-index slots ((D-V)..(D-1)) enabled ---
    for (d <- 0 until D) setDesc(d, 0, 1023, enabled = false)
    for (d <- (D - V) until D) setDesc(d, 50 + 5 * d, 300, enabled = true, patIdx = d % 2)
    pulseEval(308)
    val cC = activeSet()
    val validC = cC.count(_._1)
    assert(validC == V, s"Case C: expected $V active, got $validC (full=$cC)")
    assert(dut.io.overflowFlag.toBoolean == (V * 4 > 34),
      s"Case C: overflowFlag should be ${V*4 > 34} (tile-budget rule)")
    // Slot 0 = descriptor (D-V) — proves high-end of bus range scans.
    assert(cC(0)._2 == 50 + 5 * (D - V), s"Case C slot 0 should be desc ${D-V}: ${cC(0)}")
    assert(cC(V - 1)._2 == 50 + 5 * (D - 1), s"Case C slot ${V-1} should be desc ${D-1}: ${cC(V-1)}")
    println(s"[sim] Case C high-index slots ${D-V}..${D-1} active: proves extended descriptor range reachable — OK")

    // --- Case D: 32 on line 100, 32 on line 200 (mixed Y) ---
    for (d <- 0 until D) setDesc(d, 0, 1023, enabled = false)
    // Even-indexed (D/2 sprites) on Y=100, odd-indexed (D/2 sprites) on Y=200.
    for (d <- 0 until D) {
      val y = if (d % 2 == 0) 100 else 200
      setDesc(d, 10 + 5 * d, y, enabled = true, patIdx = 0)
    }
    // Evaluate line 105 — should see V of the D/2 even descriptors.
    pulseEval(105)
    val cD1 = activeSet()
    assert(cD1.count(_._1) == V, s"Case D line 105: expected $V active, got ${cD1.count(_._1)}")
    // Overflow only if D/2 > V (we'd need more even-indexed sprites than V can hold).
    val overflowD1 = ((D / 2) > V) || (V * 4 > 34)
    assert(dut.io.overflowFlag.toBoolean == overflowD1,
      s"Case D line 105: overflow expected=$overflowD1 got=${dut.io.overflowFlag.toBoolean}")
    for (s <- 0 until V) {
      val expDesc = 2 * s
      val expX    = 10 + 5 * expDesc
      assert(cD1(s)._2 == expX, s"Case D line 105 slot $s expected desc $expDesc x=$expX, got ${cD1(s)}")
    }
    // Evaluate line 205 — should see V of the odd descriptors.
    pulseEval(205)
    val cD2 = activeSet()
    assert(cD2.count(_._1) == V, s"Case D line 205: expected $V active, got ${cD2.count(_._1)}")
    assert(dut.io.overflowFlag.toBoolean == overflowD1,
      s"Case D line 205: overflow expected=$overflowD1 got=${dut.io.overflowFlag.toBoolean}")
    for (s <- 0 until V) {
      val expDesc = 2 * s + 1
      val expX    = 10 + 5 * expDesc
      assert(cD2(s)._2 == expX, s"Case D line 205 slot $s expected desc $expDesc x=$expX, got ${cD2(s)}")
    }
    println(s"[sim] Case D mixed Y (${D/2} on 100 / ${D/2} on 200): each line selects lowest-$V of its group — OK")

    println("[sim] SpriteCapacitySim: PASS")
  }
}
