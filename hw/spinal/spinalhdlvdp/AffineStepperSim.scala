package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 19 Checkpoint B: verify the AffineStepper math.
  *
  * Each test case writes a known matrix, waits a cycle for combinational
  * settle, then checks (uInt, vInt) at representative (x, y) pairs against
  * hand-calculated expectations. Covers:
  *   1. Identity           (u = x, v = y)
  *   2. 90° CW rotation    (u = y, v = -x → wrap)
  *   3. 2× scale-down      (u = x/2, v = y/2)
  *   4. Shear              (u = x + y/2)
  *   5. Translation        (u = x + 10, v = y + 20)
  *   6. Negative wrap      (u = x - 5 → positive 7-bit mod-128)
  *   7. Rotation with centre offset (u = sx*x + cx*y + tx etc.)
  *
  * 8.8 fixed-point encoding: 1.0 = 0x0100, 0.5 = 0x0080, -1.0 = 0xFF00.
  * 10.6 fixed-point encoding (translation): 1.0 = 0x0040.
  */
object AffineStepperSim extends App {
  Config.sim.compile(AffineStepper()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    def apply(mA: Int, mB: Int, mC: Int, mD: Int, tX: Int, tY: Int,
              x: Int, y: Int): (Int, Int) = {
      dut.io.matrixA #= mA & 0xFFFF
      dut.io.matrixB #= mB & 0xFFFF
      dut.io.matrixC #= mC & 0xFFFF
      dut.io.matrixD #= mD & 0xFFFF
      dut.io.transX  #= tX & 0xFFFF
      dut.io.transY  #= tY & 0xFFFF
      dut.io.x #= x
      dut.io.y #= y
      dut.clockDomain.waitSampling()   // combinational, 1 cycle is enough to settle
      sleep(1)                          // let combinational paths propagate in the sim
      (dut.io.uInt.toInt, dut.io.vInt.toInt)
    }

    def check(label: String, mA: Int, mB: Int, mC: Int, mD: Int, tX: Int, tY: Int,
              points: Seq[((Int, Int), (Int, Int))]): Unit = {
      points.foreach { case ((x, y), (expU, expV)) =>
        val (u, v) = apply(mA, mB, mC, mD, tX, tY, x, y)
        assert(u == expU && v == expV,
          f"$label%-28s(x=$x%3d, y=$y%3d): got (u=$u%3d, v=$v%3d) expected ($expU%3d, $expV%3d)")
      }
      println(f"$label%-28sPASS (${points.size} points)")
    }

    // 8.8 constants
    val q1    = 0x0100   //  1.0
    val qHalf = 0x0080   //  0.5
    val qNeg1 = 0xFF00   // -1.0
    val q0    = 0x0000   //  0.0

    // 10.6 translation constants
    val t10   = 10 * 64          // 0x0280
    val t20   = 20 * 64          // 0x0500
    val tNeg5 = (-5 * 64) & 0xFFFF

    // --- Case 1: identity ---
    check("identity", q1, q0, q0, q1, 0, 0, Seq(
      ((0, 0), (0, 0)),
      ((5, 7), (5, 7)),
      ((127, 127), (127, 127)),
      ((130, 130), (2, 2)),    // wraps modulo 128
    ))

    // --- Case 2: 90° CW rotation → u = y, v = -x ---
    // Mathematically [u,v] = [[0,1],[-1,0]] [x,y]
    check("rotation-90-cw", q0, q1, qNeg1, q0, 0, 0, Seq(
      ((0, 0), (0, 0)),
      ((3, 5), (5, (0 - 3) & 0x7F)),     // v = -3 → 125
      ((10, 20), (20, (0 - 10) & 0x7F)), // v = -10 → 118
    ))

    // --- Case 3: 2× scale-down → u = x/2, v = y/2 ---
    check("scale-half", qHalf, q0, q0, qHalf, 0, 0, Seq(
      ((0, 0), (0, 0)),
      ((10, 20), (5, 10)),
      ((128, 128), (64, 64)),
    ))

    // --- Case 4: shear → u = x + y/2, v = y ---
    check("shear-0.5-y", q1, qHalf, q0, q1, 0, 0, Seq(
      ((0, 0), (0, 0)),
      ((10, 20), (20, 20)),      // 10 + 10 = 20
      ((0, 40), (20, 40)),
    ))

    // --- Case 5: translation X=10, Y=20 ---
    check("translate-(10,20)", q1, q0, q0, q1, t10, t20, Seq(
      ((0, 0), (10, 20)),
      ((5, 5), (15, 25)),
      ((118, 100), (0, 120)),    // 118+10=128 wraps to 0
    ))

    // --- Case 6: negative wrap ---
    check("translate-(-5,-5)", q1, q0, q0, q1, tNeg5, tNeg5, Seq(
      ((0, 0), (123, 123)),      // -5 mod 128 = 123
      ((3, 3), (126, 126)),
      ((5, 5), (0, 0)),
      ((10, 10), (5, 5)),
    ))

    // --- Case 7: combined rotation + translation (45° is awkward in 8.8,
    //     so test a 0.75x scale-down + translation combination instead) ---
    //     u = 0.75*x + 0*y + 2,  v = 0*x + 0.75*y + 2
    val qThreeQuarters = 0x00C0  // 0.75
    val t2 = 2 * 64
    check("scale-0.75-translate-2", qThreeQuarters, q0, q0, qThreeQuarters, t2, t2, Seq(
      ((0, 0), (2, 2)),
      ((4, 4), (5, 5)),          // 0.75*4 + 2 = 5
      ((8, 8), (8, 8)),          // 0.75*8 + 2 = 8
      ((12, 12), (11, 11)),      // 0.75*12 + 2 = 11
    ))

    println("AffineStepperSim: all matrix cases passed (Task 19 Checkpoint B math)")
  }
}
