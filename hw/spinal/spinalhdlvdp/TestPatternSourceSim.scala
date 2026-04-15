package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

object TestPatternSourceSim extends App {
  Config.sim.compile(TestPatternSource()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 40)

    // Helper to sample one pixel
    def sample(x: Int, y: Int, pat: Int): (Int, Int) = {
      dut.io.x #= x
      dut.io.y #= y
      dut.io.patternSelect #= pat
      dut.clockDomain.waitSampling(1)
      (dut.io.pixelIndex.toInt, dut.io.paletteBank.toInt)
    }

    // --- Pattern 0: Color bars ---
    assert(sample(0,   0, 0)._1 == 0)   // leftmost bar = black
    assert(sample(79,  0, 0)._1 == 0)   // still bar 0
    assert(sample(80,  0, 0)._1 == 1)   // bar 1 = white
    assert(sample(160, 0, 0)._1 == 2)   // bar 2 = red
    assert(sample(560, 0, 0)._1 == 7)   // rightmost bar = magenta
    assert(sample(0,   0, 0)._2 == 0)   // bank 0

    // --- Pattern 1-4: Solid fields ---
    assert(sample(100, 100, 1) == (0xF, 1)) // red
    assert(sample(100, 100, 2) == (0xF, 2)) // green
    assert(sample(100, 100, 3) == (0xF, 3)) // blue
    assert(sample(100, 100, 4) == (0xF, 4)) // gray

    // --- Pattern 5: Checkerboard ---
    // (0,0) -> black, (16,0) -> white, (0,16) -> white, (16,16) -> black
    assert(sample(0,   0,  5) == (0x0, 0))
    assert(sample(16,  0,  5) == (0xF, 0))
    assert(sample(0,  16,  5) == (0xF, 0))
    assert(sample(16, 16,  5) == (0x0, 0))

    // --- Pattern 6: Grid ---
    assert(sample(0,  0, 6) == (0xF, 0))  // hLine
    assert(sample(0,  1, 6) == (0xF, 0))  // vLine
    assert(sample(1,  1, 6) == (0x0, 0))  // interior
    assert(sample(64, 0, 6) == (0xF, 0))  // next hLine
    assert(sample(0, 64, 6) == (0xF, 0))  // next vLine

    // --- Pattern 7: Vertical stripes ---
    assert(sample(0, 0, 7) == (0x0, 0))
    assert(sample(1, 0, 7) == (0xF, 0))
    assert(sample(2, 0, 7) == (0x0, 0))
    assert(sample(3, 0, 7) == (0xF, 0))

    println("TestPatternSourceSim: all assertions passed")
    simSuccess()
  }
}
