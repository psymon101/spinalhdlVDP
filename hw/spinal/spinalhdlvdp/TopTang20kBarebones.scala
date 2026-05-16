package spinalhdlvdp

import spinal.core._

/** PM #10026 mode0-barebones-step-1: truly-minimal Tang Nano 20K top.
  *
  * Active build path contains only:
  *   - PLL/CLKDIV pixel clock chain (27 MHz → 125.875 MHz CLKOUT → /5 → 25.175 MHz pixel)
  *   - 640x480@60 video timing generator (DVI-style, active-low hsync/vsync)
  *   - one (1) background layer (BasicPatternSource)
  *   - 8-entry palette → 24-bit RGB
  *   - Tang20kHdmiTx TMDS encoder + serializer
  *
  * Deliberately ABSENT from the active path (per PM #10026 scope):
  *   - SdramController + SDRAM pads
  *   - QSPI host transport (QspiSlave / QspiDecoder / QspiSdramBridge)
  *   - VdpTop (and therefore all of: sprites, L1/L2/L3, affine, copper,
  *     blitter, DMA, raster triggers, planar fetch, tile-attribute fetch,
  *     bitmap fetch, scroll tables, color math, window unit, test pattern
  *     source, debug LEDs/probes)
  *   - HostInterface / RegBusArbiter / AdapterBusMux / AdapterRegRouter
  *   - HdmiCleanStart mute window
  *
  * Register bus surface: NONE in this slice — the shell boots into a
  * fixed displayed state. There is no host-writable surface yet; PM #10026
  * said "register bus surface required to boot the shell"; the empirical
  * minimum required to boot the shell is zero host-writable registers
  * because the BasicPatternSource is ROM-initialized and L0 is hard-on.
  * If the next slice needs a programmable surface, it adds a minimal
  * RegBusArbiter + bootstrap master at that time.
  *
  * Verilog top module name: `top_tang20k_barebones` (separate from
  * `top_tang20k` so the main mode2optimized build and this barebones
  * build can coexist in the same checkout).
  */
case class TopTang20kBarebones() extends Component {
  setDefinitionName("top_tang20k_barebones")
  noIoPrefix()

  val I_clk          = in Bool()
  val O_led          = out Bits(6 bits)
  val O_tmds_clk_p   = out Bool()
  val O_tmds_clk_n   = out Bool()
  val O_tmds_data_p  = out Bits(3 bits)
  val O_tmds_data_n  = out Bits(3 bits)

  // ----------------------------------------------------------------------
  // Pixel-clock PLL chain: same defaults as TopTang20kHdmi's pixel PLL.
  // Produces 125.875 MHz on pll.CLKOUT and 25.175 MHz on clkdiv.CLKOUT
  // (the 5x relationship is what tang20k_hdmi_tx.sv's OSER10 expects).
  // ----------------------------------------------------------------------
  val pll = GowinRpll()
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

  // Hold clkdiv RESETN low for 16 cycles of pll.CLKOUT after pll.LOCK rises
  // so the divider sees a settled clock before toggling (same pattern as
  // Hdmi720pProofTop / TopTang20kHdmi).
  val pllClockDomain = ClockDomain(
    clock  = pll.CLKOUT,
    reset  = !pll.LOCK,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )
  val pllResetArea = new ClockingArea(pllClockDomain) {
    val clkdivResetCounter = Reg(UInt(4 bits)) init 0
    when(clkdivResetCounter =/= 15) { clkdivResetCounter := clkdivResetCounter + 1 }
  }
  clkdiv.RESETN := pllResetArea.clkdivResetCounter === 15

  val pixelReset = !pll.LOCK
  val pixelClockDomain = ClockDomain(
    clock  = clkdiv.CLKOUT,
    reset  = pixelReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  // ----------------------------------------------------------------------
  // Pixel-domain: 640x480 timing + 1 BG layer + palette + HDMI TX.
  // ----------------------------------------------------------------------
  val pixelArea = new ClockingArea(pixelClockDomain) {
    // 640x480@60 VGA timing — DVI-style (active-low hsync/vsync) per the
    // existing reference at MODE0_PLANNING §9 / VdpTop active spec.
    val hActive = 640; val hFront = 16; val hSyncW = 96; val hBack = 48
    val hTotal  = hActive + hFront + hSyncW + hBack   // 800
    val vActive = 480; val vFront = 10; val vSyncW = 2;  val vBack = 33
    val vTotal  = vActive + vFront + vSyncW + vBack   // 525

    val hCounter = Reg(UInt(log2Up(hTotal) bits)) init 0
    val vCounter = Reg(UInt(log2Up(vTotal) bits)) init 0
    when(hCounter === U(hTotal - 1, hCounter.getWidth bits)) {
      hCounter := 0
      when(vCounter === U(vTotal - 1, vCounter.getWidth bits)) {
        vCounter := 0
      } otherwise {
        vCounter := vCounter + 1
      }
    } otherwise {
      hCounter := hCounter + 1
    }

    val de = (hCounter < U(hActive, hCounter.getWidth bits)) &&
             (vCounter < U(vActive, vCounter.getWidth bits))
    val hSyncStart = U(hActive + hFront,         hCounter.getWidth bits)
    val hSyncEnd   = U(hActive + hFront + hSyncW, hCounter.getWidth bits)
    val vSyncStart = U(vActive + vFront,         vCounter.getWidth bits)
    val vSyncEnd   = U(vActive + vFront + vSyncW, vCounter.getWidth bits)
    // DVI-style active-low (Tang20k convention per [[reference_hdmi_signal_spec]]).
    val hsyncN = !(hCounter >= hSyncStart && hCounter < hSyncEnd)
    val vsyncN = !(vCounter >= vSyncStart && vCounter < vSyncEnd)

    // Single BG layer. Scroll inputs tied to 0 (no host-programmable scroll).
    val layer0 = BasicPatternSource()
    layer0.io.x       := hCounter.resize(10)
    layer0.io.y       := vCounter.resize(10)
    layer0.io.scrollX := U(0, 10 bits)
    layer0.io.scrollY := U(0, 10 bits)

    // 8-entry minimal palette (matches BasicPatternSource's 3-bit index range).
    val paletteRom = Vec(
      B("24'h000000"),   // 0 black
      B("24'hFFFFFF"),   // 1 white
      B("24'hFF0000"),   // 2 red
      B("24'h00FF00"),   // 3 green
      B("24'h0000FF"),   // 4 blue
      B("24'hFFFF00"),   // 5 yellow
      B("24'h00FFFF"),   // 6 cyan
      B("24'hFF00FF")    // 7 magenta
    )
    val rgb = paletteRom(layer0.io.pixelIndex.asUInt)
    val redRaw   = Mux(de, rgb(23 downto 16), B(0, 8 bits))
    val greenRaw = Mux(de, rgb(15 downto  8), B(0, 8 bits))
    val blueRaw  = Mux(de, rgb( 7 downto  0), B(0, 8 bits))

    // HDMI TX wrapper. Single pipeline register on the way in (matches
    // proven Hdmi720pProofTop pattern).
    val hdmiTx = Tang20kHdmiTx()
    hdmiTx.clk_pixel    := clkdiv.CLKOUT
    hdmiTx.clk_pixel_x5 := pll.CLKOUT
    hdmiTx.reset        := pixelReset
    hdmiTx.hsync := RegNext(hsyncN) init True
    hdmiTx.vsync := RegNext(vsyncN) init True
    hdmiTx.de    := RegNext(de)     init False
    hdmiTx.red   := RegNext(redRaw) init 0
    hdmiTx.green := RegNext(greenRaw) init 0
    hdmiTx.blue  := RegNext(blueRaw)  init 0
  }

  O_tmds_clk_p  := pixelArea.hdmiTx.tmds_clk_p
  O_tmds_clk_n  := pixelArea.hdmiTx.tmds_clk_n
  O_tmds_data_p := pixelArea.hdmiTx.tmds_data_p
  O_tmds_data_n := pixelArea.hdmiTx.tmds_data_n

  // LEDs (active-low: 0 = lit). LED0 lit while PLL is unlocked (fault
  // indicator); LED1..5 parked off.
  O_led    := B"6'b111111"
  O_led(0) := !pll.LOCK
}

object TopTang20kBarebonesVerilog extends App {
  Config.spinal.generateVerilog(TopTang20kBarebones())
}
