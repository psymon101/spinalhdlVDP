package spinalhdlvdp

/** Bitplane + palette init data for the Mode0 Hardening hardware proof.
  *
  * Encodes the 8 classic SMPTE colour bars across 320 pixels using only
  * the lower 3 bitplanes (planeCount=5 with planes 3..4 always 0). Each
  * plane row is 320 bits (40 bytes = 10 dout32 dwords). Plane 0 = LSB of
  * the bar index, plane 4 = MSB. With our 32-entry palette indexing the
  * 8 visible-bar colours at slots 0..7, the result on screen is the
  * familiar white→yellow→cyan→green→magenta→red→blue→black pattern,
  * but generated through the full 5-plane fetch + reconstruction stack.
  */
object PlanarProofAssets {
  val PlaneCount: Int = 5
  val PlanePixels: Int = 320
  val BarsCount: Int = 8                 // 8 SMPTE bars across PlanePixels
  val BarWidth: Int = PlanePixels / BarsCount   // 40 px per bar
  val DwordsPerPlane: Int = PlanePixels / 32    // 10
  val PaletteSize: Int = 32

  /** Returns one 32-bit dword for plane `p`, slot `s` (0=leftmost). */
  def planeDword(plane: Int, slot: Int): BigInt = {
    require(plane >= 0 && plane < PlaneCount)
    require(slot >= 0 && slot < DwordsPerPlane)
    if (plane >= 3) BigInt(0)            // planes 3..4 unused (only 8 bars)
    else {
      var word = BigInt(0)
      for (b <- 0 until 32) {
        val pixelIdx = slot * 32 + b
        val barIdx   = pixelIdx / BarWidth   // 0..7
        val bit      = (barIdx >> plane) & 1
        // MSB-first inside the dword: pixel `pixelIdx` lives at bit
        // (31 - b) of the stored dword (matches BitplaneRowFetch output
        // and BitplaneReconstruct's MSB-first convention).
        if (bit == 1) word = word.setBit(31 - b)
      }
      word
    }
  }

  /** Init data for one plane's BRAM (DwordsPerPlane entries, 32 bits each). */
  def planeInit(plane: Int): Seq[BigInt] =
    (0 until DwordsPerPlane).map(s => planeDword(plane, s))

  /** 32-entry palette: 8 SMPTE colours at slots 0..7, black elsewhere.
    * RGB888 packed as `R ## G ## B` to match Hdmi720pSyntheticSource etc. */
  val PaletteRGB: Seq[BigInt] = {
    val bars = Seq(
      0xFFFFFFL,  // 0 white
      0xFFFF00L,  // 1 yellow
      0x00FFFFL,  // 2 cyan
      0x00FF00L,  // 3 green
      0xFF00FFL,  // 4 magenta
      0xFF0000L,  // 5 red
      0x0000FFL,  // 6 blue
      0x000000L   // 7 black
    )
    bars.map(BigInt(_)) ++ Seq.fill(PaletteSize - bars.size)(BigInt(0))
  }
}
