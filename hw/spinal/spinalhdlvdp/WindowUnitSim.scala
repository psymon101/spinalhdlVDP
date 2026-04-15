package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** R6 Task 20: WindowUnit sim.
  *
  * Per task artifact validation plan #1:
  *   - Program winX0=100, winX1=540, winY0=100, winY1=380.
  *   - Drive raster coords and verify `inside` is true ONLY in [100,540) × [100,380).
  *   - Test invert=1 and verify the inversion semantics.
  */
object WindowUnitSim extends App {
  Config.sim.compile(WindowUnit()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.invert #= false
    dut.io.winX0  #= 100
    dut.io.winX1  #= 540
    dut.io.winY0  #= 100
    dut.io.winY1  #= 380
    dut.io.hCounter #= 0
    dut.io.vCounter #= 0
    dut.clockDomain.waitSampling(2)

    def probe(x: Int, y: Int): (Boolean, Boolean) = {
      dut.io.hCounter #= x
      dut.io.vCounter #= y
      sleep(1)
      (dut.io.inside.toBoolean, dut.io.effect.toBoolean)
    }

    // ---- Case 1: edge cases on the rectangle boundary -----------------------
    val edgeProbes = Seq(
      // (x, y, expectedInside, label)
      ( 99,  99, false, "outside top-left corner"),
      (100, 100, true,  "inclusive top-left corner"),
      (539, 379, true,  "inclusive bottom-right corner"),
      (540, 380, false, "exclusive bottom-right corner"),
      (320, 240, true,  "centre"),
      (100,  99, false, "above top edge"),
      ( 99, 100, false, "left of left edge"),
      (540, 100, false, "right of right edge"),
      (100, 380, false, "below bottom edge"),
      (  0,   0, false, "origin"),
      (639, 479, false, "screen far corner"),
    )
    for ((x, y, exp, label) <- edgeProbes) {
      val (ins, eff) = probe(x, y)
      assert(ins == exp,
        s"case1 $label @($x,$y): inside got=$ins exp=$exp")
      assert(eff == exp,
        s"case1 $label @($x,$y) invert=0: effect got=$eff exp=$exp")
    }
    println(s"[sim] case1 rectangle [100,540)x[100,380) edge & corner cases — OK")

    // ---- Case 2: invert=true flips inside/effect semantics ------------------
    dut.io.invert #= true
    sleep(1)
    val invertProbes = Seq(
      ( 99,  99, false, true),    // outside → effect=true
      (100, 100, true,  false),   // inside → effect=false
      (320, 240, true,  false),
      (540, 380, false, true),
    )
    for ((x, y, expIns, expEff) <- invertProbes) {
      val (ins, eff) = probe(x, y)
      assert(ins == expIns, s"case2 invert @($x,$y): inside got=$ins exp=$expIns")
      assert(eff == expEff, s"case2 invert @($x,$y): effect got=$eff exp=$expEff")
    }
    println(s"[sim] case2 invert=true flips effect — OK")

    // ---- Case 3: degenerate rectangle (winX0==winX1) yields no inside ------
    dut.io.invert #= false
    dut.io.winX0  #= 200
    dut.io.winX1  #= 200
    sleep(1)
    val (ins3, _) = probe(200, 240)
    assert(!ins3, s"case3 degenerate x0==x1: inside got=$ins3 exp=false")
    println(s"[sim] case3 degenerate rect (x0==x1) inside=false — OK")

    println("[sim] WindowUnitSim: PASS")
  }
}
