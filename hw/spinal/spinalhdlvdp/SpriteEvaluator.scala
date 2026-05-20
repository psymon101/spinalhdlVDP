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

    // Pass 2 outputs (line-stable across next line). All per-slot fields
    // are now consumed via the packed-word read port (`activeReadAddr` /
    // `activeReadData` below); the legacy per-slot `Vec` outputs have
    // been removed to drop activeListMem from 9 readAsync ports → 1 and
    // avoid the Gowin Mem→FF promotion documented in
    // PROJECT_PLAN/MODE0_T20_STRIP_ANALYSIS_CORALREEF.md ("RP0001
    // Mem→FF promotion: CRITICAL"). The mask priority encoder uses a
    // small shadow Reg vector written alongside `activeListMem.write`.
    //
    // Task 55 — smallest slot index with `mask=1` in the current active
    // list, defaulting to `visiblePerLine` (= "no masking sprite this
    // line"). The compositor uses this to suppress all slots with index
    // strictly greater than `firstMaskSlot` per Genesis sprite-mask
    // semantics ("suppress all sprites with lower display priority on
    // that scanline"; lower display priority == higher slot index in
    // the existing rasterizer slot order).
    val firstMaskSlot      = out UInt(log2Up(visiblePerLine + 1) bits)

    val overflowFlag = out Bool()

    // Task 2c — narrow active-list RAM read port for the SpriteRasterizer.
    // Pass 1 packs each on-line descriptor into a single SlotPackedW-bit
    // word and writes it sequentially into `activeListMem` at indices
    // 0..count-1. The rasterizer drives `activeReadAddr` and consumes
    // `activeReadData` (combinational), bounded by `activeCount`. This
    // is the sole readAsync port on activeListMem; keeping it that way
    // is a synthesis-fragility invariant (see file header note above).
    val activeReadAddr = in  UInt(log2Up(visiblePerLine) bits)
    val activeReadData = out Bits(SpriteEvaluator.SlotPackedW bits)
    val activeCountOut = out UInt(log2Up(visiblePerLine + 1) bits)
  }

  // ---------------------------------------------------------------------
  // Reg-backed extended descriptors.
  // ---------------------------------------------------------------------
  // Task 57 Slice 3 (CyanPeak PASS #9597): per-slot non-matrix Vec[Reg]
  // arrays moved to three readAsync `Mem`s addressed by the bus-word
  // group that writes them — preserves single-write per cycle without
  // RMW. `regAffineEnable` is exempt and stays in Vec[Reg] per CyanPeak
  // ruling: it's read combinationally by the rasterizer's affine path
  // outside the scan FSM and the 28-DFF cost is negligible.
  //
  // Layouts (LSB→MSB, total 39 bits across 3 Mems):
  //   infoMemW0 (15 bits, written on busWord=0): {y[9:0], patIdxLow[3:0], enabled[1]}
  //   infoMemW1 (10 bits, written on busWord=1): {x[9:0]}
  //   infoMemW8 (14 bits, written on busWord=8): {patIdxHigh[1:0], mask[1], bppSel[2],
  //                                               flipV[1], flipH[1], priority[2],
  //                                               paletteBank[3], sizeSel[2]}
  // Each Mem has a single read port (addressed by scanIdx-rel) and a
  // single write port (addressed by busSlot-rel). Same pattern as
  // matAMem etc. from Slice 2 — single port preserves RAM inference.
  val InfoW0Width = 1 + patternSelBits.min(4) + 10   // enabled + patIdxLow + y
  val InfoW1Width = 10                                // x
  val InfoW8Width = 2 + 3 + 2 + 1 + 1 + 2 + 1 + (patternSelBits - 4).max(0)
                                                       // sizeSel + bank + prio + flipH + flipV + bppSel + mask + patIdxHigh
  // Parked-sprite boot defaults (preserved as initialContent): enabled=0,
  // x=1023, y=1023, patternIndex=0, sizeSel=DefaultSizeSel(=1), other
  // word-8 fields=0 — so an un-programmed descriptor reads as a 16×16
  // off-line sprite, the contract existing host code and sims rely on.
  //   w0 = (y=1023)<<(1+patLowBits) → 0x7FE0
  //   w1 = (x=1023)                 → 0x3FF
  //   w8 = sizeSel=1 at bits [1:0]  → 0x01
  // Storage move #10357: these Mems are read via `readSync` (scan FSM
  // below) so Gowin infers BSRAM. The earlier Mem→FF promotion was caused
  // by readAsync *with* initialContent — Gowin distributed RAM cannot
  // carry an init, so it fell back to DFFs. BSRAM does support
  // initialization, so the parked defaults are kept.
  private val w0InitVal = BigInt(1023) << (1 + patternSelBits.min(4))
  private val w1InitVal = BigInt(1023)
  private val w8InitVal = BigInt(SpriteDescriptor.DefaultSizeSel)
  val infoMemW0 = Mem(Bits(InfoW0Width bits),
    initialContent = Array.fill(extCount)(B(w0InitVal, InfoW0Width bits))).simPublic()
  val infoMemW1 = Mem(Bits(InfoW1Width bits),
    initialContent = Array.fill(extCount)(B(w1InitVal, InfoW1Width bits))).simPublic()
  val infoMemW8 = Mem(Bits(InfoW8Width bits),
    initialContent = Array.fill(extCount)(B(w8InitVal, InfoW8Width bits))).simPublic()
  val regAffineEnable = Vec.fill(extCount)(RegInit(False))
  // Task 57 Slice 2 (#9502 / #9487) + storage move #10357: the 6×16-bit
  // affine matrix state per slot lives in `Mem`s (was Vec[Reg]). Reads
  // are `readSync` (scan-FSM hoist below) so Gowin infers BSRAM; bus
  // writes target one Mem at a time on word matches 2..7. The readSync
  // latency is absorbed by the 2-cycle scan walk (see the scan FSM).
  val matAMem  = Mem(Bits(16 bits), extCount).simPublic()
  val matBMem  = Mem(Bits(16 bits), extCount).simPublic()
  val matCMem  = Mem(Bits(16 bits), extCount).simPublic()
  val matDMem  = Mem(Bits(16 bits), extCount).simPublic()
  val transXMem = Mem(Bits(16 bits), extCount).simPublic()
  val transYMem = Mem(Bits(16 bits), extCount).simPublic()
  // No initialContent on these Mems + `readSync` access → Gowin infers
  // BSRAM (block RAM). Manual syn_ramstyle is not set: it caused EX0200
  // "Property set invalid" (Task 57); default inference is correct.

  // Task 57 Slice 3: regFlipH/V/Mask, regPaletteBank, regPriority,
  // regSizeSel, regBppSel are now packed into infoMemW8 (read via the
  // helpers below). Removed Vec[Reg] declarations.

  // simPublic for sims that probe regAffineEnable directly. All other
  // per-slot regs are now in Mems; sims must probe via Mem.getBigInt
  // or via the active-list outputs.
  for (i <- 0 until extCount) {
    regAffineEnable(i).simPublic()
  }

  // Task 57 Slice 3: bus writes go to one of three Mems based on
  // io.busWord. Each Mem has a single write port; affineEnable (extracted
  // from word-0 bit [10]) still goes to its remaining DFF Vec.
  {
    val isExtBus = io.busWr && (io.busSlot >= U(legacyIoCount, descIdxBits bits))
    val rel = (io.busSlot - U(legacyIoCount, descIdxBits bits)).resize(log2Up(extCount))

    // Word 0 packed: {y[9:0], patIdxLow[3:0], enabled[1]} — 15 bits total
    val patLowBits = patternSelBits.min(4)
    val w0Pack = Cat(
      io.busData(9 downto 0).asUInt.asBits,                         // y
      io.busData(14 downto 11).asUInt.resize(patLowBits).asBits,    // patIdxLow
      io.busData(15).asBits                                          // enabled
    ).resize(InfoW0Width bits)
    infoMemW0.write(rel, w0Pack, enable = isExtBus && (io.busWord === U(0, busWordBits bits)))

    // Word 1 packed: {x[9:0]} — 10 bits
    val w1Pack = io.busData(9 downto 0).asUInt.asBits.resize(InfoW1Width bits)
    infoMemW1.write(rel, w1Pack, enable = isExtBus && (io.busWord === U(1, busWordBits bits)))

    // Word 8 packed: {patIdxHigh, mask, bppSel, flipV, flipH, priority,
    //                 paletteBank, sizeSel} — LSB→MSB order
    val w8HighW = (patternSelBits - 4).max(0)
    val w8Pack = if (w8HighW > 0) {
      Cat(
        io.busData(w8HighW - 1 downto 0).asUInt.asBits,                  // patIdxHigh
        io.busData(4).asBits,                                            // mask
        io.busData(6 downto 5).asUInt.asBits,                            // bppSel
        io.busData(7).asBits,                                            // flipV
        io.busData(8).asBits,                                            // flipH
        io.busData(10 downto 9).asUInt.asBits,                           // priority
        io.busData(13 downto 11).asUInt.asBits,                          // paletteBank
        io.busData(15 downto 14).asUInt.asBits                           // sizeSel
      ).resize(InfoW8Width bits)
    } else {
      Cat(
        io.busData(4).asBits,
        io.busData(6 downto 5).asUInt.asBits,
        io.busData(7).asBits,
        io.busData(8).asBits,
        io.busData(10 downto 9).asUInt.asBits,
        io.busData(13 downto 11).asUInt.asBits,
        io.busData(15 downto 14).asUInt.asBits
      ).resize(InfoW8Width bits)
    }
    infoMemW8.write(rel, w8Pack, enable = isExtBus && (io.busWord === U(8, busWordBits bits)))

    // affineEnable still per-slot DFF (CyanPeak exemption #9597).
    when(isExtBus && (io.busWord === U(0, busWordBits bits))) {
      switch(rel) {
        for (i <- 0 until extCount) {
          is(U(i, log2Up(extCount) bits)) {
            regAffineEnable(i) := io.busData(10)
          }
        }
      }
    }
  }

  // Task 57 Slice 2: matrix/trans Mem writes. Single write port per Mem
  // addressed by `rel` (the slot's index in extCount). Enable fires when
  // (a) bus write is asserted, (b) slot is in the extended range, and
  // (c) the bus word matches the field's slot.
  {
    val isExtBus = io.busWr && (io.busSlot >= U(legacyIoCount, descIdxBits bits))
    val rel = (io.busSlot - U(legacyIoCount, descIdxBits bits)).resize(log2Up(extCount))
    matAMem.write(  rel, io.busData, enable = isExtBus && (io.busWord === U(2, busWordBits bits)))
    matBMem.write(  rel, io.busData, enable = isExtBus && (io.busWord === U(3, busWordBits bits)))
    matCMem.write(  rel, io.busData, enable = isExtBus && (io.busWord === U(4, busWordBits bits)))
    matDMem.write(  rel, io.busData, enable = isExtBus && (io.busWord === U(5, busWordBits bits)))
    transXMem.write(rel, io.busData, enable = isExtBus && (io.busWord === U(6, busWordBits bits)))
    transYMem.write(rel, io.busData, enable = isExtBus && (io.busWord === U(7, busWordBits bits)))
  }

  // ---------------------------------------------------------------------
  // Unified descriptor read path.
  //
  // Task 57 Slice 3: ext-slot fields (X/Y/Enabled/PatternIdx/FlipH/V/Mask
  // PaletteBank/Priority/SizeSel/BppSel) are backed by `readSync` Mems
  // and read by a single-port hoist below the scan switch. The helpers
  // return DEFAULTS for ext slots — actual values are overridden by the
  // hoisted block. This avoids per-i Mem read ports inside the
  // scan-switch (which would create extCount ports per Mem and prevent
  // RAM inference).
  //
  // affineEnable stays in DFFs (CyanPeak exemption #9597), so it still
  // routes per-slot through the helper.
  // ---------------------------------------------------------------------
  def descEnabled(i: Int): Bool =
    if (i < legacyIoCount) io.descEnabled(i) else False     // overridden below
  def descX(i: Int): UInt =
    if (i < legacyIoCount) io.descX(i) else U(0, 10 bits)    // overridden below
  def descY(i: Int): UInt =
    if (i < legacyIoCount) io.descY(i) else U(1023, 10 bits) // overridden below (off-line default)
  def descPatternIdx(i: Int): UInt =
    if (i < legacyIoCount) io.descPatternIdx(i).resize(patternSelBits)
    else                   U(0, patternSelBits bits)         // overridden below
  def descAffineEnable(i: Int): Bool =
    if (i < legacyIoCount) False else regAffineEnable(i - legacyIoCount)
  // Task 57 Slice 2 / storage move #10357: affine matrix + translation
  // values are read in the hoisted scan-FSM block below — one `readSync`
  // port per Mem so Gowin infers BSRAM. (The former `descMatrix` helper,
  // a readAsync per-slot accessor, was dead code and has been removed.)
  // Sprite Envelope Hardening — legacy slots get back-compat defaults.
  // Ext-slot defaults are overridden below by the hoisted Mem read.
  def descFlipH(i: Int): Bool =
    if (i < legacyIoCount) False else False
  def descFlipV(i: Int): Bool =
    if (i < legacyIoCount) False else False
  def descMask(i: Int): Bool =
    if (i < legacyIoCount) False else False
  def descPaletteBank(i: Int): UInt =
    if (i < legacyIoCount) U(0, 3 bits) else U(0, 3 bits)
  def descPriority(i: Int): UInt =
    if (i < legacyIoCount) U(0, 2 bits) else U(0, 2 bits)
  def descSizeSel(i: Int): UInt =
    if (i < legacyIoCount) U(SpriteDescriptor.DefaultSizeSel, 2 bits)
    else                   U(SpriteDescriptor.DefaultSizeSel, 2 bits)
  def descBppSel(i: Int): UInt =
    if (i < legacyIoCount) U(0, 2 bits) else U(0, 2 bits)

  // sizeForSel lives on the SpriteDescriptor companion so VdpTop's
  // per-slot pattern-fetch loop can share the same encoding.

  // ---------------------------------------------------------------------
  // Sequential Pass-1 FSM.
  // ---------------------------------------------------------------------
  val scanIdx        = Reg(UInt(descIdxBits bits)) init 0
  val activeCount    = Reg(UInt(log2Up(visiblePerLine + 1) bits)) init 0
  val totalOnLine    = Reg(UInt(log2Up(descCount + 1) bits)) init 0
  val scanBusy       = Reg(Bool()) init False
  // Storage move #10357: two scan cycles per descriptor — readPhase False
  // presents the readSync Mem address, True processes the valid data.
  val readPhase      = Reg(Bool()) init False

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
    readPhase    := False   // storage move #10357: start in address phase
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
          // Task 57 Slice 2: matrix/trans NOT routed through this
          // per-slot switch — that would create extCount read ports
          // per Mem and prevent RAM inference. Single-port Mem reads
          // addressed by `scanIdx` are wired below the switch.
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

  // Task 57 Slice 2 + Slice 3: single-port Mem reads addressed by
  // `scanIdx`. For ext slots, override the cur* defaults from the
  // helpers with the actual stored values from the Mems. Each Mem
  // gets exactly one read port → preserves RAM inference.
  {
    val isExtScan = scanIdx >= U(legacyIoCount, descIdxBits bits)
    val scanRel   = (scanIdx - U(legacyIoCount, descIdxBits bits)).resize(log2Up(extCount))

    // Slice 2 — affine matrix Mems
    val matAR     = matAMem.readSync(scanRel)
    val matBR     = matBMem.readSync(scanRel)
    val matCR     = matCMem.readSync(scanRel)
    val matDR     = matDMem.readSync(scanRel)
    val transXR   = transXMem.readSync(scanRel)
    val transYR   = transYMem.readSync(scanRel)

    // Slice 3 — packed info Mems. readSync → BSRAM; data valid in the
    // process phase (scanIdx held stable across the 2-cycle walk).
    val w0R = infoMemW0.readSync(scanRel)   // {y[14:5], patIdxLow[4:1], enabled[0]}
    val w1R = infoMemW1.readSync(scanRel)   // {x[9:0]}
    val w8R = infoMemW8.readSync(scanRel)
    // w8 unpack offsets (LSB first per write order):
    //   [1:0]   sizeSel
    //   [4:2]   paletteBank
    //   [6:5]   priority
    //   [7]     flipH
    //   [8]     flipV
    //   [10:9]  bppSel
    //   [11]    mask
    //   [11+w8HighW : 12]  patIdxHigh (if patternSelBits > 4)
    val w8HighW = (patternSelBits - 4).max(0)
    val patLowBits = patternSelBits.min(4)

    val w0_enabled  = w0R(0)
    val w0_patLow   = w0R(patLowBits downto 1).asUInt   // bits [patLowBits..1]
    val w0_y        = w0R(InfoW0Width - 1 downto (1 + patLowBits)).asUInt  // bits [InfoW0Width-1..(1+patLowBits)] = 10 bits
    val w1_x        = w1R(9 downto 0).asUInt
    val w8_sizeSel  = w8R(1 downto 0).asUInt
    val w8_bank     = w8R(4 downto 2).asUInt
    val w8_priority = w8R(6 downto 5).asUInt
    val w8_flipH    = w8R(7)
    val w8_flipV    = w8R(8)
    val w8_bppSel   = w8R(10 downto 9).asUInt
    val w8_mask     = w8R(11)

    when(scanBusy && isExtScan) {
      // Slice 2 overrides
      curMatrixA := matAR
      curMatrixB := matBR
      curMatrixC := matCR
      curMatrixD := matDR
      curTransX  := transXR
      curTransY  := transYR
      // Slice 3 overrides
      curEnabled := w0_enabled.asUInt
      curY       := w0_y
      curX       := w1_x
      curSizeSel := w8_sizeSel
      curPaletteBank := w8_bank
      curPriority := w8_priority
      curFlipH := w8_flipH
      curFlipV := w8_flipV
      curBppSel := w8_bppSel
      curMask  := w8_mask
      // patIdx assembly: low from w0, high from w8 (if any)
      if (w8HighW > 0) {
        val patHigh = w8R(11 + w8HighW downto 12).asUInt
        curPat := (patHigh @@ w0_patLow).resize(patternSelBits)
      } else {
        curPat := w0_patLow.resize(patternSelBits)
      }
    }
  }
  // Sprite Envelope Hardening: Y-range is now `[y, y + sizeForSel(sizeSel))`
  // instead of the prior fixed-16 height. sizeForSel returns 8/16/32/64.
  val curSize  = SpriteDescriptor.sizeForSel(curSizeSel)         // 7 bits, max 64
  val dOnLine  = scanBusy && curEnabled(0) &&
                 (io.evalLine >= curY) &&
                 (io.evalLine < (curY + curSize.resize(10)))

  // Storage move #10357: descriptor Mems are `readSync`, so each
  // descriptor takes two scan cycles. readPhase=False presents the read
  // address; readPhase=True processes the descriptor with the now-valid
  // registered Mem data and advances. Scan length is 2×descCount cycles —
  // still a small fraction of the per-line budget.
  when(scanBusy) {
    readPhase := !readPhase
    when(readPhase) {
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
        scanBusy  := False
        readPhase := False
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
    mask      = curMask,
    // Task 54: descriptor index of the qualifying scanned descriptor,
    // packed at write time. Resized up to `DescIdxWidth=6` so the
    // packed slot word width is independent of `descCount`.
    descIdx   = scanIdx.resize(SpriteEvaluator.DescIdxWidth)
  )
  // Mem write occurs in the same conditions as the legacy active*Reg
  // Vec writes — gated on (scanBusy && dOnLine && activeCount<visiblePerLine).
  // Storage move #10357: gated on readPhase so the write fires once, in
  // the process phase, with valid readSync descriptor data.
  val memWrite = scanBusy && readPhase && dOnLine && (activeCount < U(visiblePerLine, activeCount.getWidth bits))
  activeListMem.write(
    address = activeCount.resize(log2Up(visiblePerLine)),
    data    = packedSlot,
    enable  = memWrite
  )

  io.activeReadData := activeListMem.readAsync(io.activeReadAddr)
  io.activeCountOut := activeCount

  // Task 55 — shadow Reg vector mirroring the per-slot mask bit. Written
  // alongside `activeListMem.write` so the priority encoder can read all
  // `visiblePerLine` mask bits combinationally without adding readAsync
  // ports to activeListMem. Keeps activeListMem at a single readAsync
  // port (the `activeReadData` path), which is the synthesis-fragility
  // invariant called out in the file-header IO comment. Cost is one FF
  // per slot (visiblePerLine total) — trivial vs the +5485 DFFs that
  // per-slot readAsync replication of activeListMem would force on
  // Gowin (see PROJECT_PLAN/MODE0_T20_STRIP_ANALYSIS_CORALREEF.md).
  private val activeMaskShadow = Vec.fill(visiblePerLine)(Reg(Bool()) init False)
  when(memWrite) {
    activeMaskShadow(activeCount.resize(log2Up(visiblePerLine))) := curMask
  }

  // Task 55 — combinational priority encoder over the mask shadow,
  // returning the lowest active slot index with mask=1, or
  // `visiblePerLine` if no active masking sprite. Reverse-then-overwrite
  // pattern relies on SpinalHDL's last-assignment-wins semantics so the
  // smallest matching index ends up retained.
  private val firstMaskSlotW = log2Up(visiblePerLine + 1)
  io.firstMaskSlot := U(visiblePerLine, firstMaskSlotW bits)
  for (s <- (visiblePerLine - 1) to 0 by -1) {
    when(activeMaskShadow(s) &&
         (U(s, firstMaskSlotW bits) < activeCount.resize(firstMaskSlotW))) {
      io.firstMaskSlot := U(s, firstMaskSlotW bits)
    }
  }
}

object SpriteEvaluator {
  // Task 2c — packed active-slot word width and field offsets.
  // Task 53 (#9419) — `PatIdxWidth` widened 4→6, `SlotPackedW` 128→130.
  // Task 55 (#9440) — Genesis sprite-mask bit appended at MSB; `SlotPackedW` 130→131.
  // Task 54 (#9620) — descIdx field appended at MSB; `SlotPackedW` 131→137.
  //   Width fixed at DescIdxWidth=6 (covers any descCount ≤ 64) so the
  //   packed-word size is independent of the per-instance descCount param.
  val PatIdxWidth:  Int = 6
  val DescIdxWidth: Int = 6
  val SlotPackedW:  Int = DescIdxWidth + 1 + 96 + 10 + 6 + PatIdxWidth + 3 + 2 + 2 + 2 + 1 + 1 + 1   // = 137

  // Pack a slot's fields into a `SlotPackedW`-bit word.
  def packSlot(
      matrixA: Bits, matrixB: Bits, matrixC: Bits, matrixD: Bits,
      transX: Bits, transY: Bits,
      x: UInt, row: UInt,
      patIdx: UInt, paletteBank: UInt, priority: UInt,
      sizeSel: UInt, bppSel: UInt,
      affineEnable: Bool, flipH: Bool, flipV: Bool,
      mask: Bool,
      descIdx: UInt): Bits = {
    descIdx.asBits.resize(DescIdxWidth) ##
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
  // Bit positions match `packSlot` above. Total = 137 bits.
  //   [136:131] descIdx       (6)   ← Task 54
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
  def slotDescIdx(w: Bits)   : UInt = w(136 downto 131).asUInt   // Task 54
}
