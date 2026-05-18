package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Integration sim — Copper-emitted `regWr` propagates through `copperFifo`
  * and the `effWrite` merge into a real `borderCtrlReg` commit.
  *
  * Closes the coverage gap noted in mail #10196: `CopperSim` covers the
  * Copper FSM in isolation and `BorderRegSim` covers BORDER_CTRL decode
  * from host (`extHit`) writes, but neither exercises the full chain
  * when the source of `effWrite` is the FIFO pop (`copperPopped`).
  *
  *   copper.io.regWr
  *     → copperFifo.push
  *     → drain at hCounter==0 && !extHit
  *     → effAddr/effData mux selects copperFifo.pop.payload
  *     → effWrite && effAddr===0x0347 sets borderCtrlPend
  *     → borderCtrlReg commit at next hCounter==0
  *
  * Authorized as test-coverage maintenance per BronzeGate #10212.
  */
object CopperBorderIntegrationSim extends App {
  Config.sim.compile(VdpTop()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val hTotal = 800

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

    // Baseline: borderCtrlReg starts at 0 (init), copper disabled (copperCtrlReg init 0).
    assert(dut.borderCtrlReg.toInt == 0,
      s"baseline: borderCtrlReg expected 0, got 0x${dut.borderCtrlReg.toInt.toHexString}")

    // Upload a minimal Copper program while copper is disabled:
    //   prog[0] = 0x000A  WAIT(10)         legacy 1-word, Y=10
    //   prog[1] = 0x4347  WRITE header, addr=0x0347 (BORDER_CTRL)
    //   prog[2] = 0x1801  data: palette idx 24 (bits[12:8]) + enable (bit[0])
    //   prog[3] = 0xC000  JUMP(0)          legacy unconditional, target PC=0
    busPulse(0x0400, 0x000A)
    busPulse(0x0401, 0x4347)
    busPulse(0x0402, 0x1801)
    busPulse(0x0403, 0xC000)

    // Enable copper: VDP_CTRL @ 0x0310 bit[0] = 1.
    busPulse(0x0310, 0x0001)

    // Latency budget from enable to visible borderCtrlReg update:
    //   - copperCtrlReg commits at next hCounter==0           : <= 1 line
    //   - FSM sFetch (line 0..9 of active) → sWaitStall        : ~few cycles
    //   - WAIT(10) match at vCounter==10 && hCounter==0        : up to ~10 lines
    //   - sFetch(WRITE) → sWriteData → regWr pulse            : 2 cycles
    //   - copperFifo push then drain at next hCounter==0      : 1 line
    //   - borderCtrlPend → borderCtrlReg commit at hCounter==0: 1 line
    // Total: ~13 lines. Wait 30 lines = 24000 cycles for generous slack.
    dut.clockDomain.waitSampling(hTotal * 30)

    val br = dut.borderCtrlReg.toInt
    assert(br == 0x1801,
      s"Case 1: copper WRITE(0x347, 0x1801) should have committed borderCtrlReg=0x1801, " +
      s"got 0x${br.toHexString}")
    println(f"[sim] Case 1 (single-program): borderCtrlReg=0x$br%04X — OK")

    // ===========================================================================
    // R5.4 Case 2 — end-to-end live update via VDP_CTRL[1] = COPPER_SWAP_REQUEST
    // ===========================================================================
    // Without disabling copper, upload progB to bank 1 (writes route there
    // because copper is enabled), then write VDP_CTRL = 0x0003 (enable +
    // swap_request). The swap commits at vSyncStart (vCounter == vActive + vFront).
    // After that, borderCtrlReg should reflect progB's WRITE.
    //
    // progB writes a different palette idx so the change is observable:
    //   prog[0] = 0x0014  WAIT(20)
    //   prog[1] = 0x4347  WRITE header, addr=0x0347
    //   prog[2] = 0x0801  data: palette idx 8 (bits[12:8]) + enable (bit[0])
    //   prog[3] = 0xC000  JUMP(0)
    busPulse(0x0400, 0x0014)
    busPulse(0x0401, 0x4347)
    busPulse(0x0402, 0x0801)
    busPulse(0x0403, 0xC000)

    // Request swap (bit[1] = 1) while keeping copper enabled (bit[0] = 1)
    busPulse(0x0310, 0x0003)

    // Wait enough for: copperSwapPending set → next vSyncStart hCounter==0
    // commit → activeBank flip + pc reset → next WAIT(20) match + WRITE +
    // copperFifo drain + borderCtrlReg commit. Worst case ~1 full frame.
    dut.clockDomain.waitSampling(hTotal * 525 + hTotal * 30)

    val br2 = dut.borderCtrlReg.toInt
    assert(br2 == 0x0801,
      s"Case 2: after swap_request, borderCtrlReg should reflect progB's WRITE=0x0801, " +
      s"got 0x${br2.toHexString} — live update via VDP_CTRL[1] failed")
    println(f"[sim] Case 2 (live update via swap_request): borderCtrlReg=0x$br2%04X — OK")

    println("[sim] CopperBorderIntegrationSim: PASS — single-program + live-update both verified")
  }
}
