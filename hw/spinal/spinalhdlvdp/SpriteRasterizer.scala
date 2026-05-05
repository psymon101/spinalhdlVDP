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
  visiblePerLine: Int = 8,
  patternSelBits: Int = 4,
  hActive: Int       = 640,
  cycleBudget: Int   = 798
) extends Component {

  val patAddrBits = 12  // {patIdx[3:0], row[3:0], col[3:0]}

  val io = new Bundle {
    // SpriteEvaluator outputs (combinational; latched per-line by evalStart)
    val activeValid        = in Vec(Bool(), visiblePerLine)
    val activeX            = in Vec(UInt(10 bits), visiblePerLine)
    val activeRow          = in Vec(UInt(6 bits), visiblePerLine)
    val activePatternIdx   = in Vec(UInt(patternSelBits bits), visiblePerLine)
    val activeAffineEnable = in Vec(Bool(), visiblePerLine)
    val activeMatrixA      = in Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixB      = in Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixC      = in Vec(Bits(16 bits), visiblePerLine)
    val activeMatrixD      = in Vec(Bits(16 bits), visiblePerLine)
    val activeTransX       = in Vec(Bits(16 bits), visiblePerLine)
    val activeTransY       = in Vec(Bits(16 bits), visiblePerLine)
    val activeFlipH        = in Vec(Bool(), visiblePerLine)
    val activeFlipV        = in Vec(Bool(), visiblePerLine)
    val activePaletteBank  = in Vec(UInt(3 bits), visiblePerLine)
    val activePriority     = in Vec(UInt(2 bits), visiblePerLine)
    val activeSizeSel      = in Vec(UInt(2 bits), visiblePerLine)
    val activeBppSel       = in Vec(UInt(2 bits), visiblePerLine)

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
  io.patternRamAddr := (slotPatIdxR(3 downto 0).asBits ## finalAddr.asBits.resize(8)).asUInt

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
  val pixelVisible = (rState === ST_RUN) && onPixel && !pixelTransparent

  // ----- sprite line buffer (ping-pong, 2 banks of hActive × 9 bits) ----
  val SLB_W = 4 + 3 + 2 + 1  // pixel + paletteBank + priority + slot0 flag

  val activeFillBank = Reg(Bool()) init False
  when(io.bufferSwap) {
    activeFillBank := !activeFillBank
  }

  val slbA = Mem(Bits(SLB_W bits), initialContent = Array.fill(hActive)(B(0, SLB_W bits)))
  val slbB = Mem(Bits(SLB_W bits), initialContent = Array.fill(hActive)(B(0, SLB_W bits)))

  // Layout: [9]=slot0 [8:7]=prio [6:4]=bank [3:0]=pixel
  val wrData = (slotIsZeroR.asBits ## slotPrioR.asBits ## slotBankR.asBits ## pixel).resize(SLB_W)
  val wrAddr = writeXR.resize(log2Up(hActive))
  val wrEnA  = pixelVisible && !activeFillBank
  val wrEnB  = pixelVisible &&  activeFillBank

  slbA.write(address = wrAddr, data = wrData, enable = wrEnA)
  slbB.write(address = wrAddr, data = wrData, enable = wrEnB)

  // Drain reads the OPPOSITE bank.
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

  // Helper: select active* fields by slotIdx
  val sIdx = slotIdx.resize(log2Up(visiblePerLine))

  // Default: hold register values
  // (SpinalHDL auto-holds Reg without explicit ":="; no defaults needed)

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
        when(io.activeValid(sIdx)) {
          // Valid slot — wait if render is busy; otherwise transition to LOAD.
          when(!renderBusy) {
            sfState := SF_LOAD
          }
          // else: hold (no register update)
        } otherwise {
          // Invalid slot — skip; advance toward 0.
          when(slotIdx === U(0, slotIdxW bits)) {
            slotZeroDone := True
            sfState      := SF_DONE
          } otherwise {
            slotIdx := slotIdx - 1
          }
        }
      }
    }
    is(SF_LOAD) {
      slotXR       := io.activeX(sIdx)
      slotRowR     := io.activeRow(sIdx)
      slotPatIdxR  := io.activePatternIdx(sIdx)
      slotAffEnR   := io.activeAffineEnable(sIdx)
      slotFlipHR   := io.activeFlipH(sIdx)
      slotFlipVR   := io.activeFlipV(sIdx)
      slotBankR    := io.activePaletteBank(sIdx)
      slotPrioR    := io.activePriority(sIdx)
      slotSizeSelR := io.activeSizeSel(sIdx)
      slotBppSelR  := io.activeBppSel(sIdx)
      slotMatrixAR := io.activeMatrixA(sIdx)
      slotMatrixBR := io.activeMatrixB(sIdx)
      slotMatrixCR := io.activeMatrixC(sIdx)
      slotMatrixDR := io.activeMatrixD(sIdx)
      slotTransXR  := io.activeTransX(sIdx)
      slotTransYR  := io.activeTransY(sIdx)
      slotIsZeroR  := slotIdx === U(0, slotIdxW bits)
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
        val matBS = io.activeMatrixB(sIdx).asSInt.resize(32)
        val matDS = io.activeMatrixD(sIdx).asSInt.resize(32)
        val xT    = io.activeTransX(sIdx).asSInt.resize(32) |<< 2
        val yT    = io.activeTransY(sIdx).asSInt.resize(32) |<< 2
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
    sfState      := SF_FIND
    rState       := ST_IDLE
    pixCnt       := 0
    slotIdx      := U(visiblePerLine - 1, slotIdxW bits)
    slotZeroDone := False
  }
}

object SpriteRasterizer {
  def apply(): SpriteRasterizer = SpriteRasterizer(
    visiblePerLine = 8, patternSelBits = 4, hActive = 640, cycleBudget = 798
  )
}
