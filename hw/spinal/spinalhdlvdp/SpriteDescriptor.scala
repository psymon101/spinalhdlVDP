package spinalhdlvdp

import spinal.core._

/** Sprite descriptor bundle.
  *
  * Task 28 — core enabled/x/y/patternIndex fields.
  * Task 37 — affine extension: affineEnable flag + Q8.8 matrix (A,B,C,D)
  * and Q10.6 translation (transX/transY). Matches the AffineStepper
  * (Task 19) fixed-point contract exactly so the sprite affine path can
  * reuse the proven primitive without re-deriving precision.
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
}

object SpriteDescriptor {
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
    d
  }
}
