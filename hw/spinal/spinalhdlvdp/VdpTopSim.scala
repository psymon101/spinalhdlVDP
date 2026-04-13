package spinalhdlvdp

import spinal.core.sim._

object VdpTopSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val hTotal = 800
    val vTotal = 525
    val hActive = 640
    val vActive = 480

    def expectedRgb(x: Int, y: Int,
                     l0en: Boolean = true, l1en: Boolean = true,
                     l0sx: Int = 0, l0sy: Int = 0,
                     l1sx: Int = 0, l1sy: Int = 0,
                     lsScrollX: Int = 0): (Int, Int, Int) = {
      val effectiveL0sx = (l0sx + lsScrollX) & 0x3FF
      val l0 = if (l0en) BasicPatternSource.expectedPixelIndex(x, y, effectiveL0sx, l0sy) else 0
      val l1 = if (l1en) BasicPatternSource.expectedPixelIndex(x, y, l1sx, l1sy) else 0
      val bg = if (l1 != 0) l1 else l0
      VdpTop.paletteRgb(bg)
    }

    def waitForActivePixel(ex: Int, ey: Int): Unit = {
      var rem = hTotal * vTotal * 2
      while (rem > 0 && !(dut.io.de.toBoolean && dut.io.x.toInt == ex && dut.io.y.toInt == ey)) {
        dut.clockDomain.waitSampling(); rem -= 1
      }
      assert(rem > 0, s"Timed out waiting for ($ex,$ey)")
    }

    def checkFullLine(y: Int, l0en: Boolean = true, l1en: Boolean = true, label: String = ""): Unit = {
      waitForActivePixel(0, y)
      for (x <- 0 until hActive) {
        val (er, eg, eb) = expectedRgb(x, y, l0en = l0en, l1en = l1en)
        assert(dut.io.red.toInt == er && dut.io.green.toInt == eg && dut.io.blue.toInt == eb,
          s"$label@($x,$y): got (${dut.io.red.toInt},${dut.io.green.toInt},${dut.io.blue.toInt}) exp ($er,$eg,$eb)")
        if (x < hActive - 1) dut.clockDomain.waitSampling()
      }
    }

    def waitVsync(): Unit = {
      while (dut.io.vsync.toBoolean) dut.clockDomain.waitSampling()
      while (!dut.io.vsync.toBoolean) dut.clockDomain.waitSampling()
    }

    // Init.
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.sprite0X #= 1000; dut.io.sprite0Y #= 1000; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1000; dut.io.sprite1Y #= 1000; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1000; dut.io.sprite2Y #= 1000; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1000; dut.io.sprite3Y #= 1000; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 1
    dut.io.lsWriteAddr #= 0; dut.io.lsWriteData #= 0; dut.io.lsWriteEnable #= false
    // Task 15 L0 mux: stay on the on-chip source for the existing sim coverage.
    dut.io.layer0UseSdram #= false
    dut.io.layer0SdramPixel #= 0
    dut.io.layer0SdramBank #= 0
    dut.io.layer0SdramPriority #= false
    // R1 raster trigger defaults: disabled so VdpTopSim keeps its existing
    // pixel-level baseline behavior (no red-channel inversion).
    dut.io.rasterTriggerLine     #= 0
    dut.io.rasterTriggerPixel    #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable   #= false
    dut.io.rasterTriggerClear    #= false

    // --- Startup black ---
    dut.clockDomain.waitSampling()
    while (!dut.io.de.toBoolean) dut.clockDomain.waitSampling()
    assert(dut.io.red.toInt == 0 && dut.io.green.toInt == 0 && dut.io.blue.toInt == 0)

    // Wait for initial commit (all lines committed once).
    waitVsync(); waitVsync()

    // --- Band checks with committed initial linestate ---
    checkFullLine(50, l0en = true, l1en = true, label = "top-band")
    checkFullLine(200, l0en = false, l1en = true, label = "mid-band")
    checkFullLine(400, l0en = true, l1en = false, label = "bot-band")

    // --- Boundary transitions ---
    waitVsync()
    checkFullLine(159, l0en = true, l1en = true, label = "bound-159")
    checkFullLine(160, l0en = false, l1en = true, label = "bound-160")
    checkFullLine(319, l0en = false, l1en = true, label = "bound-319")
    checkFullLine(320, l0en = true, l1en = false, label = "bound-320")

    // --- Prepare/commit semantic proof ---

    // Step 1: Write prepare[50] to disable both layers (different from committed value).
    val newRecord = LinestateStore.packRecord(l0en = false, l1en = false, l0sx = 0).toInt
    dut.io.lsWriteAddr #= 50
    dut.io.lsWriteData #= newRecord
    dut.io.lsWriteEnable #= true
    dut.clockDomain.waitSampling()
    dut.io.lsWriteEnable #= false

    // Step 2: On this SAME frame, line 50 still shows the OLD committed value
    // (both layers enabled) because the line buffer was already filled from committed state.
    // The prepare write only goes to the prepare Mem.
    checkFullLine(50, l0en = true, l1en = true, label = "pre-commit-50")

    // Step 3: After the next line-boundary commit for line 50, the new value takes effect.
    // Wait for the next frame where line 50's commit fires (during fill of line 50).
    waitVsync()
    checkFullLine(50, l0en = false, l1en = false, label = "post-commit-50")

    // Step 4: Other lines remain unchanged — line 100 still shows original committed value.
    checkFullLine(100, l0en = true, l1en = true, label = "unchanged-100")
  }
}
