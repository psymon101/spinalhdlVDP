package spinalhdlvdp

import spinal.core._

/** Tang Nano 20K top-level for the BronzeGate #8486 Slice C presentation
  * mapper proof.
  *
  * Pipeline:
  *   I_clk(27 MHz) → rPLL(IDIV=3,FBDIV=54,ODIV=2) → 371.25 MHz → /5 →
  *                 → 74.25 MHz pixel → Hdmi720pTimingGen
  *                 → Hdmi720pCenterBridge (windowed mux)
  *                       └─ inner content from Hdmi480pSyntheticContent
  *                          (640×480 SMPTE bars + 1-px white frame)
  *                 → HdmiCleanStart (clean-start mute)
  *                 → Tang20kHdmiTx
  *
  * The 720p shell from Slice B is preserved unchanged. The only
  * addition is the centered 640×480 window above the inner content
  * source, with 320 px H / 120 px V black borders. No `VdpTop`,
  * SDRAM, or QSPI logic — Slice C scope per #8486.
  */
case class Hdmi720pBridgeProofTop() extends Component {
  setDefinitionName("top_tang20k_720p_bridge")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits(6 bits)
  val O_tmds_clk_p = out Bool()
  val O_tmds_clk_n = out Bool()
  val O_tmds_data_p = out Bits(3 bits)
  val O_tmds_data_n = out Bits(3 bits)

  // 27 MHz × 55 / 4 = 371.25 MHz (proven 720p path).
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

  // Reset hold counter on the 27 MHz input domain (matches Slice B; see
  // its commit message for the 371.25 MHz timing-closure rationale).
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
    val timing  = Hdmi720pTimingGen()
    val bridge  = Hdmi720pCenterBridge()
    val content = Hdmi480pSyntheticContent()

    // Outer 720p raster feeds the bridge.
    bridge.io.x  := timing.io.x
    bridge.io.y  := timing.io.y
    bridge.io.de := timing.io.de

    // Inner 640×480 source is driven from the bridge's contentX/Y.
    content.io.x := bridge.io.contentX
    content.io.y := bridge.io.contentY
    bridge.io.contentRed   := content.io.red
    bridge.io.contentGreen := content.io.grn
    bridge.io.contentBlue  := content.io.blu

    // Slice A clean-start mute, sized for 74.25 MHz (~80 ms).
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

  // LEDs (active-low: 0 = lit). Same convention as the Slice B proof.
  O_led    := B"6'b111111"
  O_led(0) := !pll.LOCK
  O_led(1) := !pixelArea.cleanStart.io.muteActive
}

object Hdmi720pBridgeProofTopVerilog extends App {
  Config.spinal.generateVerilog(Hdmi720pBridgeProofTop())
}
