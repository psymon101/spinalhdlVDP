package spinalhdlvdp

import spinal.core._
import spinal.lib.BufferCC   // Task 34 CDC — toggle-based crossing for upload pulse

/** Tang Nano 20K top.
  *
  * Pure generic Mode0 IP: boots blank, accepts host writes via QSPI, runs no
  * bootstrap copper. All platform-specific personality is implemented by
  * libvdp at runtime through register-write sequences (lane #10567).
  */
case class TopTang20kHdmi(enableL1Fetch: Boolean = true, withExtraRasterTriggers: Boolean = false, enableL2L3: Boolean = false) extends Component {
  private val useHostInit: Boolean = true

  setDefinitionName("top_tang20k")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits(6 bits)
  val O_tmds_clk_p = out Bool()
  val O_tmds_clk_n = out Bool()
  val O_tmds_data_p = out Bits(3 bits)
  val O_tmds_data_n = out Bits(3 bits)

  // QSPI host-control pins (phase 1 — CS=9, SCK=10, IO0=11, IO1=8 per plan §2).
  // Checkpoint A wires IO0/IO1 as inputs only; the tristate-buffer path for
  // READ_STATUS response lands in a later checkpoint. IO2/IO3 are not brought
  // out on Tang for lane 1 — the slave internally accepts a 4-bit bus and the
  // upper two bits are tied low here.
  val I_qspi_cs  = in Bool()
  val I_qspi_sck = in Bool()
  // Task 38a: IO0..IO3 are bidirectional — FPGA drives during QspiSlave
  // Respond state (READ_STATUS response), high-Z during Header/Payload so
  // the host can drive them. Gowin IOBUF primitives live below.
  val IO_qspi_io0 = inout(Analog(Bool()))
  val IO_qspi_io1 = inout(Analog(Bool()))
  val IO_qspi_io2 = inout(Analog(Bool()))
  val IO_qspi_io3 = inout(Analog(Bool()))

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
  // Task 44b iter 6f (CyanPeak #8123 proposal): hold clkdiv.RESETN low for 16
  // pll.CLKOUT cycles after pll.LOCK goes high, so the PLL output stabilizes
  // before pixel-domain logic starts toggling. Counter lives in a small
  // clocking area on pll.CLKOUT with async reset from !pll.LOCK.
  val pllClockDomain = ClockDomain(
    clock = pll.CLKOUT,
    reset = !pll.LOCK,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )
  val pllResetArea = new ClockingArea(pllClockDomain) {
    val clkdivResetCounter = Reg(UInt(4 bits)) init 0
    when(clkdivResetCounter =/= 15) { clkdivResetCounter := clkdivResetCounter + 1 }
  }
  clkdiv.RESETN := pllResetArea.clkdivResetCounter === 15

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
    val video = VdpTop(sdramCd = sdramClockDomain, enableL1Fetch = enableL1Fetch, withExtraRasterTriggers = withExtraRasterTriggers, enableL2L3 = enableL2L3)

    // Frame counter drives scroll offsets for visible motion proof.
    val vsyncPrev = RegNext(video.io.vsync) init True
    val vsyncRising = video.io.vsync && !vsyncPrev

    /* Platform-agnosticism purge (lane #10567): per-scenario adapter/demo
     * instantiations removed. Platform semantics live in libvdp; the FPGA
     * is pure generic Mode0 IP. */

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
    // Task 48 — L2/L3 global scroll defaults (0). Copper/host programs can
    // drive these via scenario-specific future extensions; keeping 0 here
    // preserves bit-identical rendering for all existing scenarios since
    // L2/L3 are also disabled by default (LAYER_ENABLE bits 4..3 = 0).
    video.io.layer2ScrollX := U(0, 10 bits)
    video.io.layer2ScrollY := U(0, 10 bits)
    video.io.layer3ScrollX := U(0, 10 bits)
    video.io.layer3ScrollY := U(0, 10 bits)

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
    //   copperLen+4    : write 0x0330 = 160 (VDP_WIN_X0)                [Task 20]
    //   copperLen+5    : write 0x0331 = 480 (VDP_WIN_X1)                [Task 20]
    //   copperLen+6    : write 0x0332 = 120 (VDP_WIN_Y0)                [Task 20]
    //   copperLen+7    : write 0x0333 = 360 (VDP_WIN_Y1)                [Task 20]
    //   copperLen+8    : write 0x0334 = 0x4000 (op=01 shadow, no invert) [Task 20]
    //   >=copperLen+9  : done
    val copperLen     = copperProgram.length              // 7
    val tileModeIdx   = U(copperLen,     7 bits)
    val attrModeIdx   = U(copperLen + 1, 7 bits)
    val ctrlIdx       = U(copperLen + 2, 7 bits)
    val layerIdx      = U(copperLen + 3, 7 bits)
    val winX0Idx      = U(copperLen + 4, 7 bits)
    val winX1Idx      = U(copperLen + 5, 7 bits)
    val winY0Idx      = U(copperLen + 6, 7 bits)
    val winY1Idx      = U(copperLen + 7, 7 bits)
    val colorMathIdx  = U(copperLen + 8, 7 bits)
    val LinestateCount = 60
    val linestateBase  = colorMathIdx + 1   // first linestate step
    val lastStepIdx    = colorMathIdx
    val bootIdx     = Reg(UInt(7 bits)) init 0
    // #9026 (BronzeGate #9133): when useHostInit=true, bootDoneR initializes
    // True so the bootstrap copper FSM is bypassed entirely (bootWrite =
    // !bootDoneR stays False, no internal register writes). QSPI ownership
    // transfers to the ESP host immediately at boot.
    val bootDoneR   = RegInit(if (useHostInit) True else False)

    val bootWrite      = !bootDoneR
    val inCopperPhase  = bootIdx < U(copperLen, 7 bits)
    val isTileModeStep = bootIdx === tileModeIdx
    val isAttrModeStep = bootIdx === attrModeIdx
    val isCtrlStep     = bootIdx === ctrlIdx
    val isLayerStep    = bootIdx === layerIdx
    val isWinX0Step    = bootIdx === winX0Idx
    val isWinX1Step    = bootIdx === winX1Idx
    val isWinY0Step    = bootIdx === winY0Idx
    val isWinY1Step    = bootIdx === winY1Idx
    val isColorMathStep= bootIdx === colorMathIdx

    val copperAddr = U(0x0400, 15 bits) + bootIdx.resize(15)
    val bootData   = copperProgram.map(v => U(v, 16 bits)).toSeq
    val copperDataMux = Bits(16 bits)
    copperDataMux := B(0, 16 bits)
    for (i <- copperProgram.indices) {
      when(bootIdx === U(i, 7 bits)) {
        copperDataMux := bootData(i).asBits
      }
    }

    // R4.1d Checkpoint C HW proof: shuffled/bitplane tile decode (0x0311=2)
    // with linear attribute mode (0x0312=0), copper disabled (0x0310=0), and
    // L1 disabled (0x0300=1, L0 only). The static 2×2 tile-index map combined
    // with uniform-pixel-value tiles produces a clean bitplane-checkerboard
    // exposing all four dual-plane sub-fields {plane1[bit], plane0[bit]}.
    val tileModeAddr = U(0x0311, 15 bits)
    val tileModeData = B(0x0002, 16 bits)    // shuffled tile mode default
    val attrModeAddr = U(0x0312, 15 bits)
    val attrModeData = B(0x0000, 16 bits)    // linear attribute mode
    val ctrlAddr     = U(0x0310, 15 bits)
    val ctrlData     = B(0x0000, 16 bits)    // copper disabled at boot; host owns enable
    val layerAddr    = U(0x0300, 15 bits)
    val layerData    = B(0x0001, 16 bits)    // L0 only default
    // R6 Task 20: window centred at (160..480) × (120..360) — 320×240 region
    // covering the middle of the 640×480 screen. Color-math op=01 (shadow,
    // RGB>>1) applies inside the window; outside renders unchanged. This
    // gives an unambiguous OpenCV intensity ratio across the boundary.
    val winX0Addr     = U(0x0330, 15 bits)
    val winX0Data     = B(160, 16 bits)
    val winX1Addr     = U(0x0331, 15 bits)
    val winX1Data     = B(480, 16 bits)
    val winY0Addr     = U(0x0332, 15 bits)
    val winY0Data     = B(120, 16 bits)
    val winY1Addr     = U(0x0333, 15 bits)
    val winY1Data     = B(360, 16 bits)
    val colorMathAddr = U(0x0334, 15 bits)
    val colorMathData = B(0x4000, 16 bits)

    val affineCtrlAddrReg = U(0x0346, 15 bits)
    val affineCtrlDataReg = B(0x0001, 16 bits)
    val isAffineCtrlStep  = False

    // Linestate write computation:
    // bootIdx in [colorMathIdx+1 .. lastStepIdx], k = bootIdx - linestateBase.
    // address = k * 8 (line index 0..472).
    // Per LinestateStore: bit[11]=l0en, bit[10]=l1en, bit[9:0]=l0scrollX.
    // data = 0x0400 (L1 only, l1en bit 10) when k even,
    //   else 0x0800 (L0 only, l0en bit 11). Produces 8-line bands alternating
    //   L0/L1 down the screen. ONLY the explicitly-written line indices get
    //   their enable bits set; lines between writes keep their default-init
    //   value (both layers enabled per LinestateStore.defaultInit).
    val linestateK    = (bootIdx - linestateBase).resize(7)
    val linestateAddr = (linestateK.resize(15) << 3).resize(15)   // line = k * 8
    val linestateData = Mux(linestateK(0), B(0x0800, 16 bits), B(0x0400, 16 bits))

    val bootAddr = Mux(inCopperPhase,  copperAddr,
                    Mux(isTileModeStep, tileModeAddr,
                    Mux(isAttrModeStep, attrModeAddr,
                    Mux(isCtrlStep,     ctrlAddr,
                    Mux(isLayerStep,    layerAddr,
                    Mux(isWinX0Step,    winX0Addr,
                    Mux(isWinX1Step,    winX1Addr,
                    Mux(isWinY0Step,    winY0Addr,
                    Mux(isWinY1Step,    winY1Addr,
                    Mux(isColorMathStep, colorMathAddr,
                    Mux(isAffineCtrlStep, affineCtrlAddrReg, linestateAddr)))))))))))
    val bootDataMux = Mux(inCopperPhase,  copperDataMux,
                       Mux(isTileModeStep, tileModeData,
                       Mux(isAttrModeStep, attrModeData,
                       Mux(isCtrlStep,     ctrlData,
                       Mux(isLayerStep,    layerData,
                       Mux(isWinX0Step,    winX0Data,
                       Mux(isWinX1Step,    winX1Data,
                       Mux(isWinY0Step,    winY0Data,
                       Mux(isWinY1Step,    winY1Data,
                       Mux(isColorMathStep, colorMathData,
                       Mux(isAffineCtrlStep, affineCtrlDataReg, linestateData)))))))))))

    when(bootWrite) {
      when(bootIdx <= lastStepIdx) {
        bootIdx := bootIdx + 1
      }.otherwise {
        bootDoneR := True
      }
    }

    // Platform-agnosticism purge: bootstrap affine animator removed.
    // Host owns matrix/affine register writes at runtime.
    val (animWriteAddr, animWriteData, animWriteActive): (UInt, Bits, Bool) =
      (U(0, 15 bits), B(0, 16 bits), False)

    // QSPI host-control frontend (phase 1 — Checkpoint A control contract).
    // The QspiSlave lives in the pixel clock domain and oversamples the async
    // CS/SCK/IO inputs.  After bootstrap completes it may assert regWriteEnable
    // via the QspiDecoder; bootstrap always takes priority while active.
    val qspi = QspiSlave()
    qspi.io.spi_cs_n  := I_qspi_cs
    qspi.io.spi_sck   := I_qspi_sck
    // Task 38a: bidirectional IO via Gowin IOBUF primitives. During Respond
    // state (spi_io_oe=1), the slave drives spi_io_out onto the pad. During
    // all other states (OEN=1), the pad is high-Z and we sense the host's
    // drive on .O back into spi_io_in. Pin order: IO3 high bit, IO0 low bit
    // — matches QspiSlave's {IO3,IO2,IO1,IO0} sampling expectation.
    val qspiIobuf = Seq.tabulate(4) { i =>
      val buf = GowinIobuf()
      buf.I   := qspi.io.spi_io_out(i)
      buf.OEN := !qspi.io.spi_io_oe
      buf
    }
    qspiIobuf(0).IO <> IO_qspi_io0
    qspiIobuf(1).IO <> IO_qspi_io1
    qspiIobuf(2).IO <> IO_qspi_io2
    qspiIobuf(3).IO <> IO_qspi_io3
    qspi.io.spi_io_in := (qspiIobuf(3).O ## qspiIobuf(2).O ## qspiIobuf(1).O ## qspiIobuf(0).O)
    val qspiDec = QspiDecoder()
    qspiDec.io.cmd_opcode    := qspi.io.cmd_opcode
    qspiDec.io.cmd_addr      := qspi.io.cmd_addr
    qspiDec.io.cmd_len       := qspi.io.cmd_len
    qspiDec.io.cmd_valid     := qspi.io.cmd_valid
    qspiDec.io.payload_byte  := qspi.io.payload_byte
    qspiDec.io.payload_valid := qspi.io.payload_valid
    qspiDec.io.tx_byte_sent  := qspi.io.tx_byte_sent
    qspiDec.io.active        := qspi.io.active
    qspi.io.tx_byte := qspiDec.io.tx_byte
    qspi.io.tx_load := qspiDec.io.tx_load

    // Task 35 status surface wiring. video produces the sticky word; the
    // decoder exposes it over READ_STATUS sel=5. QSPI_READY fires on every
    // cmd_valid (command accepted); QSPI_ERROR follows last_error != 0.
    qspiDec.io.status_sticky := video.io.statusSticky
    // Task 1 (#9154) — LIVE_MODE wire per CyanPeak #9161 audit correction.
    qspiDec.io.live_mode := video.io.modeSelect
    video.io.statusEvQspiReady := qspi.io.cmd_valid
    video.io.statusEvQspiError := qspiDec.io.last_error =/= B(0, 8 bits)

    // Task 34 — QSPI → SDRAM bridge. Bridge takes the decoder's raw byte
    // stream plus the latched header fields and issues per-byte writes to
    // the SDRAM controller. Arbitration per artifact §4.4: uploads gated
    // to !activeVideo (vblank + horizontal blanking) so fetch path never
    // contends with uploads. CyanPeak #7680 explicit callout: activeVideo
    // is the authoritative gate; using it mirrors the VdpTop timing that
    // drives fetch requests.
    val qspiSdramBridge = QspiSdramBridge()
    qspiSdramBridge.io.headerValid := qspiDec.io.sdramHeaderValid
    qspiSdramBridge.io.addrInit    := qspiDec.io.sdramAddrInit
    qspiSdramBridge.io.lenBytes    := qspiDec.io.sdramLenBytes
    qspiSdramBridge.io.byteIn      := qspiDec.io.sdramByteOut
    qspiSdramBridge.io.byteValid   := qspiDec.io.sdramByteValid
    qspiSdramBridge.io.allowUpload := !video.io.de
    qspiSdramBridge.io.sdramBusy   := sdramArea.ctrl.io.busy
    qspiDec.io.upload_busy := qspiSdramBridge.io.uploadBusy
    qspiDec.io.upload_done := qspiSdramBridge.io.uploadDone

    val regWriteFromBoot = bootWrite && bootIdx <= lastStepIdx
    // QSPI can only assert after bootstrap completes, preventing any
    // bus contention during the power-on register-write sequence.
    val qspiActive = bootDoneR && qspiDec.io.regBus.enable

    // Task 32b: unified register bus via RegBusArbiter. Master priority
    // index 0=bootstrap > 1=qspi > 2=animator — matches the pre-refactor
    // Mux tree exactly. `qspiDec.io.regBus` already emits the bundle;
    // bootstrap and animator are inline signals that fold into local
    // bundle assignments below.
    val regBusArbiter = RegBusArbiter(3)
    regBusArbiter.io.masters(0).addr   := bootAddr
    regBusArbiter.io.masters(0).data   := bootDataMux
    regBusArbiter.io.masters(0).enable := regWriteFromBoot
    regBusArbiter.io.masters(1).addr   := qspiDec.io.regBus.addr
    regBusArbiter.io.masters(1).data   := qspiDec.io.regBus.data
    regBusArbiter.io.masters(1).enable := qspiActive

    // Master 2 is reserved for an on-chip animator slot. With no animator
    // instantiated on this generic top, the slot stays quiescent.
    regBusArbiter.io.masters(2).addr   := animWriteAddr
    regBusArbiter.io.masters(2).data   := animWriteData
    regBusArbiter.io.masters(2).enable := animWriteActive

    // Mode0 register bus is driven straight from the arbitrator's mixed output.
    video.io.regBus <> regBusArbiter.io.mixed

    // Sprite 0: bounces diagonally at 1px/frame.
    val s0X = Reg(UInt(10 bits)) init 100
    // Bouncing logic removed for the R2 proof — sprites are pinned at fixed
    // positions so the per-line selection-limit effect is unambiguously
    // observable on a single captured frame.

    // Sprite enables vary per scenario.
    // Sc28 reuses Sc5's legacy sprite configuration so the scene has
    // content to render over; the 5 bus-programmed sprites are the
    // NEW visible proof at y=250.
    val scSprite0 = false
    val scSprite1 = false
    val scSprite23 = false
    video.io.sprite0Enabled    := Bool(scSprite0)
    video.io.sprite0PatternIdx := U(0, 1 bit)
    video.io.sprite1Enabled    := Bool(scSprite1)
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
    // Sprite positions: scenario 4 pins sprite 0 at (320,240); scenario 5
    // bounces all 4 sprites with simple counters. Scenarios 0-3 use the
    // legacy R2 proof positions (sprites off in Checkpoint C anyway).
    // Sprites disabled by default; host can enable via QSPI register writes.
    video.io.sprite0X := U(120, 10 bits); video.io.sprite0Y := U(120, 10 bits)
    video.io.sprite1X := U(240, 10 bits); video.io.sprite1Y := U(120, 10 bits)
    video.io.sprite2X := U(360, 10 bits); video.io.sprite2Y := U(120, 10 bits)
    video.io.sprite3X := U(300, 10 bits); video.io.sprite3Y := U(360, 10 bits)
    video.io.sprite2Enabled := False
    video.io.sprite3Enabled := False
    video.io.sprite2PatternIdx := U(0, 1 bit)
    video.io.sprite3PatternIdx := U(1, 1 bit)

    // R4: SDRAM tile+attribute fetch. Replaces the retired SdramTileFetch.
    // Scheduler now gates SDRAM reads via slotValid; grant pulses start a
    // line's fetch cycle; preAnnounce gives the engine a prefetch hint.
    // Task 44b — SDRAM-backed bitmap row fetch. Runs alongside tile
    // fetch but uses linear addressing and reads into pixel-domain
    // line buffers; its SDRAM bus is routed through arbiter client 1
    // (see top-level wiring below).
    val bitmapRowFetch = BitmapRowFetch(sdramClockDomain, skipSdramInit = useHostInit)
    bitmapRowFetch.io.fetchGrant := video.io.bitmapSdramFetchGrant
    bitmapRowFetch.io.fetchLine  := video.io.bitmapSdramFetchLine
    bitmapRowFetch.io.col        := video.io.bitmapSdramCol
    // BitmapRowFetch engages whenever VdpTop reports bitmap mode active;
    // host enables bitmap mode via BITMAP_CTRL writes at runtime.
    bitmapRowFetch.io.enable     := video.io.bitmapModeActive
    // CP-1c: RGB565 directcolor fetch schedule (2 bytes/pixel) when
    // BITMAP_CTRL selects bpp=0b10.
    bitmapRowFetch.io.directColor := video.io.bitmapDirectColor
    // tileBootDone wired after `fetch` instantiation below (forward ref).
    video.io.bitmapSdramByte     := bitmapRowFetch.io.bitmapByte
    video.io.bitmapSdramAttrByte := bitmapRowFetch.io.attrByte

    // Bring-up memtest disabled in production fit; proven on real silicon
    // long ago, the FSM costs LUT/FF budget for no runtime value.
    val fetch = SdramTileAttributeFetch(sdramClockDomain, skipSdramInit = useHostInit, runMemtest = false)
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

    // Task 56 Checkpoint B (#9678 / #9693): second SdramTileAttributeFetch
    // engine for Layer 1. Uses the L1 base address constants from
    // TileAttributeAssets (0xC000 / 0xD000 / 0xE000) and a distinct
    // 4-solid-tile boot pattern so L1 renders an unambiguous color-band
    // signature against L0's gradient/diagonal/rings/checker. Planar
    // assets boot and memtest are gated off — L0 already populates the
    // planar staging regions and runs the memtest scratchpad at the
    // shared SDRAM addresses, so this instance must not double-write.
    val fetchL1 = SdramTileAttributeFetch(
      sdramClockDomain,
      skipSdramInit            = useHostInit,
      tileMapBaseAddr          = TileAttributeAssets.L1TileMapBase,
      attributeMapBaseAddr     = TileAttributeAssets.L1AttributeMapBase,
      tileRowBaseAddr          = TileAttributeAssets.L1TileRowBase,
      tileMapBytesOverride      = Some(() => TileAttributeAssets.l1TileMapBytesInit),
      attributeMapBytesOverride = Some(() => TileAttributeAssets.l1AttributeMapBytesInit),
      tileRowBytesOverride      = Some(() => TileAttributeAssets.l1TileRowBytesInit),
      bootPlanarAssets         = false,
      runMemtest               = false
    )
    fetchL1.io.fetchGrant       := video.io.layer1FetchGrant
    fetchL1.io.fetchSlotValid   := video.io.layer1FetchSlotValid
    fetchL1.io.fetchPreAnnounce := video.io.layer1FetchPreAnnounce
    fetchL1.io.tileDecodeMode   := B(0, 2 bits)   // L1 stays packed-4bpp
    fetchL1.io.attributeMode    := B(0, 1 bits)   // L1 stays linear attrs
    fetchL1.io.fetchLine        := video.io.layer1FetchLine
    fetchL1.io.fetchScrollX     := video.io.layer1FetchScrollX
    fetchL1.io.fetchScrollY     := video.io.layer1FetchScrollY
    fetchL1.io.pixelAddr        := video.io.layer0FetchPixelAddr   // same hCounter
    // L1 is enabled only on scenarios that opt in (default off so pre-CP-B
    // scenes remain bit-identical). Scenario-specific tops can override.
    video.io.layer1UseSdram      := False
    video.io.layer1SdramPixel    := fetchL1.io.pixelIndex
    video.io.layer1SdramBank     := fetchL1.io.pixelPaletteBank
    video.io.layer1SdramPriority := fetchL1.io.pixelPriority

    // R1 Raster Trigger Unit — Task 34 Checkpoint C uses this as a host-
    // visible vblank indicator. Trigger fires on line 480 (first line of
    // vertical blanking in 640x480@60 timing) so host polling of
    // RASTER_MATCH (sticky bit 0, sel=5) transitions 0→1 at the start of
    // each vblank. Cleared at start-of-frame so it re-fires every frame.
    // Per Task 34 §4.4 artifact: this is the firmware-side hook for
    // vblank-paced SDRAM_WRITE streaming (BronzeGate #7683 Option B).
    video.io.rasterTriggerLine     := U(480, 10 bits)
    video.io.rasterTriggerPixel    := U(0, 10 bits)
    video.io.rasterTriggerPxEnable := False
    video.io.rasterTriggerEnable   := True
    video.io.rasterTriggerClear    := vsyncRising

    // HDMI TX pipeline
    val hdmiTx = Tang20kHdmiTx()
    hdmiTx.clk_pixel := clkdiv.CLKOUT
    hdmiTx.clk_pixel_x5 := pll.CLKOUT
    hdmiTx.reset := pixelReset
    // HDMI Output Compatibility Slice A (BronzeGate #8476):
    // 1-shot blanking window after pixel-domain reset deasserts. Holds
    // hsync/vsync inactive (high, VESA negative-active) and de=0/RGB=0
    // for ~80 ms at 25.2 MHz pixel clock so HDMI receivers (especially
    // the Guermok USB2 capture card) see a clean no-signal → signal
    // transition on every bitstream reflash. Pure top-level; no VdpTop
    // changes. Outputs of the mute feed the TMDS serializer directly.
    val hdmiCleanStart = HdmiCleanStart(muteCycles = 2_000_000)
    hdmiCleanStart.io.inHsync := video.io.hsync
    hdmiCleanStart.io.inVsync := video.io.vsync
    hdmiCleanStart.io.inDe    := video.io.de
    // Task 44b iter 6f: registered hsync/vsync/de at the TMDS boundary.
    hdmiTx.hsync := RegNext(hdmiCleanStart.io.outHsync) init True
    hdmiTx.vsync := RegNext(hdmiCleanStart.io.outVsync) init True
    hdmiTx.de    := RegNext(hdmiCleanStart.io.outDe)    init False
    // Slice-A clean-start mute: feed RGB through hdmiCleanStart so the same
    // window that holds hsync/vsync inactive also forces RGB to 0. Avoids
    // any coloured-pixel emission during the post-reset blanking window.
    // Transport canary v1 (PM #10670): 16x16 cyan block at active
    // (x=624..639, y=464..479). Gated on video.io.de only. Independent of
    // scenarioId / BITMAP_CTRL / palette / SDRAM — proves the pixel transport
    // path is moving without depending on any frame-buffer content.
    val canaryHPos = Reg(UInt(10 bits)) init 0
    val canaryVPos = Reg(UInt(10 bits)) init 0
    val dePrev = RegNext(video.io.de) init False
    val deFalling = !video.io.de && dePrev
    when(video.io.de) {
      canaryHPos := canaryHPos + 1
    } otherwise {
      canaryHPos := 0
    }
    when(deFalling) {
      canaryVPos := canaryVPos + 1
    }
    when(vsyncRising) {
      canaryVPos := 0
    }
    val inCanaryBox = video.io.de &&
      (canaryHPos >= U(624, 10 bits)) && (canaryHPos <= U(639, 10 bits)) &&
      (canaryVPos >= U(464, 10 bits)) && (canaryVPos <= U(479, 10 bits))
    hdmiCleanStart.io.inRed   := Mux(inCanaryBox, B(0x00, 8 bits), video.io.red)
    hdmiCleanStart.io.inGreen := Mux(inCanaryBox, B(0xFF, 8 bits), video.io.green)
    hdmiCleanStart.io.inBlue  := Mux(inCanaryBox, B(0xFF, 8 bits), video.io.blue)
    hdmiTx.red   := RegNext(hdmiCleanStart.io.outRed)   init 0
    hdmiTx.green := RegNext(hdmiCleanStart.io.outGreen) init 0
    hdmiTx.blue  := RegNext(hdmiCleanStart.io.outBlue)  init 0

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
    O_led(3) := !video.io.irq            // Task 35 — lit while any enabled status bit is set
    // memtestFail / underrun were bring-up telemetry — no production
    // consumer. Tie LEDs to constants so the producers DCE-prune.
    O_led(4) := False
    O_led(5) := False

  }

  // Task 34 CDC hardening (CyanPeak #7689 / BronzeGate #7690 path β).
  // Toggle-based crossing for the upload-side write pulse: bridge (in
  // pixelClockDomain) flips wrToggle on each committed write. Here we
  // 2-stage-sync it into sdramClockDomain and edge-detect to regenerate
  // a one-cycle pulse. `sdramAddr` / `sdramDin` outputs from the bridge
  // are held stable between writes (FSM holds them in wrAddrReg/wrDinReg
  // until the next write trigger), so sampling them on the regenerated
  // pulse is safe. This placement after pixelArea avoids a forward
  // reference into the bridge.
  val sdramCdcArea = new ClockingArea(sdramClockDomain) {
    val uploadToggleSync = BufferCC(pixelArea.qspiSdramBridge.io.wrToggle, False)
    val uploadTogglePrev = RegNext(uploadToggleSync) init False
    val uploadWrPulse    = uploadToggleSync =/= uploadTogglePrev

  }
  // Task 3 fix (BronzeGate #9344, CoralReef convergence #9343):
  // `planarDataReadyArea` defined AFTER `sdramArbiter` below — see post-
  // arbiter wiring for the toggle-based pulse regeneration of
  // PlanarLineFetch's sdramDataReady.

  // Wire SDRAM controller's logic-side signals to the fetch engine. Both live
  // in sdramClockDomain (the BlackBox via mapCurrentClockDomain, the fetch via
  // explicit ClockingArea inside SdramTileFetch). Upload pulse is the CDC-
  // regenerated one from sdramCdcArea — fetch retains its existing direct
  // wiring (it has been empirically stable since Task 15).
  // Task 30 — multi-client SDRAM arbiter.
  // Task 44b iter 6d (CyanPeak audit correction): Move arbiter and mux logic
  // into sdramClockDomain. Synchronize pixel-domain control signals via BufferCC
  // to ensure glitch-free switching and rule out SDRAM controller stalls.
  val sdramArbArea = new ClockingArea(sdramClockDomain) {
    val arbiter = SdramArbiter(clientCount = 4, addrWidth = 23, dataWidth = 8)

    val activeBit   = BufferCC(pixelArea.bitmapRowFetch.io.sdramActive, False)
    val grantIdSync = BufferCC(pixelArea.video.io.layer0FetchGrantClientId, U(0, 2 bits))

    arbiter.io.grantClientId := Mux(activeBit, U(1, 2 bits), grantIdSync)
    arbiter.io.slotValid     := BufferCC(pixelArea.video.io.layer0FetchSlotValid, False)
    arbiter.io.grant         := BufferCC(pixelArea.video.io.layer0FetchGrant,     False)
  }
  val sdramArbiter = sdramArbArea.arbiter

  // Client 0 — tile + attribute fetch.
  sdramArbiter.io.clientRd(0)   := pixelArea.fetch.io.sdramRd
  sdramArbiter.io.clientWr(0)   := pixelArea.fetch.io.sdramWr
  sdramArbiter.io.clientAddr(0) := pixelArea.fetch.io.sdramAddr
  sdramArbiter.io.clientDin(0)  := pixelArea.fetch.io.sdramDin
  // Client 1 — Task 44b bitmap SDRAM fetch.
  sdramArbiter.io.clientRd(1)   := pixelArea.bitmapRowFetch.io.sdramRd
  sdramArbiter.io.clientWr(1)   := pixelArea.bitmapRowFetch.io.sdramWr
  sdramArbiter.io.clientAddr(1) := pixelArea.bitmapRowFetch.io.sdramAddr
  sdramArbiter.io.clientDin(1)  := pixelArea.bitmapRowFetch.io.sdramDin
  pixelArea.bitmapRowFetch.io.sdramDout      := sdramArea.ctrl.io.dout
  pixelArea.bitmapRowFetch.io.sdramDataReady := sdramArea.ctrl.io.data_ready
  pixelArea.bitmapRowFetch.io.sdramBusy      := sdramArea.ctrl.io.busy
  // Client 2 — Task 3 PlanarLineFetch SDRAM master (gated on
  // planarFetchEnable inside VdpTop; when disabled, sdramRd stays low).
  // Read-only client; clientDin tied 0. dout32 is broadcast from the
  // SDRAM controller; the planar fetch FSM only consumes data when it
  // sees its own clientGrant + dataReady, matching the existing tile
  // and bitmap fetch gating pattern.
  sdramArbiter.io.clientRd(2)   := pixelArea.video.io.planarSdramRd
  sdramArbiter.io.clientWr(2)   := False
  sdramArbiter.io.clientAddr(2) := pixelArea.video.io.planarSdramAddr
  sdramArbiter.io.clientDin(2)  := B(0, 8 bits)
  // Task 3 fix #9344 part 1: drive busy from level signal (clientSlotValid)
  // not pulse (clientGrant). The arbiter's `grant` is a one-cycle pulse
  // when slot 2's window opens; using it as a permanent busy gate keeps
  // BitplaneRowFetch.State.Issue locked out forever (FSM never sees
  // `!sdramBusy`). `clientSlotValid` is the continuous level high
  // throughout slot 2's granted hCounter window — what we actually need.
  pixelArea.video.io.planarSdramBusy := !sdramArbiter.io.clientSlotValid(2)

  // BronzeGate #9366 Path A: PlanarLineFetch's row-fetch FSM lives in
  // sdramClockDomain. Wire data_ready/dout32 natively from the SDRAM
  // controller, qualified by `grantClientId === 2`. No CDC stack on
  // this path — the FSM samples both signals directly in the sdram
  // domain, eliminating per-read CDC latency that was the root of the
  // gray-output blocker on hardware (#9351 / #9362 / #9366).
  val planarDataReadyNative = sdramArea.ctrl.io.data_ready &&
                              (sdramArbiter.io.grantClientId === U(2, sdramArbiter.idBits bits))
  pixelArea.video.io.planarSdramDataReady := planarDataReadyNative
  pixelArea.video.io.planarSdramDout32    := sdramArea.ctrl.io.dout32
  // Task 56 Checkpoint B (#9678 / #9693) — Client 3 = L1 SdramTileAttributeFetch.
  // Same SDRAM-bus contract as client 0 (L0). The arbiter serializes
  // concurrent transactions; both L0 and L1 boot ROMs target disjoint
  // SDRAM regions (L0: 0x6000/0x7000/0x8000, L1: 0xC000/0xD000/0xE000)
  // so concurrent boot is collision-free even though both FSMs may
  // simultaneously request writes during the power-on copy phase.
  sdramArbiter.io.clientRd(3)   := pixelArea.fetchL1.io.sdramRd
  sdramArbiter.io.clientWr(3)   := pixelArea.fetchL1.io.sdramWr
  sdramArbiter.io.clientAddr(3) := pixelArea.fetchL1.io.sdramAddr
  sdramArbiter.io.clientDin(3)  := pixelArea.fetchL1.io.sdramDin
  pixelArea.fetchL1.io.sdramDout      := sdramArea.ctrl.io.dout
  pixelArea.fetchL1.io.sdramDout32    := sdramArea.ctrl.io.dout32
  pixelArea.fetchL1.io.sdramDataReady := sdramArea.ctrl.io.data_ready
  pixelArea.fetchL1.io.sdramBusy      := sdramArea.ctrl.io.busy

  val uploadDrive = sdramCdcArea.uploadWrPulse
  sdramArea.ctrl.io.rd      := sdramArbiter.io.sdramRd
  sdramArea.ctrl.io.wr      := sdramArbiter.io.sdramWr || uploadDrive
  sdramArea.ctrl.io.refresh := pixelArea.fetch.io.sdramRefresh
  sdramArea.ctrl.io.addr    := Mux(uploadDrive, pixelArea.qspiSdramBridge.io.sdramAddr, sdramArbiter.io.sdramAddr)
  sdramArea.ctrl.io.din     := Mux(uploadDrive, pixelArea.qspiSdramBridge.io.sdramDin,  sdramArbiter.io.sdramDin)
  pixelArea.fetch.io.sdramDout      := sdramArea.ctrl.io.dout
  pixelArea.fetch.io.sdramDout32    := sdramArea.ctrl.io.dout32
  pixelArea.fetch.io.sdramDataReady := sdramArea.ctrl.io.data_ready
  // Task 44b iter 6: gate BitmapRowFetch init on tile-fetch bootDone
  // (forward-referenced because `fetch` is declared after bitmapRowFetch
  // in pixelArea block).
  pixelArea.bitmapRowFetch.io.tileBootDone := pixelArea.fetch.io.bootDone
  pixelArea.fetch.io.sdramBusy      := sdramArea.ctrl.io.busy
}

object TopTang20kHdmiVerilog extends App {
  // PM #9907 Step 2: build the default Tang20k bitstream with L1 scaffolding
  // gated off so the synthesis/PnR resource delta can be measured against
  // Step 1 baseline (commit 6737bc0, 20943 logic).
  Config.spinal.generateVerilog(TopTang20kHdmi(enableL1Fetch = false))
}
