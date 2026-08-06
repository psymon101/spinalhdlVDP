package spinalhdlvdp

import spinal.core._
import spinal.lib._   // #11123 FIX 1: StreamFifoCC lossless upload crossing (+ BufferCC)

/** Tang Nano 20K top.
  *
  * Pure generic Mode0 IP: boots blank, accepts host writes via QSPI, runs no
  * bootstrap copper. All platform-specific personality is implemented by
  * libvdp at runtime through register-write sequences (lane #10567).
  */
case class TopTang20kHdmi(enableL1Fetch: Boolean = true, withExtraRasterTriggers: Boolean = false, enableL2L3: Boolean = false,
                          scaleCtrlInit:   Int = 0,
                          logicWidthInit:  Int = 640,
                          logicHeightInit: Int = 480,
                          borderCtrlInit:  Int = 0,
                          hostI80:         Boolean = false,
                          diagnosticMode:  Boolean = false) extends Component {
  // standalone-diagnostic-build: diagnosticMode=true runs the on-chip bootstrap
  // (no host, no QSPI, no SDRAM) and displays a clean 1x full-screen L0 test
  // pattern. Production keeps useHostInit=true (bootstrap bypassed entirely).
  private val useHostInit: Boolean = !diagnosticMode

  // Lane P21: when hostI80, an Intel-8080 parallel host front-end (I80HostInterface)
  // is added as regBus master(2) (the otherwise-quiescent animator slot) and the i80
  // pads are brought out. The QSPI front-end stays present so the STA build is a
  // conservative both-fronts test; deployment uses tang20k_i80.cst (QSPI displaced).
  setDefinitionName(if (diagnosticMode) "top_tang20k_diagnostic" else if (hostI80) "top_tang20k_i80" else "top_tang20k")
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
  // QSPI pads are dropped when hostI80 (the i80 build displaces QSPI as the host).
  // The QspiSlave/QspiDecoder LOGIC stays instantiated (tied-off inputs) so the
  // STA build keeps representative core size/congestion.
  val I_qspi_cs  = if (!hostI80) in Bool() else null
  val I_qspi_sck = if (!hostI80) in Bool() else null
  // Task 38a: IO0..IO3 are bidirectional — FPGA drives during QspiSlave
  // Respond state (READ_STATUS response), high-Z during Header/Payload so
  // the host can drive them. Gowin IOBUF primitives live below.
  val IO_qspi_io0 = if (!hostI80) inout(Analog(Bool())) else null
  val IO_qspi_io1 = if (!hostI80) inout(Analog(Bool())) else null
  val IO_qspi_io2 = if (!hostI80) inout(Analog(Bool())) else null
  val IO_qspi_io3 = if (!hostI80) inout(Analog(Bool())) else null

  // i80 parallel-host pads (lane P21, only present when hostI80). D0-7 bidir via
  // GowinIobuf; CS/WR/RD/DC are inputs. Constrained by tang20k_i80*.cst.
  val IO_i80_d = if (hostI80) inout(Analog(Bits(8 bits))) else null
  val I_i80_cs = if (hostI80) in Bool() else null
  val I_i80_wr = if (hostI80) in Bool() else null
  val I_i80_rd = if (hostI80) in Bool() else null
  val I_i80_dc = if (hostI80) in Bool() else null

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
  // 27 MHz → 40.5 MHz CLKOUT + 40.5 MHz 180° CLKOUTP (see tang20k_sdram_pll.v; the
  // SDRAM clock was lowered from 64.8 to 40.5 MHz under #11197 Option A).
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
    val video = VdpTop(sdramCd = sdramClockDomain, enableL1Fetch = enableL1Fetch, withExtraRasterTriggers = withExtraRasterTriggers, enableL2L3 = enableL2L3, diagnosticMode = diagnosticMode,
                       scaleCtrlInit   = scaleCtrlInit,
                       logicWidthInit  = logicWidthInit,
                       logicHeightInit = logicHeightInit,
                       borderCtrlInit  = borderCtrlInit)

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
    // SCROLL-FIX-128 (owner-directed, mail #12169/#12181): default both layers to
    // static. The wrapper previously auto-scrolled L0/L1 every vsync regardless of
    // firmware, which made any static-tile scene (e.g. CP-D starfield) impossible to
    // prove. (Host scroll-step control was once sketched for 0x0358/0x0359, but
    // I80-FRAME-ATOMIC-SWAP-145 now owns 0x0358-0x035C for bitmap/attr base
    // staging + swap-ctrl; a future scroll-step feature must pick other free
    // addresses.)
    val l0StepFrames = 0
    val l1StepFrames = 0
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
    // external-review Tier A (#14322): the linestate upload loop runs bootIdx in
    // [linestateBase .. lastStepIdx]. The old `lastStepIdx = colorMathIdx` left
    // linestateBase (colorMathIdx+1) > lastStepIdx, so the loop was empty and the
    // standalone (useHostInit=false) bootstrap wrote ZERO linestate entries. Dead
    // in production (host-init bypasses bootstrap) but the diagnostic path needs it.
    val lastStepIdx    = (linestateBase.resize(7) + U(LinestateCount - 1, 7 bits)).resize(7)
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
    // diagnosticMode: op=00 (no shadow window) so the 1x test pattern is unshaded
    // full-screen; production keeps the §12 shadow window (op=01, RGB>>1 in center).
    val colorMathData = if (diagnosticMode) B(0x0000, 16 bits) else B(0x4000, 16 bits)

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
    // Production standalone/§12 proof: alternating L0/L1 8-line bands. diagnosticMode:
    // force L0-enabled on every written line (0x0800) so the L0 test pattern fills the
    // whole screen (no L1 bands).
    val linestateData = if (diagnosticMode) B(0x0800, 16 bits)
                        else Mux(linestateK(0), B(0x0800, 16 bits), B(0x0400, 16 bits))

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

    // QSPI host-control frontend — Option A (#13973/#13974): the synchronous
    // word-drain QspiTransportCore (SCLK-domain capture + CDC token FIFO + an
    // internal QspiDecoder) replaces the legacy pixel-oversampled QspiSlave/QspiDecoder
    // pair. It fixes the READ_STATUS read-header framing mismatch (#13966: legacy
    // required a QUAD header with a LEN phase; the P4 firmware sends a single-lane
    // header with no LEN on reads) that stalled the legacy slave at 0x22222222.
    // Instantiated unconditionally (idle-tied in the i80 build) so RegBusArbiter
    // master(1) always has a driver. Its sys domain runs on the pixel clock
    // (clkdiv.CLKOUT) — the same edge as pixelClockDomain, so the core's
    // sysCd(BOOT) -> pixelClockDomain(ASYNC) signal crossings are same-clock
    // synchronous, not CDC.
    val qspiCore = QspiTransportCore()
    qspiCore.io.clk := clkdiv.CLKOUT
    if (!hostI80) {
      qspiCore.io.csn  := I_qspi_cs
      qspiCore.io.sclk := I_qspi_sck
    } else {
      qspiCore.io.csn  := True    // QSPI host removed in the i80 build; hold idle
      qspiCore.io.sclk := False
    }
    // Bidirectional quad IO via Gowin IOBUF primitives. The core drives ioOut when
    // ioOe=1 (READ_STATUS respond, answered SCLK-side); high-Z otherwise so the host's
    // drive is sensed back on .O into ioIn. Pin order IO3 high .. IO0 low.
    if (!hostI80) {
      val qspiIobuf = Seq.tabulate(4) { i =>
        val buf = GowinIobuf()
        buf.I   := qspiCore.io.ioOut(i)
        buf.OEN := !qspiCore.io.ioOe
        buf
      }
      qspiIobuf(0).IO <> IO_qspi_io0
      qspiIobuf(1).IO <> IO_qspi_io1
      qspiIobuf(2).IO <> IO_qspi_io2
      qspiIobuf(3).IO <> IO_qspi_io3
      qspiCore.io.ioIn := (qspiIobuf(3).O ## qspiIobuf(2).O ## qspiIobuf(1).O ## qspiIobuf(0).O)
    } else {
      qspiCore.io.ioIn := B(0, 4 bits)   // no pads; host drive sensed as 0
    }

    // Status-event wiring. MVP READ_STATUS surface (#13975) carries sel=0/9/10 only,
    // answered SCLK-side inside the core; the legacy sel=5 sticky / sel=7 live_mode
    // surface is not carried, so status_sticky/live_mode are no longer routed.
    // QSPI_READY proxies off an accepted register write; QSPI_ERROR is unused in the
    // MVP surface (transport health is exposed via sel=10, not last_error).
    video.io.statusEvQspiReady := qspiCore.io.regBus.enable
    video.io.statusEvQspiError := False

    // Task 34 — QSPI → SDRAM bridge. Bridge takes the decoder's raw byte
    // stream plus the latched header fields and issues per-byte writes to
    // the SDRAM controller. Arbitration per artifact §4.4: uploads gated
    // to !activeVideo (vblank + horizontal blanking) so fetch path never
    // contends with uploads. CyanPeak #7680 explicit callout: activeVideo
    // is the authoritative gate; using it mirrors the VdpTop timing that
    // drives fetch requests.
    // Lane P21: i80 parallel host front-end. Instantiated here (pixel domain, same
    // as qspiDec/the bridge — no CDC) so its block-write outputs can feed the SDRAM
    // upload bridge below. The async strobes are CDC'd inside I80HostInterface; the
    // 8-bit data bus is bidirectional via GowinIobuf (FPGA drives only on reg-read).
    // The regBus master(2) hookup lands later (after regBusArbiter is declared).
    val i80 = if (hostI80) I80HostInterface(8) else null
    if (hostI80) {
      i80.io.cs := I_i80_cs
      i80.io.wr := I_i80_wr
      i80.io.rd := I_i80_rd
      i80.io.dc := I_i80_dc
      val i80Iobuf = Seq.tabulate(8) { i =>
        val buf = GowinIobuf()
        buf.I   := i80.io.dOut(i)
        buf.OEN := !i80.io.dOutEn
        buf
      }
      val i80DIn = Bits(8 bits)
      for (i <- 0 until 8) {
        i80Iobuf(i).IO <> IO_i80_d(i)
        i80DIn(i) := i80Iobuf(i).O
      }
      i80.io.dIn := i80DIn
      i80.io.blockWr.ready := True   // bridge byteValid is fire-and-forget (fifoOverflow handles backpressure)
      // i80.io.readData is wired below, after debugSdramDataPix is defined (P22).
    }

    val qspiSdramBridge = QspiSdramBridge()
    // CP-B2: when hostI80, the bridge is fed from the i80 block-write FSM; otherwise
    // from the QSPI decoder. Both produce headerValid + addrInit/lenBytes then a
    // byteIn/byteValid payload stream in the pixel domain.
    if (hostI80) {
      qspiSdramBridge.io.headerValid := i80.io.blockHeaderValid
      qspiSdramBridge.io.addrInit    := i80.io.blockAddr
      qspiSdramBridge.io.lenBytes    := i80.io.blockLen.resize(17)
      qspiSdramBridge.io.byteIn      := i80.io.blockWr.payload
      qspiSdramBridge.io.byteValid   := i80.io.blockWr.valid
    } else {
      qspiSdramBridge.io.headerValid := qspiCore.io.sdramHeaderValid
      qspiSdramBridge.io.addrInit    := qspiCore.io.sdramAddrInit
      qspiSdramBridge.io.lenBytes    := qspiCore.io.sdramLenBytes
      qspiSdramBridge.io.byteIn      := qspiCore.io.sdramByteOut
      qspiSdramBridge.io.byteValid   := qspiCore.io.sdramByteValid
    }
    // #11246 F5: drain the upload byteFifo CONTINUOUSLY, not only in blanking. The
    // old !de gate stalled the bridge during active video, so at the production QSPI
    // rate the 16-deep byteFifo overflowed and silently dropped bytes
    // (UploadByteRateSim #11231: 200/256 lost @60 MHz). The pop side
    // (uploadPopArea.canAccept) now defers to fetch activity per-cycle with one-cycle
    // look-ahead, so emitting anytime is safe; BronzeGate's 4 MHz host write cap (F3)
    // keeps the average source rate under the SDRAM byte-write sink.
    qspiSdramBridge.io.allowUpload := True
    // #11123 FIX 1: bridge no longer takes raw cross-domain busy; its wrCmd
    // Stream is crossed losslessly via uploadCc (StreamFifoCC) at top level.
    // CP-A5 (#11464/#11470): upload_busy is driven AFTER uploadCc is defined (below) so
    // it reflects the FULL drain (bridge OR uploadCc-not-empty), not just the bridge —
    // otherwise the host poll-clear races ahead of the SDRAM-side drain and the CC
    // backs up (sentinel+32 tiles = 132 > depth 128 -> tile[31] overflow + watchdog abort,
    // proven in CpA5UploadDrainSim). Driven at the uploadCc instantiation site.
    // MVP surface (#13975): the core ties its internal decoder's upload_* status
    // inputs off (sel=6 not carried), so the bridge's uploadDone/uploadError/
    // fifoOverflow outputs are not routed back to a host-readable surface here.

    val regWriteFromBoot = bootWrite && bootIdx <= lastStepIdx
    // QSPI can only assert after bootstrap completes, preventing any
    // bus contention during the power-on register-write sequence.
    val qspiActive = bootDoneR && qspiCore.io.regBus.enable

    // Task 32b: unified register bus via RegBusArbiter. Master priority
    // index 0=bootstrap > 1=qspi > 2=animator — matches the pre-refactor
    // Mux tree exactly. `qspiDec.io.regBus` already emits the bundle;
    // bootstrap and animator are inline signals that fold into local
    // bundle assignments below.
    val regBusArbiter = RegBusArbiter(3)
    regBusArbiter.io.masters(0).addr   := bootAddr
    regBusArbiter.io.masters(0).data   := bootDataMux
    regBusArbiter.io.masters(0).enable := regWriteFromBoot
    regBusArbiter.io.masters(1).addr   := qspiCore.io.regBus.addr
    regBusArbiter.io.masters(1).data   := qspiCore.io.regBus.data
    regBusArbiter.io.masters(1).enable := qspiActive

    // Master 2 is reserved for an on-chip animator slot. With no animator
    // instantiated on this generic top, the slot stays quiescent — unless the
    // i80 front-end (lane P21, hostI80) claims it; that wiring is below.
    if (!hostI80) {
      regBusArbiter.io.masters(2).addr   := animWriteAddr
      regBusArbiter.io.masters(2).data   := animWriteData
      regBusArbiter.io.masters(2).enable := animWriteActive
    }

    // Mode0 register bus is driven straight from the arbitrator's mixed output.
    video.io.regBus <> regBusArbiter.io.mixed

    // Lane P21: i80 drives regBus master(2) (the quiescent animator slot), gated
    // post-boot like the QSPI master. The i80 instance + its pad/bridge wiring are
    // above (near the QSPI front-end); only the arbiter hookup lands here, after
    // regBusArbiter is declared.
    if (hostI80) {
      regBusArbiter.io.masters(2).addr   := i80.io.regBus.addr
      regBusArbiter.io.masters(2).data   := i80.io.regBus.data
      regBusArbiter.io.masters(2).enable := bootDoneR && i80.io.regBus.enable
    }

    // === DIAG #10908 (P4 Task A) — host-visible SDRAM readback surface ========
    // Two REG_WRITE regs latch a 23-bit debug SDRAM address; writing the HI reg
    // (0x0327) ARMS a one-shot read performed in the sdram domain (dbgReadArea
    // at top level). The 32-bit result is CDC'd back here and exposed via
    // READ_STATUS sel=8. Diagnostic-only — remove with the 2bpp planar HW lane.
    val dbgMixed   = regBusArbiter.io.mixed
    val dbgAddrLo  = Reg(Bits(16 bits)) init 0
    val dbgAddrHi  = Reg(Bits(7 bits))  init 0
    val dbgArm     = Reg(Bool())        init False
    // #11246 F1@789 (CyanPeak GT-CDC): the old code wrote dbgAddrHi AND toggled
    // dbgArm in the SAME cycle, then crossed dbgAddr (23b) and dbgArm (1b) on
    // SEPARATE BufferCCs into sdramClockDomain. Data changing coincident with its
    // req => the sdram-side addrSync could be sampled mid-skew (torn readback
    // address). Fix is source-side: capture the FULL address into a stable holding
    // register (dbgArmedAddr) on the HI write, and toggle the arm ONE CYCLE LATER,
    // so the address is provably stable before the arm toggle crosses. The
    // existing BufferCC(dbgAddr) at top level then sees a coherent, settled value.
    val dbgArmedAddr = Reg(UInt(23 bits)) init 0
    when(dbgMixed.enable && dbgMixed.addr === U(0x0326, 15 bits)) {
      dbgAddrLo := dbgMixed.data
    }
    val dbgHiWrite = dbgMixed.enable && dbgMixed.addr === U(0x0327, 15 bits)
    when(dbgHiWrite) {
      dbgAddrHi    := dbgMixed.data(6 downto 0)
      dbgArmedAddr := (dbgMixed.data(6 downto 0) ## dbgAddrLo).asUInt
    }
    when(RegNext(dbgHiWrite) init False) {
      dbgArm := !dbgArm   // toggle one-shot trigger AFTER dbgArmedAddr is stable
    }
    val dbgAddr = dbgArmedAddr   // stable, CDC-coherent 23-bit armed address
    // Result wire driven from the sdram-domain read FSM via BufferCC (top level).
    // Consumed by the i80 reg-read path (0x0328/9) below AND by the QSPI word-drain
    // READ_STATUS sel=8 surface (#14246 — re-enabled to split upload vs downstream on the
    // 2bpp banding). qspiCore CDCs it into its SCLK responder (quasi-static, 2FF-safe).
    val debugSdramDataPix = Bits(32 bits)
    qspiCore.io.debug_sdram_data := debugSdramDataPix
    // option-4 (#14568/#14574): READ_DONE completion flag (pixel domain), driven from dbgResultPixArea
    // and surfaced at READ_STATUS sel=0x0C bit0. Cleared on the 0x0327 arm, set after the settled latch.
    val debugReadDonePix = Bool()
    qspiCore.io.debug_read_done := debugReadDonePix

    // Status-contract cleanup (#14638): wire the re-surfaced host status + centralized 0x0323 clear.
    // QSPI reads via sel=5 (sticky) / sel=6 (upload); i80 reads via 0x0320/0x0323 (readData mux below).
    // 0x0323 write-1-to-clear is decoded centrally in VdpTop and strobed to the bridge here.
    qspiCore.io.status_sticky   := video.io.statusSticky
    qspiCore.io.upload_busy     := qspiSdramBridge.io.uploadBusy
    qspiCore.io.upload_done     := qspiSdramBridge.io.uploadDone
    qspiCore.io.upload_error    := qspiSdramBridge.io.uploadError
    qspiCore.io.upload_overflow := qspiSdramBridge.io.fifoOverflow
    qspiSdramBridge.io.uploadErrorClear  := video.io.uploadErrorClear
    qspiSdramBridge.io.fifoOverflowClear := video.io.fifoOverflowClear

    // === Lane P22: i80 reg-read access to the SDRAM debug readback surface =====
    // P21's i80 reg-read was a last-write loopback. P22 needs byte-exact SDRAM
    // readback over i80, so reg-reads at 0x0328/0x0329 return the 32-bit one-shot
    // SDRAM debug word (armed via 0x0326/0x0327, same surface QSPI READ_STATUS
    // sel=8 exposes). Every other read addr keeps the last-write loopback so the
    // P21-proven reg-read discriminator (0xA55A/0x1234) still holds.
    if (hostI80) {
      val i80Loopback = RegNext(i80.io.regBus.data) init 0
      // VDP-SOFT-RESET-135: a read of VDP_CTRL @ 0x0310 returns LIVE status, not
      // the last-write loopback, so the host can poll bit[2]=SOFT_RESET_BUSY for
      // soft-reset completion (write 0x0004, then poll until bit2 reads 0).
      i80.io.readData := i80.io.readAddr.mux(
        U(0x0328, 15 bits) -> debugSdramDataPix(15 downto 0),
        U(0x0329, 15 bits) -> debugSdramDataPix(31 downto 16),
        // Status-contract cleanup (#14638): i80 status reads. 0x0320 = VDP sticky (16b); 0x0323 =
        // upload status [3:0]=busy,done,error,overflow (matches QSPI sel=5/sel=6). W1C via 0x0320/0x0323 writes.
        U(0x0320, 15 bits) -> video.io.statusSticky,
        U(0x0323, 15 bits) -> (B(0, 12 bits) ## qspiSdramBridge.io.fifoOverflow ## qspiSdramBridge.io.uploadError ## qspiSdramBridge.io.uploadDone ## qspiSdramBridge.io.uploadBusy),
        // VDP-SOFT-RESET-135: VDP_CTRL read = loopback bits (copper enable[0]/swap[1]
        // etc.) with bit[2] forced from the LIVE softResetBusy. CyanPeak (#12634)
        // caught that the old `Mux(busy,0x0004,0)` zeroed bits 0/1 (broke RMW). But
        // her suggested `loopback | busy` would re-stick bit[2] (loopback bit2 = the
        // 0x0004 the host wrote to trigger), so we MASK bit[2] out of loopback first
        // (& 0xFFFB) then OR in live busy — bits 0/1 preserved AND bit[2] self-clears.
        U(0x0310, 15 bits) -> ((i80Loopback & B(0xFFFB, 16 bits)) |
                               Mux(video.io.softResetBusy, B(0x0004, 16 bits), B(0, 16 bits))),
        // I80-FRAME-ATOMIC-SWAP-145: BITMAP_SWAP_CTRL read returns LIVE swap
        // status (b0=request, b1=committed), not the last-write loopback, so
        // firmware polls real vblank-commit completion instead of a fixed delay.
        U(0x035C, 15 bits) -> video.io.swapStatus,
        default            -> i80Loopback
      )
    }

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
    // BITMAP-PLUMB-129 (#12169/#12205): host-programmable bitmap/attr base,
    // stride, and height (regs 0x0351..0x0357). BitmapRowFetch BufferCC's these
    // into its SDRAM clock domain. Defaults reproduce the former hardcoded
    // 0x3000/0x4000/512/240 so existing demos are unchanged.
    bitmapRowFetch.io.bitmapBase   := video.io.bitmapBase
    bitmapRowFetch.io.attrBase     := video.io.attrBase
    bitmapRowFetch.io.bitmapStride := video.io.bitmapStride
    bitmapRowFetch.io.attrStride   := video.io.attrStride
    bitmapRowFetch.io.bitmapHeight := video.io.bitmapHeight
    // tileBootDone wired after `fetch` instantiation below (forward ref).
    video.io.bitmapSdramByte     := bitmapRowFetch.io.bitmapByte
    video.io.bitmapSdramAttrByte := bitmapRowFetch.io.attrByte

    // Bring-up memtest disabled in production fit; proven on real silicon
    // long ago, the FSM costs LUT/FF budget for no runtime value.
    val fetch = SdramTileAttributeFetch(sdramClockDomain, skipSdramInit = useHostInit, runMemtest = false, useExternalRefresh = true)  // CP-A3: central arbiter refresh
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
    // standalone-diagnostic-build: in diagnosticMode L0 renders the on-chip test
    // pattern (no SDRAM); production keeps the SDRAM-backed L0 path unchanged.
    video.io.layer0UseSdram      := (if (diagnosticMode) False else True)

    // Test pattern override: default disabled so normal SDRAM-backed rendering
    // continues. Set enable=True and select a pattern (1..7) for validation.
    // diagnosticMode -> enable, select 6 (grid) for a geometry-obvious 1x pattern.
    video.io.layer0TestPatternEnable := (if (diagnosticMode) True else False)
    video.io.layer0TestPatternSelect := U(if (diagnosticMode) 6 else 0, 3 bits)

    // Task 56 Checkpoint B (#9678 / #9693): second SdramTileAttributeFetch
    // engine for Layer 1. Uses the L1 base address constants from
    // TileAttributeAssets (0xC000 / 0xD000 / 0xE000) and a distinct
    // 4-solid-tile boot pattern so L1 renders an unambiguous color-band
    // signature against L0's gradient/diagonal/rings/checker. Planar
    // assets boot and memtest are gated off — L0 already populates the
    // planar staging regions and runs the memtest scratchpad at the
    // shared SDRAM addresses, so this instance must not double-write.
    // BSRAM-L1-GATE-154 (#12911): only instantiate the L1 SDRAM fetch engine when
    // enableL1Fetch is set. Production builds pass enableL1Fetch=false, so this whole
    // second SdramTileAttributeFetch (its line buffers, async FIFO, boot ROMs and FSM)
    // is no longer elaborated — freeing its BSRAM/LUT/FF. When disabled, VdpTop's
    // layer-1 SDRAM inputs are tied off here and SDRAM-arbiter client 3 is held idle
    // (see the post-arbiter wiring below).
    val fetchL1 = if (enableL1Fetch) {
      val f = SdramTileAttributeFetch(
        sdramClockDomain,
        skipSdramInit            = useHostInit,
        tileMapBaseAddr          = TileAttributeAssets.L1TileMapBase,
        attributeMapBaseAddr     = TileAttributeAssets.L1AttributeMapBase,
        tileRowBaseAddr          = TileAttributeAssets.L1TileRowBase,
        tileMapBytesOverride      = Some(() => TileAttributeAssets.l1TileMapBytesInit),
        attributeMapBytesOverride = Some(() => TileAttributeAssets.l1AttributeMapBytesInit),
        tileRowBytesOverride      = Some(() => TileAttributeAssets.l1TileRowBytesInit),
        bootPlanarAssets         = false,
        runMemtest               = false,
        useExternalRefresh       = true   // CP-A3: central arbiter refresh (same cadence as L0)
      )
      f.io.fetchGrant       := video.io.layer1FetchGrant
      f.io.fetchSlotValid   := video.io.layer1FetchSlotValid
      f.io.fetchPreAnnounce := video.io.layer1FetchPreAnnounce
      f.io.tileDecodeMode   := B(0, 2 bits)   // L1 stays packed-4bpp
      f.io.attributeMode    := B(0, 1 bits)   // L1 stays linear attrs
      f.io.fetchLine        := video.io.layer1FetchLine
      f.io.fetchScrollX     := video.io.layer1FetchScrollX
      f.io.fetchScrollY     := video.io.layer1FetchScrollY
      f.io.pixelAddr        := video.io.layer1FetchPixelAddr   // L1 uses its own scheduling surface (external-review Tier A #14322)
      video.io.layer1SdramPixel    := f.io.pixelIndex
      video.io.layer1SdramBank     := f.io.pixelPaletteBank
      video.io.layer1SdramPriority := f.io.pixelPriority
      Some(f)
    } else {
      // L1 fetch disabled (production): tie off VdpTop's layer-1 SDRAM inputs.
      video.io.layer1SdramPixel    := B(0, 4 bits)
      video.io.layer1SdramBank     := U(0, 3 bits)
      video.io.layer1SdramPriority := False
      None
    }
    // L1 is enabled only on scenarios that opt in (default off so pre-CP-B
    // scenes remain bit-identical). Scenario-specific tops can override.
    video.io.layer1UseSdram      := False

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
    // DIAG #10963: sticky fetch-underrun telemetry (instrumentation only — no
    // scheduling/arbitration change). fetch.io.underrun is produced in the SDRAM
    // clock domain; BufferCC into this pixel domain, then latch sticky so a brief
    // per-line underrun is never missed. Sticky clears only on pixelReset (PLL
    // lock loss / power-cycle) — satisfies the "latches until cleared" requirement.
    val underrunSyncPix   = BufferCC(fetch.io.underrun, False)
    val underrunStickyReg = Reg(Bool()) init False
    when(underrunSyncPix) { underrunStickyReg := True }

    O_led := B"6'b111111"
    O_led(0) := !pll.LOCK
    O_led(1) := !sdramPll.lock
    O_led(2) := !fetch.io.bootDone
    O_led(3) := !video.io.irq            // Task 35 — lit while any enabled status bit is set
    O_led(4) := !underrunStickyReg       // DIAG #10963: lit once ANY fetch underrun has occurred (sticky)
    O_led(5) := !underrunSyncPix         // DIAG #10963: live (non-sticky) underrun pulse

  }

  // #11123 FIX 1 (BronzeGate #11120 Finding 1) — lossless pixel->SDRAM upload
  // crossing. The bridge (pixelClockDomain) emits a `wrCmd` Stream carrying
  // {addr,din} in ONE 31-bit payload; this StreamFifoCC carries it into
  // sdramClockDomain with address and data inseparable. Replaces the prior
  // toggle + quasi-static-bus + raw-cross-domain-busy scheme, which could
  // mis-pair addr/data, collapse two toggle flips, and drop writes — the cause
  // of partial sentinel bytes and writes landing at the wrong address.
  // depth=128 (power-of-two, GT-022). #11246 F5b: deepened 16->128 alongside the
  // bridge byteFifo so the CDC stage isn't the bottleneck — UploadSeamSim proved
  // depth 128 yields ZERO upload drops at the 4 MHz cap under per-line fetch read
  // bursts (16 dropped most, 64 dropped 13). The SDRAM-side pop (uploadPopArea, below
  // dbgReadArea) consumes one entry only when the controller can accept it.
  val uploadCc = StreamFifoCC(Bits(31 bits), 128, pixelClockDomain, sdramClockDomain)
  uploadCc.io.push << pixelArea.qspiSdramBridge.io.wrCmd
  // CP-A5 (MVP surface #13975): the QspiTransportCore ties its internal decoder's
  // upload_busy off (sel=6 host-readable upload status is not carried), so the
  // bridge.uploadBusy OR uploadCc-occupancy signal is no longer routed to a host
  // surface. The lossless uploadCc drain path itself is unchanged (push wired above).
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
    // CP-A2 (Phase A #11421/#11426): clientCount 4→5. Client 4 = UPLOAD DMA,
    // promoted from a side-channel OR into a first-class arbiter client granted
    // on idle cycles (priority below refresh+fetch). idBits widens 2→3.
    // CP-A2b (#11429/#11432): clientCount 5→6. Client 5 = DEBUG READ (lowest
    // priority), promoted from the ctrl.rd OR / ctrl.addr Mux side-path so the
    // dataCaptured snoop can't latch a fetch transaction (CyanPeak audit).
    val arbiter = SdramArbiter(clientCount = 6, addrWidth = 23, dataWidth = 8, burstRefresh = true)

    val activeBit   = BufferCC(pixelArea.bitmapRowFetch.io.sdramActive, False)
    // #11246 F1@712: cross grantClientId(2b) + slotValid + grant as ONE bundle so
    // the multi-bit grant id cannot skew relative to its qualifying grant/slotValid
    // pulse. Separate per-signal BufferCCs allowed the 2-bit id to tear mid-
    // transition vs the grant edge -> arbiter routes the granted transaction to the
    // wrong client for a cycle. Same coherent-bundle pattern as the fetch engine's
    // ctrlBundle (SdramTileAttributeFetch:292).
    val grantBundle = BufferCC(
      pixelArea.video.io.layer0FetchGrantClientId.asBits ##
        pixelArea.video.io.layer0FetchSlotValid.asBits ##
        pixelArea.video.io.layer0FetchGrant.asBits,
      B(0, 4 bits))
    val grantIdSync = grantBundle(3 downto 2).asUInt

    // CP-A2: base (fetch) grant id, widened to the new 3-bit id space. The UPLOAD
    // override (-> client 4) is applied at top level AFTER uploadPopArea is defined,
    // because it depends on the idle-cycle decision (uploadDrive). slotValid/grant
    // remain the scheduler's — fan-out (clientGrant/clientSlotValid) for fetch
    // clients 0-3 is unaffected by the upload override.
    val baseGrantId = Mux(activeBit, U(1, 3 bits), grantIdSync.resize(3))
    arbiter.io.slotValid     := grantBundle(1)
    arbiter.io.grant         := grantBundle(0)
    // SDRAM-BURST-REFRESH (P16, #11978): vblank flag (pixel y >= vActive=480)
    // synced into the SDRAM domain. burstRefresh is opt-in (default off), so this
    // feeds an unused arbiter input today; enabling burst is then a one-line flip
    // (burstRefresh=true) at the arbiter instantiation after cosim/HW proof.
    arbiter.io.vblankActive  := BufferCC(pixelArea.video.io.y >= U(480, 10 bits), False)
  }
  val sdramArbiter = sdramArbArea.arbiter

  // VDP-SOFT-RESET-135 #3 part 2b: occupied-region SDRAM zero-fill engine.
  // Runs in sdramClockDomain alongside the arbiter/controller. The VdpTop
  // soft-reset controller raises `sdramFillStart` (pixel) after the on-chip Mem
  // clear; this engine zeroes the configured framebuffer region(s), then raises
  // `sdramFillDone` (synced back) so the controller drops busy. While the engine
  // is `active`, the command mux below routes it to the controller (arbiter
  // bypassed — display is quiescent during reset).
  val sdramFillArea = new ClockingArea(sdramClockDomain) {
    // 11 regions: bitmap, attr, tileMap L0/L1, tileRows L0/L1, planar 0-4.
    val fill = SdramZeroFill(addrWidth = 23, regionCount = 11, refreshInterval = 600)
    fill.io.start := BufferCC(pixelArea.video.io.sdramFillStart, False)
    // Geometry → region descriptors. All these gate/geometry signals are stable
    // throughout the fill (host is polling, not writing; not reset until Stage 4
    // which runs after), so a BufferCC of these quasi-static values is a
    // sufficient synchronizer (same basis as the debug-readback CDC).
    val bmActive = BufferCC(pixelArea.video.io.bitmapModeActive, False)
    val bmBase   = BufferCC(pixelArea.video.io.bitmapBase,   U(0, 23 bits))
    val bmStride = BufferCC(pixelArea.video.io.bitmapStride, U(0, 16 bits))
    val bmHeight = BufferCC(pixelArea.video.io.bitmapHeight, U(0, 10 bits))
    val atBase   = BufferCC(pixelArea.video.io.attrBase,     U(0, 23 bits))
    val atStride = BufferCC(pixelArea.video.io.attrStride,   U(0, 16 bits))
    // Tile-layer SDRAM-active gates (TopTang drives these VdpTop inputs).
    val l0Tile   = BufferCC(pixelArea.video.io.layer0UseSdram, False)
    val l1Tile   = BufferCC(pixelArea.video.io.layer1UseSdram, False)
    // Planar bases + active gate (exposed by VdpTop in part 2c).
    val plActive = BufferCC(pixelArea.video.io.planarFillActive, False)
    val plBase   = Vec(UInt(23 bits), 5)
    for (p <- 0 until 5) plBase(p) := BufferCC(pixelArea.video.io.planeBaseAddr(p), U(0, 23 bits))

    def setRegion(i: Int, base: UInt, rowBytes: UInt, rowCount: UInt, en: Bool): Unit = {
      fill.io.regions(i).base     := base
      fill.io.regions(i).rowBytes := rowBytes
      fill.io.regions(i).rowCount := rowCount
      fill.io.regions(i).enable   := en
    }
    // 0/1 = RGB565 bitmap low + attr/high planes (gated by BITMAP_CTRL[0]); share HEIGHT.
    setRegion(0, bmBase, bmStride, bmHeight.resize(11), bmActive)
    setRegion(1, atBase, atStride, bmHeight.resize(11), bmActive)
    // 2-5 = tile map/rows L0+L1 (fixed SDRAM layout; gated by layerN-uses-SDRAM).
    //   TotalRowBytes = TileCount·TileHeight·TileRowStride.
    val tileRowBytes = TileAttributeAssets.TileCount * TileAttributeAssets.TileHeight *
                       TileAttributeAssets.TileRowStride
    setRegion(2, U(TileAttributeAssets.TileMapBase,   23 bits), U(TileAttributeAssets.MapEntries, 16 bits), U(1, 11 bits), l0Tile)
    setRegion(3, U(TileAttributeAssets.TileRowBase,   23 bits), U(tileRowBytes,                   16 bits), U(1, 11 bits), l0Tile)
    setRegion(4, U(TileAttributeAssets.L1TileMapBase, 23 bits), U(TileAttributeAssets.MapEntries, 16 bits), U(1, 11 bits), l1Tile)
    setRegion(5, U(TileAttributeAssets.L1TileRowBase, 23 bits), U(tileRowBytes,                   16 bits), U(1, 11 bits), l1Tile)
    // 6-10 = planar planes (each PLANE_PIXELS/8 = 40 bytes; gated by planarFetchEnable).
    for (p <- 0 until 5) setRegion(6 + p, plBase(p), U(320 / 8, 16 bits), U(1, 11 bits), plActive)
  }
  val sdramFill = sdramFillArea.fill
  sdramFill.io.ctrlBusy := sdramArea.ctrl.io.busy
  // fill done → pixel domain → VdpTop soft-reset controller (level handshake).
  val sdramFillDonePixArea = new ClockingArea(pixelClockDomain) {
    pixelArea.video.io.sdramFillDone := BufferCC(sdramFill.io.done, False)
  }

  // CP-A3 (Option B): both fetch engines take their refresh cadence from the arbiter's
  // single central timer (Priority-0 accounting). Same sdram domain -> direct wire.
  // ctrl.io.refresh keeps the L0||L1 cmdRefresh OR below; both are now fed by ONE timer
  // (no per-engine drift; L1 guaranteed on-cadence).
  pixelArea.fetch.io.refreshDue   := sdramArbiter.io.refreshDue
  pixelArea.fetchL1.foreach(_.io.refreshDue := sdramArbiter.io.refreshDue)  // BSRAM-L1-GATE-154: only when present

  // Client 0 — tile + attribute fetch.
  sdramArbiter.io.clientRd(0)   := pixelArea.fetch.io.sdramRd
  sdramArbiter.io.clientWr(0)   := pixelArea.fetch.io.sdramWr
  sdramArbiter.io.clientAddr(0) := pixelArea.fetch.io.sdramAddr
  sdramArbiter.io.clientDin(0)  := pixelArea.fetch.io.sdramDin
  // RGB565-FULLFRAME-132 Phase 0: per-client read burst length. Every client except
  // the bitmap direct-color client (1, wired below) uses single reads; only client 1
  // ever bursts. (Each index assigned exactly once — no SpinalHDL assignment overlap.)
  for (i <- Seq(0, 2, 3, 4, 5)) sdramArbiter.io.clientBurstLen(i) := U(1, 4 bits)
  // Client 1 — Task 44b bitmap SDRAM fetch.
  sdramArbiter.io.clientRd(1)   := pixelArea.bitmapRowFetch.io.sdramRd
  sdramArbiter.io.clientWr(1)   := pixelArea.bitmapRowFetch.io.sdramWr
  sdramArbiter.io.clientAddr(1) := pixelArea.bitmapRowFetch.io.sdramAddr
  sdramArbiter.io.clientDin(1)  := pixelArea.bitmapRowFetch.io.sdramDin
  sdramArbiter.io.clientBurstLen(1) := pixelArea.bitmapRowFetch.io.sdramBurstLen
  pixelArea.bitmapRowFetch.io.sdramDout      := sdramArea.ctrl.io.dout
  // RGB565-FULLFRAME-132 (#12283): feed the 32-bit aperture so the direct-color
  // fetch reads 4 bytes per SDRAM transaction (same broadcast dout32 the tile/
  // planar fetches already consume). Cuts 640 byte-reads/row to 160 word-reads.
  pixelArea.bitmapRowFetch.io.sdramDout32    := sdramArea.ctrl.io.dout32
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
  // BSRAM-L1-GATE-154: client 3 = L1 fetch when enabled; held idle when disabled
  // (clientCount stays 6 — only this client's request lines are tied off).
  pixelArea.fetchL1 match {
    case Some(f) =>
      sdramArbiter.io.clientRd(3)   := f.io.sdramRd
      sdramArbiter.io.clientWr(3)   := f.io.sdramWr
      sdramArbiter.io.clientAddr(3) := f.io.sdramAddr
      sdramArbiter.io.clientDin(3)  := f.io.sdramDin
      f.io.sdramDout      := sdramArea.ctrl.io.dout
      f.io.sdramDout32    := sdramArea.ctrl.io.dout32
      f.io.sdramDataReady := sdramArea.ctrl.io.data_ready
      f.io.sdramBusy      := sdramArea.ctrl.io.busy
    case None =>
      sdramArbiter.io.clientRd(3)   := False
      sdramArbiter.io.clientWr(3)   := False
      sdramArbiter.io.clientAddr(3) := U(0, 23 bits)
      sdramArbiter.io.clientDin(3)  := B(0, 8 bits)
  }

  // #11123 FIX 1: `uploadDrive` is defined below (uploadPopArea), after
  // dbgReadArea, so the upload pop can defer to an in-flight debug read.

  // DIAG #10908 (P4 Task A) — sdram-domain one-shot read FSM for the readback
  // surface. Arms on the dbgArm toggle (CDC from pixel domain); issues the read
  // ONLY when the SDRAM is fully idle (no arbiter rd/wr, no upload, no refresh,
  // controller !busy). When busy=0 there is no transaction in flight, so no
  // engine (fetch/bitmap/planar) is awaiting data_ready — the debug read can
  // never steal another consumer's data_ready. Result latched in dataReg.
  val dbgReadArea = new ClockingArea(sdramClockDomain) {
    val armSync  = BufferCC(pixelArea.dbgArm, False)
    val armPrev  = RegNext(armSync) init False
    val armEdge  = armSync =/= armPrev
    val addrSync = BufferCC(pixelArea.dbgAddr, U(0, 23 bits))
    val pending  = Reg(Bool()) init False
    val inFlight = Reg(Bool()) init False
    val rdPulse  = Reg(Bool()) init False
    val rdAddr   = Reg(UInt(23 bits)) init 0
    val dataReg  = Reg(Bits(32 bits)) init 0
    rdPulse := False
    when(armEdge) { pending := True }
    // #11123 FIX 2 (commit barrier, BronzeGate #11144 Finding 2): the debug read
    // must reflect COMMITTED writes, so it waits until the upload queue is fully
    // drained — CC FIFO empty (no pending pop) AND the bridge no longer producing.
    // Otherwise an immediate readback can overtake queued writes and return stale
    // storage (uploadDone fires on FIFO-accept, not SDRAM commit). Priority:
    // arbiter/refresh > upload drain > debug read (deadlock-free: upload never
    // waits on the debug read; the debug read waits on the drain).
    val uploadBusySync = BufferCC(pixelArea.qspiSdramBridge.io.uploadBusy, False)
    val uploadDrained  = !uploadCc.io.pop.valid && !uploadBusySync
    val sdramIdle = !sdramArbiter.io.sdramRd && !sdramArbiter.io.sdramWr &&
                    !pixelArea.fetch.io.sdramRefresh &&
                    !sdramArea.ctrl.io.busy
    when(pending && !inFlight && sdramIdle && uploadDrained) {
      rdPulse  := True
      rdAddr   := addrSync
      inFlight := True
      pending  := False
    }
    val dataCaptured = inFlight && sdramArea.ctrl.io.data_ready
    when(dataCaptured) {
      dataReg  := sdramArea.ctrl.io.dout32
      inFlight := False
    }
    // #11246 F1@869: dataReg crosses to the pixel domain (sel=8 readback) over a
    // 32-bit BufferCC. Flip a result-ready toggle ONE CYCLE AFTER dataReg settles so
    // the pixel side latches the value only once the multi-bit BufferCC is coherent,
    // never a torn intermediate during the update cycle.
    val resultToggle = Reg(Bool()) init False
    when(RegNext(dataCaptured) init False) { resultToggle := !resultToggle }
  }

  // #11123 FIX 1 + arbiter-race repair (BronzeGate #11144 Finding 1): pop an
  // upload write ONLY when the controller is truly idle — not busy, in blanking,
  // AND no arbiter read/write or refresh THIS cycle. The earlier canAccept omitted
  // the arbiter/refresh exclusions: a coincident fetch read + upload pop made
  // sdram.v take a READ (rd wins the rd|wr ternary) at the UPLOAD address, so the
  // fetch got wrong-address data AND the upload write was LOST (the pop had already
  // fired) — exactly the address-mixing / lost-write symptom on the live H-blank
  // schedule (VdpTop moves fetch grants to the start of H-blank, which is !de).
  // Debug read now defers to upload drain (FIX 2), so upload no longer defers to a
  // pending/in-flight debug read; `!rdPulse` only prevents same-cycle ctrl drive
  // (moot since debug issues only when the FIFO is drained). addr+din are atomic in
  // the popped payload. With these exclusions, uploadDrive can NEVER coincide with
  // arbiter/refresh/debug ctrl traffic, so every popped entry commits as a write.
  val uploadPopArea = new ClockingArea(sdramClockDomain) {
    // #11246 F2 (GT-17 look-ahead) + F4/F5 (drop the stale de gate). The upload
    // write is muxed onto the controller at top level, competing with whatever the
    // fetch clients drive. Each client's sdramRd/Wr is REGISTERED (asserts the
    // cycle AFTER its FSM commits), so gating only on the current value let an
    // upload pop fire into the cycle a fetch was about to read -> sdram.v's rd|wr
    // ternary takes the read and the upload write is silently lost (CyanPeak GT-17).
    // Fix: gate on each contending client's CURRENT and NEXT-cycle (getAheadValue)
    // request, plus refresh. This also removes the de/deSync gating (F4/F5) — uploads
    // drain during ANY truly-idle SDRAM cycle (incl. active video), bounded by the
    // 4 MHz host cap, instead of only in blanking (which overflowed the byteFifo).
    val fetchBusy = pixelArea.fetch.io.sdramRd   || pixelArea.fetch.io.sdramWr   ||
                    pixelArea.fetch.io.sdramRdNext || pixelArea.fetch.io.sdramWrNext ||
                    pixelArea.fetch.io.sdramRefresh || pixelArea.fetch.io.sdramRefreshNext
    // BSRAM-L1-GATE-154: False when L1 fetch is not instantiated.
    val fetchL1Busy = pixelArea.fetchL1.map(f =>
                        f.io.sdramRd   || f.io.sdramWr   ||
                        f.io.sdramRdNext || f.io.sdramWrNext ||
                        f.io.sdramRefresh || f.io.sdramRefreshNext).getOrElse(False)
    val bitmapBusy = pixelArea.bitmapRowFetch.io.sdramRd || pixelArea.bitmapRowFetch.io.sdramWr ||
                     pixelArea.bitmapRowFetch.io.sdramRdNext || pixelArea.bitmapRowFetch.io.sdramWrNext
    // Planar (client 2) is gated on its CURRENT request: its Next would require
    // 4-level IO plumbing (BitplaneRowFetch->PlanarLineFetch->VdpTop->top) and it is
    // not a live client during tile uploads. Current-gating is sufficient here (the
    // upload write reaches the controller first and makes it busy; a next-cycle
    // planar read then waits). Revisit if the seam sim shows otherwise.
    val planarBusy = pixelArea.video.io.planarSdramRd
    val anyClientActive = fetchBusy || fetchL1Busy || bitmapBusy || planarBusy
    val canAccept = !sdramArea.ctrl.io.busy && !anyClientActive && !dbgReadArea.rdPulse
    uploadCc.io.pop.ready := canAccept
    val fire = uploadCc.io.pop.fire
    val addr = uploadCc.io.pop.payload(30 downto 8).asUInt
    val din  = uploadCc.io.pop.payload(7 downto 0)
  }
  val uploadDrive = uploadPopArea.fire

  // CP-A2 (#11421/#11426): UPLOAD = first-class arbiter client 4. uploadDrive
  // (= uploadPopArea.pop.fire, the proven idle-cycle decision: !ctrl.busy &&
  // !anyClientActive && !dbgRead) overrides the grant id to 4 for the ctrl-bound
  // request mux. The arbiter then emits ONLY the upload write (clientRd(4)=False),
  // so an upload write can NEVER collide with a fetch read on sdram.v's rd|wr
  // ternary — the structural cure for the old side-channel-OR hazard. Fetch clients
  // 0-3 keep scheduler-driven clientGrant/clientSlotValid fan-out (baseGrantId);
  // the override only fires when !anyClientActive, so it never steals a fetch grant.
  sdramArbiter.io.clientRd(4)   := False
  sdramArbiter.io.clientWr(4)   := uploadDrive
  sdramArbiter.io.clientAddr(4) := uploadPopArea.addr
  sdramArbiter.io.clientDin(4)  := uploadPopArea.din
  // CP-A2b: DEBUG READ = client 5 (read-only, lowest priority). dbgReadArea.rdPulse
  // already fires ONLY when sdramIdle (no fetch rd/wr/refresh, !busy) AND uploadDrained,
  // so granting client 5 on rdPulse never collides with fetch or upload. The read makes
  // the controller busy, which blocks fetch issue for the read's duration -> the
  // dataCaptured snoop (inFlight && data_ready) can only see the debug read's own data.
  sdramArbiter.io.clientRd(5)   := dbgReadArea.rdPulse
  sdramArbiter.io.clientWr(5)   := False
  sdramArbiter.io.clientAddr(5) := dbgReadArea.rdAddr
  sdramArbiter.io.clientDin(5)  := B(0, 8 bits)
  // Grant priority: UPLOAD(4) > DEBUG(5) > fetch(baseGrantId). uploadDrive and rdPulse
  // are mutually exclusive (rdPulse requires uploadDrained), so the order is moot for
  // correctness but matches the signed-off hierarchy (Refresh P0 > Fetch P1 > Upload P2
  // > Debug P3; refresh is still controller-internal until CP-A3).
  sdramArbiter.io.grantClientId := Mux(uploadDrive,            U(4, sdramArbiter.idBits bits),
                                   Mux(dbgReadArea.rdPulse,    U(5, sdramArbiter.idBits bits),
                                       sdramArbArea.baseGrantId))

  // DIAG #10928 readback fix: the debug read OWNS the controller bus from issue
  // (rdPulse) until capture (inFlight clears). Without `!dbgReadArea.inFlight`,
  // the fetch engine kept issuing reads through the arbiter while the debug read
  // was outstanding, so the snoop at `when(inFlight && data_ready)` latched the
  // FETCH transaction's dout32 — data independent of the debug address (the
  // "fixed data regardless of upload/addr" symptom in TopazCliff #10928).
  // sdramIdle already blocks issue while a fetch read is mid-flight, so this only
  // delays a not-yet-started fetch read by the few cycles of the one-shot read.
  // CP-A2b: debug read reaches ctrl via the arbiter (client 5) — no more `|| rdPulse`
  // OR. A FETCH read (grant != 5) is still blocked while a debug read is in flight
  // (the #10928 guard) so the snoop can't latch fetch data; the debug read itself
  // (grant === 5) is allowed.
  // VDP-SOFT-RESET-135 #3 part 2b: while the zero-fill engine is active it OWNS the
  // controller command bus (arbiter bypassed — display quiescent during reset).
  // All of fill/arbiter/ctrl are in sdramClockDomain, so this mux is same-domain.
  val sdramFillActive = sdramFill.io.active
  sdramArea.ctrl.io.rd      := !sdramFillActive && sdramArbiter.io.sdramRd &&
                               (!dbgReadArea.inFlight ||
                                sdramArbiter.io.grantClientId === U(5, sdramArbiter.idBits bits))
  // CP-A2: upload write now arrives via the arbiter (client 4) — no more `|| uploadDrive`
  // side-channel OR. sdramWr already carries the upload write when grantClientId===4.
  sdramArea.ctrl.io.wr      := Mux(sdramFillActive, sdramFill.io.wr, sdramArbiter.io.sdramWr)
  // #11246 F6: merge L1's refresh request too (was L0-only — L1's refresh pulses
  // were dropped, so an enabled L1 region would decay). AUTO_REFRESH is chip-global
  // (refreshes all banks/rows), so a single OR'd pulse correctly serves both
  // engines; no per-engine refresh accounting needed. Latent in the current
  // bitstream (enableL1Fetch=false) but required before L1 is ever enabled.
  sdramArea.ctrl.io.refresh := Mux(sdramFillActive, sdramFill.io.refresh,
                                   pixelArea.fetch.io.sdramRefresh ||
                                     pixelArea.fetchL1.map(_.io.sdramRefresh).getOrElse(False))  // BSRAM-L1-GATE-154
  // CP-A2b: addr/din now come PURELY from the arbiter for ALL clients — upload (4)
  // via clientAddr/Din(4), debug read (5) via clientAddr(5)=rdAddr. The dbgRead addr
  // Mux side-path is gone; ctrl is driven by a single arbitrated source.
  sdramArea.ctrl.io.addr    := Mux(sdramFillActive, sdramFill.io.addr, sdramArbiter.io.sdramAddr)
  sdramArea.ctrl.io.din     := Mux(sdramFillActive, sdramFill.io.din,  sdramArbiter.io.sdramDin)
  // RGB565-FULLFRAME-132 Phase 0: forward the granted client's read burst length to
  // the controller. Only the bitmap direct-color client (1) requests bursts (8 words);
  // every other client uses single reads (burstLen=1, bit-identical to legacy).
  sdramArea.ctrl.io.burstLen := Mux(sdramFillActive, U(1, 4 bits), sdramArbiter.io.sdramBurstLen)
  pixelArea.fetch.io.sdramDout      := sdramArea.ctrl.io.dout
  pixelArea.fetch.io.sdramDout32    := sdramArea.ctrl.io.dout32
  pixelArea.fetch.io.sdramDataReady := sdramArea.ctrl.io.data_ready
  // DIAG #10908 (P4 Task A) — CDC the sdram-domain read result back to the
  // pixel domain and feed the QspiDecoder sel=8 surface. dataReg is quasi-static
  // (changes once per armed read; host polls it in a much later transaction),
  // so a 2-stage BufferCC is a sufficient synchronizer.
  val dbgResultPixArea = new ClockingArea(pixelClockDomain) {
    // #11246 F1@869: latch the readback result only on the synchronized result-ready
    // toggle edge, by which point the 32-bit dataReg BufferCC has settled (dataReg
    // was stable >=1 cycle before the toggle flipped). Prevents a torn 32-bit
    // readback if the host polls during the update window.
    val resultToggleSync = BufferCC(dbgReadArea.resultToggle, False)
    val resultTogglePrev = RegNext(resultToggleSync) init False
    val dataSync         = BufferCC(dbgReadArea.dataReg, B(0, 32 bits))
    val edge             = resultToggleSync =/= resultTogglePrev
    // option-4 CDC hardening (lane qspi-upload-si-hardening #14568/#14574): the source flips
    // `resultToggle` only ~1 sdram cycle after `dataReg` settles; that <1-pixel-cycle lead can be
    // absorbed by the slower pixel clock (and 2FF metastability can invert the resolution order),
    // so latching `dataSync` ON the toggle edge risks grabbing the PRIOR value — the confirmed
    // 1-read lag on the sel=8 result (dummy-neighbor lag_matches=16/16, HW #14563). Fix: DELAY the
    // latch two pixel cycles after the edge so `dataSync` is fully settled, and derive READ_DONE
    // from that settled latch. NOTE: Verilator models BufferCC as ideal 2-FF (no metastability), so
    // the co-sim shows logical correctness but the HW test is the arbiter of the real-timing margin.
    val edgeReady        = RegNext(RegNext(edge, False), False)   // 2-cycle settle after the edge
    val dbgResultHold    = Reg(Bits(32 bits)) init 0
    when(edgeReady) { dbgResultHold := dataSync }
    pixelArea.debugSdramDataPix := dbgResultHold

    // READ_DONE (sel=0x0C bit0, high-true): cleared on the 0x0327 arm write, set after the settled
    // latch. Arm-clear wins if coincident (a fresh arm supersedes a stale completion).
    val readDone = Reg(Bool()) init False
    when(edgeReady)            { readDone := True }
    when(pixelArea.dbgHiWrite) { readDone := False }
    pixelArea.debugReadDonePix := readDone
  }

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

object TopTang20kHdmiDiagnosticVerilog extends App {
  // standalone-diagnostic-build lane: no-host / no-QSPI / no-SDRAM native 640x480
  // build that boots the on-chip bootstrap and displays a clean 1x full-screen L0
  // grid test pattern. Module name = top_tang20k_diagnostic -> hw/gen/top_tang20k_diagnostic.v.
  Config.spinal.generateVerilog(TopTang20kHdmi(enableL1Fetch = false, diagnosticMode = true))
}

object TopTang20kI80Verilog extends App {
  // Lane P21 Checkpoint C: i80 parallel-host variant (top_tang20k_i80) for the
  // 3-build STA. Same VDP core as the default top with the i80 front-end added.
  Config.spinal.generateVerilog(TopTang20kHdmi(enableL1Fetch = false, hostI80 = true))
}

