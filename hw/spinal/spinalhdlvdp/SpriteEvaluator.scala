package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Task 28 — Two-Pass Sprite Evaluator + Task 37 affine extension.
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
  * Descriptor storage architecture:
  *   - Slots `[0 .. legacyIoCount)` driven from io `desc*` input Vecs
  *     (affineEnable hardwired False — legacy flat path).
  *   - Slots `[legacyIoCount .. descCount)` Reg-backed, programmable via
  *     the `bus*` write port. Bus layout is 8 words per slot:
  *       word 0: {enabled[15], patIdx[14:11], affineEnable[10], y[9:0]}
  *       word 1: {_[15:10], x[9:0]}
  *       word 2: matrixA[15:0]   (Q8.8 signed)
  *       word 3: matrixB[15:0]
  *       word 4: matrixC[15:0]
  *       word 5: matrixD[15:0]
  *       word 6: transX[15:0]    (Q10.6 signed)
  *       word 7: transY[15:0]
  *
  * Host maps the Mode0 bus block `0x0800 + slot*8 + word` to these words.
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
  val busWordBits = 3

  val io = new Bundle {
    // Legacy IO descriptor ports — slots 0..legacyIoCount-1.
    val descX          = in Vec(UInt(10 bits), legacyIoCount)
    val descY          = in Vec(UInt(10 bits), legacyIoCount)
    val descEnabled    = in Vec(Bool(), legacyIoCount)
    val descPatternIdx = in Vec(UInt(patternSelBits bits), legacyIoCount)

    // Bus-write port — populates the Reg-backed slots [legacyIoCount..descCount).
    val busSlot = in UInt(descIdxBits bits)
    val busWord = in UInt(busWordBits bits)
    val busData = in Bits(16 bits)
    val busWr   = in Bool()

    // Pass 1 trigger.
    val evalLine  = in UInt(10 bits)
    val evalStart = in Bool()

    // Pass 2 outputs (line-stable across next line).
    val activeValid        = out Vec(Bool(), visiblePerLine)
    val activeX            = out Vec(UInt(10 bits), visiblePerLine)
    val activeY            = out Vec(UInt(10 bits), visiblePerLine)
    val activeRow          = out Vec(UInt(4 bits), visiblePerLine)
    val activePatternIdx   = out Vec(UInt(patternSelBits bits), visiblePerLine)
    // Task 37 affine outputs.
    val activeAffineEnable = out Vec(Bool(), visiblePerLine)
    val activeMatrixA      = out Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixB      = out Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixC      = out Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixD      = out Vec(Bits(16 bits), visiblePerLine)
    val activeTransX       = out Vec(Bits(16 bits), visiblePerLine)
    val activeTransY       = out Vec(Bits(16 bits), visiblePerLine)

    val overflowFlag = out Bool()
  }

  // ---------------------------------------------------------------------
  // Reg-backed extended descriptors.
  // ---------------------------------------------------------------------
  val regEnabled      = Vec.fill(extCount)(RegInit(False))
  val regX            = Vec.fill(extCount)(RegInit(U(1023, 10 bits)))
  val regY            = Vec.fill(extCount)(RegInit(U(1023, 10 bits)))
  val regPatternIndex = Vec.fill(extCount)(RegInit(U(0, patternSelBits bits)))
  val regAffineEnable = Vec.fill(extCount)(RegInit(False))
  val regMatrixA      = Vec.fill(extCount)(RegInit(B(0, 16 bits)))
  val regMatrixB      = Vec.fill(extCount)(RegInit(B(0, 16 bits)))
  val regMatrixC      = Vec.fill(extCount)(RegInit(B(0, 16 bits)))
  val regMatrixD      = Vec.fill(extCount)(RegInit(B(0, 16 bits)))
  val regTransX       = Vec.fill(extCount)(RegInit(B(0, 16 bits)))
  val regTransY       = Vec.fill(extCount)(RegInit(B(0, 16 bits)))

  // simPublic for integration sims.
  for (i <- 0 until extCount) {
    regEnabled(i).simPublic()
    regX(i).simPublic()
    regY(i).simPublic()
    regPatternIndex(i).simPublic()
    regAffineEnable(i).simPublic()
    regMatrixA(i).simPublic()
    regMatrixB(i).simPublic()
    regMatrixC(i).simPublic()
    regMatrixD(i).simPublic()
    regTransX(i).simPublic()
    regTransY(i).simPublic()
    regEnabled(i).addAttribute("syn_keep", "1")
    regX(i).addAttribute("syn_keep", "1")
    regY(i).addAttribute("syn_keep", "1")
    regPatternIndex(i).addAttribute("syn_keep", "1")
  }

  when(io.busWr) {
    val slot = io.busSlot
    when(slot >= U(legacyIoCount, descIdxBits bits)) {
      val rel = (slot - U(legacyIoCount, descIdxBits bits)).resize(log2Up(extCount))
      val enBit = io.busData(15)
      val patW0 = io.busData(14 downto (15 - patternSelBits)).asUInt
      val affW0 = io.busData(10)
      val yW0   = io.busData(9 downto 0).asUInt
      val xW1   = io.busData(9 downto 0).asUInt
      switch(rel) {
        for (i <- 0 until extCount) {
          is(U(i, log2Up(extCount) bits)) {
            switch(io.busWord) {
              is(U(0, busWordBits bits)) {
                regEnabled(i)      := enBit
                regPatternIndex(i) := patW0
                regAffineEnable(i) := affW0
                regY(i)            := yW0
              }
              is(U(1, busWordBits bits)) { regX(i)       := xW1 }
              is(U(2, busWordBits bits)) { regMatrixA(i) := io.busData }
              is(U(3, busWordBits bits)) { regMatrixB(i) := io.busData }
              is(U(4, busWordBits bits)) { regMatrixC(i) := io.busData }
              is(U(5, busWordBits bits)) { regMatrixD(i) := io.busData }
              is(U(6, busWordBits bits)) { regTransX(i)  := io.busData }
              is(U(7, busWordBits bits)) { regTransY(i)  := io.busData }
            }
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------
  // Unified descriptor read path.
  // ---------------------------------------------------------------------
  def descEnabled(i: Int): Bool =
    if (i < legacyIoCount) io.descEnabled(i) else regEnabled(i - legacyIoCount)
  def descX(i: Int): UInt =
    if (i < legacyIoCount) io.descX(i) else regX(i - legacyIoCount)
  def descY(i: Int): UInt =
    if (i < legacyIoCount) io.descY(i) else regY(i - legacyIoCount)
  def descPatternIdx(i: Int): UInt =
    if (i < legacyIoCount) io.descPatternIdx(i).resize(patternSelBits)
    else                   regPatternIndex(i - legacyIoCount)
  def descAffineEnable(i: Int): Bool =
    if (i < legacyIoCount) False else regAffineEnable(i - legacyIoCount)
  def descMatrix(i: Int, sel: Int): Bits = {
    if (i < legacyIoCount) B(0, 16 bits)
    else sel match {
      case 0 => regMatrixA(i - legacyIoCount)
      case 1 => regMatrixB(i - legacyIoCount)
      case 2 => regMatrixC(i - legacyIoCount)
      case 3 => regMatrixD(i - legacyIoCount)
      case 4 => regTransX(i - legacyIoCount)
      case 5 => regTransY(i - legacyIoCount)
    }
  }

  // ---------------------------------------------------------------------
  // Sequential Pass-1 FSM.
  // ---------------------------------------------------------------------
  val scanIdx        = Reg(UInt(descIdxBits bits)) init 0
  val activeCount    = Reg(UInt(log2Up(visiblePerLine + 1) bits)) init 0
  val totalOnLine    = Reg(UInt(log2Up(descCount + 1) bits)) init 0
  val scanBusy       = Reg(Bool()) init False

  val activeValidReg        = Vec.fill(visiblePerLine)(RegInit(False))
  val activeXReg            = Vec.fill(visiblePerLine)(RegInit(U(0, 10 bits)))
  val activeYReg            = Vec.fill(visiblePerLine)(RegInit(U(0, 10 bits)))
  val activeRowReg          = Vec.fill(visiblePerLine)(RegInit(U(0, 4 bits)))
  val activePatternReg      = Vec.fill(visiblePerLine)(RegInit(U(0, patternSelBits bits)))
  val activeAffineEnableReg = Vec.fill(visiblePerLine)(RegInit(False))
  val activeMatrixAReg      = Vec.fill(visiblePerLine)(RegInit(B(0, 16 bits)))
  val activeMatrixBReg      = Vec.fill(visiblePerLine)(RegInit(B(0, 16 bits)))
  val activeMatrixCReg      = Vec.fill(visiblePerLine)(RegInit(B(0, 16 bits)))
  val activeMatrixDReg      = Vec.fill(visiblePerLine)(RegInit(B(0, 16 bits)))
  val activeTransXReg       = Vec.fill(visiblePerLine)(RegInit(B(0, 16 bits)))
  val activeTransYReg       = Vec.fill(visiblePerLine)(RegInit(B(0, 16 bits)))
  val overflowFlagReg       = Reg(Bool()) init False

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
  val curEnabled      = UInt(1 bit);              curEnabled := 0
  val curX            = UInt(10 bits);            curX := 0
  val curY            = UInt(10 bits);            curY := 0
  val curPat          = UInt(patternSelBits bits); curPat := 0
  val curAffineEnable = Bool();                   curAffineEnable := False
  val curMatrixA      = Bits(16 bits);            curMatrixA := 0
  val curMatrixB      = Bits(16 bits);            curMatrixB := 0
  val curMatrixC      = Bits(16 bits);            curMatrixC := 0
  val curMatrixD      = Bits(16 bits);            curMatrixD := 0
  val curTransX       = Bits(16 bits);            curTransX := 0
  val curTransY       = Bits(16 bits);            curTransY := 0
  when(scanBusy) {
    switch(scanIdx) {
      for (i <- 0 until descCount) {
        is(U(i, descIdxBits bits)) {
          curEnabled      := descEnabled(i).asUInt
          curX            := descX(i)
          curY            := descY(i)
          curPat          := descPatternIdx(i)
          curAffineEnable := descAffineEnable(i)
          curMatrixA      := descMatrix(i, 0)
          curMatrixB      := descMatrix(i, 1)
          curMatrixC      := descMatrix(i, 2)
          curMatrixD      := descMatrix(i, 3)
          curTransX       := descMatrix(i, 4)
          curTransY       := descMatrix(i, 5)
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
        switch(slot) {
          for (s <- 0 until visiblePerLine) {
            is(U(s, slotBits bits)) {
              activeValidReg(s)        := True
              activeXReg(s)            := curX
              activeYReg(s)            := curY
              activeRowReg(s)          := (io.evalLine - curY).resize(4)
              activePatternReg(s)      := curPat
              activeAffineEnableReg(s) := curAffineEnable
              activeMatrixAReg(s)      := curMatrixA
              activeMatrixBReg(s)      := curMatrixB
              activeMatrixCReg(s)      := curMatrixC
              activeMatrixDReg(s)      := curMatrixD
              activeTransXReg(s)       := curTransX
              activeTransYReg(s)       := curTransY
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

  io.activeValid        := activeValidReg
  io.activeX            := activeXReg
  io.activeY            := activeYReg
  io.activeRow          := activeRowReg
  io.activePatternIdx   := activePatternReg
  io.activeAffineEnable := activeAffineEnableReg
  io.activeMatrixA      := activeMatrixAReg
  io.activeMatrixB      := activeMatrixBReg
  io.activeMatrixC      := activeMatrixCReg
  io.activeMatrixD      := activeMatrixDReg
  io.activeTransX       := activeTransXReg
  io.activeTransY       := activeTransYReg
  io.overflowFlag       := overflowFlagReg
}
