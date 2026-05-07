package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Sequential Scanline Sprite Rasterizer (Task 2a — reshaped Checkpoint 2).
  *
  * Implements the canonical retro-PPU rendering pattern as used by the NES,
  * SNES, and the Commander X16 VERA chip. Authored per BronzeGate #9235 PM
  * ruling, CyanPeak audit PASS #9237, design packet #9236.
  *
  * Two cooperating FSMs:
  *
  *   - **search**: walks the SpriteEvaluator active* Vec from highest index
  *     to lowest (reverse-iter), skipping invalid slots; on finding a valid
  *     slot AND the render FSM idle, latches the slot's descriptor fields
  *     and emits `startRender`.
  *   - **render**: pipeline-fills the pattern Mem read for pixel 0 in FILL
  *     cycle, then writes one pixel per cycle to the sprite line buffer
  *     during RUN cycles, advancing `pixCnt` until reaching `slotWidth`.
  *
  * Per-pixel timing (matches existing per-slot pipeline contract):
  *   cycle T : pattern Mem addr for col=pixCnt presented (combinational)
  *   cycle T+1 : rawPixel for that col arrives (readSync); bppSel-aware
  *               unpack; write to sprite line buffer at addr=(slotX+pixCnt-1)
  *               iff pixel != 0 (transparency); pixCnt advances
  *
  * Reverse-iter draw order yields back-to-front compositing without
  * read-modify-write: lowest descriptor-index sprite (= drawn LAST in our
  * iteration order) overwrites higher-index sprites on overlap, matching
  * the existing Task 28 priority semantic that the parallel for-loop in
  * `VdpTop.scala` produced.
  *
  * Cycle budget: parameter `cycleBudget` (default 798 — VERA literal)
  * counts up from `lineRenderStart`; on saturation, the search FSM forces
  * DONE and the render FSM aborts at the next slot boundary. Mirrors VERA
  * `sprite_renderer.v:35`.
  *
  * Affine path: incremental state {uState, vState} (single set, NOT
  * replicated per slot — that was the cost driver in the discarded
  * Checkpoint 2). Initialized at FILL from
  * `matrixB·y + (transX << 2)` and incremented by `matrixA` each pixel.
  * Math is bit-identical to the closed-form `matrixA·x + matrixB·y +
  * (transX << 2)` from `AffineStepper.scala`.
  */
case class SpriteRasterizer(
  visiblePerLine: Int = 32,
  patternSelBits: Int = 6,
  hActive: Int       = 640,
  cycleBudget: Int   = 798
) extends Component {

  // Task 53 (#9419): {patIdx[patternSelBits-1:0], row[3:0], col[3:0]}
  val patAddrBits = patternSelBits + 8

  val io = new Bundle {
    // === Task 2c Checkpoint D: narrow active-list RAM read port ===
    // Replaces 16 wide active* Vec inputs (~4,448 wires for V=32) with a
    // single 128-bit RAM read port + activeCount. The Evaluator now owns
    // the active-list storage; the rasterizer is a pure consumer that
    // walks indices [0..activeCount-1] and unpacks per slot.
    val activeReadAddr = out UInt(log2Up(visiblePerLine) bits)
    val activeReadData = in  Bits(SpriteEvaluator.SlotPackedW bits)
    val activeCount    = in  UInt(log2Up(visiblePerLine + 1) bits)
    // Task 55 (#9440) — Genesis sprite-mask threshold from the
    // evaluator. Slots with index strictly greater than `firstMaskSlot`
    // are suppressed (transparent) for the line. Default
    // `visiblePerLine` = no masking sprite, no suppression.
    val firstMaskSlot  = in  UInt(log2Up(visiblePerLine + 1) bits)

    // Per-line trigger: pulse one cycle to start drawing for the line
    // identified by `fillLineY`. Caller responsibility to assert when the
    // SpriteEvaluator scan has completed and active* is stable.
    val lineRenderStart = in Bool()
    val fillLineY       = in UInt(10 bits)

    // Pattern Mem read interface (single shared sprite pattern Mem in
    // VdpTop). Address presented combinationally; data via readSync at T+1.
    val patternRamAddr = out UInt(patAddrBits bits)
    val patternRamData = in  Bits(4 bits)

    // Sprite line buffer drain (composer-side read at hCounter rate).
    // 1-cycle readSync latency owned internally; outputs valid 1 cycle
    // after `drainAddr` is presented.
    val drainAddr        = in  UInt(log2Up(hActive) bits)
    val drainPixel       = out Bits(4 bits)   // 0 = transparent / no sprite
    val drainPaletteBank = out UInt(3 bits)
    val drainPriority    = out UInt(2 bits)
    // Slot-0 provenance bit (PM #9244 (ii)): asserts when the drained
    // pixel was written by the slot-0 (lowest descriptor index) sprite.
    // Preserves the existing `sprite0HitPulse` semantic at drain time.
    val drainSlot0       = out Bool()

    // Ping-pong swap (typically tied to hCounter === hTotal-1).
    val bufferSwap = in Bool()

    // Status: pulses high while the cycle budget is exhausted with active
    // slots still pending — i.e., some sprites were dropped this line.
    val cycleOverflow = out Bool()
  }

  // ----- search FSM -----------------------------------------------------
  // 3 states encoded so DONE is the reset state (renderer is idle after
  // power-up; no work expected until lineRenderStart).
  val SF_DONE = U(0, 2 bits)
  val SF_FIND = U(1, 2 bits)
  val SF_LOAD = U(2, 2 bits)

  val sfState = Reg(UInt(2 bits)) init SF_DONE

  // slotIdx walks visiblePerLine-1 → 0. Use one extra bit so we can hold
  // a "before zero / done" sentinel (-1 represented as 0..N-1+1 tricky;
  // simpler: track it with a bool).
  val slotIdxW = log2Up(visiblePerLine)
  val slotIdx  = Reg(UInt(slotIdxW bits)) init 0
  val slotZeroDone = Reg(Bool()) init True   // sentinel for "we already
                                              // drew (or skipped) slot 0"

  // ----- render FSM -----------------------------------------------------
  val ST_IDLE = U(0, 2 bits)
  val ST_FILL = U(1, 2 bits)
  val ST_RUN  = U(2, 2 bits)

  val rState = Reg(UInt(2 bits)) init ST_IDLE
  val renderBusy = rState =/= ST_IDLE

  // ----- per-slot latched fields ----------------------------------------
  val slotXR        = Reg(UInt(10 bits))                  init 0
  val slotRowR      = Reg(UInt(6 bits))                   init 0
  val slotPatIdxR   = Reg(UInt(patternSelBits bits))      init 0
  val slotAffEnR    = Reg(Bool())                         init False
  val slotFlipHR    = Reg(Bool())                         init False
  val slotFlipVR    = Reg(Bool())                         init False
  val slotBankR     = Reg(UInt(3 bits))                   init 0
  val slotPrioR     = Reg(UInt(2 bits))                   init 0
  val slotSizeSelR  = Reg(UInt(2 bits))                   init 0
  val slotBppSelR   = Reg(UInt(2 bits))                   init 0
  val slotMatrixAR  = Reg(Bits(16 bits))                  init 0
  val slotMatrixBR  = Reg(Bits(16 bits))                  init 0
  val slotMatrixCR  = Reg(Bits(16 bits))                  init 0
  val slotMatrixDR  = Reg(Bits(16 bits))                  init 0
  val slotTransXR   = Reg(Bits(16 bits))                  init 0
  val slotTransYR   = Reg(Bits(16 bits))                  init 0
  // Slot-0 provenance: latched at SF_LOAD when slotIdx === 0.
  val slotIsZeroR   = Reg(Bool())                         init False
  // Task 55 — index of the slot currently being RENDERED. Latched at
  // SF_LOAD because `slotIdx` is the *next-load pointer* (decremented
  // during the same SF_LOAD cycle), so by the time rState=ST_RUN is
  // writing this slot's pixels, `slotIdx` already points to the next
  // slot to be loaded. The mask gate must use the render slot's
  // index, not the lookahead pointer.
  val slotRenderIdxR = Reg(UInt(log2Up(visiblePerLine + 1) bits)) init U(visiblePerLine)

  val slotWidth = slotSizeSelR.mux(
    U(0, 2 bits) -> U( 8, 7 bits),
    U(1, 2 bits) -> U(16, 7 bits),
    U(2, 2 bits) -> U(32, 7 bits),
    default      -> U(64, 7 bits)
  )

  // ----- pixCnt: column being PRESENTED to pattern Mem this cycle -------
  // RUN entry: pixCnt = 1 (we just presented col 0 in FILL; this cycle we
  // present col 1 and the data for col 0 is arriving).
  // Write column = pixCnt - 1 (= the col whose data is now valid).
  val pixCnt = Reg(UInt(7 bits)) init 0
  val writeCol = pixCnt - 1

  // ----- pipeline registers aligned with readSync output ----------------
  // These must be latched in the cycle the addr is presented so their
  // values match the rawPixel that arrives one cycle later.
  val colLowR = Reg(UInt(2 bits)) init 0
  val bppSelR = Reg(UInt(2 bits)) init 0
  val affEnR  = Reg(Bool())       init False
  val affOnR  = Reg(Bool())       init False
  val writeXR = Reg(UInt(10 bits)) init 0  // (slotX + writeCol).resize(10)

  // ----- cycle budget ---------------------------------------------------
  val budgetCounter = Reg(UInt(log2Up(cycleBudget + 1) bits)) init 0
  val budgetExhausted = budgetCounter === U(cycleBudget, log2Up(cycleBudget + 1) bits)
  when(io.lineRenderStart) {
    budgetCounter := 0
  } elsewhen (!budgetExhausted) {
    budgetCounter := budgetCounter + 1
  }

  io.cycleOverflow := budgetExhausted &&
                     (sfState =/= SF_DONE || rState =/= ST_IDLE)

  // ----- combinational address generation -------------------------------
  // The ADDR presented is for column = pixCnt (FILL: 0; RUN: pixCnt).
  val pxC = pixCnt

  // Flat path
  val flippedColFlat = Mux(slotFlipHR, ~pxC(3 downto 0), pxC(3 downto 0))
  val flippedRowFlat = Mux(slotFlipVR, ~slotRowR(3 downto 0), slotRowR(3 downto 0))
  val flatAddr        = (flippedRowFlat ## flippedColFlat).asUInt
  val colShifted: UInt = slotBppSelR.mux(
    U(0, 2 bits) -> flippedColFlat,
    U(1, 2 bits) -> (B"0"  ## flippedColFlat(3 downto 1)).asUInt,
    U(2, 2 bits) -> (B"00" ## flippedColFlat(3 downto 2)).asUInt,
    default      -> flippedColFlat
  )
  val flatAddrBpp = (flippedRowFlat ## colShifted.asBits.resize(4)).asUInt
  val effFlatAddr = Mux(slotBppSelR === U(0, 2 bits), flatAddr, flatAddrBpp)

  // Affine path
  val uState = Reg(SInt(32 bits)) init S(0, 32 bits)
  val vState = Reg(SInt(32 bits)) init S(0, 32 bits)
  val uIntFull = uState(31 downto 8)
  val vIntFull = vState(31 downto 8)
  val uOk     = uIntFull >= S(0, 24 bits) && uIntFull < S(16, 24 bits)
  val vOk     = vIntFull >= S(0, 24 bits) && vIntFull < S(16, 24 bits)
  val affOn   = uOk && vOk
  val affAddr = (vIntFull(3 downto 0).asBits ## uIntFull(3 downto 0).asBits).asUInt

  val finalAddr = Mux(slotAffEnR, affAddr, effFlatAddr)
  io.patternRamAddr := (slotPatIdxR.asBits.resize(patternSelBits) ## finalAddr.asBits.resize(8)).asUInt

  // ----- pixel unpack from registered alignment signals -----------------
  val rawPixel = io.patternRamData
  val twoBpp = Mux(colLowR(0), rawPixel(3 downto 2), rawPixel(1 downto 0)).asBits.resize(4)
  val oneBpp = rawPixel(colLowR).asBits.resize(4)
  val pixel: Bits = bppSelR.mux(
    U(0, 2 bits) -> rawPixel,
    U(1, 2 bits) -> twoBpp,
    U(2, 2 bits) -> oneBpp,
    default      -> rawPixel
  )

  val onPixel = Mux(affEnR, affOnR, True)         // flat hitbox is always on within slotWidth (gated by FSM)
  val pixelTransparent = pixel === B(0, 4 bits)
  // Task 55 — Genesis sprite-mask suppression. Latched at
  // lineRenderStart so the threshold applies for the whole render
  // burst. Slots with index > firstMaskSlot are suppressed for the
  // entire line (no writes to slbA/slbB).
  val firstMaskSlotR = Reg(UInt(log2Up(visiblePerLine + 1) bits)) init U(visiblePerLine)
  when(io.lineRenderStart) {
    firstMaskSlotR := io.firstMaskSlot
  }
  val slotMaskedOut = slotRenderIdxR > firstMaskSlotR
  val pixelVisible = (rState === ST_RUN) && onPixel && !pixelTransparent && !slotMaskedOut

  // ----- sprite line buffer (ping-pong, 2 banks of hActive × 9 bits) ----
  val SLB_W = 4 + 3 + 2 + 1  // pixel + paletteBank + priority + slot0 flag

  val activeFillBank = Reg(Bool()) init False
  when(io.bufferSwap) {
    activeFillBank := !activeFillBank
  }

  val slbA = Mem(Bits(SLB_W bits), initialContent = Array.fill(hActive)(B(0, SLB_W bits)))
  val slbB = Mem(Bits(SLB_W bits), initialContent = Array.fill(hActive)(B(0, SLB_W bits)))

  // Latch the write-target bank at lineRenderStart and hold it for the
  // whole burst. The 798-cycle render burst spans across the next
  // bufferSwap (which flips activeFillBank 12 cycles after
  // lineRenderStart fires), so using the live activeFillBank for the
  // write target sends only the first ~12 cycles of writes to the
  // correct bank and the remaining ~786 cycles to the wrong bank —
  // i.e., almost no sprite data lands in the bank that's drained
  // during the displayed line. Latching pins the target across the
  // swap so the entire burst lands in one bank.
  val fillBankLatched = Reg(Bool()) init False
  when(io.lineRenderStart) {
    fillBankLatched := activeFillBank
  }

  // Layout: [9]=slot0 [8:7]=prio [6:4]=bank [3:0]=pixel
  val wrData = (slotIsZeroR.asBits ## slotPrioR.asBits ## slotBankR.asBits ## pixel).resize(SLB_W)
  val wrAddr = writeXR.resize(log2Up(hActive))
  val wrEnA  = pixelVisible && !fillBankLatched
  val wrEnB  = pixelVisible &&  fillBankLatched

  // Background-clear pass: every active-video cycle, write 0 to the
  // bank OPPOSITE the latched render target at addr=drainAddr. Over a
  // full active-video span this clears all hActive entries of that
  // bank, so when the next lineRenderStart latches THAT bank as the
  // new render target, every address starts at 0 (transparent). This
  // is the missing piece that produced the original "vertical streak"
  // symptom — without it, non-sprite addresses retained sprite pixels
  // from prior bursts and leaked downward as full-height columns.
  //
  // Clear target = !fillBankLatched, i.e., the bank that will be the
  // NEXT burst's render target. The current render target
  // (fillBankLatched) is being actively written and read this line —
  // we deliberately don't touch it. The opposite bank is idle for
  // render this line, so clearing it is collision-free.
  val clearAddr = io.drainAddr
  val clearZero = B(0, SLB_W bits)
  val clearEnA  =  fillBankLatched   // clear A when render targets B
  val clearEnB  = !fillBankLatched   // clear B when render targets A
  slbA.write(
    address = Mux(clearEnA, clearAddr, wrAddr),
    data    = Mux(clearEnA, clearZero, wrData),
    enable  = clearEnA || wrEnA
  )
  slbB.write(
    address = Mux(clearEnB, clearAddr, wrAddr),
    data    = Mux(clearEnB, clearZero, wrData),
    enable  = clearEnB || wrEnB
  )

  // Drain reads the OPPOSITE bank from the live fill role.
  val drainBankIsA = activeFillBank      // when fill=B, drain=A
  val drainDataA = slbA.readSync(io.drainAddr)
  val drainDataB = slbB.readSync(io.drainAddr)
  val drainBankR = RegNext(drainBankIsA) init True
  val drainData  = Mux(drainBankR, drainDataA, drainDataB)

  io.drainPixel       := drainData(3 downto 0)
  io.drainPaletteBank := drainData(6 downto 4).asUInt
  io.drainPriority    := drainData(8 downto 7).asUInt
  io.drainSlot0       := drainData(9)

  // ===== unified FSM state-update block ================================
  // The two FSMs share a single `always` block via SpinalHDL's
  // last-assignment-wins semantics. Order:
  //   1. defaults (hold)
  //   2. search FSM transitions (may emit startRender)
  //   3. render FSM transitions (consumes startRender)
  //   4. lineRenderStart override (highest priority)

  // Helper: select active-list slot index for the RAM read port.
  val sIdx = slotIdx.resize(log2Up(visiblePerLine))
  io.activeReadAddr := sIdx
  // Helper aliases for the unpacked slot fields (combinational on
  // activeReadData; valid for the slot indexed by `sIdx` this cycle).
  val rdW = io.activeReadData

  val startRender = Bool()
  startRender := False

  // ---- search FSM transition logic ----
  switch(sfState) {
    is(SF_FIND) {
      when(budgetExhausted) {
        sfState := SF_DONE
      } elsewhen (slotZeroDone) {
        sfState := SF_DONE
      } otherwise {
        // Every slot in [0..activeCount-1] is valid by construction —
        // the Evaluator only writes the active-list Mem at indices for
        // qualifying descriptors, so no `activeValid` check is needed.
        // Wait if render is busy; otherwise transition to LOAD.
        when(!renderBusy) {
          sfState := SF_LOAD
        }
        // else: hold (no register update)
      }
    }
    is(SF_LOAD) {
      // Unpack the slot word for `sIdx` (combinational on activeReadData)
      // and latch into per-slot registers.
      slotXR       := SpriteEvaluator.slotX(rdW)
      slotRowR     := SpriteEvaluator.slotRow(rdW)
      slotPatIdxR  := SpriteEvaluator.slotPatIdx(rdW).resize(patternSelBits)
      slotAffEnR   := SpriteEvaluator.slotAffineEnable(rdW)
      slotFlipHR   := SpriteEvaluator.slotFlipH(rdW)
      slotFlipVR   := SpriteEvaluator.slotFlipV(rdW)
      slotBankR    := SpriteEvaluator.slotPaletteBank(rdW)
      slotPrioR    := SpriteEvaluator.slotPriority(rdW)
      slotSizeSelR := SpriteEvaluator.slotSizeSel(rdW)
      slotBppSelR  := SpriteEvaluator.slotBppSel(rdW)
      slotMatrixAR := SpriteEvaluator.slotMatrixA(rdW)
      slotMatrixBR := SpriteEvaluator.slotMatrixB(rdW)
      slotMatrixCR := SpriteEvaluator.slotMatrixC(rdW)
      slotMatrixDR := SpriteEvaluator.slotMatrixD(rdW)
      slotTransXR  := SpriteEvaluator.slotTransX(rdW)
      slotTransYR  := SpriteEvaluator.slotTransY(rdW)
      slotIsZeroR  := slotIdx === U(0, slotIdxW bits)
      slotRenderIdxR := slotIdx.resize(log2Up(visiblePerLine + 1))   // Task 55
      startRender  := True

      // Advance slot pointer
      when(slotIdx === U(0, slotIdxW bits)) {
        slotZeroDone := True
        sfState      := SF_DONE
      } otherwise {
        slotIdx := slotIdx - 1
        sfState := SF_FIND
      }
    }
    is(SF_DONE) {
      // idle until lineRenderStart
    }
  }

  // ---- render FSM transition logic ----
  switch(rState) {
    is(ST_IDLE) {
      when(startRender) {
        rState := ST_FILL
        pixCnt := 0
        // Affine init: uState = matrixA·0 + matrixB·y + (transX<<2)
        //              vState = matrixC·0 + matrixD·y + (transY<<2)
        // Per artifact + audit #9254: use LATCHED slot regs (just written
        // in this same cycle from SF_LOAD) instead of combinational reads
        // of the active-list RAM. Decouples affine init from the RAM read
        // port timing during the active drawing loop.
        val matBS = slotMatrixBR.asSInt.resize(32)
        val matDS = slotMatrixDR.asSInt.resize(32)
        val xT    = slotTransXR.asSInt.resize(32) |<< 2
        val yT    = slotTransYR.asSInt.resize(32) |<< 2
        val ySig  = io.fillLineY.asSInt.resize(32)
        uState := (matBS * ySig).resize(32) + xT
        vState := (matDS * ySig).resize(32) + yT
      }
    }
    is(ST_FILL) {
      // Addr for col=0 was presented this cycle (since pixCnt=0 and
      // io.patternRamAddr is combinational). Latch alignment regs.
      colLowR := flippedColFlat(1 downto 0)
      bppSelR := slotBppSelR
      affEnR  := slotAffEnR
      affOnR  := affOn
      writeXR := slotXR    // for col=0, writeCol=0 → writeX=slotX

      pixCnt := 1
      rState := ST_RUN

      // Increment affine state for next cycle's addr (col=1).
      when(slotAffEnR) {
        uState := uState + slotMatrixAR.asSInt.resize(32)
        vState := vState + slotMatrixCR.asSInt.resize(32)
      }
    }
    is(ST_RUN) {
      // Data for col=(pixCnt-1) is now available in `pixel`.
      // Write happens via wrData/wrEn* combinational logic (above).
      // This cycle ALSO presents addr for col=pixCnt.
      colLowR := flippedColFlat(1 downto 0)
      bppSelR := slotBppSelR
      affEnR  := slotAffEnR
      affOnR  := affOn
      writeXR := (slotXR + pixCnt.resize(10)).resize(10)

      when(pixCnt === slotWidth.resize(7)) {
        // Just wrote the LAST pixel (col = width-1). Done.
        rState := ST_IDLE
      } otherwise {
        pixCnt := pixCnt + 1
        when(slotAffEnR) {
          uState := uState + slotMatrixAR.asSInt.resize(32)
          vState := vState + slotMatrixCR.asSInt.resize(32)
        }
      }
    }
  }

  // ---- lineRenderStart override (highest priority) ----
  when(io.lineRenderStart) {
    rState       := ST_IDLE
    pixCnt       := 0
    slotZeroDone := False
    // If the Evaluator delivered an empty active list, jump straight to
    // SF_DONE; otherwise start at the highest valid slot index.
    when(io.activeCount === U(0, io.activeCount.getWidth bits)) {
      sfState := SF_DONE
      slotIdx := U(0, slotIdxW bits)
    } otherwise {
      sfState := SF_FIND
      slotIdx := (io.activeCount - 1).resize(slotIdxW)
    }
  }
}

object SpriteRasterizer {
  def apply(): SpriteRasterizer = SpriteRasterizer(
    visiblePerLine = 32, patternSelBits = 4, hActive = 640, cycleBudget = 798
  )
}
