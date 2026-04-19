package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 19 Checkpoint B end-to-end sim: affine register + stepper + texture +
  * L0 source mux all wired together inside VdpTop.
  *
  * Scenario: identity matrix, affineEnable=1. Verifies that at active pixel
  * (x, y) the VdpTop's `layer0Index`/`layer0Bank` signals match the expected
  * texel lookup `AffineAssets.texel(x & 0x7F, y & 0x7F)`.
  *
  * Also verifies that with affineEnable=0 (default) the affine path has zero
  * effect and the existing L0 sources win — this protects Checkpoint A's
  * backward-compat guarantee.
  */
object AffineVdpTopSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent stimulus (matches VdpTopSim defaults).
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1
    dut.io.regBus.addr #= 0; dut.io.regBus.data #= 0; dut.io.regBus.enable #= false
    dut.io.layer0UseSdram #= false
    dut.io.layer0TestPatternEnable #= false
    dut.io.layer0TestPatternSelect #= 0
    dut.io.layer0SdramPixel #= 0
    dut.io.layer0SdramBank #= 0
    dut.io.layer0SdramPriority #= false
    dut.io.rasterTriggerLine #= 0
    dut.io.rasterTriggerPixel #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear #= false

    dut.clockDomain.waitSampling()

    def writeReg(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr #= addr
      dut.io.regBus.data #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
    }

    def waitForCommit(): Unit = {
      // Two hTotal cycles is plenty for any pending register to commit.
      for (_ <- 0 until 2000) dut.clockDomain.waitSampling()
    }

    def waitForActivePixel(ex: Int, ey: Int): Unit = {
      var cap = 800 * 525 * 3
      while (cap > 0 && !(dut.io.de.toBoolean && dut.io.x.toInt == ex && dut.io.y.toInt == ey)) {
        dut.clockDomain.waitSampling(); cap -= 1
      }
      assert(cap > 0, s"timed out waiting for active pixel ($ex,$ey)")
    }

    // --- Step 1: defaults — affineEnable=0, no affine influence ---
    // Settle through one full frame so committed registers are all at defaults.
    for (_ <- 0 until 800 * 525 + 100) dut.clockDomain.waitSampling()
    waitForActivePixel(0, 0)
    val baselineIndex00 = dut.layer0Index.toLong
    val baselineBank00  = dut.layer0Bank.toLong
    println(f"Step 1: baseline L0 at (0,0) index=$baselineIndex00 bank=$baselineBank00")

    // --- Step 2: enable affine with identity matrix ---
    writeReg(0x0340, 0x0100)   // A = 1.0
    writeReg(0x0341, 0x0000)   // B = 0
    writeReg(0x0342, 0x0000)   // C = 0
    writeReg(0x0343, 0x0100)   // D = 1.0
    writeReg(0x0344, 0x0000)   // X = 0
    writeReg(0x0345, 0x0000)   // Y = 0
    writeReg(0x0346, 0x0001)   // CTRL: affineEnable = 1
    waitForCommit()

    // Verify a handful of pixels match the expected texel after commit.
    val probes = Seq(
      (0, 0),    // grid (x%16=0 and y%16=0) → index 2, bank 0
      (1, 1),    // non-grid → index 1, bank 0
      (16, 0),   // grid column → index 2, bank 0
      (0, 16),   // grid row → index 2, bank 0
      (3, 5),    // interior → index 1, bank 0
      (0, 32),   // top of bank-1 row → grid + bank 1
      (7, 35),   // interior of bank 1 → index 1, bank 1
    )
    probes.foreach { case (x, y) =>
      waitForActivePixel(x, y)
      val expectedPixel = AffineAssets.texel(x & 0x7F, y & 0x7F)
      val expectedIdx = expectedPixel & 0xF
      val expectedBank = (expectedPixel >> 4) & 0x7
      val expectedPrio = (expectedPixel >> 7) & 0x1
      val gotIdx = dut.layer0Index.toLong.toInt
      val gotBank = dut.layer0Bank.toLong.toInt
      val gotPrio = if (dut.layer0Prio.toBoolean) 1 else 0
      assert(gotIdx == expectedIdx && gotBank == expectedBank && gotPrio == expectedPrio,
        f"affine ($x%3d, $y%3d): got (idx=$gotIdx, bank=$gotBank, prio=$gotPrio) expected (idx=$expectedIdx, bank=$expectedBank, prio=$expectedPrio)")
    }
    println(f"Step 2 PASS: ${probes.size} affine-pixel probes match texel oracle")

    // --- Step 3: disable affine, verify baseline restored ---
    writeReg(0x0346, 0x0000)
    waitForCommit()
    waitForActivePixel(0, 0)
    val postDisableIndex00 = dut.layer0Index.toLong
    val postDisableBank00  = dut.layer0Bank.toLong
    assert(postDisableIndex00 == baselineIndex00 && postDisableBank00 == baselineBank00,
      s"affine disable should restore baseline at (0,0); got idx=$postDisableIndex00 bank=$postDisableBank00 expected idx=$baselineIndex00 bank=$baselineBank00")
    println(f"Step 3 PASS: affineEnable=0 restores baseline L0 at (0,0)")

    println("AffineVdpTopSim: Checkpoint B end-to-end mux + texture + stepper PASS")
  }
}
