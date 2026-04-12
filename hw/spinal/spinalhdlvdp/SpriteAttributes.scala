package spinalhdlvdp

import spinal.core._

/** Minimal sprite attribute store for single-sprite proof.
  *
  * Attributes stored:
  *   - x: 10-bit horizontal position
  *   - y: 10-bit vertical position
  *   - enabled: whether the sprite is visible
  *   - tileIndex: reserved for future use (fixed to 0 for Task 11)
  *
  * For Task 11, attributes are written externally via the write interface.
  * Future tasks may add a register-mapped write path.
  */
case class SpriteAttributes() extends Component {
  val io = new Bundle {
    // Write interface
    val writeX       = in UInt(10 bits)
    val writeY       = in UInt(10 bits)
    val writeEnabled = in Bool()
    val writeStrobe  = in Bool()

    // Read interface (active attributes)
    val x       = out UInt(10 bits)
    val y       = out UInt(10 bits)
    val enabled = out Bool()
  }

  val xReg       = Reg(UInt(10 bits)) init 0
  val yReg       = Reg(UInt(10 bits)) init 0
  val enabledReg = Reg(Bool()) init False

  when(io.writeStrobe) {
    xReg := io.writeX
    yReg := io.writeY
    enabledReg := io.writeEnabled
  }

  io.x := xReg
  io.y := yReg
  io.enabled := enabledReg
}
