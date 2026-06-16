package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** VDP-SOFT-RESET-135 — INTEGRATED reproduction of the HW failure (#12622:
  * VDP_CTRL[2] never clears). The earlier sims tested the pieces in isolation
  * (SoftResetHandshakeSim defaulted sdramFillDone=True; SdramZeroFillCoSim ran
  * the fill FSM standalone) — so the full controller<->fill CDC handshake was
  * NEVER exercised together. This wires VdpTop's soft-reset controller to the
  * REAL SdramZeroFill FSM through the same BufferCC start/done handshake +
  * region descriptors as TopTang, and proves softResetBusy actually clears.
  */
case class SoftResetIntegratedDut() extends Component {
  val io = new Bundle {
    val regAddr       = in  UInt (15 bits)
    val regData       = in  Bits (16 bits)
    val regEnable     = in  Bool ()
    val ctrlBusy      = in  Bool () default False   // fault injection (SDRAM ready by default)
    val softResetBusy = out Bool ()
    val fillActive    = out Bool ()
  }
  val vdp  = VdpTop()
  val fill = SdramZeroFill(addrWidth = 23, regionCount = 11, refreshInterval = 600)

  vdp.io.regBus.addr   := io.regAddr
  vdp.io.regBus.data   := io.regData
  vdp.io.regBus.enable := io.regEnable
  io.softResetBusy := vdp.io.softResetBusy
  io.fillActive    := fill.io.active

  // === Same CDC + region wiring as TopTang's sdramFillArea (single clock here) ===
  fill.io.start    := BufferCC(vdp.io.sdramFillStart, False)
  vdp.io.sdramFillDone := BufferCC(fill.io.done, False)
  fill.io.ctrlBusy := io.ctrlBusy
  for (i <- 4 until 11) { fill.io.regions(i).base := 0; fill.io.regions(i).rowBytes := 0; fill.io.regions(i).rowCount := 0; fill.io.regions(i).enable := False }
  fill.io.regions(0).base := vdp.io.bitmapBase; fill.io.regions(0).rowBytes := vdp.io.bitmapStride; fill.io.regions(0).rowCount := vdp.io.bitmapHeight.resize(11); fill.io.regions(0).enable := vdp.io.bitmapModeActive
  fill.io.regions(1).base := vdp.io.attrBase;   fill.io.regions(1).rowBytes := vdp.io.attrStride;   fill.io.regions(1).rowCount := vdp.io.bitmapHeight.resize(11); fill.io.regions(1).enable := vdp.io.bitmapModeActive
  // tile L0 (matches HW: layer0UseSdram driven True below → tile regions enabled)
  fill.io.regions(2).base := U(TileAttributeAssets.TileMapBase, 23 bits); fill.io.regions(2).rowBytes := U(TileAttributeAssets.MapEntries, 16 bits); fill.io.regions(2).rowCount := U(1, 11 bits); fill.io.regions(2).enable := vdp.io.layer0UseSdram
  fill.io.regions(3).base := U(TileAttributeAssets.TileRowBase, 23 bits); fill.io.regions(3).rowBytes := U(TileAttributeAssets.TileCount * TileAttributeAssets.TileHeight * TileAttributeAssets.TileRowStride, 16 bits); fill.io.regions(3).rowCount := U(1, 11 bits); fill.io.regions(3).enable := vdp.io.layer0UseSdram

  // Tie off VdpTop inputs (HW-like: layer0 uses SDRAM tiles → tile regions active).
  vdp.io.layer0ScrollX := 0; vdp.io.layer0ScrollY := 0
  vdp.io.layer1ScrollX := 0; vdp.io.layer1ScrollY := 0
  vdp.io.layer2ScrollX := 0; vdp.io.layer2ScrollY := 0
  vdp.io.layer3ScrollX := 0; vdp.io.layer3ScrollY := 0
  vdp.io.sprite0X := 1023; vdp.io.sprite0Y := 1023; vdp.io.sprite0Enabled := False; vdp.io.sprite0PatternIdx := 0
  vdp.io.sprite1X := 1023; vdp.io.sprite1Y := 1023; vdp.io.sprite1Enabled := False; vdp.io.sprite1PatternIdx := 0
  vdp.io.sprite2X := 1023; vdp.io.sprite2Y := 1023; vdp.io.sprite2Enabled := False; vdp.io.sprite2PatternIdx := 0
  vdp.io.sprite3X := 1023; vdp.io.sprite3Y := 1023; vdp.io.sprite3Enabled := False; vdp.io.sprite3PatternIdx := 0
  vdp.io.layer0UseSdram := True; vdp.io.layer0SdramPixel := 0; vdp.io.layer0SdramBank := 0; vdp.io.layer0SdramPriority := False
  vdp.io.layer0TestPatternSelect := 0; vdp.io.layer0TestPatternEnable := False
  vdp.io.layer1UseSdram := False; vdp.io.layer1SdramPixel := 0; vdp.io.layer1SdramBank := 0; vdp.io.layer1SdramPriority := False
  vdp.io.bitmapSdramByte := 0; vdp.io.bitmapSdramAttrByte := 0
  vdp.io.rasterTriggerLine := 0; vdp.io.rasterTriggerPixel := 0; vdp.io.rasterTriggerPxEnable := False
  vdp.io.rasterTriggerEnable := False; vdp.io.rasterTriggerClear := False
  vdp.io.statusEvQspiReady := False; vdp.io.statusEvQspiError := False
  vdp.io.planarSdramBusy := False; vdp.io.planarSdramDataReady := False; vdp.io.planarSdramDout32 := 0
}

object SoftResetIntegratedSim extends App {
  Config.sim.compile(SoftResetIntegratedDut()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    def idle(): Unit = { dut.io.regEnable #= false; dut.io.regAddr #= 0; dut.io.regData #= 0 }
    def regWrite(addr: Int, data: Int): Unit = {
      dut.io.regAddr #= addr; dut.io.regData #= data; dut.io.regEnable #= true
      dut.clockDomain.waitSampling(); idle(); dut.clockDomain.waitSampling()
    }
    idle(); dut.io.ctrlBusy #= false; dut.clockDomain.waitSampling(5)
    var fail = false

    // Trigger the soft reset (VDP_CTRL[2]) and watch busy through the FULL chain:
    // memClear -> SDRAM fill (real FSM via CDC) -> core reg reset -> done.
    regWrite(0x0310, 0x0004)
    var asserted = 0
    while (!dut.io.softResetBusy.toBoolean && asserted < 10) { dut.clockDomain.waitSampling(); asserted += 1 }
    if (!dut.io.softResetBusy.toBoolean) { println("[sim] FAIL: busy never asserted"); fail = true }

    var cyc = 0
    val LIMIT = 80000  // memClear(16384) + fill(tile regions) + coreReset(<=hTotal) << 80k
    while (dut.io.softResetBusy.toBoolean && cyc < LIMIT) { dut.clockDomain.waitSampling(); cyc += 1 }
    if (dut.io.softResetBusy.toBoolean) {
      println(f"[sim] FAIL: softResetBusy STUCK after $LIMIT cycles — reproduces the HW hang. fillActive=${dut.io.fillActive.toBoolean}")
      fail = true
    } else {
      println(f"[sim] busy cleared after $cyc cycles (full chain incl. real fill FSM via CDC)")
    }

    println(if (fail) "[sim] SoftResetIntegratedSim: FAIL — integrated controller<->fill path hangs"
            else "[sim] SoftResetIntegratedSim: PASS — busy clears through the real fill handshake")
  }
}
