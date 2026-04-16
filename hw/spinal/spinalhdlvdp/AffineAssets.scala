package spinalhdlvdp

import spinal.core._

/** Task 19 Checkpoint B: diagnostic asset for the 128×128 affine texture.
  *
  * 8 bits per texel, laid out as {priority[7], bank[6:4], index[3:0]}.
  * The asset is a high-contrast grid designed so non-axis-aligned transforms
  * (rotation, shear) are visually obvious on hardware:
  *
  *   - horizontal+vertical 1-pixel rules every 16 texels (grid)
  *   - solid 9-texel dot at the texture centre (64,64)
  *   - colour cycles through 4 palette banks along the vertical axis so
  *     rotation is unambiguous (you can see which corner rotated where)
  *   - non-grid pixels are index 1 with bank 0 (opaque, single base colour)
  *   - grid + dot pixels use index 2 with banks 0..3 depending on row
  *
  * Transparent index 0 is never used so the rotated region is fully opaque —
  * this keeps the OpenCV edge-orientation check uncluttered.
  */
object AffineAssets {
  val Width = 128
  val Height = 128

  /** Compute the 8-bit pixel value for texel (x, y). Kept as a pure function
    * so the same mapping can be used for sim oracles and the BRAM initialiser.
    */
  def texel(x: Int, y: Int): Int = {
    require(x >= 0 && x < Width && y >= 0 && y < Height)
    val onGrid = (x % 16 == 0) || (y % 16 == 0)
    val dx = x - 64
    val dy = y - 64
    val onDot = (dx * dx + dy * dy) <= 4   // 9-texel disc (r ≈ 2)
    val isFeature = onGrid || onDot
    val index = if (isFeature) 2 else 1
    val bank = (y / 32) & 0x3               // 4 bank bands vertically
    val priority = if (onDot) 1 else 0      // centre dot gets priority bit set
    ((priority & 0x1) << 7) | ((bank & 0x7) << 4) | (index & 0xF)
  }

  /** Flat init buffer for `Mem(Bits(8 bits), 128*128)` — row-major (y * 128 + x). */
  val textureInit: Seq[Bits] = (for {
    y <- 0 until Height
    x <- 0 until Width
  } yield B(texel(x, y), 8 bits)).toSeq
}
