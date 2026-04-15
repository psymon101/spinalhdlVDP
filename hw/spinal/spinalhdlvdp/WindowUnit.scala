package spinalhdlvdp

import spinal.core._

/** R6 Task 20: programmable rectangular window mask.
  *
  * Combinationally compares the current raster position against a rectangle
  * `[x0, x1) × [y0, y1)`. The optional `invert` flag flips the inside/outside
  * meaning so the same rectangle can drive an effect either inside or outside
  * its bounds.
  *
  * Register-space backing (driven by VdpTop):
  *   0x0330 winX0 (inclusive)
  *   0x0331 winX1 (exclusive)
  *   0x0332 winY0 (inclusive)
  *   0x0333 winY1 (exclusive)
  *   0x0334.bit13 = invert
  */
case class WindowUnit() extends Component {
  val io = new Bundle {
    val hCounter = in  UInt(10 bits)
    val vCounter = in  UInt(10 bits)
    val winX0    = in  UInt(10 bits)
    val winX1    = in  UInt(10 bits)
    val winY0    = in  UInt(10 bits)
    val winY1    = in  UInt(10 bits)
    val invert   = in  Bool()
    val inside   = out Bool()
    val effect   = out Bool()
  }
  io.inside := (io.hCounter >= io.winX0) && (io.hCounter < io.winX1) &&
               (io.vCounter >= io.winY0) && (io.vCounter < io.winY1)
  io.effect := io.inside ^ io.invert
}
