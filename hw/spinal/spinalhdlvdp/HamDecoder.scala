package spinalhdlvdp

import spinal.core._

/** HAM-DECODER-171 — Amiga HAM6 (Hold-And-Modify) decoder.
  *
  * Decodes a stream of 6-bit HAM codes (raster order, one per source pixel) into
  * a 12-bit 4:4:4 colour accumulator, expanded to 24-bit 8:8:8 on the output.
  *
  * Code layout (6 bits): [5:4] = control, [3:0] = data
  *   ctrl 00  SET          : load `baseColor` (palette[data], 4:4:4) into the accumulator
  *   ctrl 01  MODIFY BLUE  : hold R,G; B := data
  *   ctrl 10  MODIFY RED   : R := data; hold G,B
  *   ctrl 11  MODIFY GREEN : hold R,B; G := data
  * (Matches the Amiga OCS HAM6 control encoding.)
  *
  * Accumulator layout: bits [11:8]=R, [7:4]=G, [3:0]=B (4 bits/channel).
  *
  * Timing / hold semantics (driver contract):
  *   - The accumulator is the "hold" = the previous source pixel's colour.
  *   - `io.rgb444`/`io.rgb888` are COMBINATIONAL: the colour of the pixel whose
  *     code is presented THIS cycle (= decode(code, hold)). Same-cycle output.
  *   - On `step`, the accumulator latches that colour so it becomes the next
  *     pixel's hold.
  *   - On `lineStart` (assert one cycle BEFORE the line's first `step`), the
  *     accumulator is reset to `seedColor` — HAM hold does not persist across
  *     scanlines. `lineStart` takes priority over `step`.
  *
  * `baseColor` is supplied by the caller (the SET control indexes the 16-entry
  * base palette externally; e.g. palette[code[3:0]] truncated to 4:4:4), keeping
  * this module a pure decode + accumulator with no palette port.
  */
case class HamDecoder() extends Component {
  val io = new Bundle {
    val lineStart = in  Bool()         // reset accumulator to seedColor (one cycle before first step)
    val step      = in  Bool()         // advance one HAM pixel (latch this pixel's colour as next hold)
    val code      = in  Bits(6 bits)   // [5:4]=control, [3:0]=data
    val baseColor = in  Bits(12 bits)  // palette[code[3:0]] as R4:G4:B4 (used by SET)
    val seedColor = in  Bits(12 bits)  // accumulator value loaded at lineStart
    val rgb444    = out Bits(12 bits)  // this pixel's colour, 4:4:4
    val rgb888    = out Bits(24 bits)  // this pixel's colour, expanded 8:8:8
  }

  // Accumulator = the hold (previous pixel's 4:4:4 colour).
  val acc = Reg(Bits(12 bits)) init 0
  val rHold = acc(11 downto 8)
  val gHold = acc(7 downto 4)
  val bHold = acc(3 downto 0)

  val ctrl = io.code(5 downto 4)
  val data = io.code(3 downto 0)

  // Combinational decode of the current pixel's colour from (code, hold).
  val nextColor = Bits(12 bits)
  switch(ctrl) {
    is(0) { nextColor := io.baseColor }              // SET
    is(1) { nextColor := rHold ## gHold ## data }    // MODIFY BLUE
    is(2) { nextColor := data  ## gHold ## bHold }   // MODIFY RED
    is(3) { nextColor := rHold ## data  ## bHold }   // MODIFY GREEN
  }

  when(io.lineStart) {
    acc := io.seedColor
  } elsewhen(io.step) {
    acc := nextColor
  }

  io.rgb444 := nextColor

  // 4→8 expansion by bit replication (full-scale 0xF → 0xFF).
  val nr = nextColor(11 downto 8)
  val ng = nextColor(7 downto 4)
  val nb = nextColor(3 downto 0)
  io.rgb888 := (nr ## nr) ## (ng ## ng) ## (nb ## nb)
}

object HamDecoderVerilog extends App {
  Config.spinal.generateVerilog(HamDecoder())
}
