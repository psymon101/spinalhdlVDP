package spinalhdlvdp

import spinal.core._
import spinal.lib.{StreamFifoCC, Stream}

/** Tang Nano 20K top-level for the BronzeGate #8505 Slice D-B1-L proof —
  * native-rate (25.2 MHz) writer feeds a dual-clock BRAM line buffer
  * which is drained by the 74.25 MHz reader, fed through the proven
  * Slice C centered bridge, and out the 720p shell.
  *
  * Two clock domains, both pixel-grade:
  *   writerCd (25.2 MHz)  — synthetic content writer
  *   readerCd (74.25 MHz) — Hdmi720pTimingGen + Slice C bridge + HDMI TX
  *
  * Two rPLL instances:
  *   pllWriter  IDIV=2, FBDIV=13, ODIV=8 → 126 MHz, /5 → 25.2 MHz
  *   pllReader  IDIV=3, FBDIV=54, ODIV=2 → 371.25 MHz, /5 → 74.25 MHz
  *
  * CDC seam: a `StreamFifoCC` carrying 24-bit RGB pixels writer→reader.
  * This is the codebase's established cross-clock pattern (see
  * `SdramTileFetch.scala` header note "cross-domain handoff to the
  * pixel clock uses StreamFifoCC, avoiding dual-clock BRAM"). The
  * synthetic source is intentionally **line-invariant** (8 SMPTE bars
  * purely a function of x), so the reader receives 8-bar content
  * regardless of which "writer line" the FIFO front belongs to —
  * this sidesteps the 25.2 → 74.25 MHz per-line rate mismatch
  * (writer push max 25.2 M/s vs reader instantaneous demand 74.25 M/s
  * during the centered active window) that would otherwise force a
  * multi-line buffer. Backpressure throttles the writer so it doesn't
  * outpace the reader on average.
  *
  * Slice D-B1-L scope per #8505:
  *   IN: native-rate writer, dual-clock CDC seam, 720p reader, sim/HW
  *       lock+geometry proof, resource report
  *   OUT: no SDRAM frame buffer, no VdpTop, no real Mode0 content
  */
case class Hdmi720pLineBufferProofTop() extends Component {
  setDefinitionName("top_tang20k_720p_linebuf")
  noIoPrefix()

  val I_clk = in Bool()
  val O_led = out Bits(6 bits)
  val O_tmds_clk_p = out Bool()
  val O_tmds_clk_n = out Bool()
  val O_tmds_data_p = out Bits(3 bits)
  val O_tmds_data_n = out Bits(3 bits)

  // ----------------------------------------------------------------------
  // Writer-side PLL chain: 27 MHz × 14 / 3 = 126 MHz, /5 → 25.2 MHz.
  // (Same defaults that drive the existing main TopTang20kHdmi.)
  // ----------------------------------------------------------------------
  val pllWriter = GowinRpll()
  pllWriter.setName("pllWriter")
  pllWriter.CLKIN   := I_clk
  pllWriter.CLKFB   := False
  pllWriter.FBDSEL  := B(0, 6 bits)
  pllWriter.IDSEL   := B(0, 6 bits)
  pllWriter.ODSEL   := B(0, 6 bits)
  pllWriter.DUTYDA  := B(0, 4 bits)
  pllWriter.PSDA    := B(0, 4 bits)
  pllWriter.FDLY    := B(0, 4 bits)
  pllWriter.RESET   := False
  pllWriter.RESET_P := False

  val clkdivWriter = GowinClkdiv()
  clkdivWriter.setName("clkdivWriter")
  clkdivWriter.HCLKIN := pllWriter.CLKOUT
  clkdivWriter.CALIB  := True

  // ----------------------------------------------------------------------
  // Reader-side PLL chain: 27 MHz × 55 / 4 = 371.25 MHz, /5 → 74.25 MHz.
  // ----------------------------------------------------------------------
  val pllReader = GowinRpll(idivSel = 3, fbdivSel = 54, odivSel = 2)
  pllReader.setName("pllReader")
  pllReader.CLKIN   := I_clk
  pllReader.CLKFB   := False
  pllReader.FBDSEL  := B(0, 6 bits)
  pllReader.IDSEL   := B(0, 6 bits)
  pllReader.ODSEL   := B(0, 6 bits)
  pllReader.DUTYDA  := B(0, 4 bits)
  pllReader.PSDA    := B(0, 4 bits)
  pllReader.FDLY    := B(0, 4 bits)
  pllReader.RESET   := False
  pllReader.RESET_P := False

  val clkdivReader = GowinClkdiv()
  clkdivReader.setName("clkdivReader")
  clkdivReader.HCLKIN := pllReader.CLKOUT
  clkdivReader.CALIB  := True

  // Reset hold counters on the 27 MHz input domain, gated by each PLL's lock.
  val rawClockDomain = ClockDomain(
    clock  = I_clk,
    reset  = !(pllWriter.LOCK && pllReader.LOCK),
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )
  val pllResetArea = new ClockingArea(rawClockDomain) {
    val writerHold = Reg(UInt(4 bits)) init 0
    val readerHold = Reg(UInt(4 bits)) init 0
    when(writerHold =/= 15) { writerHold := writerHold + 1 }
    when(readerHold =/= 15) { readerHold := readerHold + 1 }
  }
  clkdivWriter.RESETN := pllResetArea.writerHold === 15
  clkdivReader.RESETN := pllResetArea.readerHold === 15

  val writerReset = !pllWriter.LOCK
  val readerReset = !pllReader.LOCK

  val writerCd = ClockDomain(
    clock  = clkdivWriter.CLKOUT,
    reset  = writerReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )
  val readerCd = ClockDomain(
    clock  = clkdivReader.CLKOUT,
    reset  = readerReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  // ----------------------------------------------------------------------
  // CDC seam — `StreamFifoCC` carrying 24-bit RGB pixels writer→reader.
  // The codebase's established cross-clock pattern (see SdramTileFetch
  // header note "cross-domain handoff to the pixel clock uses
  // StreamFifoCC, avoiding dual-clock BRAM"). Backpressure on the writer
  // side naturally rate-matches the slow 25.2 MHz producer to the
  // bursty 74.25 MHz consumer (which only consumes one pixel per
  // centered-active reader cycle, not every reader cycle).
  // ----------------------------------------------------------------------
  // P-2 (per #8511): pack 4 pixels per FIFO entry so writer's effective
  // rate (25.2 M × 4 = 100.8 M pixels/s) outpaces reader's 74.4 M
  // pixels/s instantaneous demand. 96 bits per entry, MSB = oldest pixel.
  val PixPack = 4
  val PixPackBits = 24 * PixPack
  val pixelFifo = StreamFifoCC(
    dataType  = Bits(PixPackBits bits),
    depth     = 32,
    pushClock = writerCd,
    popClock  = readerCd
  )

  // ----------------------------------------------------------------------
  // Writer clocking area (25.2 MHz): produces ALL `PixPack` pixels per
  // cycle in parallel from `xCount`, packs them into one FIFO entry, and
  // pushes one entry per cycle (rate-matched to reader's centered demand).
  // xCount advances by `PixPack` per push, wrapping at 640 → 0. With
  // PixPack=4, effective rate is 25.2 M × 4 = 100.8 M pixels/s, ≥ the
  // reader's 74.4 M instantaneous pop rate.
  // ----------------------------------------------------------------------
  val writerArea = new ClockingArea(writerCd) {
    val xCount = Reg(UInt(10 bits)) init 0  // base index of the current pack

    // Combinational 8-SMPTE-bar lookup over a 10-bit x.
    def patternFor(x: UInt): Bits = {
      val palette = Seq(
        (0xFF, 0xFF, 0xFF), (0xFF, 0xFF, 0x00), (0x00, 0xFF, 0xFF), (0x00, 0xFF, 0x00),
        (0xFF, 0x00, 0xFF), (0xFF, 0x00, 0x00), (0x00, 0x00, 0xFF), (0x00, 0x00, 0x00)
      )
      val si = (x / U(80, 10 bits)).resize(3)
      val r = Bits(8 bits); r := B(0, 8 bits)
      val g = Bits(8 bits); g := B(0, 8 bits)
      val b = Bits(8 bits); b := B(0, 8 bits)
      switch(si) {
        for ((c, i) <- palette.zipWithIndex) {
          is(U(i, 3 bits)) {
            r := B(c._1, 8 bits); g := B(c._2, 8 bits); b := B(c._3, 8 bits)
          }
        }
      }
      r ## g ## b
    }

    val p0 = patternFor(xCount)
    val p1 = patternFor((xCount + U(1, 10 bits)).resize(10))
    val p2 = patternFor((xCount + U(2, 10 bits)).resize(10))
    val p3 = patternFor((xCount + U(3, 10 bits)).resize(10))

    pixelFifo.io.push.valid   := True
    pixelFifo.io.push.payload := p0 ## p1 ## p2 ## p3   // 96 bits, MSB=oldest

    when(pixelFifo.io.push.fire) {
      xCount := Mux(xCount === U(636, 10 bits), U(0, 10 bits), xCount + U(4, 10 bits))
    }
  }

  // ----------------------------------------------------------------------
  // Reader clocking area (74.25 MHz): 720p timing gen, BRAM read at
  // contentX, Slice C bridge, clean-start, HDMI TX.
  // ----------------------------------------------------------------------
  val readerArea = new ClockingArea(readerCd) {
    val timing = Hdmi720pTimingGen()

    val bridge = Hdmi720pCenterBridge()
    bridge.io.x  := timing.io.x
    bridge.io.y  := timing.io.y
    bridge.io.de := timing.io.de

    // Demux the 4-pack: phase counter cycles 0..3 across each FIFO entry;
    // pop fires on phase==3 (last pixel of current entry) so on the next
    // cycle (phase 0) `pop.payload` shows the next entry. Phase resets to
    // 0 outside the centered window so each row starts pixel-aligned with
    // a new entry.
    val phase = Reg(UInt(log2Up(PixPack) bits)) init 0
    val needPop = bridge.io.inWindow && phase === U(3, 2 bits)
    pixelFifo.io.pop.ready := needPop

    when(bridge.io.inWindow) {
      phase := phase + 1                                 // wraps 0..3
    } otherwise {
      phase := 0
    }

    val pack = pixelFifo.io.pop.payload
    val pix = phase.mux(
      U(0, 2 bits) -> pack(95 downto 72),
      U(1, 2 bits) -> pack(71 downto 48),
      U(2, 2 bits) -> pack(47 downto 24),
      default      -> pack(23 downto  0)
    )
    bridge.io.contentRed   := pix(23 downto 16)
    bridge.io.contentGreen := pix(15 downto  8)
    bridge.io.contentBlue  := pix( 7 downto  0)

    // Clean-start mute (~80 ms at 74.25 MHz).
    val cleanStart = HdmiCleanStart(muteCycles = 6_000_000)
    cleanStart.io.inHsync := timing.io.hsync
    cleanStart.io.inVsync := timing.io.vsync
    cleanStart.io.inDe    := timing.io.de
    cleanStart.io.inRed   := bridge.io.red
    cleanStart.io.inGreen := bridge.io.green
    cleanStart.io.inBlue  := bridge.io.blue

    val hdmiTx = Tang20kHdmiTx()
    hdmiTx.clk_pixel    := clkdivReader.CLKOUT
    hdmiTx.clk_pixel_x5 := pllReader.CLKOUT
    hdmiTx.reset        := readerReset
    hdmiTx.hsync := RegNext(cleanStart.io.outHsync) init True
    hdmiTx.vsync := RegNext(cleanStart.io.outVsync) init True
    hdmiTx.de    := RegNext(cleanStart.io.outDe)    init False
    hdmiTx.red   := RegNext(cleanStart.io.outRed)   init 0
    hdmiTx.green := RegNext(cleanStart.io.outGreen) init 0
    hdmiTx.blue  := RegNext(cleanStart.io.outBlue)  init 0
  }

  O_tmds_clk_p  := readerArea.hdmiTx.tmds_clk_p
  O_tmds_clk_n  := readerArea.hdmiTx.tmds_clk_n
  O_tmds_data_p := readerArea.hdmiTx.tmds_data_p
  O_tmds_data_n := readerArea.hdmiTx.tmds_data_n

  // LEDs (active-low):
  //   LED0 = !pllReader.LOCK  (HDMI domain unlocked)
  //   LED1 = !pllWriter.LOCK  (writer domain unlocked)
  //   LED2 = !cleanStart.muteActive (mute window)
  O_led    := B"6'b111111"
  O_led(0) := !pllReader.LOCK
  O_led(1) := !pllWriter.LOCK
  O_led(2) := !readerArea.cleanStart.io.muteActive
}

object Hdmi720pLineBufferProofTopVerilog extends App {
  Config.spinal.generateVerilog(Hdmi720pLineBufferProofTop())
}
