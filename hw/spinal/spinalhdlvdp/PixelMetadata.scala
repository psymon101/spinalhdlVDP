package spinalhdlvdp

import spinal.core._

/** Task 41 — per-pixel metadata bundle carried from fetch engines through
  * the line-buffer boundary into the compositor.
  *
  * 4-bit bundle:
  *   - `mathEnable`     : pixel opts into color-math blend
  *   - `forcedPriority` : pixel wins against normal layer order (sprite-zero-hit,
  *                        foreground-sprite-over-BG, etc.)
  *   - `layerSource[1:0]` : which fetch engine produced the pixel
  *                          `00 = BG0`, `01 = BG1`, `10 = SPRITE`, `11 = reserved`.
  *
  * Default value is `4'b0000` — no math, normal priority, source = BG0. All
  * fetch-engine stubs in the current pipeline drive the default until the
  * corresponding fetch-engine lane (sprite evaluator, per-tile metadata
  * decode, etc.) gets its own task.
  *
  * The bundle packs/unpacks via `toBits` / `fromBits` helpers so it can ride
  * alongside the 8-bit `{priority, bank, idx}` pixel in the widened
  * `LineBuffer` without needing a parallel shadow RAM.
  */
case class PixelMetadata() extends Bundle {
  val mathEnable     = Bool()
  val forcedPriority = Bool()
  val layerSource    = UInt(2 bits)

  /** Pack into 4 bits: { layerSource[1:0], forcedPriority, mathEnable }. */
  def toBits: Bits = layerSource.asBits ## forcedPriority.asBits ## mathEnable.asBits
}

object PixelMetadata {
  /** Structural default — no math, normal priority, BG0 source. */
  def default(): PixelMetadata = {
    val m = PixelMetadata()
    m.mathEnable     := False
    m.forcedPriority := False
    m.layerSource    := U(0, 2 bits)
    m
  }

  /** Reconstitute from a 4-bit word produced by `toBits`. */
  def fromBits(b: Bits): PixelMetadata = {
    val m = PixelMetadata()
    m.mathEnable     := b(0)
    m.forcedPriority := b(1)
    m.layerSource    := b(3 downto 2).asUInt
    m
  }

  val Width: Int = 4
}
