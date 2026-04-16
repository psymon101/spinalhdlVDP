package spinalhdlvdp

import spinal.core._

/** Task 19 Checkpoint B: per-pixel affine coordinate generator.
  *
  *   u(x, y) = A * x + B * y + X
  *   v(x, y) = C * x + D * y + Y
  *
  * Fixed-point contract:
  *   - A, B, C, D : signed 8.8  (16 bits, range ≈ -128.0 .. +127.996)
  *   - X, Y       : signed 10.6 (16 bits, range ≈ -512.0 .. +511.984)
  *   - x, y       : unsigned 10-bit screen coordinates
  *
  * To align A*x (Q8.8) with X (Q10.6) we shift X/Y left by 2 so both live in
  * Q…8 fractional precision. The final sum is computed in a 32-bit signed
  * accumulator, which is wider than the worst-case 8.8 × 1024 + 10.8
  * magnitude (|A|·x ≤ 128·1023 ≈ 2¹⁷, plus |B|·y of the same magnitude, plus
  * |X| ≤ 512·256 = 2¹⁷) so no overflow is possible.
  *
  * Outputs are 7-bit unsigned integer coordinates (modulo 128) — the caller
  * uses these as direct texture-BRAM addresses. Because texture dimensions are
  * power-of-two, negative wrap falls out of two's-complement truncation.
  */
case class AffineStepper() extends Component {
  val io = new Bundle {
    val x = in UInt (10 bits)
    val y = in UInt (10 bits)
    val matrixA = in Bits (16 bits)
    val matrixB = in Bits (16 bits)
    val matrixC = in Bits (16 bits)
    val matrixD = in Bits (16 bits)
    val transX = in Bits (16 bits)
    val transY = in Bits (16 bits)
    val uInt = out UInt (7 bits)
    val vInt = out UInt (7 bits)
    val uFrac = out SInt (32 bits)
    val vFrac = out SInt (32 bits)
  }

  // Widen all inputs to 32-bit signed. Matrix coefficients are already Q8.8.
  val aS = io.matrixA.asSInt.resize(32)
  val bS = io.matrixB.asSInt.resize(32)
  val cS = io.matrixC.asSInt.resize(32)
  val dS = io.matrixD.asSInt.resize(32)

  // Translation registers are Q10.6; shift left by 2 to match the Q…8 fractional
  // precision the matrix products land in.
  val xT = (io.transX.asSInt.resize(32) |<< 2)
  val yT = (io.transY.asSInt.resize(32) |<< 2)

  // Screen coordinates promoted to signed integers. They are integer-valued
  // (zero fractional bits), so multiplying a Q8.8 by this integer produces Q8.8.
  val xS = io.x.asSInt.resize(32)
  val yS = io.y.asSInt.resize(32)

  val uAcc = (aS * xS + bS * yS).resize(32) + xT
  val vAcc = (cS * xS + dS * yS).resize(32) + yT

  io.uFrac := uAcc
  io.vFrac := vAcc

  // Integer part: take bits [14:8] (7 bits above the fractional word). In
  // two's complement this wraps negative values into 0..127 without an
  // explicit modulo step.
  io.uInt := uAcc(14 downto 8).asUInt
  io.vInt := vAcc(14 downto 8).asUInt
}
