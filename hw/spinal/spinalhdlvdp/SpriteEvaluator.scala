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
  *     (affineEnable hardwired False — legacy flat path; Hardening fields
  *     hardwired to defaults).
  *   - Slots `[legacyIoCount .. descCount)` Reg-backed, programmable via
  *     the `bus*` write port. Bus layout is 16 words per slot:
  *       word 0: {enabled[15], patIdx[3:0]@[14:11], affineEnable[10], y[9:0]}
  *       (Task 53 — patIdx high bits live in word 8 [1:0]; with
  *        patternSelBits=4 they are unused/zero so legacy hosts are
  *        unchanged.)
  *       word 1: {_[15:10], x[9:0]}
  *       word 2: matrixA[15:0]   (Q8.8 signed)
  *       word 3: matrixB[15:0]
  *       word 4: matrixC[15:0]
  *       word 5: matrixD[15:0]
  *       word 6: transX[15:0]    (Q10.6 signed)
  *       word 7: transY[15:0]
  *       word 8: {sizeSel[15:14], paletteBank[13:11], priority[10:9],
  *                flipH[8], flipV[7], bppSel[6:5], mask[4], _[3:2],
  *                patIdx[5:4]@[1:0]}
  *                — Sprite Envelope Hardening fields (CyanPeak #8577 §4.3)
  *                + Phase 2 extensions (CyanPeak #8614): priority widened
  *                  1→2 bits, bppSel new (4/2/1 bpp pattern format).
  *                + Task 53 (#9419): bits [1:0] carry patIdx[5:4] when
  *                  patternSelBits > 4. Legacy hosts that always wrote
  *                  zero into [4:0] keep working unchanged.
  *       words 9..15: reserved (zeroed by hardware on read; ignored on
  *                write so future extension can claim them without
  *                breaking the host bus protocol).
  *
  * Host maps:
  *   - words 0..7  via `0x0800 + slot*8 + word`     (legacy slot block)
  *   - word 8      via `0x0D20 + slot`              (Hardening extension;
  *                                                   relocated from 0x0900
  *                                                   to avoid the L0
  *                                                   scroll-table block in
  *                                                   sc31, then from 0x0C00
  *                                                   to avoid the Blitter
  *                                                   range 0x0C00..0x0D0F.
  *                                                   Decode at
  *                                                   VdpTop.scala:1163-1171.)
  */
case class SpriteEvaluator(
    descCount: Int = 64,
    visiblePerLine: Int = 32,
    patternSelBits: Int = 6,
    legacyIoCount: Int = 4
) extends Component {
  require(descCount >= legacyIoCount, "descCount must be ≥ legacyIoCount")
  require(visiblePerLine >= 1 && visiblePerLine <= descCount,
          "visiblePerLine out of range")

  val descIdxBits = log2Up(descCount)
  val slotBits    = log2Up(visiblePerLine)
  val extCount    = descCount - legacyIoCount
  val busWordBits = 4   // bumped 3 → 4 to host word 8 (Hardening fields)

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
    val activeRow          = out Vec(UInt(6 bits), visiblePerLine)   // 6 bits to span 64×64 sizeSel=11
    val activePatternIdx   = out Vec(UInt(patternSelBits bits), visiblePerLine)
    // Task 37 affine outputs.
    val activeAffineEnable = out Vec(Bool(), visiblePerLine)
    val activeMatrixA      = out Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixB      = out Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixC      = out Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixD      = out Vec(Bits(16 bits), visiblePerLine)
    val activeTransX       = out Vec(Bits(16 bits), visiblePerLine)
    val activeTransY       = out Vec(Bits(16 bits), visiblePerLine)

    // Sprite Envelope Hardening (CyanPeak #8577) + Phase 2 (#8614) outputs.
    val activeFlipH        = out Vec(Bool(), visiblePerLine)
    val activeFlipV        = out Vec(Bool(), visiblePerLine)
    // Task 55 (#9440) — Genesis sprite-mask bit propagated to compositor.
    val activeMask         = out Vec(Bool(), visiblePerLine)
    // Task 55 — smallest slot index with `mask=1` in the current active
    // list, defaulting to `visiblePerLine` (= "no masking sprite this
    // line"). The compositor uses this to suppress all slots with index
    // strictly greater than `firstMaskSlot` per Genesis sprite-mask
    // semantics ("suppress all sprites with lower display priority on
    // that scanline"; lower display priority == higher slot index in
    // the existing rasterizer slot order).
    val firstMaskSlot      = out UInt(log2Up(visiblePerLine + 1) bits)
    val activePaletteBank  = out Vec(UInt(3 bits), visiblePerLine)
    val activePriority     = out Vec(UInt(2 bits), visiblePerLine)   // P2-3b: 1→2 bits
    val activeSizeSel      = out Vec(UInt(2 bits), visiblePerLine)
    val activeBppSel       = out Vec(UInt(2 bits), visiblePerLine)   // P2-2: new field

    val overflowFlag = out Bool()

    // Task 2c — narrow active-list RAM read port for the SpriteRasterizer.
    // Pass 1 packs each on-line descriptor into a single 128-bit slot word
    // and writes it sequentially into `activeListMem` at indices 0..count-1.
    // The rasterizer drives `activeReadAddr` and consumes `activeReadData`
    // (combinational), bounded by `activeCount`. Removes ~4.3k FFs at V=32
    // by collapsing the per-slot active*Reg Vecs into one shared Mem.
    val activeReadAddr = in  UInt(log2Up(visiblePerLine) bits)
    val activeReadData = out Bits(SpriteEvaluator.SlotPackedW bits)
    val activeCountOut = out UInt(log2Up(visiblePerLine + 1) bits)
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

  // Sprite Envelope Hardening (CyanPeak #8577) — default sizeSel = 1 (16×16)
  // matches the pre-Hardening 16-pixel-tall Y-range, so existing scenes that
  // never write word 8 retain bit-identical behaviour.
  val regFlipH        = Vec.fill(extCount)(RegInit(False))
  val regFlipV        = Vec.fill(extCount)(RegInit(False))
  // Task 55 (#9440) — Genesis sprite-mask bit at word 8 [4].
  val regMask         = Vec.fill(extCount)(RegInit(False))
  val regPaletteBank  = Vec.fill(extCount)(RegInit(U(0, 3 bits)))
  val regPriority     = Vec.fill(extCount)(RegInit(U(0, 2 bits)))    // P2-3b: 1→2 bits
  val regSizeSel      = Vec.fill(extCount)(RegInit(U(SpriteDescriptor.DefaultSizeSel, 2 bits)))
  val regBppSel       = Vec.fill(extCount)(RegInit(U(0, 2 bits)))    // P2-2: 4bpp default

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
      // Task 53 — word 0 always carries the LOW 4 bits of patIdx at
      // [14:11]; the high bits live in word 8 [1:0]. With
      // `patternSelBits = 4` (legacy) the .resize(4) is a no-op.
      val patW0Low = io.busData(14 downto 11).asUInt
      val affW0 = io.busData(10)
      val yW0   = io.busData(9 downto 0).asUInt
      val xW1   = io.busData(9 downto 0).asUInt
      switch(rel) {
        for (i <- 0 until extCount) {
          is(U(i, log2Up(extCount) bits)) {
            switch(io.busWord) {
              is(U(0, busWordBits bits)) {
                regEnabled(i)      := enBit
                if (patternSelBits > 4) {
                  // Update only patIdx[3:0]; preserve patIdx[5:4] from a
                  // previous word-8 write so the host can write the two
                  // halves in either order.
                  regPatternIndex(i)(3 downto 0) := patW0Low
                } else {
                  regPatternIndex(i) := patW0Low.resize(patternSelBits)
                }
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
              is(U(8, busWordBits bits)) {
                regSizeSel(i)     := io.busData(15 downto 14).asUInt
                regPaletteBank(i) := io.busData(13 downto 11).asUInt
                regPriority(i)    := io.busData(10 downto 9).asUInt   // P2-3b: 2 bits
                regFlipH(i)       := io.busData(8)
                regFlipV(i)       := io.busData(7)
                regMask(i)        := io.busData(4)   // Task 55 — Genesis mask bit
                regBppSel(i)      := io.busData(6 downto 5).asUInt    // P2-2: new
                if (patternSelBits > 4) {
                  // Task 53 — patIdx high bits at [1:0]. Width is
                  // (patternSelBits - 4) — 2 bits for Option A, more if
                  // Option B is later opened. Preserves low 4 bits set
                  // by a prior word-0 write.
                  val highW = patternSelBits - 4
                  regPatternIndex(i)(patternSelBits - 1 downto 4) :=
                    io.busData(highW - 1 downto 0).asUInt
                }
              }
              // words 9..15 reserved — no write effect; reads omitted.
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
  // Sprite Envelope Hardening — legacy slots get back-compat defaults.
  def descFlipH(i: Int): Bool =
    if (i < legacyIoCount) False else regFlipH(i - legacyIoCount)
  def descFlipV(i: Int): Bool =
    if (i < legacyIoCount) False else regFlipV(i - legacyIoCount)
  // Task 55 — legacy IO slots have no mask bit.
  def descMask(i: Int): Bool =
    if (i < legacyIoCount) False else regMask(i - legacyIoCount)
  def descPaletteBank(i: Int): UInt =
    if (i < legacyIoCount) U(0, 3 bits) else regPaletteBank(i - legacyIoCount)
  def descPriority(i: Int): UInt =
    if (i < legacyIoCount) U(0, 2 bits) else regPriority(i - legacyIoCount)
  def descSizeSel(i: Int): UInt =
    if (i < legacyIoCount) U(SpriteDescriptor.DefaultSizeSel, 2 bits)
    else                   regSizeSel(i - legacyIoCount)
  def descBppSel(i: Int): UInt =
    if (i < legacyIoCount) U(0, 2 bits) else regBppSel(i - legacyIoCount)

  // sizeForSel lives on the SpriteDescriptor companion so VdpTop's
  // per-slot pattern-fetch loop can share the same encoding.

  // ---------------------------------------------------------------------
  // Sequential Pass-1 FSM.
  // ---------------------------------------------------------------------
  val scanIdx        = Reg(UInt(descIdxBits bits)) init 0
  val activeCount    = Reg(UInt(log2Up(visiblePerLine + 1) bits)) init 0
  val totalOnLine    = Reg(UInt(log2Up(descCount + 1) bits)) init 0
  val scanBusy       = Reg(Bool()) init False

  // Sprite Phase 2 — P2-4 (CyanPeak #8614): SNES-style tile-fetch budget
  // counter. Sums (size/8)² 8×8-tile equivalents per on-line sprite,
  // overflows when the line demand exceeds 34 tiles. Sized at 10 bits to
  // hold up to descCount × 64 = 32 × 64 = 2048 worst-case (all 64×64
  // sprites on the same line).
  val TileBudget    = 34
  val tileCountReg  = Reg(UInt(11 bits)) init 0
  def tilesForSize(sel: UInt): UInt = {
    val out = UInt(7 bits)
    switch(sel) {
      is(U(0, 2 bits)) { out := 1  }   //  8×8 →  1 tile
      is(U(1, 2 bits)) { out := 4  }   // 16×16 →  4 tiles
      is(U(2, 2 bits)) { out := 16 }   // 32×32 → 16 tiles
      is(U(3, 2 bits)) { out := 64 }   // 64×64 → 64 tiles
    }
    out
  }

  // Task 2c final cleanup: legacy active*Reg Vecs removed. The active list
  // is stored in `activeListMem` (declared below); legacy IO Vec outputs
  // are driven by per-slot combinational Mem reads. activeY is dead and
  // also removed.
  val overflowFlagReg       = Reg(Bool()) init False

  when(io.evalStart) {
    scanIdx      := 0
    activeCount  := 0
    totalOnLine  := 0
    tileCountReg := 0    // Phase 2 P2-4: reset tile-budget counter per line
    scanBusy     := True
    // Task 2c: no per-slot activeValidReg clearing needed — validity is
    // implicit via `s < activeCount`, which goes to 0 here.
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
  val curFlipH        = Bool();                   curFlipH := False
  val curFlipV        = Bool();                   curFlipV := False
  val curMask         = Bool();                   curMask  := False   // Task 55
  val curPaletteBank  = UInt(3 bits);             curPaletteBank := 0
  val curPriority     = UInt(2 bits);             curPriority := U(0, 2 bits)
  val curSizeSel      = UInt(2 bits);             curSizeSel := U(SpriteDescriptor.DefaultSizeSel, 2 bits)
  val curBppSel       = UInt(2 bits);             curBppSel := U(0, 2 bits)
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
          curFlipH        := descFlipH(i)
          curFlipV        := descFlipV(i)
          curMask         := descMask(i)
          curPaletteBank  := descPaletteBank(i)
          curPriority     := descPriority(i)
          curSizeSel      := descSizeSel(i)
          curBppSel       := descBppSel(i)
        }
      }
    }
  }
  // Sprite Envelope Hardening: Y-range is now `[y, y + sizeForSel(sizeSel))`
  // instead of the prior fixed-16 height. sizeForSel returns 8/16/32/64.
  val curSize  = SpriteDescriptor.sizeForSel(curSizeSel)         // 7 bits, max 64
  val dOnLine  = scanBusy && curEnabled(0) &&
                 (io.evalLine >= curY) &&
                 (io.evalLine < (curY + curSize.resize(10)))

  when(scanBusy) {
    when(dOnLine) {
      totalOnLine  := totalOnLine + 1
      // Phase 2 P2-4: every on-line sprite contributes its tile demand to
      // the budget regardless of whether it gets a visible slot — this
      // matches the SNES OAM evaluation cost model where the limit is on
      // tile fetches, not on visible-slot allocation.
      tileCountReg := tileCountReg + tilesForSize(curSizeSel).resize(11)
      when(activeCount < U(visiblePerLine, activeCount.getWidth bits)) {
        // Task 2c: per-slot Vec writes removed. The active-list Mem
        // write below (single packed-word write at addr=activeCount)
        // replaces the 16 per-Vec assignments.
        activeCount := activeCount + 1
      }
    }
    when(scanIdx === U(descCount - 1, descIdxBits bits)) {
      scanBusy := False
      // Phase 2 P2-4: overflow now fires on EITHER (a) more sprites on
      // line than visiblePerLine, or (b) per-line tile demand > 34 (SNES
      // budget). The two cases share the same status bit.
      val finalTileCount = tileCountReg +
        Mux(dOnLine, tilesForSize(curSizeSel).resize(11), U(0, 11 bits))
      val capacityOver   = (totalOnLine +
        Mux(dOnLine, U(1, totalOnLine.getWidth bits), U(0, totalOnLine.getWidth bits))) >
        U(visiblePerLine, totalOnLine.getWidth bits)
      val tileBudgetOver = finalTileCount > U(TileBudget, 11 bits)
      overflowFlagReg := capacityOver || tileBudgetOver
    } otherwise {
      scanIdx := scanIdx + 1
    }
  }

  io.overflowFlag       := overflowFlagReg

  // ============================================================
  // Task 2c — Active-list RAM (parallel back-end, Phase 1 of cutover)
  //
  // The active*Reg Vecs above are the original FF-based storage; they
  // continue to drive the legacy IO Vec outputs for sim backward-compat.
  // In parallel, we maintain a Mem-based packed active list that the
  // SpriteRasterizer can read through `io.activeReadAddr/Data/Count`.
  //
  // Phase 2 (Checkpoint D) switches the rasterizer onto the RAM port;
  // Phase 3 (cleanup) removes the legacy active*Reg Vecs and the
  // legacy IO Vec outputs entirely — at which point the V=32 P&R
  // proof should land with zero unplaced REGs.
  //
  // Pack layout (MSB → LSB), per artifact appendix:
  //   [127:112] matrixA   (16)
  //   [111: 96] matrixB   (16)
  //   [ 95: 80] matrixC   (16)
  //   [ 79: 64] matrixD   (16)
  //   [ 63: 48] transX    (16)
  //   [ 47: 32] transY    (16)
  //   [ 31: 22] x         (10)
  //   [ 21: 16] row       (6)
  //   [ 15: 12] patIdx    (4)
  //   [ 11:  9] paletteBank (3)
  //   [  8:  7] priority  (2)
  //   [  6:  5] sizeSel   (2)
  //   [  4:  3] bppSel    (2)
  //   [  2]     affineEnable (1)
  //   [  1]     flipH     (1)
  //   [  0]     flipV     (1)
  // (activeY is omitted — dead since Task 2a Step 2.)
  val activeListMem = Mem(Bits(SpriteEvaluator.SlotPackedW bits), visiblePerLine)

  val packedSlot = SpriteEvaluator.packSlot(
    matrixA   = curMatrixA,
    matrixB   = curMatrixB,
    matrixC   = curMatrixC,
    matrixD   = curMatrixD,
    transX    = curTransX,
    transY    = curTransY,
    x         = curX,
    row       = (io.evalLine - curY).resize(6),
    patIdx    = curPat.resize(SpriteEvaluator.PatIdxWidth),
    paletteBank = curPaletteBank,
    priority  = curPriority,
    sizeSel   = curSizeSel,
    bppSel    = curBppSel,
    affineEnable = curAffineEnable,
    flipH     = curFlipH,
    flipV     = curFlipV,
    mask      = curMask
  )
  // Mem write occurs in the same conditions as the legacy active*Reg
  // Vec writes — gated on (scanBusy && dOnLine && activeCount<visiblePerLine).
  val memWrite = scanBusy && dOnLine && (activeCount < U(visiblePerLine, activeCount.getWidth bits))
  activeListMem.write(
    address = activeCount.resize(log2Up(visiblePerLine)),
    data    = packedSlot,
    enable  = memWrite
  )

  io.activeReadData := activeListMem.readAsync(io.activeReadAddr)
  io.activeCountOut := activeCount

  // Legacy IO Vec outputs — combinational per-slot reads of activeListMem.
  // Preserves backward-compat for SpriteEvaluatorSim's per-slot probes
  // (dut.io.activeX(s), etc.) without the FF-density cost of the prior
  // active*Reg Vec storage.
  for (s <- 0 until visiblePerLine) {
    val w = activeListMem.readAsync(U(s, log2Up(visiblePerLine) bits))
    io.activeValid(s)        := U(s, log2Up(visiblePerLine + 1) bits) < activeCount
    io.activeX(s)            := SpriteEvaluator.slotX(w)
    io.activeY(s)            := U(0, 10 bits)   // dead since Task 2a Step 2
    io.activeRow(s)          := SpriteEvaluator.slotRow(w)
    io.activePatternIdx(s)   := SpriteEvaluator.slotPatIdx(w).resize(patternSelBits)
    io.activeAffineEnable(s) := SpriteEvaluator.slotAffineEnable(w)
    io.activeMatrixA(s)      := SpriteEvaluator.slotMatrixA(w)
    io.activeMatrixB(s)      := SpriteEvaluator.slotMatrixB(w)
    io.activeMatrixC(s)      := SpriteEvaluator.slotMatrixC(w)
    io.activeMatrixD(s)      := SpriteEvaluator.slotMatrixD(w)
    io.activeTransX(s)       := SpriteEvaluator.slotTransX(w)
    io.activeTransY(s)       := SpriteEvaluator.slotTransY(w)
    io.activeFlipH(s)        := SpriteEvaluator.slotFlipH(w)
    io.activeFlipV(s)        := SpriteEvaluator.slotFlipV(w)
    io.activeMask(s)         := SpriteEvaluator.slotMask(w)
    io.activePaletteBank(s)  := SpriteEvaluator.slotPaletteBank(w)
    io.activePriority(s)     := SpriteEvaluator.slotPriority(w)
    io.activeSizeSel(s)      := SpriteEvaluator.slotSizeSel(w)
    io.activeBppSel(s)       := SpriteEvaluator.slotBppSel(w)
  }

  // Task 55 — combinational priority encoder over `activeMask` Vec
  // returning the lowest active slot index with mask=1, or
  // `visiblePerLine` if no active masking sprite. Reverse-then-overwrite
  // pattern relies on SpinalHDL's last-assignment-wins semantics so the
  // smallest matching index ends up retained.
  private val firstMaskSlotW = log2Up(visiblePerLine + 1)
  io.firstMaskSlot := U(visiblePerLine, firstMaskSlotW bits)
  for (s <- (visiblePerLine - 1) to 0 by -1) {
    when(io.activeMask(s) &&
         (U(s, firstMaskSlotW bits) < activeCount.resize(firstMaskSlotW))) {
      io.firstMaskSlot := U(s, firstMaskSlotW bits)
    }
  }
}

object SpriteEvaluator {
  // Task 2c — packed active-slot word width and field offsets.
  // Task 53 (#9419) — `PatIdxWidth` widened 4→6, `SlotPackedW` 128→130.
  // Task 55 (#9440) — Genesis sprite-mask bit appended at MSB; `SlotPackedW` 130→131.
  val PatIdxWidth: Int = 6
  val SlotPackedW: Int = 1 + 96 + 10 + 6 + PatIdxWidth + 3 + 2 + 2 + 2 + 1 + 1 + 1   // = 131

  // Pack a slot's fields into a `SlotPackedW`-bit word.
  def packSlot(
      matrixA: Bits, matrixB: Bits, matrixC: Bits, matrixD: Bits,
      transX: Bits, transY: Bits,
      x: UInt, row: UInt,
      patIdx: UInt, paletteBank: UInt, priority: UInt,
      sizeSel: UInt, bppSel: UInt,
      affineEnable: Bool, flipH: Bool, flipV: Bool,
      mask: Bool): Bits = {
    mask.asBits ##
    matrixA ## matrixB ## matrixC ## matrixD ##
    transX  ## transY  ##
    x.asBits.resize(10) ## row.asBits.resize(6) ##
    patIdx.asBits.resize(PatIdxWidth) ## paletteBank.asBits.resize(3) ##
    priority.asBits.resize(2) ##
    sizeSel.asBits.resize(2) ## bppSel.asBits.resize(2) ##
    affineEnable.asBits ## flipH.asBits ## flipV.asBits
  }

  // Field-extraction helpers (slot word from `activeReadData`).
  // Bit positions match `packSlot` above. Total = 131 bits.
  //   [130]     mask          (1)   ← Task 55
  //   [129:114] matrixA       (16)
  //   [113: 98] matrixB       (16)
  //   [ 97: 82] matrixC       (16)
  //   [ 81: 66] matrixD       (16)
  //   [ 65: 50] transX        (16)
  //   [ 49: 34] transY        (16)
  //   [ 33: 24] x             (10)
  //   [ 23: 18] row           (6)
  //   [ 17: 12] patIdx        (6)   ← Task 53
  //   [ 11:  9] paletteBank   (3)
  //   [  8:  7] priority      (2)
  //   [  6:  5] sizeSel       (2)
  //   [  4:  3] bppSel        (2)
  //   [  2]     affineEnable  (1)
  //   [  1]     flipH         (1)
  //   [  0]     flipV         (1)
  def slotMatrixA(w: Bits)   : Bits = w(129 downto 114)
  def slotMatrixB(w: Bits)   : Bits = w(113 downto  98)
  def slotMatrixC(w: Bits)   : Bits = w( 97 downto  82)
  def slotMatrixD(w: Bits)   : Bits = w( 81 downto  66)
  def slotTransX (w: Bits)   : Bits = w( 65 downto  50)
  def slotTransY (w: Bits)   : Bits = w( 49 downto  34)
  def slotX      (w: Bits)   : UInt = w( 33 downto  24).asUInt
  def slotRow    (w: Bits)   : UInt = w( 23 downto  18).asUInt
  def slotPatIdx (w: Bits)   : UInt = w( 17 downto  12).asUInt
  def slotPaletteBank(w: Bits): UInt = w(11 downto   9).asUInt
  def slotPriority(w: Bits)  : UInt = w(  8 downto   7).asUInt
  def slotSizeSel(w: Bits)   : UInt = w(  6 downto   5).asUInt
  def slotBppSel (w: Bits)   : UInt = w(  4 downto   3).asUInt
  def slotAffineEnable(w: Bits): Bool = w(2)
  def slotFlipH  (w: Bits)   : Bool = w(1)
  def slotFlipV  (w: Bits)   : Bool = w(0)
  def slotMask   (w: Bits)   : Bool = w(130)   // Task 55
}
