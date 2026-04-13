package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** R2 Two-Pass Sprite Evaluator.
  *
  * Pass 1 (evalStart): scan `descCount` static descriptors, select up to
  * `visiblePerLine` whose Y-range `[descY, descY+16)` covers `evalLine`.
  * Priority = lowest descriptor index wins. Latched into active-slot
  * registers so the list is stable for the full line.
  *
  * Pass 2: the active slots are exposed combinationally (`active*`) for the
  * pixel-fill path in `VdpTop` to resolve per-pixel sprite contributions.
  *
  * Diagnostic: `overflowFlag` latches True on the line if more than
  * `visiblePerLine` descriptors were on the line — the canonical per-line
  * sprite-overflow signal (NES-style). Kept as information only; no
  * adapter-semantic commitment in this task.
  *
  * No `Mem` — purely register/combinational logic. GT-022 N/A.
  */
case class SpriteEvaluator(
    descCount: Int = 4,
    visiblePerLine: Int = 2,
    patternSelBits: Int = 1
) extends Component {
  require(descCount >= 2, "descCount must be at least 2")
  require(visiblePerLine >= 1 && visiblePerLine <= descCount,
          "visiblePerLine out of range")

  val descIdxBits = log2Up(descCount)

  val io = new Bundle {
    // Descriptor store (external source; stable during line)
    val descX          = in Vec(UInt(10 bits), descCount)
    val descY          = in Vec(UInt(10 bits), descCount)
    val descEnabled    = in Vec(Bool(), descCount)
    val descPatternIdx = in Vec(UInt(patternSelBits bits), descCount)

    // Pass 1 trigger — line to evaluate, one-cycle strobe
    val evalLine  = in UInt(10 bits)
    val evalStart = in Bool()

    // Pass 2 outputs (line-stable)
    val activeValid      = out Vec(Bool(), visiblePerLine)
    val activeX          = out Vec(UInt(10 bits), visiblePerLine)
    val activeRow        = out Vec(UInt(4 bits), visiblePerLine)
    val activePatternIdx = out Vec(UInt(patternSelBits bits), visiblePerLine)

    // Diagnostic: per-line overflow flag (sticky through the line; cleared on next evalStart)
    val overflowFlag = out Bool()
  }

  // ----- Per-descriptor on-line check (combinational) -----
  val onLine = Vec(Bool(), descCount)
  for (d <- 0 until descCount) {
    onLine(d) := io.descEnabled(d) &&
                 (io.evalLine >= io.descY(d)) &&
                 (io.evalLine < (io.descY(d) + U(16, 10 bits)))
  }

  // Count on-line descriptors for overflow detection.
  val onLineCount = CountOne(onLine)

  // ----- Priority selection for the 2 slots (lowest descIdx wins) -----
  // Build priority-encoded selections: slot(i) = the i-th smallest d with onLine(d).
  // For visiblePerLine=2, unroll manually: slot0 = lowest, slot1 = second-lowest.
  val slotValid   = Vec(Bool(), visiblePerLine)
  val slotDescIdx = Vec(UInt(descIdxBits bits), visiblePerLine)
  for (s <- 0 until visiblePerLine) {
    slotValid(s)   := False
    slotDescIdx(s) := 0
  }

  // Slot 0 = lowest on-line descriptor.
  for (d <- (descCount - 1) to 0 by -1) {
    when(onLine(d)) {
      slotValid(0)   := True
      slotDescIdx(0) := U(d, descIdxBits bits)
    }
  }
  // Slot 1 = lowest on-line descriptor with index strictly greater than slot0's.
  // Iterating high→low with last-assignment-wins; guarding by d > slot0DescIdx
  // gives the desired second-lowest.
  if (visiblePerLine >= 2) {
    for (d <- (descCount - 1) to 0 by -1) {
      val dU = U(d, descIdxBits bits)
      when(onLine(d) && slotValid(0) && dU > slotDescIdx(0)) {
        slotValid(1)   := True
        slotDescIdx(1) := dU
      }
    }
  }

  // ----- Latch active list on evalStart -----
  val activeValidReg      = Vec(Reg(Bool()) init False, visiblePerLine)
  val activeXReg          = Vec(Reg(UInt(10 bits)) init 0, visiblePerLine)
  val activeRowReg        = Vec(Reg(UInt(4 bits)) init 0, visiblePerLine)
  val activePatternIdxReg = Vec(Reg(UInt(patternSelBits bits)) init 0, visiblePerLine)
  val overflowReg         = Reg(Bool()) init False

  when(io.evalStart) {
    for (s <- 0 until visiblePerLine) {
      val d = slotDescIdx(s)
      activeValidReg(s)      := slotValid(s)
      activeXReg(s)          := io.descX(d)
      activeRowReg(s)        := (io.evalLine - io.descY(d)).resize(4)
      activePatternIdxReg(s) := io.descPatternIdx(d)
    }
    overflowReg := onLineCount > U(visiblePerLine, onLineCount.getWidth bits)
  }

  io.activeValid      := activeValidReg
  io.activeX          := activeXReg
  io.activeRow        := activeRowReg
  io.activePatternIdx := activePatternIdxReg
  io.overflowFlag     := overflowReg
}
