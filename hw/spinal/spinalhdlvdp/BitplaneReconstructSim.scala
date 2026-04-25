package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Unit sim for `BitplaneReconstruct`.
  *
  * Cases:
  *   1. 2-plane / 8-bit-row golden vector matching the existing R4.1b
  *      planar decode rule (`px = plane1[idx] ## plane0[idx]`).
  *   2. 5-plane / 8-bit-row identity vectors — exercises every plane
  *      contributing the only set bit, confirming bit-position layout.
  *   3. MSB-first sweep on a 5-plane row — bitIdx 0 must select the
  *      leftmost on-screen bit (the high bit of each plane).
  *   4. 1-plane edge case — pixel == single-plane bit (degenerate but
  *      should compile and behave).
  */
object BitplaneReconstructSim extends App {

  // -------- Case 1: 2-plane / 8-bit golden ---------------------------------
  Config.sim.compile(BitplaneReconstruct(planeCount = 2, planeWidth = 8)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val plane0 = 0xC3   // 11000011
    val plane1 = 0xA5   // 10100101
    dut.io.planes(0) #= plane0
    dut.io.planes(1) #= plane1

    // Sweep bitIdx 0..7 (left-to-right on screen) and verify
    //   pixel == plane1[7-idx] ## plane0[7-idx]
    for (idx <- 0 until 8) {
      dut.io.bitIdx #= idx
      sleep(1)
      val msbFirst = 7 - idx
      val expected = ((plane1 >> msbFirst) & 1) << 1 | ((plane0 >> msbFirst) & 1)
      val got      = dut.io.pixel.toInt
      assert(got == expected,
        s"Case 1 idx=$idx: expected ${expected.toBinaryString} got ${got.toBinaryString}")
    }
    println("[sim] Case 1 — 2-plane/8-bit golden — OK")
  }

  // -------- Case 2: 5-plane / 8-bit identity vectors -----------------------
  Config.sim.compile(BitplaneReconstruct(planeCount = 5, planeWidth = 8)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // For each plane index p in 0..4, set planes(p) = 0x80 (only the leftmost
    // on-screen bit), all others = 0. With bitIdx=0 (leftmost), pixel must
    // equal 1 << p (only that plane's bit set in the output).
    for (p <- 0 until 5) {
      for (j <- 0 until 5) dut.io.planes(j) #= (if (j == p) 0x80 else 0x00)
      dut.io.bitIdx #= 0
      sleep(1)
      val expected = 1 << p
      assert(dut.io.pixel.toInt == expected,
        s"Case 2 p=$p: expected ${expected.toBinaryString} got ${dut.io.pixel.toInt.toBinaryString}")
    }
    println("[sim] Case 2 — 5-plane identity-bit walk — OK")

    // -------- Case 3: 5-plane MSB-first sweep ------------------------------
    // planes(p) = 1 << p (only bit p is set, on the LSB end). With bitIdx
    // sweeping leftward (0 = leftmost), only bitIdx=7 (rightmost) should
    // produce a non-zero pixel — and that pixel == 0b11111 (all planes set).
    for (p <- 0 until 5) dut.io.planes(p) #= (1 << p)   // plane p has bit 0 set
    for (idx <- 0 until 8) {
      dut.io.bitIdx #= idx
      sleep(1)
      val msbFirst = 7 - idx
      val expected = (0 until 5).map { p =>
        if (((1 << p) >> msbFirst & 1) == 1) (1 << p) else 0
      }.sum
      assert(dut.io.pixel.toInt == expected,
        s"Case 3 idx=$idx: expected $expected got ${dut.io.pixel.toInt}")
    }
    println("[sim] Case 3 — 5-plane MSB-first sweep — OK")
  }

  // -------- Case 4: 1-plane degenerate -------------------------------------
  Config.sim.compile(BitplaneReconstruct(planeCount = 1, planeWidth = 8)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    dut.io.planes(0) #= 0x95   // 10010101
    for (idx <- 0 until 8) {
      dut.io.bitIdx #= idx
      sleep(1)
      val msbFirst = 7 - idx
      val expected = (0x95 >> msbFirst) & 1
      assert(dut.io.pixel.toInt == expected,
        s"Case 4 idx=$idx: expected $expected got ${dut.io.pixel.toInt}")
    }
    println("[sim] Case 4 — 1-plane degenerate — OK")
  }

  // -------- Case 5: 5-plane / 32-bit-row (dout32 aperture shape) -----------
  // Mode0 hardening assessment §6.3 calls for `dout32` 32-bit reads as the
  // wide-read primitive. Verify the same algorithm holds at width 32 so the
  // `BitplaneRowFetch` integration can wire 32-bit plane rows directly.
  Config.sim.compile(BitplaneReconstruct(planeCount = 5, planeWidth = 32)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val rng = new scala.util.Random(0xBEEF)
    val rows = Array.fill(5)(rng.nextLong() & 0xFFFFFFFFL)
    for (p <- 0 until 5) dut.io.planes(p) #= rows(p)

    for (idx <- 0 until 32) {
      dut.io.bitIdx #= idx
      sleep(1)
      val msbFirst = 31 - idx
      val expected = (0 until 5).map { p =>
        ((rows(p).toInt >>> msbFirst) & 1) << p
      }.sum
      assert(dut.io.pixel.toInt == expected,
        s"Case 5 idx=$idx: expected $expected got ${dut.io.pixel.toInt}")
    }
    println("[sim] Case 5 — 5-plane / 32-bit-row (dout32 shape) — OK")
  }

  println("[sim] BitplaneReconstructSim: PASS")
}
