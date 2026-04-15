package spinalhdlvdp

import spinal.core._

/** R6 Task 20: post-palette color-math stage.
  *
  * Combinational mux on a 24-bit RGB triple selecting one of three operations
  * when `enable` is asserted; otherwise the input passes through unchanged.
  *
  *   op = 00 : passthrough  (rgbOut = rgbIn)
  *   op = 01 : shadow       (each channel >> 1)
  *   op = 10 : add constant (each channel + constant, clamped to 255)
  *   op = 11 : reserved     (currently passthrough)
  *
  * The mux sits AFTER the palette lookup and BEFORE the final RGB drive in
  * VdpTop. Default register state at power-on is op=00 / enable=0 so the
  * stage is a no-op for legacy bitstreams.
  */
case class ColorMath() extends Component {
  val io = new Bundle {
    val rgbIn    = in  Bits(24 bits)
    val op       = in  UInt(2 bits)
    val constant = in  UInt(8 bits)
    val enable   = in  Bool()
    val rgbOut   = out Bits(24 bits)
  }

  val r = io.rgbIn(23 downto 16).asUInt
  val g = io.rgbIn(15 downto 8).asUInt
  val b = io.rgbIn(7  downto 0).asUInt

  // Shadow: (channel >> 1)
  val rShadow = io.rgbIn(23 downto 17)        // 7 bits = (r >> 1)
  val gShadow = io.rgbIn(15 downto 9)
  val bShadow = io.rgbIn(7  downto 1)
  val shadowRgb = (B"0" ## rShadow ## B"0" ## gShadow ## B"0" ## bShadow).asBits

  // Add-constant with clamp: 9-bit sum; if MSB set the channel saturates to 0xFF.
  def addClamp(c: UInt, k: UInt): Bits = {
    val sum = (c.resize(9) + k.resize(9))
    Mux(sum.msb, B(0xFF, 8 bits), sum(7 downto 0).asBits)
  }
  val addRgb = (addClamp(r, io.constant) ##
                addClamp(g, io.constant) ##
                addClamp(b, io.constant)).asBits

  io.rgbOut := io.rgbIn   // default = passthrough
  when(io.enable) {
    switch(io.op) {
      is(U(1, 2 bits)) { io.rgbOut := shadowRgb }
      is(U(2, 2 bits)) { io.rgbOut := addRgb }
      default          { io.rgbOut := io.rgbIn }
    }
  }
}
