package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** BrightForge #10500 / BronzeGate #10501 follow-on — fills the
  * `QspiRegWriteSim` coverage gap that left `BITMAP_CTRL` (0x0350)
  * untested at the VdpTop commit level.
  *
  * BronzeGate proved (#10501) the ESP8266 emits `0x0350 ← 0x0085`
  * cleanly on the wire and the FPGA latches no QSPI error, yet
  * RGB565 directcolor does not engage on hardware (#10475). The
  * full upstream path (QspiDecoder, RegBusArbiter)
  * has been audited and is symmetric for `0x031x` (BORDER, working)
  * and `0x035x` (BITMAP_CTRL, broken). The VdpTop-internal `0x0350`
  * decode → `bitmapCtrlPend` → `bitmapCtrlReg` commit chain has
  * exactly one writer each (VdpTop:709/710/711/809) — mechanically
  * simple, but never end-to-end simulated until now.
  *
  * This sim drives `io.regBus` writes mirroring TopazCliff's bench
  * sequence and asserts each commit reaches its target Reg after a
  * safe-boundary boundary.
  *
  * Three cases:
  *   1. `BITMAP_CTRL` alone: 0x0350 ← 0x0085 → `bitmapCtrlReg=0x0085`,
  *      `io.bitmapDirectColor=True`.
  *   2. `LAYER_ENABLE` alone: 0x0300 ← 0x0001 → `layerEnableReg=0x01`.
  *   3. Combined TopazCliff sequence (BITMAP_CTRL then LAYER_ENABLE)
  *      after a reset → both committed, `io.bitmapDirectColor=True`.
  *
  * If any case fails, the VdpTop-internal commit path for that
  * register is broken — a reproducible RTL bug. If all pass, the
  * hardware directcolor block is upstream (QspiSlave / hardware-only
  * CDC / signal integrity / timing) and the sim becomes a permanent
  * regression test for a path that previously had none.
  */
object BitmapCtrlCommitSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Quiescent init — mirror BorderRegSim.
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
      * safe-boundary commit. hTotal ≈ 800 cycles for 640x480@60Hz. */
    def settleOneFrame(): Unit = dut.clockDomain.waitSampling(900)

    // --- Case 1: BITMAP_CTRL = enable | bpp=10 | useSdram = 0x0085 ---
    busPulse(0x0350, 0x0085)
    settleOneFrame()
    val ctrl1 = dut.bitmapCtrlReg.toInt
    assert(ctrl1 == 0x0085,
      f"Case 1: BITMAP_CTRL expected 0x0085, got 0x$ctrl1%04X — 0x0350 decode or safe-boundary commit broken")
    // bitmapDirectColor = bitmapEnable && (bitmapBpp == 2). With 0x0085: enable=1, bpp=10b=2 → True.
    val dc1 = dut.io.bitmapDirectColor.toBoolean
    assert(dc1, "Case 1: io.bitmapDirectColor expected True (enable=1, bpp=2), got False")
    println(f"[sim] Case 1 BITMAP_CTRL=0x0085 committed, bitmapDirectColor=True — OK")

    // --- Case 2: LAYER_ENABLE = 0x0001 ---
    busPulse(0x0300, 0x0001)
    settleOneFrame()
    val le = dut.layerEnableReg.toInt
    assert(le == 0x01,
      f"Case 2: LAYER_ENABLE expected 0x01, got 0x$le%02X — 0x0300 decode or safe-boundary commit broken")
    println(f"[sim] Case 2 LAYER_ENABLE=0x01 committed — OK")

    // --- Case 3: TopazCliff's combined sequence after a reset ---
    busPulse(0x0350, 0x0000)
    settleOneFrame()
    val ctrl0 = dut.bitmapCtrlReg.toInt
    assert(ctrl0 == 0x0000, f"Case 3 reset: BITMAP_CTRL expected 0x0000, got 0x$ctrl0%04X")

    busPulse(0x0350, 0x0085)
    settleOneFrame()
    busPulse(0x0300, 0x0001)
    settleOneFrame()

    val ctrl3 = dut.bitmapCtrlReg.toInt
    val le3   = dut.layerEnableReg.toInt
    val dc3   = dut.io.bitmapDirectColor.toBoolean
    assert(ctrl3 == 0x0085, f"Case 3: BITMAP_CTRL expected 0x0085, got 0x$ctrl3%04X")
    assert(le3 == 0x01,     f"Case 3: LAYER_ENABLE expected 0x01, got 0x$le3%02X")
    assert(dc3, "Case 3: io.bitmapDirectColor expected True, got False")
    println(f"[sim] Case 3 combined sequence — bitmapCtrlReg=0x0085, layerEnableReg=0x01, bitmapDirectColor=True — OK")

    // --- Case 4: BITMAP-PLUMB-129 base/stride/height regs (0x0351..0x0357) ---
    // 4a: power-on defaults reproduce the former hardcoded constants on the
    //     VdpTop outputs (these regs were untouched by Cases 1-3).
    val bbDef = dut.io.bitmapBase.toLong
    val abDef = dut.io.attrBase.toLong
    val bsDef = dut.io.bitmapStride.toLong
    val asDef = dut.io.attrStride.toLong
    val bhDef = dut.io.bitmapHeight.toLong
    assert(bbDef == 0x3000, f"Case 4a: bitmapBase default expected 0x3000, got 0x$bbDef%X")
    assert(abDef == 0x4000, f"Case 4a: attrBase default expected 0x4000, got 0x$abDef%X")
    assert(bsDef == 512,    f"Case 4a: bitmapStride default expected 512, got $bsDef")
    assert(asDef == 512,    f"Case 4a: attrStride default expected 512, got $asDef")
    assert(bhDef == 240,    f"Case 4a: bitmapHeight default expected 240, got $bhDef")
    println(f"[sim] Case 4a defaults — base 0x$bbDef%X/0x$abDef%X stride $bsDef/$asDef height $bhDef — OK")

    // 4b: reprogram all seven addresses, expect assembled outputs after commit.
    //   bitmapBase = (HI<<16)|LO = (0x05<<16)|0x1234 = 0x51234
    //   attrBase   = (0x02<<16)|0x5678 = 0x25678
    busPulse(0x0351, 0x1234) // BITMAP_BASE_LO
    busPulse(0x0352, 0x0005) // BITMAP_BASE_HI (7-bit)
    busPulse(0x0353, 0x5678) // ATTR_BASE_LO
    busPulse(0x0354, 0x0002) // ATTR_BASE_HI
    busPulse(0x0355, 0x0400) // BITMAP_STRIDE = 1024
    busPulse(0x0356, 0x0300) // ATTR_STRIDE   = 768
    busPulse(0x0357, 0x0064) // BITMAP_HEIGHT = 100
    settleOneFrame()
    val bb = dut.io.bitmapBase.toLong
    val ab = dut.io.attrBase.toLong
    val bs = dut.io.bitmapStride.toLong
    val as = dut.io.attrStride.toLong
    val bh = dut.io.bitmapHeight.toLong
    assert(bb == 0x51234, f"Case 4b: bitmapBase expected 0x51234, got 0x$bb%X — 0x0351/0x0352 decode/commit broken")
    assert(ab == 0x25678, f"Case 4b: attrBase expected 0x25678, got 0x$ab%X — 0x0353/0x0354 decode/commit broken")
    assert(bs == 1024,    f"Case 4b: bitmapStride expected 1024, got $bs — 0x0355 decode/commit broken")
    assert(as == 768,     f"Case 4b: attrStride expected 768, got $as — 0x0356 decode/commit broken")
    assert(bh == 100,     f"Case 4b: bitmapHeight expected 100, got $bh — 0x0357 decode/commit broken")
    println(f"[sim] Case 4b reprogram — base 0x$bb%X/0x$ab%X stride $bs/$as height $bh — OK")

    println("[sim] BitmapCtrlCommitSim: PASS — BITMAP_CTRL 0x0350 + BITMAP-PLUMB-129 0x0351..0x0357 commit paths sound at the VdpTop boundary")
  }
}
