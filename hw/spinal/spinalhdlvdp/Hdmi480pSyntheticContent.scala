package spinalhdlvdp

import spinal.core._

/** Deterministic 640×480 inner-content source for the BronzeGate #8486
  * Slice C bridge proof.
  *
  * Geometry-obvious pattern:
  *   - 8 vertical SMPTE colour bars across the 640-pixel inner width
  *     (80 px per bar): white, yellow, cyan, green, magenta, red,
  *     blue, black — same lane-wiring proof as Slice B's outer source.
  *   - 1-pixel white frame on all four edges of the 640×480 inner
  *     rectangle. The frame overwrites the bar colour at the boundary
  *     row/column. Its job is to make the content/border edge
  *     visually unambiguous on the rig monitor — without it the
  *     rightmost (black) bar bleeds into the surrounding black border
  *     and the seam disappears.
  *
  * @param hInner  inner width  (default 640)
  * @param vInner  inner height (default 480)
  */
case class Hdmi480pSyntheticContent(hInner: Int = 640, vInner: Int = 480)
    extends Component {
  require(hInner % 8 == 0,
    s"hInner must be divisible by 8 for 8 equal stripes, got $hInner")
  val stripeWidth = hInner / 8

  val cxWidth = log2Up(hInner)
  val cyWidth = log2Up(vInner)

  val io = new Bundle {
    val x   = in  UInt(cxWidth bits)
    val y   = in  UInt(cyWidth bits)
    val red = out Bits(8 bits)
    val grn = out Bits(8 bits)
    val blu = out Bits(8 bits)
  }

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

  val stripeIdx = (io.x / U(stripeWidth, cxWidth bits)).resize(3)

  val barR = Bits(8 bits); barR := B(0, 8 bits)
  val barG = Bits(8 bits); barG := B(0, 8 bits)
  val barB = Bits(8 bits); barB := B(0, 8 bits)
  switch(stripeIdx) {
    for ((bar, i) <- bars.zipWithIndex) {
      is(U(i, 3 bits)) {
        barR := B(bar.r, 8 bits)
        barG := B(bar.g, 8 bits)
        barB := B(bar.b, 8 bits)
      }
    }
  }

  val onFrame =
    io.x === U(0, cxWidth bits)            ||
    io.x === U(hInner - 1, cxWidth bits)   ||
    io.y === U(0, cyWidth bits)            ||
    io.y === U(vInner - 1, cyWidth bits)

  io.red := Mux(onFrame, B(0xFF, 8 bits), barR)
  io.grn := Mux(onFrame, B(0xFF, 8 bits), barG)
  io.blu := Mux(onFrame, B(0xFF, 8 bits), barB)
}
