package spinalhdlvdp

import spinal.core._

/** R6 Task 20 + Color/Window Hardening CW-4: post-palette color-math stage.
  *
  * Combinational mux on a 24-bit RGB triple selecting one of four operations
  * when `enable` is asserted; otherwise the input passes through unchanged.
  *
  *   op = 00 : passthrough  (rgbOut = rgbIn)
  *   op = 01 : shadow       (each channel >> 1)
  *   op = 10 : highlight    (each channel << 1, clamp 0xFF) — Genesis-style
  *   op = 11 : add constant (each channel + constant, clamped to 255)
  *
  * NOTE: op=10 was previously "add constant" — that semantic now lives at
  * op=11. The new encoding mirrors a one-bit shadow/highlight axis that
  * makes SNES-style paired effects natural to express.
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

  // Highlight: (channel << 1) clamped to 0xFF. If the input MSB is set the
  // shifted value would overflow, so saturate.
  def shlClamp(c: UInt): Bits = Mux(c.msb, B(0xFF, 8 bits), (c(6 downto 0) ## B"0").asBits)
  val highlightRgb = (shlClamp(r) ## shlClamp(g) ## shlClamp(b)).asBits

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
      is(U(2, 2 bits)) { io.rgbOut := highlightRgb }
      is(U(3, 2 bits)) { io.rgbOut := addRgb }
      default          { io.rgbOut := io.rgbIn }
    }
  }
}
