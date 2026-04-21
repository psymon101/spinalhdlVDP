package spinalhdlvdp

import spinal.core._
import spinal.lib.BufferCC   // Task 34 CDC — toggle-based crossing for upload pulse

/** Tang Nano 20K top.
  *
  * `scenarioId` selects the bootstrap configuration:
  *   0 = default (Task 20 + R4.1d Checkpoint C: shuffled diagnostic + shadow window)
  *   1 = Wave 1 Scenario 1 — static L1 background, no sprites, no scroll, color math passthrough
  *   2 = Wave 1 Scenario 2 — Scenario 1 + per-frame layer1ScrollX +1 px/frame
  *   3 = Wave 1 Scenario 3 — Scenario 1 + per-frame layer1ScrollX +8 px/frame (frequent wrap)
  *   4 = Wave 1 Scenario 4 — Scenario 1 + sprite 0 enabled at fixed (320, 240)
  *   5 = Wave 1 Scenario 5 — Scenario 1 + 4 sprites enabled, bouncing motion
  *   6..11 = Wave 2 scenarios (see `PROJECT_PLAN/scenarios/SCENARIO_*.md`)
  *  12 = Task 19 Checkpoint C — affine background with sprite. L0 driven by
  *       AffineStepper + 128×128 diagnostic texture. Per-frame matrix animator
  *       rotates the texture ~2°/frame around the screen center at scale 0.9×.
  *       One sprite moves horizontally across the rotating background.
  *  13 = Palette animation during motion — L0 packed-mode rich tiles + L1
  *       1 px/frame scroll + copper-driven `VDP_ATTR_MODE` toggle across 7 bands
  *       (linear ↔ packed 2×2). Palette is ROM-only, so this proves
  *       palette-cycle-like color animation via attribute-mode switching rather
  *       than literal palette rewrites. See `SCENARIO_13.md`.
  *  15 = Task 21 Mixed-Scene Integration — three horizontal L0 bands (tile /
  *       planar / shuffled) driven by copper-commanded `VDP_TILE_MODE`
  *       switches at y=160 and y=320, concurrent L0 scroll, and two
  *       horizontally-bouncing sprites crossing the mode boundaries. Pure
  *       integration, no new primitives/registers. See `SCENARIO_15.md`.
  *  16 = Task 22 Long-Soak baseline — identical to Sc15 integration scene.
  *  17 = Task 23 Stress-Scene — maximum concurrent load: L0 mixed-mode
  *       bands + L0 scroll 2 px/frame + L1 packed scroll 4 px/frame +
  *       4 sprites bouncing 4 px/frame + copper 3 triggers/frame.
  *       No new primitives. See `SCENARIO_17.md`.
  */
case class TopTang20kHdmi(scenarioId: Int = 0) extends Component {
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
    // Scroll step rates differ per scenario:
    //   scenarioId 0 = R4.1d Checkpoint C / Task 20 default (existing rates)
    //   1 = static (no scroll)
    //   2 = +1 px/frame on L1 (Scenario 2 single-axis scroll)
    //   3 = +8 px/frame on L1 (Scenario 3 frequent wrap)
    //   4 = static (single sprite)
    //   5 = static (4 bouncing sprites, motion is on sprite X/Y not on scroll)
    val l0StepFrames = scenarioId match {
      case 0 => 1
      case 8 => 1     // Sc8 parallax: L0 slow
      case 15 => 1    // Sc15 mixed-scene integration: L0 @ 1 px/frame
      case 16 => 1    // Sc16 long-soak baseline: same L0 scroll as Sc15
      case 17 => 2    // Sc17 stress: L0 @ 2 px/frame
      case _ => 0
    }
    val l1StepFrames = scenarioId match {
      case 0 => 2
      case 2 => 1
      case 3 => 8
      case 6 => 1     // Sc6 sprites over scrolling bg
      case 8 => 3     // Sc8 parallax: L1 fast (3× L0)
      case 13 => 1    // Sc13 palette-animation-during-motion: L1 @ 1 px/frame
      case 17 => 4    // Sc17 stress: L1 @ 4 px/frame (parallax 2× L0)
      case _ => 0
    }
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
    val copperProgram: Seq[Int] = scenarioId match {
      case 13 =>
        // Sc13: toggle VDP_ATTR_MODE @ 0x0312 every 60 lines across 7 bands.
        // Safe-boundary commit (hCounter===0) already handled in VdpTop.scala
        // so each toggle lands cleanly at line start.
        Seq(
          (0 << 14) |  60, (1 << 14) | 0x0312, 0x0001,
          (0 << 14) | 120, (1 << 14) | 0x0312, 0x0000,
          (0 << 14) | 180, (1 << 14) | 0x0312, 0x0001,
          (0 << 14) | 240, (1 << 14) | 0x0312, 0x0000,
          (0 << 14) | 300, (1 << 14) | 0x0312, 0x0001,
          (0 << 14) | 360, (1 << 14) | 0x0312, 0x0000,
          (0 << 14) | 420, (1 << 14) | 0x0312, 0x0001,
          (3 << 14) | 0
        )
      case 15 | 16 | 17 =>
        // Sc15 (Task 21): switch L0 VDP_TILE_MODE from packed (0) → planar (1)
        // at y=160, then planar → shuffled (2) at y=320. Three horizontal L0
        // bands of distinct fetch modes. Safe-boundary commit guarantees clean
        // band edges.
        // Fix: add WAIT y=0 reset to packed so frame start is deterministic.
        // Sc16 (Task 22) reuses the identical bootstrap for the 1-hour soak test.
        // Sc17 (Task 23) also reuses this copper cadence under maximum load.
        Seq(
          (0 << 14) |   0, (1 << 14) | 0x0311, 0x0000,
          (0 << 14) | 160, (1 << 14) | 0x0311, 0x0001,
          (0 << 14) | 320, (1 << 14) | 0x0311, 0x0002,
          (3 << 14) | 0
        )
      case 28 =>
        // Task 28 CP-C — probe confirmed regEnabled(0) LATCHES on
        // hardware (CyanPeak #7888 verdict: green corner observed).
        // Restoring the full 4-descriptor program (slots 4..7) at y=250.
        // Green-corner probe retained as a live indicator that slot 4
        // remains enabled throughout the capture.
        Seq(
          (0 << 14) | 0,                                     // WAIT y=0
          // Task 37 bus layout: 8 words per slot. slot N word W = 0x0800+N*8+W.
          (1 << 14) | 0x0820, 0x8000 | 250,                  // slot 4 word0: en, y=250
          (1 << 14) | 0x0821, 60,                            // slot 4 x=60
          (1 << 14) | 0x0828, 0x8000 | (1 << 11) | 250,      // slot 5 word0: en, patIdx=1, y=250
          (1 << 14) | 0x0829, 140,                           // slot 5 x=140
          (1 << 14) | 0x0830, 0x8000 | 250,                  // slot 6 word0
          (1 << 14) | 0x0831, 220,                           // slot 6 x=220
          (1 << 14) | 0x0838, 0x8000 | (1 << 11) | 250,      // slot 7 word0
          (1 << 14) | 0x0839, 300,                           // slot 7 x=300
          (3 << 14) | 0                                      // JUMP 0
        )
      case 29 =>
        // Task 29 hardware proof: sprite-background collision sticky
        // flags. Copper bootstrap enables sprite slot 4 at (100, 100)
        // pattern 0 so it sits over the on-chip BasicPatternSource
        // background (non-transparent in most tiles). The ever-present
        // overlap causes STATUS_STICKY bit 4 (SPRITE_0_HIT) and bit 5
        // (SPRITE_BG_HIT) to latch. An on-screen canary in the top-
        // left corner (driven at top-level from video.io.statusSticky)
        // visualises bit 4 for hardware confirmation without
        // requiring a firmware polling loop.
        //
        // slot 4 is descCount=8's lowest bus-programmable descriptor;
        // with slots 0..3 (legacy IO) disabled it becomes the Pass-1
        // active slot 0, so SPRITE_0_HIT applies.
        Seq(
          (0 << 14) | 0,                                 // WAIT y=0
          (1 << 14) | 0x0820, 0x8000 | 100,              // slot 4 w0: en|pat=0|y=100
          (1 << 14) | 0x0821, 100,                       // slot 4 w1: x=100
          (3 << 14) | 0                                  // JUMP 0
        )
      case 31 =>
        // Task 31 hardware proof: per-column scroll table.
        // Program L0 scroll-table entries so the right half of the
        // screen scrolls +16 px relative to the left half. Entries
        // 0..39 cover hCounter 0..319 (left half) and default to 0;
        // entries 40..79 cover hCounter 320..639 (right half) and get
        // an offset of +16. Bands are 8 pixels each (hCounter bits
        // [9:3] index the 128-entry table).
        //   address = 0x0900 + entry  (L0 block)
        val shearBase = Seq(
          (0 << 14) | 0                                   // WAIT y=0
        )
        val shearEntries: Seq[Int] = (40 until 80).flatMap { e =>
          Seq((1 << 14) | (0x0900 + e), 8)
        }
        val shearJump = Seq((3 << 14) | 0)                // JUMP 0
        shearBase ++ shearEntries ++ shearJump
      case 37 =>
        // Task 37 hardware proof: per-sprite affine transforms.
        // Copper bootstrap programs three extended slots:
        //   - slot 4 @ (200,200) patIdx=0, 45° rotation around center
        //   - slot 5 @ (400,200) patIdx=1, 2× scale (texture sampled
        //     half-rate; middle scanlines of a 32×32 screen-bbox visible
        //     within the 16-line Pass-1 y-bbox)
        //   - slot 6 @ (100,400) patIdx=0, flat reference (affineEnable=0)
        //
        // Inverse-transform matrices (host computes "screen → texture"):
        //   45°:    A=cos=0x00B5, B=sin=0x00B5, C=-sin=0xFF4B, D=cos=0x00B5
        //           transX = (2048 - (A*cx + B*cy))/4; cx=cy=208
        //                  = (2048 - 75296)/4 = -18312 = 0xB878
        //           transY = (2048 - (C*cx + D*cy))/4 = 512 = 0x0200
        //   2×:     A=0x0080, D=0x0080, B=C=0;   cx=408, cy=208
        //           transX = (2048 - 128*408)/4 = -12544 = 0xCF00
        //           transY = (2048 - 128*208)/4 =  -6144 = 0xE800
        // Reuses the Task 19 AffineStepper Q8.8 / Q10.6 contract.
        Seq(
          (0 << 14) | 0,                                             // WAIT y=0
          // Slot 4 — 45° rotation sprite at (200, 200), patIdx=0
          (1 << 14) | 0x0820, 0x8400 | 200,                          // w0 en|aff|pat=0|y=200
          (1 << 14) | 0x0821, 200,                                   // w1 x=200
          (1 << 14) | 0x0822, 0x00B5,                                // matrixA = cos 45
          (1 << 14) | 0x0823, 0x00B5,                                // matrixB = sin 45
          (1 << 14) | 0x0824, 0xFF4B,                                // matrixC = -sin 45
          (1 << 14) | 0x0825, 0x00B5,                                // matrixD = cos 45
          (1 << 14) | 0x0826, 0xB878,                                // transX
          (1 << 14) | 0x0827, 0x0200,                                // transY
          // Slot 5 — 2× scale sprite at (400, 200), patIdx=1
          (1 << 14) | 0x0828, 0x8C00 | 200,                          // w0 en|aff|pat=1|y=200
          (1 << 14) | 0x0829, 400,                                   // w1 x=400
          (1 << 14) | 0x082A, 0x0080,                                // matrixA = 0.5
          (1 << 14) | 0x082B, 0x0000,
          (1 << 14) | 0x082C, 0x0000,
          (1 << 14) | 0x082D, 0x0080,                                // matrixD = 0.5
          (1 << 14) | 0x082E, 0xCF00,                                // transX
          (1 << 14) | 0x082F, 0xE800,                                // transY
          // Slot 6 — flat reference sprite at (100, 400), patIdx=0
          (1 << 14) | 0x0830, 0x8000 | 400,                          // w0 en|pat=0|y=400
          (1 << 14) | 0x0831, 100,                                   // w1 x=100
          (3 << 14) | 0                                              // JUMP 0
        )
      case 33 =>
        // Task 33 HW proof (per CyanPeak #7767 / BronzeGate #7766 direction):
        // HDMA drives COLOR_MATH_CTRL (0x0334) at 4 vertical positions,
        // producing 4 full-screen tint bands independent of layer content.
        //
        // COLOR_MATH_CTRL bit layout (see VdpTop.scala:825-831, ColorMath.scala):
        //   bits[15:14] = op  (00=pass, 01=shadow, 10=add const)
        //   bit[13]     = windowUnit.invert — with scenario's zero-sized
        //                 window this is equivalent to "effect=True
        //                 everywhere," so ColorMath runs full-screen.
        //   bits[7:0]   = add-op constant
        //
        // Band plan (4 visually distinct tints):
        //   line 0   : 0x2000 invert+pass     → passthrough band (baseline bright)
        //   line 120 : 0x6000 invert+shadow   → dim band (>>1)
        //   line 240 : 0xA080 invert+add 0x80 → bright saturated band
        //   line 360 : 0x6000 invert+shadow   → dim band again
        Seq(
          (0 << 14) | 0,                                              // WAIT y=0 (paces once/frame)
          (1 << 14) | 0x0382, 0x0334,                                 // chAddr0 = COLOR_MATH_CTRL
          (1 << 14) | 0x038A, 0x8000 | 0,    (1 << 14) | 0x038B, 0x2000,
          (1 << 14) | 0x038C, 0x8000 | 120,  (1 << 14) | 0x038D, 0x6000,
          (1 << 14) | 0x038E, 0x8000 | 240,  (1 << 14) | 0x038F, 0xA080,
          (1 << 14) | 0x0390, 0x8000 | 360,  (1 << 14) | 0x0391, 0x6000,
          (1 << 14) | 0x0380, 0x0003,                                 // HDMA_CTRL = enable + ch0 mask
          (3 << 14) | 0                                               // JUMP 0 (back to WAIT)
        )
      case _ =>
        Seq(
          (0 << 14) | 160,              // WAIT y=160
          (1 << 14) | 0x0300,           // WRITE addr=0x0300
          0x0001,                       // data (L0 only)
          (0 << 14) | 320,              // WAIT y=320
          (1 << 14) | 0x0300,           // WRITE addr=0x0300
          0x0003,                       // data (L0 + L1)
          (3 << 14) | 0                 // JUMP 0
        )
    }

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
    // Sc 11 only: 60 additional linestate writes (every 8th line, lines 0..472).
    val LinestateCount = 60
    val linestateBase  = colorMathIdx + 1   // first linestate step
    val lastStepIdx    = scenarioId match {
      case 11 => U(copperLen + 8 + LinestateCount, 7 bits)   // = 75
      case 12 => U(copperLen + 9, 7 bits)                    // + AFFINE_CTRL step
      case _  => colorMathIdx
    }
    val bootIdx     = Reg(UInt(7 bits)) init 0
    val bootDoneR   = Reg(Bool())      init False

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
    val tileModeData = B(scenarioId match {
      case 0      => 0x0002    // R4.1d Checkpoint C: shuffled
      case 9      => 0x0001    // Sc9: planar
      case 10     => 0x0002    // Sc10: shuffled
      case _      => 0x0000    // packed default (Sc16 also takes this)
    }, 16 bits)
    val attrModeAddr = U(0x0312, 15 bits)
    val attrModeData = B(scenarioId match {
      case 8 | 11 => 0x0001    // Sc8/Sc11: packed 2×2 attr for L0 visual richness
      case _      => 0x0000    // linear (all other scenarios)
    }, 16 bits)
    val ctrlAddr     = U(0x0310, 15 bits)
    // Copper enabled ONLY for Sc13 (copper drives ATTR_MODE toggle animation).
    // All other scenarios leave copper disabled even though the program is
    // uploaded to 0x0400+.
    val ctrlData     = B(
      if (scenarioId == 13 || scenarioId == 15 || scenarioId == 16 || scenarioId == 17 || scenarioId == 33 || scenarioId == 28 || scenarioId == 37 || scenarioId == 31 || scenarioId == 29) 0x0001
      else 0x0000, 16 bits)
    val layerAddr    = U(0x0300, 15 bits)
    val layerData    = B(scenarioId match {
      case 0           => 0x0001  // R4.1d Checkpoint C: L0 only
      case 1 | 2 | 3   => 0x0002  // L1 only
      case 4 | 5 | 28  => 0x0006  // L1 + sprite layer (Sc28 reuses Sc5 pattern)
      case 6 | 7       => 0x0006  // L1 + sprite layer (sprites over bg)
      case 8           => 0x0003  // L0 + L1 (parallax, no sprites)
      case 9 | 10      => 0x0001  // L0 only (planar/shuffled bitmap)
      case 11          => 0x0003  // L0 + L1 default; per-line linestate overrides
      case 12          => 0x0005  // L0 + sprite (affine background under sprite)
      case 13          => 0x0003  // L0 + L1 (palette-animation-during-motion)
      case 15          => 0x0005  // L0 + sprite (mixed-scene integration)
      case 16          => 0x0005  // Sc16 long-soak baseline: same layer config as Sc15
      case 17          => 0x0007  // Sc17 stress: L0 + L1 + sprite (maximum load)
      case 29          => 0x0005  // Sc29: L0 + sprite (collision flag proof)
      case 31          => 0x0001  // Sc31: L0 only — on-chip BasicPatternSource for per-column scroll shear
      case 37          => 0x0005  // Sc37: L0 background + sprite (affine proof)
      case _           => 0x0001
    }, 16 bits)
    // R6 Task 20: window centred at (160..480) × (120..360) — 320×240 region
    // covering the middle of the 640×480 screen. Color-math op=01 (shadow,
    // RGB>>1) applies inside the window; outside renders unchanged. This
    // gives an unambiguous OpenCV intensity ratio across the boundary.
    val winX0Addr     = U(0x0330, 15 bits)
    // Scenarios 1-5: window all-zero + color math passthrough so the new
    // R6 stage doesn't accidentally mask scenario validation.
    val scWindow = scenarioId != 0
    val winX0Data     = B(if (scWindow) 0   else 160, 16 bits)
    val winX1Addr     = U(0x0331, 15 bits)
    val winX1Data     = B(if (scWindow) 0   else 480, 16 bits)
    val winY0Addr     = U(0x0332, 15 bits)
    val winY0Data     = B(if (scWindow) 0   else 120, 16 bits)
    val winY1Addr     = U(0x0333, 15 bits)
    val winY1Data     = B(if (scWindow) 0   else 360, 16 bits)
    val colorMathAddr = U(0x0334, 15 bits)
    val colorMathData = B(if (scWindow) 0x0000 else 0x4000, 16 bits)

    // Sc12 only: last bootstrap step writes AFFINE_CTRL = 1 to enable affine.
    // The matrix regs (0x0340..0x0345) stay at their zero init until the first
    // vsync kicks off the animator below; between affineEnable rising and the
    // first animator write there's a sub-frame window where L0 sees u=v=0
    // (solid texel (0,0)) — harmless under the 100-frame capture warmup skip.
    val affineCtrlAddrReg = U(0x0346, 15 bits)
    val affineCtrlDataReg = B(0x0001, 16 bits)
    val isAffineCtrlStep  =
      if (scenarioId == 12) bootIdx === U(copperLen + 9, 7 bits) else False

    // Sc 11 linestate write computation (only used when scenarioId == 11):
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

    // Task 19 Checkpoint C — Sc12 affine matrix animator. Compute a 180-entry
    // LUT of (A, B, C, D, X, Y) at 2°/frame rotation around screen center
    // (320, 240) mapped to texture center (64, 64), scale 0.9×. After the
    // bootstrap finishes, each vsyncRising kicks off a 6-cycle sequence that
    // rewrites the affine matrix registers via the regWriteBus.
    // Per BronzeGate #7340 / CyanPeak #7341: proof-scene zoom so ONE texture
    // tile spans the 640-px screen width (128 texel / 640 px ≈ 0.2). This is
    // a local proof-scene parameter change — the AffineStepper contract,
    // register map, and modulo-128 wrap logic are untouched.
    val sc12Lut: Seq[(Int, Int, Int, Int, Int, Int)] = (0 until 180).map { i =>
      val theta = i.toDouble * 2.0 * math.Pi / 180.0
      val cos = math.cos(theta)
      val sin = math.sin(theta)
      val scale = 0.2
      val A = scale * cos
      val B = -scale * sin
      val C = scale * sin
      val D = scale * cos
      val cx = 320.0; val cy = 240.0
      val tcx = 64.0; val tcy = 64.0
      val X = tcx - cx * A - cy * B
      val Y = tcy - cx * C - cy * D
      val aFix = (A * 256.0).round.toInt & 0xFFFF
      val bFix = (B * 256.0).round.toInt & 0xFFFF
      val cFix = (C * 256.0).round.toInt & 0xFFFF
      val dFix = (D * 256.0).round.toInt & 0xFFFF
      val xFix = (X * 64.0).round.toInt & 0xFFFF
      val yFix = (Y * 64.0).round.toInt & 0xFFFF
      (aFix, bFix, cFix, dFix, xFix, yFix)
    }

    val (animWriteAddr, animWriteData, animWriteActive): (UInt, Bits, Bool) =
      if (scenarioId == 12) {
        val frameIdx     = Reg(UInt(8 bits)) init 0
        val animWriteIdx = Reg(UInt(3 bits)) init 7   // 7 = idle, 0..5 = writing
        when(bootDoneR && vsyncRising) {
          frameIdx := Mux(frameIdx === U(179, 8 bits), U(0, 8 bits), frameIdx + 1)
          animWriteIdx := 0
        }
        when(animWriteIdx < U(6, 3 bits)) {
          animWriteIdx := animWriteIdx + 1
        }

        def seqToBits(extract: ((Int, Int, Int, Int, Int, Int)) => Int): Seq[Bits] =
          sc12Lut.map(t => B(extract(t), 16 bits))
        val matA = Mem(Bits(16 bits), 180).init(seqToBits(_._1))
        val matB = Mem(Bits(16 bits), 180).init(seqToBits(_._2))
        val matC = Mem(Bits(16 bits), 180).init(seqToBits(_._3))
        val matD = Mem(Bits(16 bits), 180).init(seqToBits(_._4))
        val matX = Mem(Bits(16 bits), 180).init(seqToBits(_._5))
        val matY = Mem(Bits(16 bits), 180).init(seqToBits(_._6))

        val a = UInt(15 bits)
        val d = Bits(16 bits)
        a := U(0, 15 bits)
        d := B(0, 16 bits)
        switch(animWriteIdx) {
          is(U(0, 3 bits)) { a := U(0x0340, 15 bits); d := matA.readAsync(frameIdx) }
          is(U(1, 3 bits)) { a := U(0x0341, 15 bits); d := matB.readAsync(frameIdx) }
          is(U(2, 3 bits)) { a := U(0x0342, 15 bits); d := matC.readAsync(frameIdx) }
          is(U(3, 3 bits)) { a := U(0x0343, 15 bits); d := matD.readAsync(frameIdx) }
          is(U(4, 3 bits)) { a := U(0x0344, 15 bits); d := matX.readAsync(frameIdx) }
          is(U(5, 3 bits)) { a := U(0x0345, 15 bits); d := matY.readAsync(frameIdx) }
          default          { a := U(0, 15 bits);      d := B(0, 16 bits) }
        }
        val active = animWriteIdx < U(6, 3 bits)
        (a, d, active)
      } else {
        (U(0, 15 bits), B(0, 16 bits), False)
      }

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
    regBusArbiter.io.masters(2).addr   := animWriteAddr
    regBusArbiter.io.masters(2).data   := animWriteData
    regBusArbiter.io.masters(2).enable := animWriteActive
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
    val scSprite0 = Set(4, 5, 6, 7, 12, 15, 16, 17, 28).contains(scenarioId)
    val scSprite1 = Set(5, 6, 7, 15, 16, 17, 28).contains(scenarioId)
    val scSprite23 = Set(5, 6, 17, 28).contains(scenarioId)
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
    if (scenarioId == 4) {
      video.io.sprite0X := U(320, 10 bits)
      video.io.sprite0Y := U(240, 10 bits)
      video.io.sprite1X := U(0, 10 bits)
      video.io.sprite1Y := U(0, 10 bits)
      video.io.sprite2X := U(0, 10 bits); video.io.sprite2Y := U(0, 10 bits)
      video.io.sprite3X := U(0, 10 bits); video.io.sprite3Y := U(0, 10 bits)
      video.io.sprite2Enabled := False
      video.io.sprite3Enabled := False
      video.io.sprite2PatternIdx := U(0, 1 bit)
      video.io.sprite3PatternIdx := U(1, 1 bit)
    } else if (scenarioId == 7) {
      // Sc7 priority overlap: BOTH sprites at the SAME (320,240). Slot-1
      // (sprite 1) wins everywhere — the entire visible footprint should
      // show pattern 1, not pattern 0. Cleanest test of slot-priority.
      video.io.sprite0X := U(320, 10 bits)
      video.io.sprite0Y := U(240, 10 bits)
      video.io.sprite1X := U(320, 10 bits)
      video.io.sprite1Y := U(240, 10 bits)
      video.io.sprite2X := U(0, 10 bits); video.io.sprite2Y := U(0, 10 bits)
      video.io.sprite3X := U(0, 10 bits); video.io.sprite3Y := U(0, 10 bits)
      video.io.sprite2Enabled := False
      video.io.sprite3Enabled := False
      video.io.sprite2PatternIdx := U(0, 1 bit)
      video.io.sprite3PatternIdx := U(1, 1 bit)
    } else if (Set(15, 16, 18).contains(scenarioId)) {
      // Sc15 (Task 21 Mixed-Scene Integration): two sprites bouncing
      // horizontally at 2 px/frame at y=100 (top band / tile mode) and
      // y=300 (middle band / planar mode), opposite phase so they sweep
      // the screen asynchronously. Sprites cross mode boundaries when
      // the copper triggers fire.
      val xMin = 16; val xMax = 624
      val s0x = Reg(UInt(10 bits)) init xMin
      val s1x = Reg(UInt(10 bits)) init xMax   // opposite phase
      val s0dir = Reg(Bool()) init False       // false = +2
      val s1dir = Reg(Bool()) init True        // true  = -2 (mirrors s0)
      when(vsyncRising) {
        when(s0dir) {
          when(s0x <= U(xMin + 2, 10 bits)) { s0dir := False; s0x := U(xMin, 10 bits) }
            .otherwise                        { s0x := s0x - 2 }
        }.otherwise {
          when(s0x >= U(xMax - 2, 10 bits)) { s0dir := True;  s0x := U(xMax, 10 bits) }
            .otherwise                        { s0x := s0x + 2 }
        }
        when(s1dir) {
          when(s1x <= U(xMin + 2, 10 bits)) { s1dir := False; s1x := U(xMin, 10 bits) }
            .otherwise                        { s1x := s1x - 2 }
        }.otherwise {
          when(s1x >= U(xMax - 2, 10 bits)) { s1dir := True;  s1x := U(xMax, 10 bits) }
            .otherwise                        { s1x := s1x + 2 }
        }
      }
      video.io.sprite0X := s0x
      video.io.sprite0Y := U(100, 10 bits)
      video.io.sprite1X := s1x
      video.io.sprite1Y := U(300, 10 bits)
      video.io.sprite2X := U(0, 10 bits); video.io.sprite2Y := U(0, 10 bits)
      video.io.sprite3X := U(0, 10 bits); video.io.sprite3Y := U(0, 10 bits)
      video.io.sprite2Enabled := False
      video.io.sprite3Enabled := False
      video.io.sprite2PatternIdx := U(0, 1 bit)
      video.io.sprite3PatternIdx := U(1, 1 bit)
    } else if (scenarioId == 17) {
      // Sc17 (Task 23 Stress-Scene Validation): all 4 sprites bouncing
      // horizontally at 4 px/frame between x=16..624, at y=80, 200, 320, 400.
      // Alternating phase so the per-line evaluator sees overlap often.
      val xMin = 16; val xMax = 624
      def bouncer(initX: Int, reverse: Boolean) = {
        val rx  = Reg(UInt(10 bits)) init (if (reverse) xMax else initX)
        val dir = Reg(Bool()) init (if (reverse) True else False)  // false = +4
        when(vsyncRising) {
          when(dir) {
            when(rx <= U(xMin + 4, 10 bits)) { dir := False; rx := U(xMin, 10 bits) }
              .otherwise                      { rx := rx - 4 }
          }.otherwise {
            when(rx >= U(xMax - 4, 10 bits)) { dir := True;  rx := U(xMax, 10 bits) }
              .otherwise                      { rx := rx + 4 }
          }
        }
        rx
      }
      val s0x = bouncer(xMin,        reverse = false)
      val s1x = bouncer(xMax,        reverse = true)
      val s2x = bouncer(xMin + 200,  reverse = false)
      val s3x = bouncer(xMax - 200,  reverse = true)
      video.io.sprite0X := s0x; video.io.sprite0Y := U( 80, 10 bits)
      video.io.sprite1X := s1x; video.io.sprite1Y := U(200, 10 bits)
      video.io.sprite2X := s2x; video.io.sprite2Y := U(320, 10 bits)
      video.io.sprite3X := s3x; video.io.sprite3Y := U(400, 10 bits)
      video.io.sprite2Enabled := True
      video.io.sprite3Enabled := True
      video.io.sprite2PatternIdx := U(0, 1 bit)
      video.io.sprite3PatternIdx := U(1, 1 bit)
    } else if (scenarioId == 12) {
      // Sc12: one sprite moves horizontally across the affine background at
      // 2 px/frame, pinned at Y=200. Other sprites disabled.
      val xMin = 16; val xMax = 624
      val s0x = Reg(UInt(10 bits)) init xMin
      val s0dir = Reg(Bool()) init False  // false = +2
      when(vsyncRising) {
        when(s0dir) {
          when(s0x <= U(xMin + 2, 10 bits)) { s0dir := False; s0x := U(xMin, 10 bits) }
            .otherwise                        { s0x := s0x - 2 }
        }.otherwise {
          when(s0x >= U(xMax - 2, 10 bits)) { s0dir := True;  s0x := U(xMax, 10 bits) }
            .otherwise                        { s0x := s0x + 2 }
        }
      }
      video.io.sprite0X := s0x
      video.io.sprite0Y := U(200, 10 bits)
      video.io.sprite1X := U(0, 10 bits); video.io.sprite1Y := U(0, 10 bits)
      video.io.sprite2X := U(0, 10 bits); video.io.sprite2Y := U(0, 10 bits)
      video.io.sprite3X := U(0, 10 bits); video.io.sprite3Y := U(0, 10 bits)
      video.io.sprite2Enabled := False
      video.io.sprite3Enabled := False
      video.io.sprite2PatternIdx := U(0, 1 bit)
      video.io.sprite3PatternIdx := U(1, 1 bit)
    } else if (scenarioId == 5 || scenarioId == 6 || scenarioId == 28) {
      // Per-sprite bounce: each sprite has its own X/Y reg + sign bit. Step
      // sizes spread out so the 4 sprites move at different rates.
      val xMin = 16; val xMax = 624     // 16 ≤ x ≤ 624 keeps 16×16 sprite on-screen
      val yMin = 16; val yMax = 464
      def bouncer(initX: Int, initY: Int, stepX: Int, stepY: Int) = {
        val rx = Reg(UInt(10 bits)) init initX
        val ry = Reg(UInt(10 bits)) init initY
        val dx = Reg(Bool()) init False     // false = +stepX
        val dy = Reg(Bool()) init False
        when(vsyncRising) {
          when(dx) {
            when(rx <= U(xMin + stepX, 10 bits)) { dx := False; rx := U(xMin, 10 bits) }
              .otherwise                            { rx := rx - U(stepX, 10 bits) }
          }.otherwise {
            when(rx >= U(xMax - stepX, 10 bits)) { dx := True;  rx := U(xMax, 10 bits) }
              .otherwise                            { rx := rx + U(stepX, 10 bits) }
          }
          when(dy) {
            when(ry <= U(yMin + stepY, 10 bits)) { dy := False; ry := U(yMin, 10 bits) }
              .otherwise                            { ry := ry - U(stepY, 10 bits) }
          }.otherwise {
            when(ry >= U(yMax - stepY, 10 bits)) { dy := True;  ry := U(yMax, 10 bits) }
              .otherwise                            { ry := ry + U(stepY, 10 bits) }
          }
        }
        (rx, ry)
      }
      val (s0x, s0y) = bouncer(120, 100, 1, 1)
      val (s1x, s1y) = bouncer(400, 100, 2, 1)
      val (s2x, s2y) = bouncer(120, 300, 1, 2)
      val (s3x, s3y) = bouncer(400, 300, 2, 2)
      video.io.sprite0X := s0x; video.io.sprite0Y := s0y
      video.io.sprite1X := s1x; video.io.sprite1Y := s1y
      video.io.sprite2X := s2x; video.io.sprite2Y := s2y
      video.io.sprite3X := s3x; video.io.sprite3Y := s3y
      video.io.sprite2Enabled := True
      video.io.sprite3Enabled := True
      video.io.sprite2PatternIdx := U(0, 1 bit)
      video.io.sprite3PatternIdx := U(1, 1 bit)
    } else {
      // scenarios 0/1/2/3: legacy R2 proof positions; sprites disabled
      video.io.sprite0X := U(120, 10 bits); video.io.sprite0Y := U(120, 10 bits)
      video.io.sprite1X := U(240, 10 bits); video.io.sprite1Y := U(120, 10 bits)
      video.io.sprite2X := U(360, 10 bits); video.io.sprite2Y := U(120, 10 bits)
      video.io.sprite3X := U(300, 10 bits); video.io.sprite3Y := U(360, 10 bits)
      video.io.sprite2Enabled := False
      video.io.sprite3Enabled := False
      video.io.sprite2PatternIdx := U(0, 1 bit)
      video.io.sprite3PatternIdx := U(1, 1 bit)
    }

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
    // Sc31 uses on-chip BasicPatternSource so per-column scroll-table
    // offsets are visible (SDRAM fetch latches scroll once per line,
    // hiding column-band variation).
    video.io.layer0UseSdram      := Bool(scenarioId != 31 && scenarioId != 29)

    // Test pattern override: default disabled so normal SDRAM-backed rendering
    // continues. Set enable=True and select a pattern (1..7) for validation.
    video.io.layer0TestPatternEnable := False
    video.io.layer0TestPatternSelect := U(0, 3 bits)

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
    hdmiTx.hsync := video.io.hsync
    hdmiTx.vsync := video.io.vsync
    hdmiTx.de := video.io.de
    // Task 29 Sc29 canary: top-left 40×40 corner shows a green block
    // iff STATUS_STICKY bit 4 (SPRITE_0_HIT) is latched. This gives a
    // firmware-free hardware confirmation that the collision flag
    // fires on Gowin silicon. Disabled for all other scenarios so
    // production renders are unaffected.
    val sc29Canary = Bool()
    if (scenarioId == 29) {
      val inCanary = video.io.x < U(40, 10 bits) && video.io.y < U(40, 10 bits)
      sc29Canary := inCanary && video.io.statusSticky(4)
    } else {
      sc29Canary := False
    }
    hdmiTx.red   := Mux(sc29Canary, B(0x00, 8 bits), video.io.red)
    hdmiTx.green := Mux(sc29Canary, B(0xFF, 8 bits), video.io.green)
    hdmiTx.blue  := Mux(sc29Canary, B(0x00, 8 bits), video.io.blue)

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
    O_led(4) := fetch.io.memtestFail
    O_led(5) := fetch.io.underrun
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

  // Wire SDRAM controller's logic-side signals to the fetch engine. Both live
  // in sdramClockDomain (the BlackBox via mapCurrentClockDomain, the fetch via
  // explicit ClockingArea inside SdramTileFetch). Upload pulse is the CDC-
  // regenerated one from sdramCdcArea — fetch retains its existing direct
  // wiring (it has been empirically stable since Task 15).
  // Task 30 — multi-client SDRAM arbiter. The scheduler's grantClientId
  // selects which of 4 clients drives the SDRAM request lines on each
  // cycle. Today only client 0 (tile/attribute fetch) is wired; clients
  // 1..3 are reserved for future engines (sprite-to-SDRAM, blitter,
  // spare) and tied inactive. With the current 2-slot schedule
  // (both slots clientId=0) the arbiter's mux is identity → bit-
  // identical to the pre-arbiter direct wiring.
  //
  // Upload bypass (QSPI-SDRAM write pulse) remains a separate priority
  // override in front of the arbiter — it is not scheduled, and its
  // scope is orthogonal to multi-fetch arbitration.
  val sdramArbiter = SdramArbiter(clientCount = 4, addrWidth = 23, dataWidth = 8)
  sdramArbiter.io.grantClientId := pixelArea.video.io.layer0FetchGrantClientId
  sdramArbiter.io.slotValid     := pixelArea.video.io.layer0FetchSlotValid
  sdramArbiter.io.grant         := pixelArea.video.io.layer0FetchGrant
  // Client 0 — tile + attribute fetch.
  sdramArbiter.io.clientRd(0)   := pixelArea.fetch.io.sdramRd
  sdramArbiter.io.clientWr(0)   := pixelArea.fetch.io.sdramWr
  sdramArbiter.io.clientAddr(0) := pixelArea.fetch.io.sdramAddr
  sdramArbiter.io.clientDin(0)  := pixelArea.fetch.io.sdramDin
  // Clients 1..3 — reserved, tied inactive.
  for (c <- 1 until 4) {
    sdramArbiter.io.clientRd(c)   := False
    sdramArbiter.io.clientWr(c)   := False
    sdramArbiter.io.clientAddr(c) := U(0, 23 bits)
    sdramArbiter.io.clientDin(c)  := B(0, 8 bits)
  }

  val uploadDrive = sdramCdcArea.uploadWrPulse
  sdramArea.ctrl.io.rd      := sdramArbiter.io.sdramRd
  sdramArea.ctrl.io.wr      := sdramArbiter.io.sdramWr || uploadDrive
  sdramArea.ctrl.io.refresh := pixelArea.fetch.io.sdramRefresh
  sdramArea.ctrl.io.addr    := Mux(uploadDrive, pixelArea.qspiSdramBridge.io.sdramAddr, sdramArbiter.io.sdramAddr)
  sdramArea.ctrl.io.din     := Mux(uploadDrive, pixelArea.qspiSdramBridge.io.sdramDin,  sdramArbiter.io.sdramDin)
  pixelArea.fetch.io.sdramDout      := sdramArea.ctrl.io.dout
  pixelArea.fetch.io.sdramDout32    := sdramArea.ctrl.io.dout32
  pixelArea.fetch.io.sdramDataReady := sdramArea.ctrl.io.data_ready
  pixelArea.fetch.io.sdramBusy      := sdramArea.ctrl.io.busy
}

object TopTang20kHdmiVerilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi())
}

// Wave 1 scenario top-level objects. Each generates its own top_tang20k_scN.v.
object TopTang20kHdmiScenario1Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 1))
}
object TopTang20kHdmiScenario2Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 2))
}
object TopTang20kHdmiScenario3Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 3))
}
object TopTang20kHdmiScenario4Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 4))
}
object TopTang20kHdmiScenario5Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 5))
}
// Wave 2 scenarios.
object TopTang20kHdmiScenario6Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 6))
}
object TopTang20kHdmiScenario7Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 7))
}
object TopTang20kHdmiScenario8Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 8))
}
object TopTang20kHdmiScenario9Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 9))
}
object TopTang20kHdmiScenario10Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 10))
}
object TopTang20kHdmiScenario11Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 11))
}
// Task 19 Checkpoint C — affine background with sprite.
object TopTang20kHdmiScenario12Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 12))
}
// Task 21 Mixed-Scene Integration.
object TopTang20kHdmiScenario15Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 15))
}
// Task 22 Long Soak baseline (Sc16) + Task 23 Stress-Scene Validation (Sc17).
object TopTang20kHdmiScenario16Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 16))   // long-soak baseline
}
object TopTang20kHdmiScenario17Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 17))   // stress scene
}
// Task 26 QSPI wire-test diagnostic (BronzeGate #7508, throwaway).
object TopTang20kHdmiScenario99Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 99))
}
// Wave 3 scenario.
object TopTang20kHdmiScenario13Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 13))
}
object TopTang20kHdmiScenario33Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 33))   // Task 33 HDMA HW proof
}
object TopTang20kHdmiScenario28Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 28))   // Task 28 sprite-evaluator HW proof
}
object TopTang20kHdmiScenario37Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 37))   // Task 37 affine sprite HW proof
}
object TopTang20kHdmiScenario31Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 31))   // Task 31 scroll-table HW proof
}
object TopTang20kHdmiScenario29Verilog extends App {
  Config.spinal.generateVerilog(TopTang20kHdmi(scenarioId = 29))   // Task 29 sprite-collision HW proof
}
