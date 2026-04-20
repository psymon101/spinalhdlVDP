package spinalhdlvdp

import spinal.core._

/** Task 28 — sprite descriptor bundle.
  *
  * Holds a single sprite's scanline-evaluation state. The full descriptor
  * table is N of these; Task 28 expands from the legacy 4-descriptor
  * inline path to 32 descriptors with an 8-sprite-per-line limit.
  *
  * `patternIndex` is deliberately 4 bits for forward-compatibility even
  * though the current renderer only resolves patternIndex bit[0] (two
  * pattern Mems — pattern Mem expansion belongs to a later task).
  */
case class SpriteDescriptor(patternSelBits: Int = 4) extends Bundle {
  val enabled      = Bool()
  val x            = UInt(10 bits)
  val y            = UInt(10 bits)
  val patternIndex = UInt(patternSelBits bits)
}

object SpriteDescriptor {
  /** Structural default — disabled, positioned off-screen. */
  def disabled(patternSelBits: Int = 4): SpriteDescriptor = {
    val d = SpriteDescriptor(patternSelBits)
    d.enabled      := False
    d.x            := U(1023, 10 bits)
    d.y            := U(1023, 10 bits)
    d.patternIndex := U(0, patternSelBits bits)
    d
  }
}
