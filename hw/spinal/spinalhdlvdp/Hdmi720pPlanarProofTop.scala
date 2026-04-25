package spinalhdlvdp

import spinal.core._

/** Tang Nano 20K hardware proof for the Mode0 Planar Fetch Hardening
  * primitives (H-1 / H-2 / H-3b composite) — `top_tang20k_720p_planar`.
  *
  * Architecture (single 74.25 MHz domain, no real SDRAM controller):
  *
  *   [PlaneROM × 5] ──► mock-SDRAM responder ──► PlanarLineFetch
  *                                                    │
  *   720p timing  ─────────────────────────────────► pixelIdx
  *                                                    │
  *                                            5-bit pixel
  *                                                    │
  *                                              palette ROM (32 × 24 bit)
  *                                                    │
  *                                              RGB ─► Slice C bridge
  *                                                    ─► clean start
  *                                                    ─► HDMI TX
  *
  * The fetch fires once when the clean-start mute releases and the
  * `planeRows` registers latch the assembled bitplane data
  * permanently. After that, every reader cycle is a pure combinational
  * lookup on `pixelIdx` → 5-bit pixel → palette → RGB. This proves the
  * primitives without dragging in the real SDRAM controller, the
  * arbiter, or per-line scheduling — those become a later integration
  * slice.
  *
  * Visual signature on the rig: the same 8 SMPTE colour bars that
  * Slices B/C/D-B1-L produce, but here generated through the full
  * 5-plane fetch + reconstruct path. The bars look identical because
  * the palette maps the 5-bit pixel index back to the same RGB triplets;
  * the proof is that the centered window is non-black and bar-shaped at
  * all, since any wiring or reconstruction error collapses to noise.
  */
case class Hdmi720pPlanarProofTop() extends Component {
  setDefinitionName("top_tang20k_720p_planar")
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

  // Reset hold counter on the 27 MHz input (Slice B closure rationale).
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
    // ----------------------------------------------------------------------
    // Mock SDRAM: 5 plane ROMs (BSRAM) backing the PlanarLineFetch fetch
    // path, with a 4-cycle read latency model that mirrors the nand2mario
    // controller's behaviour.
    // ----------------------------------------------------------------------
    import PlanarProofAssets._

    val planeROMs: Seq[Mem[Bits]] = (0 until PlaneCount).map { p =>
      Mem(Bits(32 bits), DwordsPerPlane)
        .initBigInt(planeInit(p))
        .setName(s"planeROM_$p")
    }

    val planeFetch = PlanarLineFetch(
      planeCount  = PlaneCount,
      planePixels = PlanePixels
    )

    // Plane base addresses are arbitrary placeholders — the responder
    // below uses only the dword index within a plane, not the absolute
    // SDRAM address. Bases are spaced by 0x1000 bytes (room for any plane
    // size up to 4 KiB) and identify which plane the request targets.
    val planeBaseConst = Seq(0x10000, 0x11000, 0x12000, 0x13000, 0x14000)
    for (p <- 0 until PlaneCount) {
      planeFetch.io.planeBaseAddr(p) := U(planeBaseConst(p), 23 bits)
    }

    // SDRAM responder: identify which plane based on the high address
    // bits, then read the matching ROM at (addr & 0xFFF) >> 2.
    val sdramRdReg   = RegNext(planeFetch.io.sdramRd)   init False
    val sdramAddrReg = RegNext(planeFetch.io.sdramAddr) init U(0, 23 bits)

    val readCounter = Reg(UInt(3 bits)) init 0
    val pendingPlane = Reg(UInt(log2Up(PlaneCount) bits)) init 0
    val pendingDword = Reg(UInt(log2Up(DwordsPerPlane) bits)) init 0

    val busyW       = readCounter =/= U(0, 3 bits)
    val dataReadyW  = readCounter === U(1, 3 bits)

    when(planeFetch.io.sdramRd && !busyW) {
      // Decode plane index from address bits [15:12] (matches base
      // spacing of 0x1000). Dword index from bits [5:2].
      pendingPlane := planeFetch.io.sdramAddr(15 downto 12).resize(log2Up(PlaneCount))
      pendingDword := (planeFetch.io.sdramAddr(2 + log2Up(DwordsPerPlane) - 1 downto 2))
        .resize(log2Up(DwordsPerPlane))
      readCounter := U(4, 3 bits)
    } otherwise {
      when(busyW) { readCounter := readCounter - 1 }
    }

    // Combinational ROM read: 5 simultaneous reads (only the one matching
    // pendingPlane is consumed). SpinalHDL infers BSRAM with sync-read
    // semantics, so wrap with `readAsync` for combinational lookup.
    val planeReads: Vec[Bits] = Vec((0 until PlaneCount).map { p =>
      planeROMs(p).readAsync(pendingDword)
    })
    val dout32W = planeReads(pendingPlane)

    planeFetch.io.sdramBusy      := busyW
    planeFetch.io.sdramDataReady := dataReadyW
    planeFetch.io.sdramDout32    := dout32W

    // ----------------------------------------------------------------------
    // One-shot trigger: on first clock after reset deassertion, kick off
    // the fetch so `planeRows` latch the SMPTE pattern. Subsequent reads
    // are pure combinational on pixelIdx; no further trigger needed.
    // ----------------------------------------------------------------------
    val triggered = Reg(Bool()) init False
    planeFetch.io.start := !triggered
    when(!triggered) { triggered := True }

    // ----------------------------------------------------------------------
    // 720p timing + Slice C bridge.
    // ----------------------------------------------------------------------
    val timing = Hdmi720pTimingGen()
    val bridge = Hdmi720pCenterBridge()
    bridge.io.x  := timing.io.x
    bridge.io.y  := timing.io.y
    bridge.io.de := timing.io.de

    // Map bridge.contentX (0..639) to PlanarLineFetch.pixelIdx (0..319)
    // via a 2× horizontal nearest-neighbour stretch. Vertically the same
    // single fetched row is shown for all 480 centered active lines —
    // this is a 1-row-deep proof; the per-line fetch follow-on slice
    // will broaden to 480 unique rows.
    val pixelIdxStretched = (bridge.io.contentX >> 1).resize(planeFetch.pixelIdxBits)
    planeFetch.io.pixelIdx := pixelIdxStretched

    // 32-entry palette ROM: 5-bit pixel index → 24-bit RGB.
    val paletteROM = Mem(Bits(24 bits), PaletteSize).initBigInt(PaletteRGB)
    val paletteRGB = paletteROM.readAsync(planeFetch.io.pixel.asUInt)
    val pixR = paletteRGB(23 downto 16)
    val pixG = paletteRGB(15 downto  8)
    val pixB = paletteRGB( 7 downto  0)

    bridge.io.contentRed   := pixR
    bridge.io.contentGreen := pixG
    bridge.io.contentBlue  := pixB

    // Slice A clean-start mute (~80 ms at 74.25 MHz).
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

  // LEDs (active-low):
  //   LED0 = !pll.LOCK
  //   LED1 = !mute (pulses while clean-start active)
  //   LED2 = !planeFetch.busy (lit briefly during the one-shot fetch)
  O_led    := B"6'b111111"
  O_led(0) := !pll.LOCK
  O_led(1) := !pixelArea.cleanStart.io.muteActive
  O_led(2) := !pixelArea.planeFetch.io.busy
}

object Hdmi720pPlanarProofTopVerilog extends App {
  Config.spinal.generateVerilog(Hdmi720pPlanarProofTop())
}
