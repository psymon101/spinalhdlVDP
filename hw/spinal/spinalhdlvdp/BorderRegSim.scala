package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 50 v3 root-cause sim — verifies the BORDER_* register
  * decode + safe-boundary commit chain end-to-end.
  *
  * The v3 hardware capture (mail #8693, then post-Pico-halt re-capture)
  * showed the border display path is NOT actually taking effect on
  * silicon. Three candidate root causes:
  *   1. BORDER_CTRL bus decode at 0x0347 isn't matching effAddr.
  *   2. Safe-boundary commit isn't firing.
  *   3. The display mux logic is fine but BORDER_CTRL[0] never reads 1.
  *
  * This sim drives bus writes for all 5 border registers and asserts
  * each one reaches its `*Reg` after one hCounter==0 boundary. If any
  * fails, the bus decode or commit path is broken. If all pass, the
  * display mux integration is the bug instead.
  */
object BorderRegSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init — mirror PaletteRamSim / SpritePatternRamSim.
    dut.io.layer0ScrollX #= 0; dut.io.layer0ScrollY #= 0
    dut.io.layer1ScrollX #= 0; dut.io.layer1ScrollY #= 0
    dut.io.layer2ScrollX #= 0; dut.io.layer2ScrollY #= 0
    dut.io.layer3ScrollX #= 0; dut.io.layer3ScrollY #= 0
    dut.io.sprite0X #= 1023; dut.io.sprite0Y #= 1023; dut.io.sprite0Enabled #= false; dut.io.sprite0PatternIdx #= 0
    dut.io.sprite1X #= 1023; dut.io.sprite1Y #= 1023; dut.io.sprite1Enabled #= false; dut.io.sprite1PatternIdx #= 1
    dut.io.sprite2X #= 1023; dut.io.sprite2Y #= 1023; dut.io.sprite2Enabled #= false; dut.io.sprite2PatternIdx #= 0
    dut.io.sprite3X #= 1023; dut.io.sprite3Y #= 1023; dut.io.sprite3Enabled #= false; dut.io.sprite3PatternIdx #= 0
    dut.io.layer0UseSdram #= false
    dut.io.layer0SdramPixel #= 0
    dut.io.layer0SdramBank #= 0
    dut.io.layer0SdramPriority #= false
    dut.io.layer0TestPatternEnable #= false
    dut.io.layer0TestPatternSelect #= 0
    dut.io.bitmapSdramByte #= 0
    dut.io.bitmapSdramAttrByte #= 0
    dut.io.rasterTriggerLine #= 0
    dut.io.rasterTriggerPixel #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear #= false
    dut.io.statusEvQspiReady #= false; dut.io.statusEvQspiError #= false
    dut.io.regBus.addr #= 0; dut.io.regBus.data #= 0; dut.io.regBus.enable #= false

    dut.clockDomain.waitSampling(20)

    def busPulse(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.io.regBus.addr   #= 0
      dut.io.regBus.data   #= 0
    }

    /** Wait at least one full hTotal so any pending shadow fires its
      * safe-boundary commit. hTotal = 800 cycles for 640x480@60Hz. */
    def settleOneFrame(): Unit = dut.clockDomain.waitSampling(900)

    // --- Case 1: write BORDER_X0 only ---
    busPulse(0x033C, 192)
    settleOneFrame()
    val x0 = dut.borderX0Reg.toInt
    assert(x0 == 192, s"Case 1: BORDER_X0 expected 192, got $x0 (decode at 0x033C broken)")
    println(s"[sim] Case 1 BORDER_X0=192 committed — OK")

    // --- Case 2: write BORDER_X1 ---
    busPulse(0x033D, 448)
    settleOneFrame()
    val x1 = dut.borderX1Reg.toInt
    assert(x1 == 448, s"Case 2: BORDER_X1 expected 448, got $x1")
    println(s"[sim] Case 2 BORDER_X1=448 committed — OK")

    // --- Case 3: write BORDER_Y0 ---
    busPulse(0x033E, 144)
    settleOneFrame()
    val y0 = dut.borderY0Reg.toInt
    assert(y0 == 144, s"Case 3: BORDER_Y0 expected 144, got $y0")
    println(s"[sim] Case 3 BORDER_Y0=144 committed — OK")

    // --- Case 4: write BORDER_Y1 ---
    busPulse(0x033F, 336)
    settleOneFrame()
    val y1 = dut.borderY1Reg.toInt
    assert(y1 == 336, s"Case 4: BORDER_Y1 expected 336, got $y1")
    println(s"[sim] Case 4 BORDER_Y1=336 committed — OK")

    // --- Case 5: write BORDER_CTRL = enable | idx=24 = 0x1801 ---
    busPulse(0x0347, 0x1801)
    settleOneFrame()
    val ctrl = dut.borderCtrlReg.toInt
    assert(ctrl == 0x1801,
      f"Case 5: BORDER_CTRL expected 0x1801, got 0x$ctrl%04X (decode at 0x0347 broken or safe-boundary commit not firing)")
    println(f"[sim] Case 5 BORDER_CTRL=0x1801 committed — OK")

    println("[sim] BorderRegSim: PASS — all 5 border regs decode and commit correctly")
  }
}
