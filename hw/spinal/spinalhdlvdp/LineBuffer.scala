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

  // RTL-BSRAM-OPTIMIZATION-149 Refactor 3: the former `bufA`/`bufB` ping-pong is
  // folded into a single Mem of twice the depth, with the buffer-select bit
  // carried in the address. Two separate small Mems each claim a full BSRAM
  // block on Gowin even though one block can hold both halves; folding lets the
  // synthesizer pack the pair into the minimum block count.
  //
  // Region 0 = former bufA (addr [0, lineWidth)); region 1 = former bufB
  // (addr [lineWidth, 2*lineWidth)). `lineWidth` is not required to be a power
  // of two (it is 640 in production), so the select uses an additive base
  // (sel ? lineWidth : 0) rather than an address-MSB concatenation — the latter
  // would address past the depth-2*lineWidth Mem when lineWidth is not 2^n.
  val depth    = 2 * lineWidth
  val buf      = Mem(Bits(pixelWidth bits), depth)
  val lineBase = U(lineWidth, log2Up(depth) bits)
  val zeroBase = U(0,         log2Up(depth) bits)

  val writeSel = Reg(Bool()) init False
  when(io.swap) {
    writeSel := !writeSel
  }

  // Write goes to the buffer `writeSel` selects (former bufB when writeSel).
  when(io.writeEnable) {
    val writeBase = Mux(writeSel, lineBase, zeroBase)
    buf.write(writeBase + io.writeAddr, io.writeData)
  }

  // Synchronous read for BSRAM inference. Data available 1 cycle after address.
  //
  // The original read muxed two readSync outputs *after* the read register, so
  // the buffer select tracked `writeSel` with zero delay. With one read port the
  // select moves into the registered address; using `writeSel` directly would
  // apply the previous cycle's select and corrupt the first drained pixel after
  // each swap. Base the read on the post-swap value of `writeSel` so the
  // 1-cycle readSync delay lines the output up exactly with the original mux.
  val writeSelNext = Mux(io.swap, !writeSel, writeSel)
  val readBase     = Mux(writeSelNext, zeroBase, lineBase) // read the buffer NOT being written
  io.readData := buf.readSync(readBase + io.readAddr)
}
