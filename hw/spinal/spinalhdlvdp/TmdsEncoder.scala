package spinalhdlvdp

import spinal.core._

case class TmdsEncoder() extends Component {
  val io = new Bundle {
    val data = in Bits(8 bits)
    val c0 = in Bool()
    val c1 = in Bool()
    val de = in Bool()
    val encoded = out Bits(10 bits)
  }

  val inputOnes = io.data.asBools.map(_.asUInt.resize(4 bits)).foldLeft(U(0, 4 bits))(_ + _)
  val useXnor = inputOnes > 4 || (inputOnes === 4 && !io.data(0))

  val qm = Vec(Bool(), 9)
  qm(0) := io.data(0)
  for (bit <- 1 until 8) {
    qm(bit) := Mux(useXnor, !(qm(bit - 1) ^ io.data(bit)), qm(bit - 1) ^ io.data(bit))
  }
  qm(8) := !useXnor
  val qmBits = qm.asBits

  val qmOnes = qm.take(8).map(_.asUInt.resize(4 bits)).foldLeft(U(0, 4 bits))(_ + _)
  val balance = (qmOnes.resize(5 bits).asSInt - 4).resize(6 bits)

  def boolToSInt(bit: Bool): SInt = bit.asUInt.resize(2 bits).asSInt.resize(6 bits)

  val disparity = Reg(SInt(6 bits)) init 0
  val encodedReg = Reg(Bits(10 bits)) init B"10'b1101010100"

  when(!io.de) {
    switch(io.c1 ## io.c0) {
      is(B"2'b00") { encodedReg := B"10'b1101010100" }
      is(B"2'b01") { encodedReg := B"10'b0010101011" }
      is(B"2'b10") { encodedReg := B"10'b0101010100" }
      default { encodedReg := B"10'b1010101011" }
    }
    disparity := 0
  } otherwise {
    when(disparity === 0 || balance === 0) {
      encodedReg := (!qm(8)) ## qm(8) ## Mux(qm(8), qmBits(7 downto 0), ~qmBits(7 downto 0))
      when(qm(8)) {
        disparity := disparity + balance
      } otherwise {
        disparity := disparity - balance
      }
    } elsewhen((disparity > 0 && balance > 0) || (disparity < 0 && balance < 0)) {
      encodedReg := True ## qm(8) ## ~qmBits(7 downto 0)
      disparity := disparity + boolToSInt(qm(8)) - balance
    } otherwise {
      encodedReg := False ## qm(8) ## qmBits(7 downto 0)
      disparity := disparity - boolToSInt(!qm(8)) + balance
    }
  }

  io.encoded := encodedReg
}
