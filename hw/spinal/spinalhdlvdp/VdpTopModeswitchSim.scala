package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 21 debug Step 1: prove mid-frame VDP_TILE_MODE switching via the
  * regWriteBus actually transitions `tileDecodeModeReg` at the next safe
  * boundary (`hCounter===0`).
  *
  * The Sc15 capture (post option-1 fix at 5a098ff) shows pixel-identical bands
  * across the three would-be regions. Either the mode register isn't changing
  * (problem in the pixel-domain commit path), or it IS changing but the
  * downstream fetch engine doesn't see it in time (CDC `BufferCC` in
  * `SdramTileAttributeFetch.scala:328-332`). This sim isolates which.
  *
  * Test sequence:
  *   1. Read default tileDecodeModeReg == 0 (packed/tile).
  *   2. Drive a synthetic mid-frame write of 0x0001 (planar) at vCounter≈160.
  *   3. Wait past the next hCounter===0 commit edge.
  *   4. Assert tileDecodeModeReg == 0x0001.
  *   5. Drive 0x0002 (shuffled) at vCounter≈320, wait, assert == 0x0002.
  *
  * If this sim PASSES → pixel-domain commit is fine, look downstream (CDC).
  * If it FAILS → safe-boundary commit ordering is the bug; fix is in VdpTop.
  */
object VdpTopModeswitchSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val hTotal = 800
    val vActive = 480

    // Quiescent stimulus (matches VdpTopSim defaults).
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1
    dut.io.regWriteAddr #= 0; dut.io.regWriteData #= 0; dut.io.regWriteEnable #= false
    dut.io.layer0UseSdram #= false   // doesn't matter for this sim — we only watch the reg
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
      dut.io.regWriteAddr #= addr
      dut.io.regWriteData #= data
      dut.io.regWriteEnable #= true
      dut.clockDomain.waitSampling()
      dut.io.regWriteEnable #= false
    }

    def runCycles(n: Int): Unit = {
      for (_ <- 0 until n) dut.clockDomain.waitSampling()
    }

    // Settle to default state, observe the reg via io.layer0TileDecodeMode (output).
    runCycles(50)
    val initialMode = dut.io.layer0TileDecodeMode.toLong
    println(f"Step A: initial tileDecodeMode = 0x${initialMode}%X (expect 0x0)")
    assert(initialMode == 0, s"expected default 0x0, got 0x${initialMode.toHexString}")

    // --- Step 1: write 0x0311 = 0x0001 (planar) mid-line, wait through one
    //     full hTotal so the safe-boundary commit edge is guaranteed to land. ---
    writeReg(0x0311, 0x0001)
    runCycles(hTotal + 10)
    val modeAfterPlanar = dut.io.layer0TileDecodeMode.toLong
    println(f"Step B: after writing 0x0311=0x0001 + hTotal cycles: tileDecodeMode = 0x${modeAfterPlanar}%X (expect 0x1)")
    assert(modeAfterPlanar == 0x1,
      s"expected 0x1 after commit edge, got 0x${modeAfterPlanar.toHexString} — pixel-domain commit FAILED")

    // --- Step 2: write 0x0311 = 0x0002 (shuffled), wait, assert. ---
    writeReg(0x0311, 0x0002)
    runCycles(hTotal + 10)
    val modeAfterShuffled = dut.io.layer0TileDecodeMode.toLong
    println(f"Step C: after writing 0x0311=0x0002 + hTotal cycles: tileDecodeMode = 0x${modeAfterShuffled}%X (expect 0x2)")
    assert(modeAfterShuffled == 0x2,
      s"expected 0x2 after commit edge, got 0x${modeAfterShuffled.toHexString} — pixel-domain commit FAILED")

    // --- Step 3: write 0x0311 = 0x0000 (back to packed), prove cycling. ---
    writeReg(0x0311, 0x0000)
    runCycles(hTotal + 10)
    val modeBackToPacked = dut.io.layer0TileDecodeMode.toLong
    println(f"Step D: after writing 0x0311=0x0000 + hTotal cycles: tileDecodeMode = 0x${modeBackToPacked}%X (expect 0x0)")
    assert(modeBackToPacked == 0x0,
      s"expected 0x0 after commit edge, got 0x${modeBackToPacked.toHexString} — pixel-domain commit FAILED")

    println("VdpTopModeswitchSim: pixel-domain VDP_TILE_MODE commits as expected — issue is downstream of tileDecodeModeReg.")
  }
}
