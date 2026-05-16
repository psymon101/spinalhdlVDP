package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** BH-5 unit sim: four independent raster trigger units in VdpTop.
  *
  * VdpTop owns four RasterTriggerUnit instances. TR0 is driven by the
  * legacy top-level IO inputs; TR1..TR3 are driven by bus-addressable
  * registers at 0x0360..0x036B. All four pending bits aggregate into
  * the `rasterPendingMask` 4-bit bundle (LSB = TR0). The OR of the
  * four feeds the existing `io.rasterTriggerPending` output and the
  * sticky RASTER_MATCH IRQ source.
  *
  * Cases:
  *   1. Each trigger fires independently at its programmed (line, pixel)
  *      and is observable as the corresponding bit of rasterPendingMask.
  *   2. With multiple triggers programmed for the same frame, every one
  *      that matches sets its mask bit; non-programmed triggers stay 0.
  *   3. `clear` writes (bit[2]=1 to the CTRL reg) clear only the targeted
  *      trigger's pending bit — others are unaffected.
  *   4. `enable=0` suppresses pending entirely for that trigger.
  */
object RasterTrigger4xSim extends App {
  Config.sim.compile(VdpTop(withExtraRasterTriggers = true)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init mirroring SpritePatternRamSim / PaletteRamSim.
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
    dut.io.statusEvQspiReady #= false; dut.io.statusEvQspiError #= false
    dut.io.regBus.addr #= 0; dut.io.regBus.data #= 0; dut.io.regBus.enable #= false

    // TR0 (legacy IO interface).
    dut.io.rasterTriggerLine    #= 0
    dut.io.rasterTriggerPixel   #= 0
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable   #= false
    dut.io.rasterTriggerClear    #= false

    dut.clockDomain.waitSampling(20)

    def busPulse(addr: Int, data: Int): Unit = {
      dut.io.regBus.addr   #= addr
      dut.io.regBus.data   #= data
      dut.io.regBus.enable #= true
      dut.clockDomain.waitSampling()
      dut.io.regBus.enable #= false
      dut.io.regBus.addr   #= 0
      dut.io.regBus.data   #= 0
      dut.clockDomain.waitSampling()
    }

    /** Wait until every required bit of rasterPendingMask is high (or
      * timeout). Returns the observed mask. */
    def waitForMask(want: Int, maxCycles: Int = 800_000): Int = {
      var elapsed = 0
      while (elapsed < maxCycles) {
        val m = dut.rasterPendingMask.toInt
        if ((m & want) == want) return m
        dut.clockDomain.waitSampling()
        elapsed += 1
      }
      dut.rasterPendingMask.toInt
    }

    // -- Case 1: TR0 fires independently via legacy IO --
    dut.io.rasterTriggerLine     #= 50
    dut.io.rasterTriggerPxEnable #= false
    dut.io.rasterTriggerEnable   #= true
    dut.clockDomain.waitSampling(2)
    val m0 = waitForMask(0x1)   // TR0 = bit 0
    assert((m0 & 0x1) != 0, f"Case 1 TR0: expected bit0 set, got 0x$m0%X")
    assert((m0 & 0xE) == 0,   f"Case 1 TR0: TR1..3 must be 0, got mask 0x$m0%X")
    println(f"[sim] Case 1 TR0 fires alone (mask=0x$m0%X) — OK")

    // Clear TR0 and disable.
    dut.io.rasterTriggerEnable #= false
    dut.io.rasterTriggerClear  #= true
    dut.clockDomain.waitSampling(2)
    dut.io.rasterTriggerClear  #= false
    dut.clockDomain.waitSampling(2)
    assert(dut.rasterPendingMask.toInt == 0,
      f"Case 1 cleanup: expected mask=0 after TR0 clear, got 0x${dut.rasterPendingMask.toInt}%X")

    // -- Case 2: TR1..TR3 each fire independently via bus regs --
    // Program TR1 = line 100, line-only compare, enabled.
    busPulse(0x0360, 100)               // TR1_LINE
    busPulse(0x0362, 0x0001)            // TR1_CTRL: enable, pixelCmp=0, no clear
    val m1 = waitForMask(0x2)
    assert((m1 & 0x2) != 0, f"Case 2 TR1: expected bit1 set, got 0x$m1%X")
    println(f"[sim] Case 2a TR1 fires (mask=0x$m1%X) — OK")

    // Program TR2 = line 200 with pixel 320 compare, enabled.
    busPulse(0x0364, 200)               // TR2_LINE
    busPulse(0x0365, 320)               // TR2_PIXEL
    busPulse(0x0366, 0x0003)            // TR2_CTRL: enable + pixelCmpEnable
    val m2 = waitForMask(0x6)           // TR1 still pending + TR2 newly pending
    assert((m2 & 0x6) == 0x6, f"Case 2 TR2: expected bits 1+2 set, got 0x$m2%X")
    println(f"[sim] Case 2b TR2 fires alongside TR1 (mask=0x$m2%X) — OK")

    // Program TR3 = line 300, enabled.
    busPulse(0x0368, 300)               // TR3_LINE
    busPulse(0x036A, 0x0001)            // TR3_CTRL: enable
    val m3 = waitForMask(0xE)
    assert((m3 & 0xE) == 0xE, f"Case 2 TR3: expected bits 1+2+3 set, got 0x$m3%X")
    println(f"[sim] Case 2c all three bus triggers pending (mask=0x$m3%X) — OK")

    // -- Case 3: clear TR2 only — TR1 + TR3 stay pending --
    busPulse(0x0366, 0x0007)  // CTRL: enable + pixelCmp + clear pulse (bit 2)
    busPulse(0x0366, 0x0003)  // re-write without clear bit (auto-deasserts anyway)
    dut.clockDomain.waitSampling(4)
    val m4 = dut.rasterPendingMask.toInt
    assert((m4 & 0x4) == 0,    f"Case 3: TR2 clear should drop bit 2, got 0x$m4%X")
    assert((m4 & 0xA) == 0xA,  f"Case 3: TR1+TR3 must remain set, got 0x$m4%X")
    println(f"[sim] Case 3 selective clear (TR2 cleared, TR1+TR3 retained, mask=0x$m4%X) — OK")

    // -- Case 4: disabled trigger never sets pending --
    // Clear all, then program TR3 enable=0, ensure no fire.
    busPulse(0x0362, 0x0005)  // TR1: clear
    busPulse(0x0366, 0x0005)  // TR2: clear
    busPulse(0x036A, 0x0004)  // TR3: clear + disable (bit0=0)
    busPulse(0x0362, 0x0000)  // TR1 disable
    busPulse(0x0366, 0x0000)  // TR2 disable
    dut.clockDomain.waitSampling(4)
    busPulse(0x0368, 400)
    busPulse(0x036A, 0x0000)  // TR3: line=400 but enable=0
    // Run for 2+ frames to ensure line 400 happens at least once.
    dut.clockDomain.waitSampling(800_000 / 4)
    val m5 = dut.rasterPendingMask.toInt
    assert(m5 == 0,
      f"Case 4: enable=0 should suppress all triggers, got mask=0x$m5%X")
    println(f"[sim] Case 4 enable=0 suppresses all triggers (mask=0x$m5%X) — OK")

    println("[sim] RasterTrigger4xSim: PASS")
  }
}
