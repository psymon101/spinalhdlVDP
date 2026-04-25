package spinalhdlvdp

import spinal.core._

/** Deterministic synthetic source for the Slice B 720p output-shell
  * proof (BronzeGate #8482).
  *
  * Renders the SMPTE-style 8-stripe colour-bar pattern across the
  * full 1280-pixel active line: each stripe is `1280/8 = 160` pixels
  * wide, in the order white → yellow → cyan → green → magenta → red
  * → blue → black. This is a deterministic geometry-and-colour test
  * that proves:
  *   - the 720p timing generator produces 1280 active pixels per line
  *     (stripe boundaries land at predictable x positions)
  *   - each TMDS lane is wired correctly (the colour ordering reveals
  *     R/G/B lane swaps)
  *   - no scaler / VdpTop / Mode0 dependency exists in this slice
  *
  * The horizontal-only pattern doubles as a vertical-geometry probe:
  * the top and bottom of the bars must be visible without warping.
  *
  * @param hActive  visible-region width in pixels (default 1280, the
  *                 720p active raster width). Configurable for unit
  *                 sim convenience.
  */
case class Hdmi720pSyntheticSource(hActive: Int = 1280) extends Component {
  require(hActive % 8 == 0, s"hActive must be divisible by 8 for 8 equal stripes, got $hActive")
  val stripeWidth = hActive / 8

  val io = new Bundle {
    val x   = in  UInt(11 bits)
    val de  = in  Bool()
    val red = out Bits(8 bits)
    val grn = out Bits(8 bits)
    val blu = out Bits(8 bits)
  }

  // Stripe palette (RGB888): standard 75% colour bars at full 0xFF for
  // simplicity (colour amplitude correctness is not what this slice
  // proves — geometry and lane wiring are).
  case class Bar(r: Int, g: Int, b: Int)
  val bars = Seq(
    Bar(0xFF, 0xFF, 0xFF),  // 0: white
    Bar(0xFF, 0xFF, 0x00),  // 1: yellow
    Bar(0x00, 0xFF, 0xFF),  // 2: cyan
    Bar(0x00, 0xFF, 0x00),  // 3: green
    Bar(0xFF, 0x00, 0xFF),  // 4: magenta
    Bar(0xFF, 0x00, 0x00),  // 5: red
    Bar(0x00, 0x00, 0xFF),  // 6: blue
    Bar(0x00, 0x00, 0x00)   // 7: black
  )

  // Stripe index = x / stripeWidth. For powers-of-two stripeWidth this
  // is a free shift; for the 1280/8 = 160 default it requires a small
  // divider — but at 74.25 MHz this is a single-cycle UInt division on
  // a literal constant, which Gowin reduces to a comparator chain.
  val stripeIdx = (io.x / U(stripeWidth, io.x.getWidth bits)).resize(3)

  val r = Bits(8 bits)
  val g = Bits(8 bits)
  val b = Bits(8 bits)
  r := B(0, 8 bits); g := B(0, 8 bits); b := B(0, 8 bits)
  switch(stripeIdx) {
    for ((bar, i) <- bars.zipWithIndex) {
      is(U(i, 3 bits)) {
        r := B(bar.r, 8 bits)
        g := B(bar.g, 8 bits)
        b := B(bar.b, 8 bits)
      }
    }
  }

  io.red := Mux(io.de, r, B(0, 8 bits))
  io.grn := Mux(io.de, g, B(0, 8 bits))
  io.blu := Mux(io.de, b, B(0, 8 bits))
}
