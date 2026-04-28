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

  // BH-6 (Beam Hardening artifact §3.6): same-cycle host write + commit
  // collision robustness. If a Copper/HDMA bus write lands on the same
  // cycle as commitStrobe AND targets the same line being committed,
  // the readAsync(commitLine) here would have implementation-defined
  // behavior (Gowin BSRAM may surface old or new data depending on
  // inference). Detect the collision and forward `io.writeData` into
  // commit alongside the prepare write, so the host's update reaches
  // the live render path on the SAME line it was issued for, rather
  // than being lost to the read-old-value race.
  //
  // Non-colliding cases are unchanged: different address → readAsync
  // returns the stable stored value at commitLine; no write at all →
  // commit just reads prepare.
  val commitCollide = io.commitStrobe && io.writeEnable && (io.writeAddr === io.commitLine)
  val commitData    = Mux(commitCollide, io.writeData, prepare.readAsync(io.commitLine))

  // Atomic per-line commit: copy one prepare entry to commit at line boundary.
  when(io.commitStrobe) {
    commit.write(io.commitLine, commitData)
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

  /** Pad the init sequence to the next power-of-two depth. This sidesteps the
    * Gowin BSRAM non-power-of-two inference bug (GT-022 in kb/gowin/GOTCHAS.md,
    * reproduced on the 1200-entry tileMap in Task 15). The active lines are
    * 0..lineCount-1; extra padding entries return 0 (all enables off, no scroll)
    * and are never addressed at runtime since `io.*Addr` stays in range.
    */
  def nextPow2(n: Int): Int = {
    var p = 1
    while (p < n) p <<= 1
    p
  }

  // R5 stage 5: uniform L0-enabled default so copper LAYER_ENABLE toggles
  // produce the §12 visual (L0 tiles visible wherever copper disables L1).
  // L1 alternates every 160 lines so the 3-region structure that was useful
  // for earlier proofs is preserved for `expectedRecord` consumers.
  def defaultInit(lineCount: Int): Seq[Bits] = {
    val depth = nextPow2(lineCount)
    (0 until depth).map { line =>
      val packed = if (line < 160) {
        packRecord(l0en = true, l1en = true, l0sx = 0)
      } else if (line < 320) {
        packRecord(l0en = true, l1en = true, l0sx = 0)  // was: L1-only
      } else if (line < lineCount) {
        packRecord(l0en = true, l1en = false, l0sx = 0)
      } else {
        BigInt(0)
      }
      B(packed, 12 bits)
    }
  }

  def expectedRecord(line: Int): (Boolean, Boolean, Int) = {
    if (line < 160) (true, true, 0)
    else if (line < 320) (true, true, 0)
    else (true, false, 0)
  }
}
