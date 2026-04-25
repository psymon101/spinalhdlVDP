package spinalhdlvdp

import spinal.core._

/** HDMI clean-start mute (HDMI Output Compatibility, Slice A).
  *
  * Forces hsync/vsync inactive (logic-1 for the project's VESA-style
  * negative-active sync) and de=0 / RGB=0 for a fixed window after
  * reset deasserts (i.e., after `pll.LOCK` rises and the pixel-domain
  * starts running). After the window expires, the pass-through inputs
  * are forwarded unchanged.
  *
  * Why this exists
  * ---------------
  * Some HDMI receivers (notably the Guermok USB2 capture card and its
  * RTSP-streamed sibling) need a clean "no-signal → signal" transition
  * to re-acquire TMDS lock after the source is reset (e.g., bitstream
  * reflash via openFPGALoader). Without an explicit blanking window,
  * the encoder begins outputting valid pixels the very first cycle the
  * pixel domain leaves reset; the receiver may interpret the
  * mid-frame appearance as glitchy and stay unlocked.
  *
  * Holding the outputs fully inactive for a few hundred ms after
  * reset gives the receiver time to observe a clean idle line, then
  * see the first valid HSYNC/VSYNC pulse arrive cleanly.
  *
  * Scope (BronzeGate #8476):
  * - Top-level / TMDS-boundary only.
  * - No VdpTop, no PLL retune, no 720p timing shell, no scaler.
  * - Drop in between `video.io` and `hdmiTx` in `TopTang20kHdmi.scala`.
  *
  * @param muteCycles  number of pixel-clock cycles to hold output
  *                    blanked after reset deasserts. Default 2_000_000
  *                    cycles ≈ 80 ms at 25.2 MHz pixel clock — well
  *                    above typical HDMI lock-acquisition windows
  *                    (~30 ms).
  */
case class HdmiCleanStart(muteCycles: Int = 2_000_000) extends Component {
  require(muteCycles > 0, s"muteCycles must be positive, got $muteCycles")
  val counterWidth = log2Up(muteCycles + 1) max 1

  val io = new Bundle {
    // Pass-through inputs (from video.io.* in TopTang20kHdmi).
    val inHsync = in  Bool()
    val inVsync = in  Bool()
    val inDe    = in  Bool()
    val inRed   = in  Bits(8 bits)
    val inGreen = in  Bits(8 bits)
    val inBlue  = in  Bits(8 bits)

    // Outputs (to hdmiTx.* in TopTang20kHdmi).
    val outHsync = out Bool()
    val outVsync = out Bool()
    val outDe    = out Bool()
    val outRed   = out Bits(8 bits)
    val outGreen = out Bits(8 bits)
    val outBlue  = out Bits(8 bits)

    // Sticky status — held high after the mute window has elapsed at
    // least once. Useful for canary/diag readouts; not required for
    // correctness.
    val muteActive = out Bool()
  }

  // Counter counts up to (and stops at) muteCycles. While counting, mute
  // the output. The Reg `init 0` plus the `mute := counter < muteCycles`
  // comparison guarantees the mute fires for exactly the first
  // `muteCycles` cycles after reset, regardless of how the rest of the
  // chip starts up.
  val counter = Reg(UInt(counterWidth bits)) init 0
  val muteActive = counter < U(muteCycles, counterWidth bits)
  when(muteActive) {
    counter := counter + 1
  }

  // VESA negative-active sync polarity used by the project's 640x480
  // mode (`VdpTop.scala:1222-1223`). Inactive == logic high. de is
  // active-high; inactive == 0.
  io.outHsync := Mux(muteActive, True,           io.inHsync)
  io.outVsync := Mux(muteActive, True,           io.inVsync)
  io.outDe    := Mux(muteActive, False,          io.inDe)
  io.outRed   := Mux(muteActive, B(0, 8 bits),   io.inRed)
  io.outGreen := Mux(muteActive, B(0, 8 bits),   io.inGreen)
  io.outBlue  := Mux(muteActive, B(0, 8 bits),   io.inBlue)
  io.muteActive := muteActive
}
