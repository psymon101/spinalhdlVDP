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
case class LinestateStore(lineCount: Int, l0EnabledDefault: Boolean = false) extends Component {
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

  // BSRAM-first partial conversion (per fpga/ARCHITECTURE_RECOMMENDATIONS.md
  // Rec #2, addressing Gate #2 Mem→FF promotion on Tang Nano).
  //
  // `prepare` is converted to BSRAM via readSync + ram_style="block".
  // `commit` stays readAsync — the render pipeline reads it
  // combinationally per-pixel and a 1-cycle readSync delay there
  // produces a per-line first-pixel artifact (line-boundary stale
  // record) that breaks integration sims. The prepare-side
  // conversion alone frees ~half the previously-allocated SSRAM
  // blocks for the linestate submodule (~96 blocks), enough to
  // relieve the Gate #2 reshuffle's DFF promotion pressure.
  //
  // The commit pipeline is delayed by 1 cycle to align with the
  // prepare readSync output. BH-6 collision handling is preserved
  // via parallel pipeline of collide/writeData. Same external
  // contract — no impact on host-write or render-side timing.
  val prepare = Mem(Bits(12 bits), initialContent = LinestateStore.defaultInit(lineCount, l0EnabledDefault))
  val commit  = Mem(Bits(12 bits), initialContent = LinestateStore.defaultInit(lineCount, l0EnabledDefault))
  prepare.addAttribute("ram_style", "block")

  // Write to prepare side.
  when(io.writeEnable) {
    prepare.write(io.writeAddr, io.writeData)
  }

  // BH-6 (Beam Hardening artifact §3.6): same-cycle host write + commit
  // collision robustness — preserved via 1-cycle pipeline to align
  // with prepare.readSync output. Collision is detected at the
  // commit-strobe cycle, latched alongside the writeData and
  // commitLine, then applied to commit.write the next cycle when
  // prepareSync is valid.
  val commitCollide     = io.commitStrobe && io.writeEnable && (io.writeAddr === io.commitLine)
  val commitStrobeD1    = RegNext(io.commitStrobe) init False
  val commitLineD1      = RegNext(io.commitLine)   init 0
  val commitCollideD1   = RegNext(commitCollide)   init False
  val commitWriteDataD1 = RegNext(io.writeData)    init 0
  val prepareSync       = prepare.readSync(io.commitLine)
  val commitData        = Mux(commitCollideD1, commitWriteDataD1, prepareSync)
  when(commitStrobeD1) {
    commit.write(commitLineD1, commitData)
  }

  // Render-side read of commit: still readAsync so the per-pixel
  // render path gets the line's record combinationally with no
  // line-boundary stale-pixel artifact. Commit Mem stays in
  // distributed SSRAM/LUTRAM (unchanged from the original design).
  // readAsync — AUDIT #10772: Class 2 (per-pixel) — RECLASSIFIED from Class 3.
  // Per the prior author comment at line 39-50 / line 77-80 above, this read
  // MUST stay readAsync: render pipeline reads commit per-pixel and a 1-cycle
  // readSync delay produces a per-line first-pixel artifact (line-boundary
  // stale-record). DO NOT convert without redesigning the commit-side pipeline.
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

  // Generic boot default (lane #10567 agnosticism): all per-line layer-enable
  // bits start off. The host owns line-level layer activation via the copper
  // / linestate write path; the RTL ships quiescent.
  // l0Default=true ships every line with l0en (bit 11) set — used by the
  // standalone-diagnostic-build so the on-chip L0 test pattern fills the whole
  // screen without a per-line linestate upload. Production keeps l0Default=false
  // (all bits off; host owns line-level enable).
  def defaultInit(lineCount: Int, l0Default: Boolean = false): Seq[Bits] = {
    val depth = nextPow2(lineCount)
    val v = if (l0Default) BigInt(0x800) else BigInt(0)   // bit 11 = layer0Enable
    (0 until depth).map(_ => B(v, 12 bits))
  }

  def expectedRecord(line: Int): (Boolean, Boolean, Int) = (false, false, 0)
}
