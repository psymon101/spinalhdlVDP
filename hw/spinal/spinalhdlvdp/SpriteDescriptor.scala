package spinalhdlvdp

import spinal.core._

/** Sprite descriptor bundle.
  *
  * Task 28 — core enabled/x/y/patternIndex fields.
  * Task 37 — affine extension: affineEnable flag + Q8.8 matrix (A,B,C,D)
  * and Q10.6 translation (transX/transY). Matches the AffineStepper
  * (Task 19) fixed-point contract exactly so the sprite affine path can
  * reuse the proven primitive without re-deriving precision.
  *
  * Sprite Envelope Hardening (CyanPeak #8577) — 5 new shared-substrate
  * fields per assessment §4.3:
  *   - flipH / flipV       : horizontal / vertical pattern mirror
  *   - paletteBank (3 bits): per-sprite palette bank (0..7)
  *   - priority            : 1 = sprite above-background, 0 = below
  *   - sizeSel (2 bits)    : 00=8×8, 01=16×16 (default), 10=32×32, 11=64×64
  * Genesis/SNES adapters can claim honest sprite substrate support after
  * these land. Neo Geo's 96/line capacity and 16-bank palette space are
  * out of scope (deferred per #8577).
  */
case class SpriteDescriptor(patternSelBits: Int = 4) extends Bundle {
  val enabled      = Bool()
  val x            = UInt(10 bits)
  val y            = UInt(10 bits)
  val patternIndex = UInt(patternSelBits bits)

  // Task 37 affine fields.
  val affineEnable = Bool()
  val matrixA      = Bits(16 bits)   // Q8.8 signed
  val matrixB      = Bits(16 bits)
  val matrixC      = Bits(16 bits)
  val matrixD      = Bits(16 bits)
  val transX       = Bits(16 bits)   // Q10.6 signed
  val transY       = Bits(16 bits)

  // Sprite Envelope Hardening fields.
  val flipH        = Bool()
  val flipV        = Bool()
  val paletteBank  = UInt(3 bits)
  val priority     = Bool()
  val sizeSel      = UInt(2 bits)
}

object SpriteDescriptor {
  /** Pixel size for a given `sizeSel` encoding (00=8, 01=16, 10=32, 11=64). */
  def sizeForSel(sel: Int): Int = sel match {
    case 0 =>  8
    case 1 => 16
    case 2 => 32
    case 3 => 64
    case _ => throw new IllegalArgumentException(s"sizeSel must be 0..3, got $sel")
  }

  /** Runtime version of sizeForSel — returns a 7-bit UInt (max 64). */
  def sizeForSel(sel: UInt): UInt = {
    val out = UInt(7 bits)
    switch(sel) {
      is(U(0, 2 bits)) { out :=  8 }
      is(U(1, 2 bits)) { out := 16 }
      is(U(2, 2 bits)) { out := 32 }
      is(U(3, 2 bits)) { out := 64 }
    }
    out
  }

  /** Default sizeSel encoding for back-compat with the pre-Hardening
    * 16-pixel-tall sprite assumption. */
  val DefaultSizeSel: Int = 1   // 16×16

  /** Structural default — disabled, positioned off-screen, flat path. */
  def disabled(patternSelBits: Int = 4): SpriteDescriptor = {
    val d = SpriteDescriptor(patternSelBits)
    d.enabled      := False
    d.x            := U(1023, 10 bits)
    d.y            := U(1023, 10 bits)
    d.patternIndex := U(0, patternSelBits bits)
    d.affineEnable := False
    d.matrixA      := B(0, 16 bits)
    d.matrixB      := B(0, 16 bits)
    d.matrixC      := B(0, 16 bits)
    d.matrixD      := B(0, 16 bits)
    d.transX       := B(0, 16 bits)
    d.transY       := B(0, 16 bits)
    d.flipH        := False
    d.flipV        := False
    d.paletteBank  := U(0, 3 bits)
    d.priority     := False
    d.sizeSel      := U(DefaultSizeSel, 2 bits)
    d
  }
}
