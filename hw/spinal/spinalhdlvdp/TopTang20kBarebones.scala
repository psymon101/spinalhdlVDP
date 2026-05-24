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
  *   - HostInterface / RegBusArbiter
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

  // PM #10034 stage-2 minimal QSPI receive (1-bit SPI). 3 input pins
  // only — no IO2/IO3, no MISO tristate (host writes only in this slice).
  val I_qspi_cs   = in Bool()
  val I_qspi_sck  = in Bool()
  val I_qspi_mosi = in Bool()

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

    // PM #10034 stage-2 + PM #10051 stage-4: QSPI receive + 4-register file.
    //   0x0000 SCROLL_X0 (low 10 bits) — L0 horizontal scroll
    //   0x0001 SCROLL_Y0 (low 10 bits) — L0 vertical scroll
    //   0x0002 SCROLL_X1 (low 10 bits) — L1 horizontal scroll  [PM #10051]
    //   0x0003 SCROLL_Y1 (low 10 bits) — L1 vertical scroll    [PM #10051]
    // 1-bit SPI, MSB first, 40-bit frame [CMD:8][ADDR:16][DATA:16].
    // CMD must be 0x01 (REG_WRITE). All other commands ignored.
    val qspi = QspiBarebones()
    qspi.io.cs_n := I_qspi_cs
    qspi.io.sck  := I_qspi_sck
    qspi.io.mosi := I_qspi_mosi

    val scrollXReg  = Reg(UInt(10 bits)) init 0
    val scrollYReg  = Reg(UInt(10 bits)) init 0
    val scrollX1Reg = Reg(UInt(10 bits)) init 0
    val scrollY1Reg = Reg(UInt(10 bits)) init 0
    // PM #10080 simple-sprite slice: two additive position regs, no enable
    // bit, no Mem. Sprite is a procedural 16x16 white square; renders when
    // (hCounter, vCounter) is inside [spriteX, spriteX+16) x [spriteY, spriteY+16)
    // AND inside the active 640x480 region. Composition priority is
    // sprite > L1 > L0. See compositor below.
    val spriteXReg  = Reg(UInt(10 bits)) init 0
    val spriteYReg  = Reg(UInt(10 bits)) init 0
    when(qspi.io.regWr) {
      switch(qspi.io.regAddr) {
        is(U(0x0000, 16 bits)) { scrollXReg  := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0001, 16 bits)) { scrollYReg  := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0002, 16 bits)) { scrollX1Reg := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0003, 16 bits)) { scrollY1Reg := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0004, 16 bits)) { spriteXReg  := qspi.io.regData(9 downto 0).asUInt }
        is(U(0x0005, 16 bits)) { spriteYReg  := qspi.io.regData(9 downto 0).asUInt }
        default {} // unknown address — silently ignored
      }
    }

    // L0 — BasicPatternSource, scroll-driven from QSPI regs 0x0000/0x0001.
    val layer0 = BasicPatternSource()
    layer0.io.x       := hCounter.resize(10)
    layer0.io.y       := vCounter.resize(10)
    layer0.io.scrollX := scrollXReg
    layer0.io.scrollY := scrollYReg

    // L1 — second BasicPatternSource, scroll-driven from QSPI regs 0x0002/0x0003.
    // Same tile-ROM content as L0 (no separate asset to keep this slice minimal
    // per PM #10051 "minimum extra pattern/palette support"). L1 is made
    // visually distinct from L0 by using a separate 8-entry palette with a
    // shifted hue rotation (see paletteL1Rom below). Independent scroll plus
    // the distinct palette gives an unambiguous L1 vs L0 read on the capture.
    val layer1 = BasicPatternSource()
    layer1.io.x       := hCounter.resize(10)
    layer1.io.y       := vCounter.resize(10)
    layer1.io.scrollX := scrollX1Reg
    layer1.io.scrollY := scrollY1Reg

    // 8-entry palettes — L0 keeps the stage-1/2 set; L1 uses an inverted
    // hue rotation so the two layers are visibly distinct even when they
    // happen to render the same tile content. Both palettes treat index 0
    // as TRANSPARENT for the compositor below.
    val paletteRom = Vec(
      B("24'h000000"),   // 0 black (transparent for compositor)
      B("24'hFFFFFF"),   // 1 white
      B("24'hFF0000"),   // 2 red
      B("24'h00FF00"),   // 3 green
      B("24'h0000FF"),   // 4 blue
      B("24'hFFFF00"),   // 5 yellow
      B("24'h00FFFF"),   // 6 cyan
      B("24'hFF00FF")    // 7 magenta
    )
    val paletteL1Rom = Vec(
      B("24'h000000"),   // 0 black (transparent for compositor)
      B("24'h804000"),   // 1 brown (L1-distinct vs L0 white)
      B("24'h00FFFF"),   // 2 cyan  (L1: complement of L0 red)
      B("24'hFF00FF"),   // 3 magenta (L1: complement of L0 green)
      B("24'hFFFF00"),   // 4 yellow (L1: complement of L0 blue)
      B("24'h0000FF"),   // 5 blue  (L1: complement of L0 yellow)
      B("24'hFF0000"),   // 6 red   (L1: complement of L0 cyan)
      B("24'h00FF00")    // 7 green (L1: complement of L0 magenta)
    )
    val layer0Idx = layer0.io.pixelIndex.asUInt
    val layer1Idx = layer1.io.pixelIndex.asUInt
    val layer0Rgb = paletteRom  (layer0Idx)
    val layer1Rgb = paletteL1Rom(layer1Idx)

    // Compositing rule: simple "L1 over L0, transparent on idx 0".
    // L1 wins when its pixel index is non-zero; else L0 shows. Border
    // (index 0 on both layers) renders black. Deterministic, no priority
    // tweaks, no per-pixel alpha. Matches the spirit of MODE0_PLANNING
    // §6 "highest-index opaque layer wins" reduced to a 2-layer case.
    val l1Opaque = layer1Idx =/= U(0, 3 bits)
    val bgRgb = Mux(l1Opaque, layer1Rgb, layer0Rgb)
    // PM #10080 sprite hit: procedural 16x16 box, sprite > L1 > L0 priority.
    // Comparisons gate on `de` to ensure the box never asserts outside the
    // active 640x480 region (preserves blank during front/back porch).
    val sprHit = (hCounter >= spriteXReg) &&
                 (hCounter < (spriteXReg + U(16, 11 bits)).resize(hCounter.getWidth)) &&
                 (vCounter >= spriteYReg) &&
                 (vCounter < (spriteYReg + U(16, 11 bits)).resize(vCounter.getWidth)) &&
                 de
    val rgb = Mux(sprHit, B"24'hFFFFFF", bgRgb)
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
