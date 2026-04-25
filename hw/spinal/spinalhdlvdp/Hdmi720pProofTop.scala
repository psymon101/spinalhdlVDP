package spinalhdlvdp

import spinal.core._

/** Tang Nano 20K top-level for the BronzeGate #8482 Slice B 720p
  * output-shell proof.
  *
  * Pure synthetic-source proof:
  *   I_clk(27 MHz) → rPLL(IDIV=3,FBDIV=54,ODIV=2) → 371.25 MHz → CLKDIV/5
  *                 → 74.25 MHz pixel → Hdmi720pTimingGen
  *                 → Hdmi720pSyntheticSource (8 SMPTE colour bars)
  *                 → HdmiCleanStart (clean-start mute)
  *                 → Tang20kHdmiTx (TMDS encode + OSER10)
  *
  * Has NO connection to VdpTop, SDRAM, QSPI, or the scenario tree —
  * this exists solely to prove that the 720p clocking path locks the
  * capture card and produces correctly geometried colour bars across
  * repeated reflashes. SDRAM and QSPI pads are intentionally absent
  * from this top so that the FPGA fabric, the .cst, and the .sdc are
  * all minimal.
  *
  * Mute window sizing: at 74.25 MHz, ~80 ms = 5_940_000 cycles. We use
  * 6_000_000 to keep parity with Slice A's "comfortably ≥ 80 ms" choice.
  */
case class Hdmi720pProofTop() extends Component {
  setDefinitionName("top_tang20k_720p_proof")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits(6 bits)
  val O_tmds_clk_p = out Bool()
  val O_tmds_clk_n = out Bool()
  val O_tmds_data_p = out Bits(3 bits)
  val O_tmds_data_n = out Bits(3 bits)

  // ----------------------------------------------------------------------
  // PLL chain: 27 MHz × 55 / 4 = 371.25 MHz, then CLKDIV/5 → 74.25 MHz.
  // Matches the proven VDP-baseline 720p config for the same Tang Nano
  // 20K hardware. VCO 742.5 MHz fits the GW2AR-LV18 rPLL envelope.
  // ----------------------------------------------------------------------
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

  // Hold clkdiv RESETN low for 16 cycles of the 27 MHz input clock after
  // pll.LOCK rises (~593 ns) so the 5× clock has fully settled before the
  // divider toggles. The main 126 MHz VDP path runs this counter on
  // pll.CLKOUT (CyanPeak #8123 / Task 44b iter 6f), but at 371.25 MHz the
  // 4-bit counter blows recovery and min-pulse-width on the GW2AR-18
  // fabric. Running it on I_clk gives the same intent (a settled-clock
  // hold-off) with comfortable timing margin.
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

  // ----------------------------------------------------------------------
  // Pixel-domain: timing → synthetic source → clean-start → HDMI TX.
  // ----------------------------------------------------------------------
  val pixelArea = new ClockingArea(pixelClockDomain) {
    val timing = Hdmi720pTimingGen()
    val source = Hdmi720pSyntheticSource(hActive = 1280)
    source.io.x  := timing.io.x
    source.io.de := timing.io.de

    val cleanStart = HdmiCleanStart(muteCycles = 6_000_000)  // ~80 ms @ 74.25 MHz
    cleanStart.io.inHsync := timing.io.hsync
    cleanStart.io.inVsync := timing.io.vsync
    cleanStart.io.inDe    := timing.io.de
    cleanStart.io.inRed   := source.io.red
    cleanStart.io.inGreen := source.io.grn
    cleanStart.io.inBlue  := source.io.blu

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

  // LEDs (active-low: 0 = lit). Mirrors TopTang20kHdmi convention.
  //   LED0: lit while pll.LOCK is low (unlocked = fault indicator)
  //   LED1: lit while the clean-start mute window is active
  //   LED2..5: parked off
  O_led    := B"6'b111111"
  O_led(0) := !pll.LOCK
  O_led(1) := !pixelArea.cleanStart.io.muteActive
}

object Hdmi720pProofTopVerilog extends App {
  Config.spinal.generateVerilog(Hdmi720pProofTop())
}
