package spinalhdlvdp

import spinal.core._

/** Task 44 — raw bitmap + attribute pixel decoder.
  *
  * Combinational decode of one pixel from a fetched `bitmapByte` +
  * `attrByte`. The upstream fetch path (existing SDRAM scheduler) is
  * responsible for delivering the correct byte for the current pixel
  * column and row; this module encapsulates the 1bpp / 2bpp decode
  * and attribute-to-palette mapping only.
  *
  * Modes (selected by `bpp`):
  *   00 = 1bpp (ZX Spectrum / C64 hires): one bit per pixel.
  *        attrByte = {flash, bright, paper[2:0], ink[2:0]}
  *        bit==1 → pixelIndex = {1'b0, ink}, 4 bits
  *        bit==0 → pixelIndex = {1'b0, paper}, 4 bits
  *        paletteBank = {2'b00, bright}
  *   01 = 2bpp (C64 multicolor): two bits per pixel; attribute
  *        supplies four colour slots via {bg, fg1, fg2, fg3}:
  *        attrByte = {fg3[1:0], fg2[1:0], fg1[1:0], bg[1:0]} (packed 4×2b)
  *        pair == 00 → pixelIndex = {2'b00, bg}
  *        pair == 01 → pixelIndex = {2'b00, fg1}
  *        pair == 10 → pixelIndex = {2'b00, fg2}
  *        pair == 11 → pixelIndex = {2'b00, fg3}
  *        paletteBank = 0 (2bpp ignores bright)
  *   10 = RGB565 directcolor (true-color bitmap layer). The pixel is a
  *        16-bit RGB565 value supplied on `directPixel` (assembled by the
  *        upstream fetcher from two SDRAM bytes). The decoder bypasses the
  *        palette entirely: it expands 565→RGB888 and raises
  *        `directColorActive`. `pixelIndex`/`paletteBank` are 0 in this
  *        mode (the consumer must mux on `directColorActive`).
  *   11 = reserved; pixelIndex = 0.
  *
  * GT-022: power-of-two addressing is the caller's responsibility
  * (this is a pure combinational block with no storage).
  */
case class BitmapFetch() extends Component {
  val io = new Bundle {
    val bitmapByte      = in  Bits(8 bits)
    val attrByte        = in  Bits(8 bits)
    val pixelWithinByte = in  UInt(3 bits)  // 0..7 = which pixel in the byte
    val bpp             = in  UInt(2 bits)  // 0=1bpp, 1=2bpp, 2=RGB565 directcolor
    // RGB565 directcolor pixel (bpp=10). {R[4:0], G[5:0], B[4:0]} — the
    // upstream fetcher assembles this from two SDRAM bytes (hi << 8 | lo).
    val directPixel     = in  Bits(16 bits)

    val pixelIndex      = out UInt(4 bits)
    val paletteBank     = out UInt(3 bits)
    // RGB565 directcolor outputs. `directColorActive` is True only for
    // bpp=10; `directRgb` carries the 565→888-expanded colour.
    val directRgb         = out Bits(24 bits)
    val directColorActive = out Bool()
  }

  val pi   = UInt(4 bits); pi   := 0
  val bank = UInt(3 bits); bank := 0

  // RGB565 → RGB888 expansion by bit replication: the low bits of each
  // expanded channel repeat the channel's high bits, so full-scale input
  // (all-ones) maps to full-scale output (0xFF) and 0 maps to 0.
  //   R5 → {R5, R5[4:2]}   G6 → {G6, G6[5:4]}   B5 → {B5, B5[4:2]}
  val r5 = io.directPixel(15 downto 11)
  val g6 = io.directPixel(10 downto 5)
  val b5 = io.directPixel(4 downto 0)
  val r8 = r5 ## r5(4 downto 2)
  val g8 = g6 ## g6(5 downto 4)
  val b8 = b5 ## b5(4 downto 2)
  io.directRgb := r8 ## g8 ## b8

  val dcActive = Bool(); dcActive := False
  io.directColorActive := dcActive

  switch(io.bpp) {
    is(U(0, 2 bits)) {
      // 1bpp Spectrum/C64-hires mode.
      // Bit ordering: left-most pixel is bit [7], right-most is bit [0].
      val bitIdx  = U(7, 3 bits) - io.pixelWithinByte
      val bit     = io.bitmapByte(bitIdx)
      val ink     = io.attrByte(2 downto 0).asUInt  // 3 bits
      val paper   = io.attrByte(5 downto 3).asUInt  // 3 bits
      val bright  = io.attrByte(6)
      when(bit) {
        pi := (B(0, 1 bit) ## ink.asBits).asUInt
      }.otherwise {
        pi := (B(0, 1 bit) ## paper.asBits).asUInt
      }
      bank := (B(0, 2 bits) ## bright.asBits).asUInt
    }
    is(U(1, 2 bits)) {
      // 2bpp C64-multicolor mode.
      // pairIdx = 3 - (pixelWithinByte / 2). Each byte encodes 4 pixels
      // (leftmost pair in bits [7:6], rightmost in bits [1:0]).
      val pairIdx    = U(3, 2 bits) - io.pixelWithinByte(2 downto 1)
      val lsb        = (pairIdx << 1).resize(3)
      val b0         = io.bitmapByte(lsb)
      val b1         = io.bitmapByte((lsb + 1).resize(3))
      val pair       = b1.asUInt ## b0.asUInt    // Bits(2) → treated as index
      // Attribute byte: 4 × 2-bit colour slots.
      val slot0      = io.attrByte(1 downto 0).asUInt
      val slot1      = io.attrByte(3 downto 2).asUInt
      val slot2      = io.attrByte(5 downto 4).asUInt
      val slot3      = io.attrByte(7 downto 6).asUInt
      val sel        = UInt(2 bits); sel := 0
      switch(pair.asUInt) {
        is(U(0, 2 bits)) { sel := slot0 }
        is(U(1, 2 bits)) { sel := slot1 }
        is(U(2, 2 bits)) { sel := slot2 }
        is(U(3, 2 bits)) { sel := slot3 }
      }
      pi   := (B(0, 2 bits) ## sel.asBits).asUInt
      bank := 0
    }
    is(U(2, 2 bits)) {
      // RGB565 directcolor: pixel bypasses the palette. `directRgb` is
      // already driven (combinational expand above); just flag it.
      pi       := 0
      bank     := 0
      dcActive := True
    }
    default {
      pi   := 0
      bank := 0
    }
  }

  io.pixelIndex  := pi
  io.paletteBank := bank
}
