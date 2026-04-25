package spinalhdlvdp

import spinal.core._

/** Presentation-stage center bridge for the Slice C 640×480-in-720p
  * compatibility mode (BronzeGate #8486).
  *
  * Combinationally:
  *   - exposes (`contentX`, `contentY`) the inner-frame coordinates for
  *     a caller-driven 640×480 content source (the inner source must
  *     return RGB on the same cycle these coordinates are presented;
  *     practically, this means the inner source must be a pure-comb
  *     function of these coordinates, like a synthetic pattern, OR the
  *     caller is responsible for matching the latency)
  *   - asserts `inWindow` while the outer raster is inside the centered
  *     hInner × vInner rectangle and the outer `de` is high
  *   - muxes RGB: inner content when `inWindow`, black otherwise (this
  *     also paints the outer blanking region black, which is benign —
  *     the downstream HDMI TX gates RGB by its own `de` signal)
  *
  * No state; no clock dependency. The mapper exists at the
  * presentation stage only — `VdpTop` and the substrate are
  * unaware of it. Slice C scope per #8486.
  *
  * @param hOuter outer frame width  (1280 for 720p)
  * @param vOuter outer frame height (720)
  * @param hInner inner content width (640 for the 480p compat path)
  * @param vInner inner content height (480)
  */
case class Hdmi720pCenterBridge(
    hOuter: Int = 1280,
    vOuter: Int = 720,
    hInner: Int = 640,
    vInner: Int = 480
) extends Component {
  require(hInner > 0 && vInner > 0,           "inner dimensions must be positive")
  require(hOuter >= hInner && vOuter >= vInner, "inner must fit inside outer frame")
  require((hOuter - hInner) % 2 == 0,
    s"hOuter-hInner must be even for symmetric H borders, got ${hOuter - hInner}")
  require((vOuter - vInner) % 2 == 0,
    s"vOuter-vInner must be even for symmetric V borders, got ${vOuter - vInner}")

  val hBorder = (hOuter - hInner) / 2   // 320 for 1280-640
  val vBorder = (vOuter - vInner) / 2   // 120 for 720-480

  val xWidth = log2Up(hOuter)            // 11 bits for 1280
  val yWidth = log2Up(vOuter)            // 10 bits for 720
  val cxWidth = log2Up(hInner)           // 10 bits for 640
  val cyWidth = log2Up(vInner)           //  9 bits for 480

  val io = new Bundle {
    // Outer-raster inputs (from the 720p timing generator).
    val x  = in UInt(xWidth bits)
    val y  = in UInt(yWidth bits)
    val de = in Bool()

    // Inner-frame interface — caller drives a 640×480 source from these.
    val contentX = out UInt(cxWidth bits)
    val contentY = out UInt(cyWidth bits)
    val inWindow = out Bool()

    // RGB from the caller's inner content source.
    val contentRed   = in Bits(8 bits)
    val contentGreen = in Bits(8 bits)
    val contentBlue  = in Bits(8 bits)

    // Bordered RGB to the next stage (clean-start mute / HDMI TX).
    val red   = out Bits(8 bits)
    val green = out Bits(8 bits)
    val blue  = out Bits(8 bits)
  }

  val xInRange = io.x >= U(hBorder, xWidth bits) &&
                 io.x <  U(hBorder + hInner, xWidth bits)
  val yInRange = io.y >= U(vBorder, yWidth bits) &&
                 io.y <  U(vBorder + vInner, yWidth bits)
  val window = xInRange && yInRange

  io.inWindow := io.de && window
  // contentX/contentY: meaningful only while inWindow holds. Outside the
  // window we still emit the subtraction result; a careless caller might
  // see large numbers, but the muxed output is black so it cannot
  // contaminate the visible image.
  io.contentX := (io.x - U(hBorder, xWidth bits)).resize(cxWidth)
  io.contentY := (io.y - U(vBorder, yWidth bits)).resize(cyWidth)

  io.red   := Mux(io.inWindow, io.contentRed,   B(0, 8 bits))
  io.green := Mux(io.inWindow, io.contentGreen, B(0, 8 bits))
  io.blue  := Mux(io.inWindow, io.contentBlue,  B(0, 8 bits))
}
