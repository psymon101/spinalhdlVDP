package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** BACKDROP_INDEX @ 0x0348 register commit + compositor fallthrough proof.
  *
  * Four cases:
  *   1. POR — backdropIndexReg = 0 (palette[0] is the cold-boot backdrop).
  *   2. Write 0x05 — safe-boundary commit lands backdropIndexReg = 5.
  *   3. Write 0x7F — full 7-bit range exercised; backdropIndexReg = 127.
  *   4. Multi-write — last write wins after settling.
  *
  * Compositor proof: with all layer inputs quiescent (no SDRAM source, no
  * sprites, no test pattern, layerEnableReg=0 at POR), the `.otherwise`
  * fallthrough fires every cycle and `composedBgIdx`/`composedBgBank` must
  * encode the current BACKDROP_INDEX (bank=[6:4], idx=[3:0]).
  */
object BackdropCommitSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

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
    def settleOneFrame(): Unit = dut.clockDomain.waitSampling(900)

    // --- Case 1: POR ---
    val por = dut.backdropIndexReg.toInt
    assert(por == 0, f"Case 1: POR expected backdropIndexReg=0, got 0x$por%02X")
    println("[sim] Case 1 POR backdropIndexReg=0 — OK")

    // --- Case 2: write 0x05 (idx=5, bank=0 → palette[5]) ---
    busPulse(0x0348, 0x0005)
    settleOneFrame()
    val r2 = dut.backdropIndexReg.toInt
    assert(r2 == 0x05, f"Case 2: expected 0x05, got 0x$r2%02X")
    println(f"[sim] Case 2 BACKDROP_INDEX=0x05 committed — OK")

    // --- Case 3: write 0x7F (idx=15, bank=7 → palette[127]) ---
    busPulse(0x0348, 0x007F)
    settleOneFrame()
    val r3 = dut.backdropIndexReg.toInt
    assert(r3 == 0x7F, f"Case 3: expected 0x7F, got 0x$r3%02X")
    println(f"[sim] Case 3 BACKDROP_INDEX=0x7F committed (full 7-bit range) — OK")

    // --- Case 4: multi-write last-wins ---
    busPulse(0x0348, 0x0010)
    busPulse(0x0348, 0x0020)
    busPulse(0x0348, 0x0040)
    settleOneFrame()
    val r4 = dut.backdropIndexReg.toInt
    assert(r4 == 0x40, f"Case 4: last-write-wins expected 0x40, got 0x$r4%02X")
    println(f"[sim] Case 4 multi-write last-wins committed 0x40 — OK")

    // --- Case 5: compositor fallthrough emits backdrop bank/idx ---
    // With layerEnableReg=0 (POR) and all layer inputs quiescent, every
    // pixel runs through `.otherwise`. composedBgBank should equal [6:4] and
    // composedBgIdx should equal [3:0] of backdropIndexReg.
    busPulse(0x0348, 0x0053)   // bank=5 (101), idx=3 (0011)
    settleOneFrame()
    val cbBank = dut.composedBgBank.toInt
    val cbIdx  = dut.composedBgIdx.toInt
    assert(cbBank == 5, s"Case 5: composedBgBank expected 5, got $cbBank")
    assert(cbIdx  == 3, s"Case 5: composedBgIdx expected 3, got $cbIdx")
    println(s"[sim] Case 5 compositor fallthrough: bank=$cbBank, idx=$cbIdx — OK")

    println("[sim] BackdropCommitSim: PASS")
  }
}
