package spinalhdlvdp

import spinal.core._

case class TopTang20kHdmi() extends Component {
  setDefinitionName("top_tang20k")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits(6 bits)
  val O_tmds_clk_p = out Bool()
  val O_tmds_clk_n = out Bool()
  val O_tmds_data_p = out Bits(3 bits)
  val O_tmds_data_n = out Bits(3 bits)

  // Task 15: embedded SiP SDRAM pads. These map to Gowin's "magic" port names
  // (O_sdram_*, IO_sdram_DQ). No `.cst` entries — Gowin auto-binds them.
  val O_sdram_clk   = out Bool()
  val O_sdram_cke   = out Bool()
  val O_sdram_cs_n  = out Bool()
  val O_sdram_cas_n = out Bool()
  val O_sdram_ras_n = out Bool()
  val O_sdram_wen_n = out Bool()
  val O_sdram_addr  = out Bits(11 bits)
  val O_sdram_ba    = out Bits(2 bits)
  val O_sdram_dqm   = out Bits(4 bits)
  val IO_sdram_dq   = inout(Analog(Bits(32 bits)))

  // --------------------------------------------------------------------------
  // Pixel-side PLL chain (unchanged from pre-Task-15 baseline)
  // --------------------------------------------------------------------------
  val pll = GowinRpll()
  pll.CLKIN := I_clk
  pll.CLKFB := False
  pll.FBDSEL := B(0, 6 bits)
  pll.IDSEL := B(0, 6 bits)
  pll.ODSEL := B(0, 6 bits)
  pll.DUTYDA := B(0, 4 bits)
  pll.PSDA := B(0, 4 bits)
  pll.FDLY := B(0, 4 bits)
  pll.RESET := False
  pll.RESET_P := False

  val clkdiv = GowinClkdiv()
  clkdiv.HCLKIN := pll.CLKOUT
  clkdiv.CALIB := True
  clkdiv.RESETN := pll.LOCK

  val pixelReset = !pll.LOCK
  val pixelClockDomain = ClockDomain(
    clock = clkdiv.CLKOUT,
    reset = pixelReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  // --------------------------------------------------------------------------
  // SDRAM PLL + controller (new for Task 15)
  // --------------------------------------------------------------------------
  // 27 MHz → 64.8 MHz CLKOUT + 64.8 MHz 180° CLKOUTP (see tang20k_sdram_pll.v).
  val sdramPll = Tang20kSdramPll()
  sdramPll.clkin := I_clk

  val sdramReset = !sdramPll.lock
  val sdramClockDomain = ClockDomain(
    clock = sdramPll.clkout,
    reset = sdramReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  // Controller and fetch engine share sdramClockDomain. The controller's
  // logic-side `clk` is auto-mapped to this domain via BlackBox annotation;
  // `clk_sdram` receives the 180° companion output.
  val sdramArea = new ClockingArea(sdramClockDomain) {
    val ctrl = SdramController()
    ctrl.io.clk_sdram := sdramPll.clkoutp
    ctrl.io.resetn    := sdramPll.lock
  }

  // Route SDRAM pads at top level (outside any area — they are physical pins).
  O_sdram_clk   := sdramArea.ctrl.io.SDRAM_CLK
  O_sdram_cke   := sdramArea.ctrl.io.SDRAM_CKE
  O_sdram_cs_n  := sdramArea.ctrl.io.SDRAM_nCS
  O_sdram_cas_n := sdramArea.ctrl.io.SDRAM_nCAS
  O_sdram_ras_n := sdramArea.ctrl.io.SDRAM_nRAS
  O_sdram_wen_n := sdramArea.ctrl.io.SDRAM_nWE
  O_sdram_addr  := sdramArea.ctrl.io.SDRAM_A
  O_sdram_ba    := sdramArea.ctrl.io.SDRAM_BA
  O_sdram_dqm   := sdramArea.ctrl.io.SDRAM_DQM
  IO_sdram_dq   <> sdramArea.ctrl.io.SDRAM_DQ

  // --------------------------------------------------------------------------
  // Pixel-domain logic (VdpTop + SDRAM fetch instance)
  // --------------------------------------------------------------------------
  val pixelArea = new ClockingArea(pixelClockDomain) {
    val video = VdpTop()

    // Frame counter drives scroll offsets for visible motion proof.
    val vsyncPrev = RegNext(video.io.vsync) init True
    val vsyncRising = video.io.vsync && !vsyncPrev
    val frameCounter = Reg(UInt(10 bits)) init 0
    when(vsyncRising) {
      frameCounter := frameCounter + 1
    }
    // R4.1 proof scene — per CyanPeak #6804, permanent slow scroll (no
    // modeToggle) so visual motion is smooth. The bandwidth fix (widened
    // slot 1) means every line's fetch now completes before the buffer swap,
    // so scrolling should be jitter-free.
    // Scroll rate bumped so each tile advances once per Tang frame (60 Hz).
    // Below ~30 Hz the discrete 1-px steps read as a visible staircase; at
    // 60 Hz the motion is perceptually smooth.
    // R5.4: scroll counters wrap via the shared `ScrollWrap` primitive so map
    // dimensions drive the wrap point, widths are inferred at elaboration,
    // and any future increase in step size or coord width is handled by the
    // primitive's generated wrap-tree.
    val l0MapWidth   = BasicPatternSource.MapTilesX * BasicPatternSource.TileWidth  // 640
    val l1MapWidth   = BasicPatternSource.MapTilesX * BasicPatternSource.TileWidth  // 640
    val l0StepFrames = 1
    val l1StepFrames = 2
    val scrollL0 = Reg(UInt(log2Up(l0MapWidth) bits)) init 0
    val scrollL1 = Reg(UInt(log2Up(l1MapWidth) bits)) init 0
    val l0NextWrap = ScrollWrap(l0MapWidth)
    l0NextWrap.io.coord  := scrollL0
    l0NextWrap.io.scroll := U(l0StepFrames, log2Up(l0MapWidth + 1) bits)
    val l1NextWrap = ScrollWrap(l1MapWidth)
    l1NextWrap.io.coord  := scrollL1
    l1NextWrap.io.scroll := U(l1StepFrames, log2Up(l1MapWidth + 1) bits)
    when(vsyncRising) {
      scrollL0 := l0NextWrap.io.result
      scrollL1 := l1NextWrap.io.result
    }
    video.io.layer0ScrollX := scrollL0.resize(10)
    video.io.layer0ScrollY := U(0, 10 bits)
    video.io.layer1ScrollX := scrollL1.resize(10)
    video.io.layer1ScrollY := U(0, 10 bits)

    // R5 stage 4: bootstrap FSM uploads a copper program to 0x0400+N then
    // enables the copper. Runs once per power-on. Copper program implements
    // the §12 horizontal-split proof:
    //   PC 0: WAIT  y=160
    //   PC 1: WRITE 0x0300 (LAYER_ENABLE)
    //   PC 2: data  0x0001 (L0 only)
    //   PC 3: WAIT  y=320
    //   PC 4: WRITE 0x0300
    //   PC 5: data  0x0003 (L0 + L1)
    //   PC 6: JUMP  0
    // Encoding matches Copper.scala:
    //   WAIT:  [15:14]=00, [9:0]=Y
    //   WRITE: [15:14]=01, [13:0]=addr, next word = data
    //   JUMP:  [15:14]=11, [8:0]=target PC
    val copperProgram: Seq[Int] = Seq(
      (0 << 14) | 160,              // WAIT y=160
      (1 << 14) | 0x0300,           // WRITE addr=0x0300
      0x0001,                       // data (L0 only)
      (0 << 14) | 320,              // WAIT y=320
      (1 << 14) | 0x0300,           // WRITE addr=0x0300
      0x0003,                       // data (L0 + L1)
      (3 << 14) | 0                 // JUMP 0
    )

    // Bootstrap FSM phases:
    //   0..copperLen-1 : upload copper program to 0x0400+idx
    //   copperLen      : write 0x0311 = 2 (VDP_TILE_MODE = shuffled)    [R4.1d]
    //   copperLen+1    : write 0x0312 = 0 (VDP_ATTR_MODE = linear)      [R4.1d]
    //   copperLen+2    : write 0x0310 = 0 (VDP_CTRL copper disabled)    [R4.1d]
    //   copperLen+3    : write 0x0300 = 1 (LAYER_ENABLE = L0 only)      [R4.1d]
    //   >=copperLen+4  : done
    val copperLen   = copperProgram.length              // 7
    val tileModeIdx = U(copperLen,     4 bits)
    val attrModeIdx = U(copperLen + 1, 4 bits)
    val ctrlIdx     = U(copperLen + 2, 4 bits)
    val layerIdx    = U(copperLen + 3, 4 bits)
    val lastStepIdx = layerIdx
    val bootIdx     = Reg(UInt(4 bits)) init 0
    val bootDoneR   = Reg(Bool())      init False

    val bootWrite      = !bootDoneR
    val inCopperPhase  = bootIdx < U(copperLen, 4 bits)
    val isTileModeStep = bootIdx === tileModeIdx
    val isAttrModeStep = bootIdx === attrModeIdx
    val isCtrlStep     = bootIdx === ctrlIdx
    val isLayerStep    = bootIdx === layerIdx

    val copperAddr = U(0x0400, 15 bits) + bootIdx.resize(15)
    val bootData   = copperProgram.map(v => U(v, 16 bits)).toSeq
    val copperDataMux = Bits(16 bits)
    copperDataMux := B(0, 16 bits)
    for (i <- copperProgram.indices) {
      when(bootIdx === U(i, 4 bits)) {
        copperDataMux := bootData(i).asBits
      }
    }

    // R4.1d Checkpoint C HW proof: shuffled/bitplane tile decode (0x0311=2)
    // with linear attribute mode (0x0312=0), copper disabled (0x0310=0), and
    // L1 disabled (0x0300=1, L0 only). The static 2×2 tile-index map combined
    // with uniform-pixel-value tiles produces a clean bitplane-checkerboard
    // exposing all four dual-plane sub-fields {plane1[bit], plane0[bit]}.
    val tileModeAddr = U(0x0311, 15 bits)
    val tileModeData = B(0x0002, 16 bits)   // R4.1d Checkpoint C: shuffled/bitplane mode
    val attrModeAddr = U(0x0312, 15 bits)
    val attrModeData = B(0x0000, 16 bits)   // R4.1d Checkpoint C: linear attribute mode
    val ctrlAddr     = U(0x0310, 15 bits)
    val ctrlData     = B(0x0000, 16 bits)   // R4.1d Checkpoint C: copper disabled
    val layerAddr    = U(0x0300, 15 bits)
    val layerData    = B(0x0001, 16 bits)   // R4.1d Checkpoint C: LAYER_ENABLE = L0 only

    val bootAddr = Mux(inCopperPhase,  copperAddr,
                    Mux(isTileModeStep, tileModeAddr,
                    Mux(isAttrModeStep, attrModeAddr,
                    Mux(isCtrlStep,     ctrlAddr, layerAddr))))
    val bootDataMux = Mux(inCopperPhase,  copperDataMux,
                       Mux(isTileModeStep, tileModeData,
                       Mux(isAttrModeStep, attrModeData,
                       Mux(isCtrlStep,     ctrlData, layerData))))

    when(bootWrite) {
      when(bootIdx <= lastStepIdx) {
        bootIdx := bootIdx + 1
      }.otherwise {
        bootDoneR := True
      }
    }

    video.io.regWriteAddr   := bootAddr
    video.io.regWriteData   := bootDataMux
    video.io.regWriteEnable := bootWrite && bootIdx <= lastStepIdx

    // Sprite 0: bounces diagonally at 1px/frame.
    val s0X = Reg(UInt(10 bits)) init 100
    // Bouncing logic removed for the R2 proof — sprites are pinned at fixed
    // positions so the per-line selection-limit effect is unambiguously
    // observable on a single captured frame.

    // R4.1d Checkpoint C: disable all sprites for clean static bitplane
    // checkerboard. The R2 sprite proof scene (overflow band, etc.) is not
    // part of this lane; sprites overlay the diagnostic and confound the
    // bit-observable OpenCV verification.
    video.io.sprite0Enabled := False
    video.io.sprite0PatternIdx := U(0, 1 bit)
    video.io.sprite1Enabled := False
    video.io.sprite1PatternIdx := U(1, 1 bit)

    // R2 proof scene — deliberately forces the 2-per-line selection limit.
    //
    // Lines 120..135: sprites 0, 1, 2 ALL on-line (all at Y=120). The evaluator
    // picks the two lowest-indexed on-line descriptors (0 and 1). Descriptor 2
    // is DROPPED, so it never renders — that's the hardware-visible signature
    // of the per-line limit.
    // Lines 360..375: sprite 3 alone → renders as the fourth visible image.
    // Expected on screen: FOUR enabled descriptors, only THREE visible sprites.
    // (sprite 0 bouncing at Y≈200 is out of this Y band but still visible.)
    //
    // To keep the overflow band unambiguous, we override sprite 0's bounce by
    // pinning its Y for this proof run. (The bouncing position was only for
    // the old two-sprite demo.)
    video.io.sprite0X := U(120, 10 bits)
    video.io.sprite0Y := U(120, 10 bits)
    video.io.sprite1X := U(240, 10 bits)
    video.io.sprite1Y := U(120, 10 bits)

    video.io.sprite2X := U(360, 10 bits)
    video.io.sprite2Y := U(120, 10 bits)
    video.io.sprite2Enabled := False        // R4.1d Checkpoint C: sprites off
    video.io.sprite2PatternIdx := U(0, 1 bit)

    video.io.sprite3X := U(300, 10 bits)
    video.io.sprite3Y := U(360, 10 bits)
    video.io.sprite3Enabled := False        // R4.1d Checkpoint C: sprites off
    video.io.sprite3PatternIdx := U(1, 1 bit)

    // R4: SDRAM tile+attribute fetch. Replaces the retired SdramTileFetch.
    // Scheduler now gates SDRAM reads via slotValid; grant pulses start a
    // line's fetch cycle; preAnnounce gives the engine a prefetch hint.
    val fetch = SdramTileAttributeFetch(sdramClockDomain)
    fetch.io.fetchGrant       := video.io.layer0FetchGrant
    fetch.io.fetchSlotValid   := video.io.layer0FetchSlotValid
    fetch.io.fetchPreAnnounce := video.io.layer0FetchPreAnnounce
    fetch.io.tileDecodeMode   := video.io.layer0TileDecodeMode  // R4.1b stage 3: VDP_TILE_MODE @ 0x0311
    fetch.io.attributeMode    := video.io.layer0AttributeMode   // R4.1c: VDP_ATTR_MODE @ 0x0312
    fetch.io.fetchLine        := video.io.layer0FetchLine
    fetch.io.fetchScrollX     := video.io.layer0FetchScrollX
    fetch.io.fetchScrollY     := video.io.layer0FetchScrollY
    fetch.io.pixelAddr        := video.io.layer0FetchPixelAddr

    // Route R4 pixel+bank+priority into VdpTop's L0 interface.
    video.io.layer0SdramPixel    := fetch.io.pixelIndex
    video.io.layer0SdramBank     := fetch.io.pixelPaletteBank
    video.io.layer0SdramPriority := fetch.io.pixelPriority
    video.io.layer0UseSdram      := True

    // Test pattern override: default disabled so normal SDRAM-backed rendering
    // continues. Set enable=True and select a pattern (1..7) for validation.
    video.io.layer0TestPatternEnable := False
    video.io.layer0TestPatternSelect := U(0, 3 bits)

    // R1 Raster Trigger Unit: fire at line 240 (mid-visible), clear each frame
    // at start-of-frame so the trigger re-fires every frame and the screen
    // split stays stable. Pulse/pending drive a red-channel inversion below
    // the trigger line (see VdpTop), producing a crisp top/bottom split.
    video.io.rasterTriggerLine     := U(240, 10 bits)
    video.io.rasterTriggerPixel    := U(0, 10 bits)
    video.io.rasterTriggerPxEnable := False
    // R2 cleaner-BG proof: disable the R1 red-channel-inversion split so
    // sprites on black BG are unambiguous. Revert to True after R2 accepted.
    video.io.rasterTriggerEnable   := False
    video.io.rasterTriggerClear    := vsyncRising

    // HDMI TX pipeline
    val hdmiTx = Tang20kHdmiTx()
    hdmiTx.clk_pixel := clkdiv.CLKOUT
    hdmiTx.clk_pixel_x5 := pll.CLKOUT
    hdmiTx.reset := pixelReset
    hdmiTx.hsync := video.io.hsync
    hdmiTx.vsync := video.io.vsync
    hdmiTx.de := video.io.de
    hdmiTx.red := video.io.red
    hdmiTx.green := video.io.green
    hdmiTx.blue := video.io.blue

    O_tmds_clk_p := hdmiTx.tmds_clk_p
    O_tmds_clk_n := hdmiTx.tmds_clk_n
    O_tmds_data_p := hdmiTx.tmds_data_p
    O_tmds_data_n := hdmiTx.tmds_data_n

    // LEDs expose Task 15 bring-up status so first-hardware is diagnosable.
    //   O_led is active-low on the Tang Nano 20K (0 = lit).
    // R4 production LED mapping — restored after stage-1c diagnostic closed.
    // Debug attribute probe outputs remain exposed on the fetch engine for
    // any future diagnostic lane but are not surfaced to LEDs.
    O_led := B"6'b111111"
    O_led(0) := !pll.LOCK
    O_led(1) := !sdramPll.lock
    O_led(2) := !fetch.io.bootDone
    O_led(3) := !fetch.io.memtestPass
    O_led(4) := fetch.io.memtestFail
    O_led(5) := fetch.io.underrun
  }

  // Wire SDRAM controller's logic-side signals to the fetch engine. Both live
  // in sdramClockDomain (the BlackBox via mapCurrentClockDomain, the fetch via
  // explicit ClockingArea inside SdramTileFetch).
  sdramArea.ctrl.io.rd      := pixelArea.fetch.io.sdramRd
  sdramArea.ctrl.io.wr      := pixelArea.fetch.io.sdramWr
  sdramArea.ctrl.io.refresh := pixelArea.fetch.io.sdramRefresh
  sdramArea.ctrl.io.addr    := pixelArea.fetch.io.sdramAddr
  sdramArea.ctrl.io.din     := pixelArea.fetch.io.sdramDin
  pixelArea.fetch.io.sdramDout      := sdramArea.ctrl.io.dout
  pixelArea.fetch.io.sdramDout32    := sdramArea.ctrl.io.dout32
  pixelArea.fetch.io.sdramDataReady := sdramArea.ctrl.io.data_ready
  pixelArea.fetch.io.sdramBusy      := sdramArea.ctrl.io.busy
}

object TopTang20kHdmiVerilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi())
}
