package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** R2 Two-Pass Sprite Evaluator validation sim.
  *
  * Covers TASK_R2_TWO_PASS_SPRITE_EVALUATOR.md validation requirements:
  *   - sprites on and off the line are selected correctly
  *   - bounded visible-per-line limit is enforced deterministically
  *   - overlap priority between selected sprites is stable
  *   - active-sprite staging remains stable for the full line
  *   - sprites outside the bounded active set do not leak through
  *   - crowded / overlap diagnostic scenes
  */
object SpriteEvaluatorSim extends App {
  Config.sim.compile(SpriteEvaluator(descCount = 4, visiblePerLine = 2)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def setDesc(idx: Int, x: Int, y: Int, enabled: Boolean, patIdx: Int = 0): Unit = {
      dut.io.descX(idx)          #= x
      dut.io.descY(idx)          #= y
      dut.io.descEnabled(idx)    #= enabled
      dut.io.descPatternIdx(idx) #= patIdx
    }

    def pulseEval(line: Int): Unit = {
      dut.io.evalLine  #= line
      dut.io.evalStart #= true
      dut.clockDomain.waitSampling()
      dut.io.evalStart #= false
      dut.clockDomain.waitSampling()
    }

    def activeSet(): Seq[(Boolean, Int, Int, Int, Int)] =
      (0 until 2).map { s =>
        (dut.io.activeValid(s).toBoolean,
         dut.io.activeX(s).toInt,
         dut.io.activeRow(s).toInt,
         dut.io.activePatternIdx(s).toInt,
         s)
      }

    // Defaults
    for (d <- 0 until 4) setDesc(d, 0, 0, enabled = false)
    dut.io.evalLine  #= 0
    dut.io.evalStart #= false
    dut.clockDomain.waitSampling(2)

    // --- Case 1: on/off line selection ---
    // Descriptors: 0@(100,50), 1@(200,50), 2@(300,200), 3@(400,200). All enabled.
    setDesc(0, 100,  50, enabled = true, patIdx = 0)
    setDesc(1, 200,  50, enabled = true, patIdx = 1)
    setDesc(2, 300, 200, enabled = true, patIdx = 0)
    setDesc(3, 400, 200, enabled = true, patIdx = 1)

    // Eval line 55 — descriptors 0 and 1 on line (Y=[50,66)), 2 & 3 off.
    pulseEval(55)
    val c1 = activeSet()
    assert(c1(0)._1 && c1(0)._2 == 100 && c1(0)._3 == 5 && c1(0)._4 == 0,
           s"Case 1 slot 0: got $c1")
    assert(c1(1)._1 && c1(1)._2 == 200 && c1(1)._3 == 5 && c1(1)._4 == 1,
           s"Case 1 slot 1: got $c1")
    assert(!dut.io.overflowFlag.toBoolean, "Case 1: unexpected overflow")
    println("[sim] Case 1 on-line select (line 55 picks desc 0,1) — OK")

    // --- Case 2: no sprites on the line ---
    pulseEval(130)  // no descriptor covers line 130
    val c2 = activeSet()
    assert(!c2(0)._1 && !c2(1)._1, s"Case 2: expected both slots invalid, got $c2")
    assert(!dut.io.overflowFlag.toBoolean, "Case 2: unexpected overflow")
    println("[sim] Case 2 empty-line — OK")

    // --- Case 3: overflow — all 4 on the same line, only 2 visible (lowest idx wins) ---
    setDesc(0, 100, 100, enabled = true, patIdx = 0)
    setDesc(1, 200, 100, enabled = true, patIdx = 1)
    setDesc(2, 300, 100, enabled = true, patIdx = 0)
    setDesc(3, 400, 100, enabled = true, patIdx = 1)
    pulseEval(110)
    val c3 = activeSet()
    assert(c3(0)._1 && c3(0)._2 == 100 && c3(0)._4 == 0, s"Case 3 slot 0: got $c3")
    assert(c3(1)._1 && c3(1)._2 == 200 && c3(1)._4 == 1, s"Case 3 slot 1: got $c3")
    assert(dut.io.overflowFlag.toBoolean, "Case 3: expected overflow flag set")
    println("[sim] Case 3 overflow (4 on-line, bounded to 2, lowest idx wins; overflow flag set) — OK")

    // --- Case 4: priority skip — disable desc 0, 1,2 should be picked ---
    setDesc(0, 100, 100, enabled = false)
    setDesc(1, 200, 100, enabled = true, patIdx = 1)
    setDesc(2, 300, 100, enabled = true, patIdx = 0)
    setDesc(3, 400, 100, enabled = true, patIdx = 1)
    pulseEval(110)
    val c4 = activeSet()
    assert(c4(0)._1 && c4(0)._2 == 200, s"Case 4 slot 0: got $c4")
    assert(c4(1)._1 && c4(1)._2 == 300, s"Case 4 slot 1: got $c4")
    println("[sim] Case 4 priority skip (desc 0 disabled → slots=(1,2)) — OK")

    // --- Case 5: active list stays stable for the full line ---
    // Re-establish case-4 state. Snapshot after pulseEval, then do many cycles of
    // "mid-line" (no new evalStart), wait. State must stay the same.
    pulseEval(110)
    val snap = activeSet()
    for (_ <- 0 until 2000) dut.clockDomain.waitSampling()
    val later = activeSet()
    assert(snap == later, s"Case 5 stability: snap=$snap later=$later")
    println("[sim] Case 5 active list stable across 2000 cycles without evalStart — OK")

    // --- Case 6: boundary Y ---
    // Desc 0 at Y=50; on-line for Y ∈ [50, 65], off for Y=49 or 66.
    for (d <- 1 to 3) setDesc(d, 0, 0, enabled = false)
    setDesc(0, 77, 50, enabled = true, patIdx = 0)
    pulseEval(50)
    assert(dut.io.activeValid(0).toBoolean && dut.io.activeRow(0).toInt == 0, "Case 6 Y=50 row=0")
    pulseEval(65)
    assert(dut.io.activeValid(0).toBoolean && dut.io.activeRow(0).toInt == 15, "Case 6 Y=65 row=15")
    pulseEval(66)
    assert(!dut.io.activeValid(0).toBoolean, "Case 6 Y=66 should be off-line")
    pulseEval(49)
    assert(!dut.io.activeValid(0).toBoolean, "Case 6 Y=49 should be off-line")
    println("[sim] Case 6 Y-boundary inclusive [Y..Y+15], exclusive above — OK")

    println("[sim] SpriteEvaluatorSim: PASS")
  }
}
