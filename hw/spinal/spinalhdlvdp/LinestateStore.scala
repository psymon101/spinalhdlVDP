package spinalhdlvdp

import spinal.core._

/** Double-buffered per-scanline control store.
  *
  * - Prepare side: writable by host via write interface at any time
  * - Commit side: readable by render pipeline only
  * - Atomic commit at line boundary: at each line start, the prepare entry for
  *   the current fill line is copied to the commit side
  *
  * Each entry is 12 bits packed as:
  *   [11]    = layer0Enable
  *   [10]    = layer1Enable
  *   [9:0]   = layer0ScrollX
  */
case class LinestateStore(lineCount: Int) extends Component {
  val io = new Bundle {
    // Write interface (prepare side)
    val writeAddr   = in UInt(log2Up(lineCount) bits)
    val writeData   = in Bits(12 bits)
    val writeEnable = in Bool()

    // Line-boundary commit: copies prepare[commitLine] to commit[commitLine].
    val commitLine   = in UInt(log2Up(lineCount) bits)
    val commitStrobe = in Bool()

    // Read interface (commit side, used by render pipeline)
    val readAddr      = in UInt(log2Up(lineCount) bits)
    val layer0Enable  = out Bool()
    val layer1Enable  = out Bool()
    val layer0ScrollX = out UInt(10 bits)
  }

  val prepare = Mem(Bits(12 bits), initialContent = LinestateStore.defaultInit(lineCount))
  val commit  = Mem(Bits(12 bits), initialContent = LinestateStore.defaultInit(lineCount))

  // Write to prepare side.
  when(io.writeEnable) {
    prepare.write(io.writeAddr, io.writeData)
  }

  // Atomic per-line commit: copy one prepare entry to commit at line boundary.
  when(io.commitStrobe) {
    val prepData = prepare.readAsync(io.commitLine)
    commit.write(io.commitLine, prepData)
  }

  // Read from commit side.
  val record = commit.readAsync(io.readAddr)
  io.layer0Enable := record(11)
  io.layer1Enable := record(10)
  io.layer0ScrollX := record(9 downto 0).asUInt
}

object LinestateStore {
  def packRecord(l0en: Boolean, l1en: Boolean, l0sx: Int): BigInt = {
    val bits = (if (l0en) 1 << 11 else 0) |
               (if (l1en) 1 << 10 else 0) |
               (l0sx & 0x3FF)
    BigInt(bits)
  }

  def defaultInit(lineCount: Int): Seq[Bits] = {
    (0 until lineCount).map { line =>
      val packed = if (line < 160) {
        packRecord(l0en = true, l1en = true, l0sx = 0)
      } else if (line < 320) {
        packRecord(l0en = false, l1en = true, l0sx = 0)
      } else {
        packRecord(l0en = true, l1en = false, l0sx = 0)
      }
      B(packed, 12 bits)
    }
  }

  def expectedRecord(line: Int): (Boolean, Boolean, Int) = {
    if (line < 160) (true, true, 0)
    else if (line < 320) (false, true, 0)
    else (true, false, 0)
  }
}
