package spinalhdlvdp

import spinal.core._

case class LineBuffer(pixelWidth: Int, lineWidth: Int) extends Component {
  val io = new Bundle {
    val writeEnable = in Bool()
    val writeAddr   = in UInt(log2Up(lineWidth) bits)
    val writeData   = in Bits(pixelWidth bits)
    val readAddr    = in UInt(log2Up(lineWidth) bits)
    val readData    = out Bits(pixelWidth bits)
    val swap        = in Bool()
  }

  val bufA = Mem(Bits(pixelWidth bits), lineWidth)
  val bufB = Mem(Bits(pixelWidth bits), lineWidth)

  val writeSel = Reg(Bool()) init False

  when(io.swap) {
    writeSel := !writeSel
  }

  when(io.writeEnable) {
    when(writeSel) {
      bufB.write(io.writeAddr, io.writeData)
    } otherwise {
      bufA.write(io.writeAddr, io.writeData)
    }
  }

  // Synchronous read for BSRAM inference. Data available 1 cycle after address.
  val readDataA = bufA.readSync(io.readAddr)
  val readDataB = bufB.readSync(io.readAddr)
  io.readData := Mux(writeSel, readDataA, readDataB)
}
