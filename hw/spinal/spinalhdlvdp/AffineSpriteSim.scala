package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 37 — Checkpoint A simulation.
  *
  * Proves the bus-programmed affine path end-to-end at the evaluator level:
  *   1. Writes all 8 words of an extended slot via `io.bus*`.
  *   2. Peeks the Reg-Vec storage (simPublic taps) to confirm every
  *      matrix / translation word latches.
  *   3. Strobes `evalStart` for a scanline that covers the sprite and
  *      confirms the Pass-1 FSM copies `affineEnable` and every matrix
  *      word into the active-slot output vec.
  *
  * Task 28 SpriteEvaluatorSim (7 cases) remains unchanged and is the
  * regression guarantee for the flat-sprite path (affineEnable=False
  * produces bit-identical behaviour to the Task 28 baseline).
  */
object AffineSpriteSim extends App {
  Config.sim.compile(SpriteEvaluator(
      descCount = 64, visiblePerLine = 32,
      patternSelBits = 4, legacyIoCount = 4))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Quiescent IO (legacy slots 0..3 disabled off-screen).
      for (i <- 0 until 4) {
        dut.io.descX(i)          #= 1000
        dut.io.descY(i)          #= 1000
        dut.io.descEnabled(i)    #= false
        dut.io.descPatternIdx(i) #= 0
      }
      dut.io.busSlot   #= 0
      dut.io.busWord   #= 0
      dut.io.busData   #= 0
      dut.io.busWr     #= false
      dut.io.evalLine  #= 0
      dut.io.evalStart #= false
      dut.clockDomain.waitSampling(5)

      def busPulse(slot: Int, word: Int, data: Int): Unit = {
        dut.io.busSlot #= slot
        dut.io.busWord #= word
        dut.io.busData #= data & 0xFFFF
        dut.io.busWr   #= true
        dut.clockDomain.waitSampling()
        dut.io.busWr   #= false
        dut.clockDomain.waitSampling()
      }

      // Program slot 4 (rel 0) with all 8 words.
      val sY = 200
      val sX = 80
      val W0 = 0x8000 | (2 << 11) | (1 << 10) | sY
      val A  = 0x0100   // Q8.8 = 1.0
      val B  = 0x0040   // Q8.8 = 0.25
      val C  = 0xFFC0   // Q8.8 = -0.25
      val D  = 0x00C0   // Q8.8 = 0.75
      val TX = 0x4321
      val TY = 0x1234

      busPulse(4, 0, W0)
      busPulse(4, 1, sX)
      busPulse(4, 2, A)
      busPulse(4, 3, B)
      busPulse(4, 4, C)
      busPulse(4, 5, D)
      busPulse(4, 6, TX)
      busPulse(4, 7, TY)

      dut.clockDomain.waitSampling(3)

      // Phase 1: check Reg-Vec storage (rel 0 corresponds to slot 4).
      def check(label: String, got: Int, exp: Int): Unit = {
        val ok = (got & 0xFFFF) == (exp & 0xFFFF)
        println(f"  reg.$label%-10s got=0x$got%04X exp=0x$exp%04X ${if (ok) "PASS" else "FAIL"}")
        assert(ok, s"regstore mismatch on $label")
      }
      val rel = 0
      check("enabled",  if (dut.regEnabled(rel).toBoolean) 1 else 0, 1)
      check("patIdx",   dut.regPatternIndex(rel).toInt, 2)
      check("affine",   if (dut.regAffineEnable(rel).toBoolean) 1 else 0, 1)
      check("x",        dut.regX(rel).toInt, sX)
      check("y",        dut.regY(rel).toInt, sY)
      check("matrixA",  dut.regMatrixA(rel).toInt, A)
      check("matrixB",  dut.regMatrixB(rel).toInt, B)
      check("matrixC",  dut.regMatrixC(rel).toInt, C)
      check("matrixD",  dut.regMatrixD(rel).toInt, D)
      check("transX",   dut.regTransX(rel).toInt, TX)
      check("transY",   dut.regTransY(rel).toInt, TY)

      // Phase 2: kick off Pass-1 scan for a line covering sprite y=200.
      dut.io.evalLine  #= 205
      dut.io.evalStart #= true
      dut.clockDomain.waitSampling()
      dut.io.evalStart #= false
      // Let the 8-cycle scan complete.
      dut.clockDomain.waitSampling(20)

      println("-- phase 2: active-slot propagation (slot 0 expected) --")
      def checkActive(label: String, got: Int, exp: Int): Unit = {
        val ok = (got & 0xFFFF) == (exp & 0xFFFF)
        println(f"  active.$label%-12s got=0x$got%04X exp=0x$exp%04X ${if (ok) "PASS" else "FAIL"}")
        assert(ok, s"active mismatch on $label")
      }
      assert(dut.io.activeValid(0).toBoolean,
             "Pass-1 did not mark slot 0 valid for affine sprite")
      assert(dut.io.activeAffineEnable(0).toBoolean,
             "Pass-1 did not propagate affineEnable=True")
      checkActive("x",        dut.io.activeX(0).toInt, sX)
      checkActive("matrixA",  dut.io.activeMatrixA(0).toInt, A)
      checkActive("matrixB",  dut.io.activeMatrixB(0).toInt, B)
      checkActive("matrixC",  dut.io.activeMatrixC(0).toInt, C)
      checkActive("matrixD",  dut.io.activeMatrixD(0).toInt, D)
      checkActive("transX",   dut.io.activeTransX(0).toInt, TX)
      checkActive("transY",   dut.io.activeTransY(0).toInt, TY)

      println("AffineSpriteSim: PASS")
    }

}
