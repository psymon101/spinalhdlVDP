package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** R5.4 ScrollWrap unit test.
  *
  * Exercises two representative parameterizations:
  *   1. `ScrollWrap(640, 10, 10)` — typical BasicPatternSource/VdpTop case
  *      (coord = hCounter 0..639, scroll = 0..1023).  Max sum = 1662,
  *      needs up to 2 wraps.
  *   2. `ScrollWrap(640, 6, 10)` — SdramTileAttributeFetch case where
  *      `coord = tileIdx * 16` only uses 6 bits of address + mul-constant
  *      (see the `rawX` site). Max sum = 63+1023 = 1086, ≤2 wraps.
  *
  * Each config sweeps representative boundary cases and verifies against
  * a Scala-side reference `(coord + scroll) mod mapWidth`.
  */
object ScrollWrapSim extends App {
  def check(mapWidth: Int, cW: Int, sW: Int, cases: Seq[(Int, Int)]): Unit = {
    Config.sim.compile(ScrollWrap(mapWidth, cW, sW)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      // Combinational component — one cycle settles plenty.
      for ((c, s) <- cases) {
        dut.io.coord  #= c
        dut.io.scroll #= s
        dut.clockDomain.waitSampling()
        val exp = ((c + s) % mapWidth)
        val got = dut.io.result.toInt
        assert(got == exp,
          s"ScrollWrap($mapWidth, $cW, $sW) coord=$c scroll=$s: expected $exp got $got")
      }
      println(s"[sim] ScrollWrap($mapWidth, $cW, $sW): all ${cases.length} cases OK")
    }
  }

  // Config 1: 640-wide map, 10-bit coord, 10-bit scroll (BasicPatternSource style)
  {
    val cases = Seq.newBuilder[(Int, Int)]
    // Explicit boundary cases
    cases += ((0, 0))
    cases += ((639, 0))
    cases += ((0, 639))
    cases += ((0, 640))
    cases += ((0, 1023))
    cases += ((639, 1))                 // sum = 640 exactly
    cases += ((639, 1023))              // max sum = 1662
    cases += ((384, 1023))              // previous bug zone for the truncation class
    cases += ((500, 500))               // sum = 1000 (single-wrap region)
    cases += ((600, 700))               // sum = 1300 (double-wrap region)
    // Stride sweep
    for (c <- 0 to 639 by 37; s <- 0 to 1023 by 53) cases += ((c, s))
    check(640, 10, 10, cases.result())
  }

  // Config 2: 640-wide map, 6-bit coord, 10-bit scroll (rawX-style)
  {
    val cases = Seq.newBuilder[(Int, Int)]
    for (c <- 0 to 63; s <- Seq(0, 1, 639, 640, 1023, 500, 960)) cases += ((c, s))
    check(640, 6, 10, cases.result())
  }

  println("[sim] ScrollWrapSim: PASS")
}
