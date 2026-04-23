package spinalhdlvdp

import spinal.core._

/** Task 41 — per-pixel metadata bundle carried from fetch engines through
  * the line-buffer boundary into the compositor.
  *
  * Task 48: `layerSource` expanded from 2→3 bits to carry four background
  * layers (BG0..BG3) plus SPRITE. Total bundle width grows 4→5 bits; line
  * buffer widens 12→13 bits (still fits Gowin 16-bit BSRAM modes cleanly).
  *
  * 5-bit bundle:
  *   - `mathEnable`       : pixel opts into color-math blend
  *   - `forcedPriority`   : pixel wins against normal layer order (sprite-zero-hit,
  *                          foreground-sprite-over-BG, etc.)
  *   - `layerSource[2:0]` : which fetch engine produced the pixel
  *                          `0 = BG0`, `1 = BG1`, `2 = BG2`, `3 = BG3`, `4 = SPRITE`.
  *
  * Default value is `5'b00000` — no math, normal priority, source = BG0.
  *
  * The bundle packs/unpacks via `toBits` / `fromBits` helpers so it can ride
  * alongside the 8-bit `{priority, bank, idx}` pixel in the widened
  * `LineBuffer` without needing a parallel shadow RAM.
  */
case class PixelMetadata() extends Bundle {
  val mathEnable     = Bool()
  val forcedPriority = Bool()
  val layerSource    = UInt(3 bits)

  /** Pack into 5 bits: { layerSource[2:0], forcedPriority, mathEnable }. */
  def toBits: Bits = layerSource.asBits ## forcedPriority.asBits ## mathEnable.asBits
}

object PixelMetadata {
  /** Layer-source encoding constants. */
  val SourceBG0    = 0
  val SourceBG1    = 1
  val SourceBG2    = 2
  val SourceBG3    = 3
  val SourceSprite = 4

  /** Structural default — no math, normal priority, BG0 source. */
  def default(): PixelMetadata = {
    val m = PixelMetadata()
    m.mathEnable     := False
    m.forcedPriority := False
    m.layerSource    := U(SourceBG0, 3 bits)
    m
  }

  /** Reconstitute from a 5-bit word produced by `toBits`. */
  def fromBits(b: Bits): PixelMetadata = {
    val m = PixelMetadata()
    m.mathEnable     := b(0)
    m.forcedPriority := b(1)
    m.layerSource    := b(4 downto 2).asUInt
    m
  }

  val Width: Int = 5
}
