package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Task 28 — Two-Pass Sprite Evaluator.
  *
  * Pass 1 (sequential scan): walks `descCount` descriptors across one H-blank
  * period, selecting up to `visiblePerLine` whose Y-range `[y, y+16)`
  * covers the upcoming scanline. Total on-line count is tracked so the
  * overflow flag fires when more than `visiblePerLine` descriptors are
  * on the line.
  *
  * Pass 2 (line-stable): the selected slots are exposed combinationally
  * (`active*`) for the pixel-fill path to resolve per-pixel sprite
  * contributions.
  *
  * Descriptor storage architecture (Task 28 / CyanPeak #7838):
  *   - Slots `[0 .. legacyIoCount)` are driven live from the io `desc*`
  *     input Vecs — preserves backwards-compat with the existing
  *     `VdpTop.io.spriteN` external path and the scenario wiring in
  *     `TopTang20kHdmi.scala`.
  *   - Slots `[legacyIoCount .. descCount)` are Reg-backed and
  *     programmable via the `bus*` write port. Host code writes via the
  *     Mode0 register bus block `0x0800 + slot*2 + word`
  *     (word 0 = enabled/patternIndex/y, word 1 = x).
  *
  * Ordering / priority (per CyanPeak #7838 §8.3):
  *   - Slot 0 receives the lowest on-line descriptor index, slot 1 the
  *     next, etc. Descriptor index serves as the deterministic
  *     tie-breaker.
  *   - The pixel-fill consumer iterates slots 0..visiblePerLine-1 with
  *     last-hit-wins, so higher descriptor index = higher render priority
  *     (back-to-front stacking).
  *
  * Scan timing: `evalStart` strobes at the end of each line. The FSM takes
  * descCount pixel-clock cycles to complete; the results are latched into
  * registers that remain stable through the next full line. hBlank
  * provides ~160 cycles of headroom on 640×480@60 timing, so descCount
  * up to ~150 is safe.
  */
case class SpriteEvaluator(
    descCount: Int = 32,
    visiblePerLine: Int = 8,
    patternSelBits: Int = 4,
    legacyIoCount: Int = 4
) extends Component {
  require(descCount >= legacyIoCount, "descCount must be ≥ legacyIoCount")
  require(visiblePerLine >= 1 && visiblePerLine <= descCount,
          "visiblePerLine out of range")

  val descIdxBits = log2Up(descCount)
  val slotBits    = log2Up(visiblePerLine)
  val extCount    = descCount - legacyIoCount

  val io = new Bundle {
    // Legacy IO descriptor ports — slots 0..legacyIoCount-1.
    val descX          = in Vec(UInt(10 bits), legacyIoCount)
    val descY          = in Vec(UInt(10 bits), legacyIoCount)
    val descEnabled    = in Vec(Bool(), legacyIoCount)
    val descPatternIdx = in Vec(UInt(patternSelBits bits), legacyIoCount)

    // Bus-write port — populates the Reg-backed slots [legacyIoCount..descCount).
    val busSlot = in UInt(descIdxBits bits)
    val busWord = in UInt(1 bit)            // 0 = {enabled, patternIdx, y}, 1 = x
    val busData = in Bits(16 bits)
    val busWr   = in Bool()

    // Pass 1 trigger — line to evaluate, one-cycle strobe at end of line M.
    val evalLine  = in UInt(10 bits)
    val evalStart = in Bool()

    // Pass 2 outputs (line-stable across next line).
    val activeValid      = out Vec(Bool(), visiblePerLine)
    val activeX          = out Vec(UInt(10 bits), visiblePerLine)
    val activeRow        = out Vec(UInt(4 bits), visiblePerLine)
    val activePatternIdx = out Vec(UInt(patternSelBits bits), visiblePerLine)

    // Diagnostic: per-line sprite-overflow flag.
    val overflowFlag = out Bool()
  }

  // ---------------------------------------------------------------------
  // Reg-backed extended descriptors (slots legacyIoCount..descCount-1).
  // Each field gets its own Vec-of-Reg to avoid the Vec(Reg(...)) shared-
  // reference pitfall.
  // ---------------------------------------------------------------------
  val regEnabled      = Vec.fill(extCount)(RegInit(False))
  val regX            = Vec.fill(extCount)(RegInit(U(1023, 10 bits)))
  val regY            = Vec.fill(extCount)(RegInit(U(1023, 10 bits)))
  val regPatternIndex = Vec.fill(extCount)(RegInit(U(0, patternSelBits bits)))
  // Task 28 CP-C-Opt1 — expose Reg-Vec to simPublic for VdpTop-scoped
  // integration sim (SpriteBusViaVdpTopSim).
  // Task 28 CP-C Option B (BronzeGate #7883 follow-on): add syn_keep on
  // extended Reg-Vec so Gowin synthesis doesn't fold these regs away as
  // optimised constants. Verilator treats them as ordinary regs; Gowin
  // may optimise if reached-via-dynamic-index patterns appear dead.
  for (i <- 0 until extCount) {
    regEnabled(i).simPublic()
    regX(i).simPublic()
    regY(i).simPublic()
    regPatternIndex(i).simPublic()
    regEnabled(i).addAttribute("syn_keep", "1")
    regX(i).addAttribute("syn_keep", "1")
    regY(i).addAttribute("syn_keep", "1")
    regPatternIndex(i).addAttribute("syn_keep", "1")
  }

  when(io.busWr) {
    val slot = io.busSlot
    when(slot >= U(legacyIoCount, descIdxBits bits)) {
      val rel = (slot - U(legacyIoCount, descIdxBits bits)).resize(log2Up(extCount))
      // Task 28 CP-C Option 1b (BronzeGate #7871 / CoralReef #7875):
      // Explicit per-slot `switch` decode instead of a dynamic-index
      // `Vec` write. Eliminates the `({31'd0,1'b1} <<< rel)` wide one-hot
      // shift that Gowin synthesis mis-routes under place-and-route.
      // Each slot has its own fully-decoded write-enable equation, which
      // matches the stable pattern used in Copper.scala / QspiDecoder /
      // HostInterface.
      val word0 = io.busWord === U(0, 1 bit)
      val enBit = io.busData(15)
      val patW0 = io.busData(14 downto (15 - patternSelBits)).asUInt
      val yW0   = io.busData(9 downto 0).asUInt
      val xW1   = io.busData(9 downto 0).asUInt
      switch(rel) {
        for (i <- 0 until extCount) {
          is(U(i, log2Up(extCount) bits)) {
            when(word0) {
              regEnabled(i)      := enBit
              regPatternIndex(i) := patW0
              regY(i)            := yW0
            } otherwise {
              regX(i) := xW1
            }
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------
  // Unified descriptor read path: slot index 0..descCount-1.
  // Slots 0..legacyIoCount-1 come from io ports.
  // Slots legacyIoCount..descCount-1 come from the Reg table.
  // ---------------------------------------------------------------------
  def descEnabled(i: Int): Bool = {
    if (i < legacyIoCount) io.descEnabled(i)
    else                   regEnabled(i - legacyIoCount)
  }
  def descX(i: Int): UInt = {
    if (i < legacyIoCount) io.descX(i)
    else                   regX(i - legacyIoCount)
  }
  def descY(i: Int): UInt = {
    if (i < legacyIoCount) io.descY(i)
    else                   regY(i - legacyIoCount)
  }
  def descPatternIdx(i: Int): UInt = {
    if (i < legacyIoCount) io.descPatternIdx(i).resize(patternSelBits)
    else                   regPatternIndex(i - legacyIoCount)
  }

  // ---------------------------------------------------------------------
  // Sequential Pass-1 FSM.
  // On evalStart: reset scanIdx/activeCount/totalOnLine, set scanBusy.
  // While scanBusy: check descriptor at scanIdx for on-line membership.
  //   If on-line: increment totalOnLine and (if activeCount < visible)
  //   commit descriptor's {x, y, patternIdx} into slot[activeCount].
  //   Increment scanIdx; stop when past last descriptor.
  // After scan: overflow flag set iff totalOnLine > visiblePerLine.
  // ---------------------------------------------------------------------
  val scanIdx        = Reg(UInt(descIdxBits bits)) init 0
  val activeCount    = Reg(UInt(log2Up(visiblePerLine + 1) bits)) init 0
  val totalOnLine    = Reg(UInt(log2Up(descCount + 1) bits)) init 0
  val scanBusy       = Reg(Bool()) init False

  val activeValidReg   = Vec.fill(visiblePerLine)(RegInit(False))
  val activeXReg       = Vec.fill(visiblePerLine)(RegInit(U(0, 10 bits)))
  val activeRowReg     = Vec.fill(visiblePerLine)(RegInit(U(0, 4 bits)))
  val activePatternReg = Vec.fill(visiblePerLine)(RegInit(U(0, patternSelBits bits)))
  val overflowFlagReg  = Reg(Bool()) init False

  when(io.evalStart) {
    scanIdx     := 0
    activeCount := 0
    totalOnLine := 0
    scanBusy    := True
    for (s <- 0 until visiblePerLine) {
      activeValidReg(s) := False
    }
  }

  // Combinational on-line check for the currently-scanned descriptor.
  // We unroll a Mux-chain across all descCount entries selected by scanIdx
  // so the on-line check works against both IO and Reg slots uniformly.
  val curEnabled = UInt(1 bit);     curEnabled := 0
  val curX       = UInt(10 bits);   curX := 0
  val curY       = UInt(10 bits);   curY := 0
  val curPat     = UInt(patternSelBits bits); curPat := 0
  // Task 28 CP-C Option 2 (BronzeGate #7880 follow-on): explicit `switch`
  // decode for the descriptor read path. Matches the synthesis-stable
  // style used elsewhere in the repo (Copper / QspiDecoder / HostInterface).
  // The pre-rewrite form was a when-chain of N guards driving a combinational
  // Mux-tree — formally equivalent but relies on Gowin folding the guards
  // into a single case. Explicit switch removes that dependence.
  when(scanBusy) {
    switch(scanIdx) {
      for (i <- 0 until descCount) {
        is(U(i, descIdxBits bits)) {
          curEnabled := descEnabled(i).asUInt
          curX       := descX(i)
          curY       := descY(i)
          curPat     := descPatternIdx(i)
        }
      }
    }
  }
  val dOnLine = scanBusy && curEnabled(0) &&
                (io.evalLine >= curY) &&
                (io.evalLine < (curY + U(16, 10 bits)))

  when(scanBusy) {
    when(dOnLine) {
      totalOnLine := totalOnLine + 1
      when(activeCount < U(visiblePerLine, activeCount.getWidth bits)) {
        val slot = activeCount.resize(slotBits)
        // Task 28 CP-C Option 1b — explicit per-slot decode for active-list
        // writes (same reason as the bus-write rewrite: Gowin synthesis
        // mis-handles the dynamic-index Vec write).
        switch(slot) {
          for (s <- 0 until visiblePerLine) {
            is(U(s, slotBits bits)) {
              activeValidReg(s)   := True
              activeXReg(s)       := curX
              activeRowReg(s)     := (io.evalLine - curY).resize(4)
              activePatternReg(s) := curPat
            }
          }
        }
        activeCount := activeCount + 1
      }
    }
    when(scanIdx === U(descCount - 1, descIdxBits bits)) {
      scanBusy := False
      overflowFlagReg := (totalOnLine +
        Mux(dOnLine, U(1, totalOnLine.getWidth bits), U(0, totalOnLine.getWidth bits))) >
        U(visiblePerLine, totalOnLine.getWidth bits)
    } otherwise {
      scanIdx := scanIdx + 1
    }
  }

  io.activeValid      := activeValidReg
  io.activeX          := activeXReg
  io.activeRow        := activeRowReg
  io.activePatternIdx := activePatternReg
  io.overflowFlag     := overflowFlagReg
}
