package spinalhdlvdp

import spinal.core._

/** R4.1b planar (NES-style 2-plane 2bpp) test assets.
  *
  * Disjoint SDRAM region from R4 packed assets (per CoralReef #6755 layout):
  *   0x6000 packed tile map      (R4)
  *   0x7000 packed attribute map (R4)
  *   0x8000 packed tile rows     (R4)
  *   0xA000 planar tile rows     (R4.1b — this file)
  *
  * Tile data layout (matches the planar branch in
  * SdramTileAttributeFetch's unpacker):
  *   per tile row (16 pixels): word0[15:0] = plane 0, word1[15:0] = plane 1
  *   pixel x = {plane1(15-x), plane0(15-x)} → 0..3
  *   word0[31:16] and word1[31:16] are unused (zero).
  *
  * Per tile (16 rows): 16 × 8 bytes = 128 bytes
  * Per asset set (4 tiles): 4 × 128 = 512 bytes (power-of-two — GT-022 ✓)
  */
object PlanarTileAssets {
  val SdramBase        = 0x0A000        // Plane 0 base (R4.1b + R4.1d plane 0)
  val Plane1SdramBase  = 0x0B000        // Plane 1 base (R4.1d shuffled/bitplane)

  val TileCount    = 4
  val TileHeight   = 16
  val RowBytes     = 8
  val TileBytes    = TileHeight * RowBytes        // 128
  val TotalBytes   = TileCount * TileBytes        // 512 — power of two

  // Tile patterns — each is a function from pixelY → 16-pixel row of 2-bit
  // indices.  Indices 0..3 map through the active palette bank.
  private def vstripe4(y: Int): Seq[Int] =
    (0 until 16).map(x => x / 4)              // 0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3
  private def hstripe4(y: Int): Seq[Int] =
    (0 until 16).map(_ => y / 4)              // each row uniform 0/1/2/3
  private def solid(c: Int)(y: Int): Seq[Int] =
    (0 until 16).map(_ => c)
  private def diag(y: Int): Seq[Int] =
    (0 until 16).map(x => (x + y) & 0x3)      // 4-color diagonal stripes

  private val tilePatterns: Seq[Int => Seq[Int]] = Seq(
    vstripe4 _,           // 0: vertical 4-color stripes
    hstripe4 _,           // 1: horizontal 4-color stripes
    diag _,               // 2: diagonal stripes
    solid(3) _            // 3: solid index 3
  )
  require(tilePatterns.length == TileCount)

  /** Encode one row's 16 × 2bpp pixels into 8 SDRAM bytes:
    *   bytes[0..1] = plane0[15:0] little-endian
    *   bytes[2..3] = 0 (unused)
    *   bytes[4..5] = plane1[15:0] little-endian
    *   bytes[6..7] = 0 (unused)
    *
    * Plane-bit convention: pixel x maps to bit (15-x), matching the planar
    * unpack in SdramTileAttributeFetch.
    */
  private def encodeRow(pixels: Seq[Int]): Seq[Int] = {
    require(pixels.length == 16)
    var plane0 = 0
    var plane1 = 0
    for ((p, x) <- pixels.zipWithIndex) {
      require(p >= 0 && p < 4, s"planar pixel index $p out of 0..3")
      val bit = 15 - x
      if ((p & 1) != 0) plane0 |= (1 << bit)
      if ((p & 2) != 0) plane1 |= (1 << bit)
    }
    Seq(
      plane0       & 0xFF,    // word0 byte 0
      (plane0 >> 8) & 0xFF,   // word0 byte 1
      0,                      // word0 byte 2 (unused)
      0,                      // word0 byte 3 (unused)
      plane1       & 0xFF,    // word1 byte 0
      (plane1 >> 8) & 0xFF,   // word1 byte 1
      0,                      // word1 byte 2 (unused)
      0                       // word1 byte 3 (unused)
    )
  }

  /** Boot-copy ROM for planar tile data. SDRAM layout is row-major within
    * each tile, tile-major across the asset set:
    *   byte offset of tile T row R byte B = (T * TileHeight + R) * RowBytes + B
    */
  def planarRowBytesInit: Seq[Bits] = {
    val out = scala.collection.mutable.ArrayBuffer[Int]()
    for (t <- 0 until TileCount; r <- 0 until TileHeight) {
      out ++= encodeRow(tilePatterns(t)(r))
    }
    require(out.length == TotalBytes,
      s"planar bytes length ${out.length} != expected $TotalBytes")
    out.toSeq.map(b => B(b, 8 bits))
  }

  /** R4.1d shuffled-mode boot ROM: plane 1 only, laid out at the SAME per-tile
    * per-row offset as `planarRowBytesInit` but containing only plane 1 bytes
    * at word0's low 16 bits. This lets `SdramTileAttributeFetch` fetch plane 1
    * from a SEPARATE base address (0xB000) using the same tileIdx*128+py*8
    * stride. Unpack reads `unpackRow(47:32)` as plane 1 — for shuffled mode the
    * word1-side 32-bit fetch lands here, and its low 16 bits feed that slice.
    *
    * Per row layout (8 bytes):
    *   bytes[0..1] = plane1[15:0] little-endian (same encoding as planarRow)
    *   bytes[2..7] = 0 (unused; wastes 6B/row but keeps stride identical)
    */
  def plane1RowBytesInit: Seq[Bits] = {
    val out = scala.collection.mutable.ArrayBuffer[Int]()
    for (t <- 0 until TileCount; r <- 0 until TileHeight) {
      val pixels = tilePatterns(t)(r)
      require(pixels.length == 16)
      var plane1 = 0
      for ((p, x) <- pixels.zipWithIndex) {
        val bit = 15 - x
        if ((p & 2) != 0) plane1 |= (1 << bit)
      }
      out ++= Seq(
        plane1       & 0xFF,
        (plane1 >> 8) & 0xFF,
        0, 0, 0, 0, 0, 0
      )
    }
    require(out.length == TotalBytes,
      s"plane1 bytes length ${out.length} != expected $TotalBytes")
    out.toSeq.map(b => B(b, 8 bits))
  }

  /** Software reference: pixel index for tile T at (pixelX, pixelY). */
  def expectedPixel(t: Int, x: Int, y: Int): Int = {
    require(t >= 0 && t < TileCount)
    require(x >= 0 && x < 16 && y >= 0 && y < 16)
    tilePatterns(t)(y)(x)
  }

  // Sanity
  require(isPow2(TotalBytes), s"TotalBytes=$TotalBytes must be power-of-two (GT-022)")
}
