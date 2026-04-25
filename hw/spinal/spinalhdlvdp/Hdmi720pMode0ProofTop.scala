package spinalhdlvdp

import spinal.core._

/** Tang Nano 20K top-level for the BronzeGate #8496 Slice D-A proof —
  * VdpTop's internal test-pattern path under the 720p shell + Slice C
  * centered-640x480 bridge.
  *
  * The clock-enable wrap (Path 1 from #8493): VdpTop runs in a
  * `ClockingArea` whose clock-enable is gated to advance VdpTop's
  * internal raster counter exactly in lockstep with the 720p centered
  * window. Both run at 74.25 MHz, both at 60 Hz; over each 720p
  * frame VdpTop receives exactly its native 800×525 = 420 000
  * enables in the right phase.
  *
  * Schedule (per 720p frame, x:[0..1649], y:[0..749], 1 cycle = 1 px):
  *   - y in [120, 600), x in [320, 1120)  → enable VdpTop (640 active +
  *                                          160 H-blank cycles per row,
  *                                          480 rows = 384 000 enables)
  *   - y in [0,   45),  x in [0,    800)  → enable VdpTop (45 V-blank
  *                                          rows × 800 cycles = 36 000
  *                                          enables)
  *   - else                               → VdpTop paused
  *
  * Total enables per 720p frame = 800 × 480 + 800 × 45 = 420 000 = exactly
  * one VdpTop frame, aligned so VdpTop's pixel(0,0) lands at 720p
  * (x=320, y=120) and VdpTop's pixel(639,479) at (959, 599) — the
  * Slice C bridge content window.
  *
  * Proof scene: TestPatternSource pattern 6 (white grid lines every
  * 64 px on black). Geometry-obvious — vertical/horizontal gridlines
  * make any pixel-position drift, scaling, or stripe artefact
  * immediately visible.
  *
  * Slice D-A scope per #8496:
  *   - VdpTop's internal test-pattern path only (`layer0UseSdram=False`,
  *     `layer0TestPatternEnable=True`)
  *   - no SDRAM, no QSPI, no scenario bootstrap, no scroll/sprite logic
  *   - VdpTop.scala is unchanged
  */
case class Hdmi720pMode0ProofTop() extends Component {
  setDefinitionName("top_tang20k_720p_mode0")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits(6 bits)
  val O_tmds_clk_p = out Bool()
  val O_tmds_clk_n = out Bool()
  val O_tmds_data_p = out Bits(3 bits)
  val O_tmds_data_n = out Bits(3 bits)

  // 27 MHz × 55 / 4 = 371.25 MHz, /5 → 74.25 MHz pixel (proven Slice B path).
  val pll = GowinRpll(idivSel = 3, fbdivSel = 54, odivSel = 2)
  pll.CLKIN   := I_clk
  pll.CLKFB   := False
  pll.FBDSEL  := B(0, 6 bits)
  pll.IDSEL   := B(0, 6 bits)
  pll.ODSEL   := B(0, 6 bits)
  pll.DUTYDA  := B(0, 4 bits)
  pll.PSDA    := B(0, 4 bits)
  pll.FDLY    := B(0, 4 bits)
  pll.RESET   := False
  pll.RESET_P := False

  val clkdiv = GowinClkdiv()
  clkdiv.HCLKIN := pll.CLKOUT
  clkdiv.CALIB  := True

  // Reset hold counter on the 27 MHz input domain (Slice B closure rationale).
  val rawClockDomain = ClockDomain(
    clock = I_clk,
    reset = !pll.LOCK,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )
  val pllResetArea = new ClockingArea(rawClockDomain) {
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

  val pixelArea = new ClockingArea(pixelClockDomain) {
    val timing = Hdmi720pTimingGen()
    val xx = timing.io.x
    val yy = timing.io.y

    // VdpTop schedule generator — drives clockEnable so VdpTop advances
    // its 420 000-cycle frame in lockstep with 720p's centered window.
    val inActiveRow      = (yy >= U(120, 10 bits)) && (yy <  U(600, 10 bits))
    val xInActivePlusHB  = (xx >= U(320, 11 bits)) && (xx <  U(1120, 11 bits))
    val inVBlankFlushRow = yy <  U(45,  10 bits)
    val xInVBlankSpan    = xx <  U(800, 11 bits)
    val vdpEnable        = (inActiveRow      && xInActivePlusHB) ||
                           (inVBlankFlushRow && xInVBlankSpan)

    // VdpTop in a clock-enabled sub-domain. Same physical clock; registers
    // only step on cycles where vdpEnable is high. SpinalHDL fans the
    // clockEnable to every flip-flop in the area.
    val vdpClockDomain = ClockDomain(
      clock       = clkdiv.CLKOUT,
      reset       = pixelReset,
      clockEnable = vdpEnable,
      config      = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
    )

    val vdpArea = new ClockingArea(vdpClockDomain) {
      val video = VdpTop()

      // Direct test-pattern path — no SDRAM, no scenario boot.
      video.io.layer0UseSdram         := False
      video.io.layer0SdramPixel       := B(0, 4 bits)
      video.io.layer0SdramBank        := U(0, 3 bits)
      video.io.layer0SdramPriority    := False
      video.io.layer0TestPatternEnable := True
      video.io.layer0TestPatternSelect := U(6, 3 bits)   // grid (#8496 proof scene)

      // RegBus tied off — LAYER_ENABLE keeps its hardware default (0b00111).
      // L0 (test-pattern) renders; L1 has no SDRAM data driving it; sprites
      // are disabled via direct sprite-enable inputs below. If L1 produces
      // visible noise on the rig, we'll add a one-shot LAYER_ENABLE=0x0001
      // write here.
      video.io.regBus.addr   := U(0, 15 bits)
      video.io.regBus.data   := B(0, 16 bits)
      video.io.regBus.enable := False

      // Scroll: zero on every layer (no scroll for D-A).
      video.io.layer0ScrollX := U(0, 10 bits)
      video.io.layer0ScrollY := U(0, 10 bits)
      video.io.layer1ScrollX := U(0, 10 bits)
      video.io.layer1ScrollY := U(0, 10 bits)
      video.io.layer2ScrollX := U(0, 10 bits)
      video.io.layer2ScrollY := U(0, 10 bits)
      video.io.layer3ScrollX := U(0, 10 bits)
      video.io.layer3ScrollY := U(0, 10 bits)

      // Sprites: all disabled, position 0.
      Seq(video.io.sprite0Enabled, video.io.sprite1Enabled,
          video.io.sprite2Enabled, video.io.sprite3Enabled).foreach(_ := False)
      Seq(video.io.sprite0X, video.io.sprite1X,
          video.io.sprite2X, video.io.sprite3X).foreach(_ := U(0, 10 bits))
      Seq(video.io.sprite0Y, video.io.sprite1Y,
          video.io.sprite2Y, video.io.sprite3Y).foreach(_ := U(0, 10 bits))
      Seq(video.io.sprite0PatternIdx, video.io.sprite1PatternIdx,
          video.io.sprite2PatternIdx, video.io.sprite3PatternIdx)
        .foreach(_ := U(0, 1 bit))

      // Bitmap-mode SDRAM byte/attr inputs — not used; tie to zero.
      video.io.bitmapSdramByte     := B(0, 8 bits)
      video.io.bitmapSdramAttrByte := B(0, 8 bits)

      // Raster-trigger inputs — disabled.
      video.io.rasterTriggerLine     := U(0, 10 bits)
      video.io.rasterTriggerPixel    := U(0, 10 bits)
      video.io.rasterTriggerPxEnable := False
      video.io.rasterTriggerEnable   := False
      video.io.rasterTriggerClear    := False

      // Status-event inputs — quiet.
      video.io.statusEvQspiReady := False
      video.io.statusEvQspiError := False
    }

    // Slice C bridge — read VdpTop's RGB at (contentX, contentY).
    val bridge = Hdmi720pCenterBridge()
    bridge.io.x  := timing.io.x
    bridge.io.y  := timing.io.y
    bridge.io.de := timing.io.de
    bridge.io.contentRed   := vdpArea.video.io.red
    bridge.io.contentGreen := vdpArea.video.io.green
    bridge.io.contentBlue  := vdpArea.video.io.blue

    // Slice A clean-start mute — sized for 74.25 MHz (~80 ms).
    val cleanStart = HdmiCleanStart(muteCycles = 6_000_000)
    cleanStart.io.inHsync := timing.io.hsync
    cleanStart.io.inVsync := timing.io.vsync
    cleanStart.io.inDe    := timing.io.de
    cleanStart.io.inRed   := bridge.io.red
    cleanStart.io.inGreen := bridge.io.green
    cleanStart.io.inBlue  := bridge.io.blue

    val hdmiTx = Tang20kHdmiTx()
    hdmiTx.clk_pixel    := clkdiv.CLKOUT
    hdmiTx.clk_pixel_x5 := pll.CLKOUT
    hdmiTx.reset        := pixelReset
    hdmiTx.hsync := RegNext(cleanStart.io.outHsync) init True
    hdmiTx.vsync := RegNext(cleanStart.io.outVsync) init True
    hdmiTx.de    := RegNext(cleanStart.io.outDe)    init False
    hdmiTx.red   := RegNext(cleanStart.io.outRed)   init 0
    hdmiTx.green := RegNext(cleanStart.io.outGreen) init 0
    hdmiTx.blue  := RegNext(cleanStart.io.outBlue)  init 0
  }

  O_tmds_clk_p  := pixelArea.hdmiTx.tmds_clk_p
  O_tmds_clk_n  := pixelArea.hdmiTx.tmds_clk_n
  O_tmds_data_p := pixelArea.hdmiTx.tmds_data_p
  O_tmds_data_n := pixelArea.hdmiTx.tmds_data_n

  // LEDs (active-low). LED0 = !pll.LOCK, LED1 = !muteActive, LED2 = !vdpEnable
  // (pulses with the VdpTop schedule — useful as a visual heartbeat).
  O_led    := B"6'b111111"
  O_led(0) := !pll.LOCK
  O_led(1) := !pixelArea.cleanStart.io.muteActive
  O_led(2) := !pixelArea.vdpEnable
}

object Hdmi720pMode0ProofTopVerilog extends App {
  Config.spinal.generateVerilog(Hdmi720pMode0ProofTop())
}
