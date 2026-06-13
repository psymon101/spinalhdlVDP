package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** R4 Tile + Attribute Fetch engine.
  *
  * Extends the proven Task-15 `SdramTileFetch` architecture with:
  *   - Per-tile attribute fetch from a separate SDRAM map (palette bank + priority)
  *   - 4bpp tile row decode (64 bits / row = two 32-bit words)
  *   - 5-bit pixel output {priority[4], index[3:0]} through a ping-pong line buffer
  *   - Scheduler coupling: SDRAM reads are gated by `fetchSlotValid`, fetch starts
  *     on `fetchGrant` pulse. Pauses cleanly between slot windows.
  *
  * Assets come from `TileAttributeAssets` and live at disjoint SDRAM addresses
  * from the retired 3bpp `SdramTileFetch` data (per CoralReef #6755).
  */
/** Task 56 Checkpoint B (#9678 / #9691 / #9693): the fetch engine is now
  * parameterized on its SDRAM base addresses and optional boot-ROM init
  * overrides so a second instance can serve Layer 1 from a disjoint region
  * (0xC000 / 0xD000 / 0xE000) without colliding with the L0 instance.
  *
  * Defaults preserve byte-identical L0 behavior; L1 instances pass the
  * `*Base` params, optional `*BytesOverride` data, and set
  * `bootPlanarAssets=false` / `runMemtest=false` so they don't double-write
  * the planar plane-0/plane-1 staging regions or the memtest scratchpad
  * that L0 already initializes.
  */
case class SdramTileAttributeFetch(
    sdramCd: ClockDomain,
    skipSdramInit: Boolean = false,
    tileMapBaseAddr: Int = TileAttributeAssets.TileMapBase,
    attributeMapBaseAddr: Int = TileAttributeAssets.AttributeMapBase,
    tileRowBaseAddr: Int = TileAttributeAssets.TileRowBase,
    // Overrides take a thunk so the `Bits` literals are built inside the
    // engine's Component body (active elaboration context) rather than at
    // the call site where no context exists yet.
    tileMapBytesOverride: Option[() => Seq[Bits]] = None,
    attributeMapBytesOverride: Option[() => Seq[Bits]] = None,
    tileRowBytesOverride: Option[() => Seq[Bits]] = None,
    bootPlanarAssets: Boolean = true,
    runMemtest: Boolean = true,
    // CP-A3 (Phase A #11438/#11439, Option B): when true the engine takes its refresh
    // cadence from the arbiter's central timer via io.refreshDue (the port only exists
    // in this mode); when false it keeps its own internal timer (default — standalone
    // sims unchanged). The safe-point insertion + refreshReturn machinery is identical
    // either way; only the timer SOURCE differs.
    useExternalRefresh: Boolean = false
) extends Component {
  import TileAttributeAssets._

  val LineWidth      = MapPixelsX                      // 640
  val TilesPerLine   = MapTilesX + 1                    // 41 (one extra for sub-tile hscroll)
  val TotalTileBytes = MapEntries                       // 1200
  val TotalAttrBytes = MapEntries                       // 1200
  val TotalRowBytes  = TileCount * TileHeight * TileRowBytes  // 4*16*8 = 512

  val MemtestBase = 0x2000
  val MemtestSize = 256
  def memtestByte(i: UInt): Bits = (i.resize(8) ^ U(0xA5, 8 bits)).asBits

  // #11204 (TopazCliff): scaled 950 -> 593 for the 40.5 MHz SDRAM clock. The
  // divisor counts sdramCd cycles; 593 @ 40.5 MHz = 14.64 µs, preserving the
  // ~15 µs refresh interval (equivalent to 950 cycles @ the retired 64.8 MHz).
  val RefreshPeriodCycles = 593
  val FifoDepth = 32              // 4 words per tile now, double the previous depth
  val PixelLineBits = 8           // {priority[7], bank[6:4], index[3:0]}

  val io = new Bundle {
    // SDRAM controller interface
    val sdramAddr      = out UInt(23 bits)
    val sdramDin       = out Bits(8 bits)
    val sdramRd        = out Bool()
    val sdramWr        = out Bool()
    val sdramRefresh   = out Bool()
    // #11246 F2: next-cycle (register-input) view of the command lines for the
    // top-level upload look-ahead gate — see assignment near the FSM tail.
    val sdramRdNext      = out Bool()
    val sdramWrNext      = out Bool()
    val sdramRefreshNext = out Bool()
    // CP-A3: central-refresh cadence input (Option B). Present ONLY when
    // useExternalRefresh — standalone sims that use the internal timer never see it.
    val refreshDue     = useExternalRefresh generate (in Bool())
    val sdramDout      = in  Bits(8 bits)
    val sdramDout32    = in  Bits(32 bits)
    val sdramDataReady = in  Bool()
    val sdramBusy      = in  Bool()

    // Scheduler-driven fetch control (pixel clock)
    val fetchGrant        = in Bool()
    val fetchSlotValid    = in Bool()
    val fetchPreAnnounce  = in Bool()
    val fetchLine         = in UInt(10 bits)
    val fetchScrollX      = in UInt(10 bits)
    val fetchScrollY      = in UInt(10 bits)

    val pixelAddr       = in  UInt(10 bits)
    val pixelIndex      = out Bits(4 bits)
    val pixelPaletteBank = out UInt(3 bits)
    val pixelPriority   = out Bool()

    // R4.1b stage 1 (#7098) / R4.1d Checkpoint A: tile-row decode mode.
    // 2-bit encoding (VDP_TILE_MODE @ 0x0311 bits[1:0]):
    //   0x00 = packed 4bpp (R4 baseline) — 16 pixels per 64-bit row
    //   0x01 = NES-style 2-plane 2bpp planar (R4.1b) — word0[15:0] = plane 0,
    //          word1[15:0] = plane 1. Pixel = {plane1[x], plane0[x]}, range 0..3.
    //   0x02 = Amiga-style shuffled/bitplane (R4.1d) — plane 0 at SDRAM base
    //          0xA000, plane 1 at 0xB000. Pixel reconstruction identical to
    //          planar but with separate-base fetches (wired up in Checkpoint B).
    //   0x03 = reserved
    // Checkpoint A only widens the port and register; fetch-path behavior for
    // mode==0x02 is introduced in Checkpoint B.
    val tileDecodeMode  = in  Bits(2 bits)

    // R4.1c: attribute packing mode.
    //   0 = linear 1:1 (R4 baseline) — one attr byte per tile
    //   1 = NES-style 2×2 packing — one attr byte per 2×2 tile block; bits
    //       [1:0]=TL bank, [3:2]=TR, [5:4]=BL, [7:6]=BR. Priority bit derives
    //       from the selected 2-bit field; in packed mode palette banks are
    //       0..3 only.
    val attributeMode   = in  Bits(1 bits)

    val bootDone     = out Bool()
    val memtestPass  = out Bool()
    val memtestFail  = out Bool()
    val underrun     = out Bool()
    val debugWriteBuf = out Bool()
    // Telemetry for R4 stage-1b hardware diagnosis (#6767/#6768/#6769): latch
    // the attribute byte's low bits at a specific probe tile so the LEDs can
    // show what bank the engine *actually* reads on real SDRAM. Probe points
    // cover two distinct quadrants — if the engine addresses correctly, they
    // should differ.
    val debugAttrTL  = out UInt(3 bits)   // top-left (tileIdx=2,  tileY=2 ) — exp 4 (R4.1c 0xE4)
    val debugAttrBR  = out UInt(3 bits)   // bot-right(tileIdx=30, tileY=25) — exp 4 (R4.1c 0xEC)
  }

  // ==========================================================================
  // Pixel-domain: ping-pong line buffer + FIFO drain + unpack
  // ==========================================================================
  val lineBufferA = Mem(Bits(PixelLineBits bits), LineWidth)
  val lineBufferB = Mem(Bits(PixelLineBits bits), LineWidth)
  val writeBuf    = Reg(Bool()) init False
  val readA       = lineBufferA.readSync(io.pixelAddr)
  val readB       = lineBufferB.readSync(io.pixelAddr)
  val readWord    = Mux(writeBuf, readB, readA)
  io.pixelIndex        := readWord(3 downto 0)
  io.pixelPaletteBank  := readWord(6 downto 4).asUInt
  io.pixelPriority     := readWord(7)

  // Cross-domain FIFO payload: two 32-bit row words + 3-bit bank + 1-bit
  // priority per tile, packed flat for a clean CDC.
  // Layout: [67]=priority, [66:64]=bank, [63:32]=word1, [31:0]=word0.
  val tilePayloadBits = 68
  val wordFifo = StreamFifoCC(
    dataType  = Bits(tilePayloadBits bits),
    depth     = FifoDepth,
    pushClock = sdramCd,
    popClock  = ClockDomain.current
  )

  val tileCountReg = Reg(UInt(6 bits))  init 0
  val subOffsetReg = Reg(UInt(4 bits))  init 0
  val unpackIdx    = Reg(UInt(4 bits))  init 0
  val unpackRow    = Reg(Bits(64 bits)) init 0
  val unpackBank   = Reg(UInt(3 bits))   init 0
  val unpackPrio   = Reg(Bool())         init False
  val emitting     = Reg(Bool())         init False
  val underrunR    = Reg(Bool())         init False
  io.underrun := underrunR

  val fetchStartPixD = RegNext(io.fetchGrant) init False
  val fetchStartRise = io.fetchGrant && !fetchStartPixD

  // Mode0 Hardening — H-3: route the existing 2-plane planar decode through
  // the parameterized `BitplaneReconstruct` primitive. Behaviour is
  // bit-identical to the prior inline `(plane1Bits[15-idx] ## plane0Bits[15-idx])`
  // form (planes(0) is pixel LSB; MSB-first bitIdx). Future 5-plane work
  // will swap this to `planeCount = 5`.
  val planarRecon = BitplaneReconstruct(planeCount = 2, planeWidth = 16)
  planarRecon.io.planes(0) := unpackRow(15 downto  0)
  planarRecon.io.planes(1) := unpackRow(47 downto 32)
  planarRecon.io.bitIdx    := unpackIdx                  // 4 bits, matches idxWidth
  val px4PlanarOut         = planarRecon.io.pixel.resize(4)

  when(io.fetchGrant) {
    tileCountReg := 0
    subOffsetReg := io.fetchScrollX(3 downto 0)
    unpackIdx    := 0
    emitting     := False
    underrunR    := False
  }
  // P3 CP-B(3) #10791 Risk #3 fix — Option α drain-complete gate.
  //
  // Pre-fix (CP-B(1)+CP-B(2)): `when(fetchStartRise) { writeBuf := !writeBuf }`
  // flipped writeBuf the same cycle as fetchStartRise. If `emitting=True` at
  // that moment, the ping-pong reader switched to the buffer being overwritten
  // by the new fetch — half-line corruption. PlanarWriteBufRaceSim reproduced
  // ~30 trips of the CP-B(1) input-edge assert under pathological back-to-back
  // grants.
  //
  // Option α: defer the writeBuf flip until the prior row's emission has
  // fully drained. `pendingFlip` latches on fetchStartRise; the actual flip
  // fires the first cycle when `!emitting && !wordFifo.io.pop.valid` so
  // there's nothing left in flight that could be read from the wrong buffer.
  // pendingFlip clears with the flip so the next grant gets its own latch.
  //
  // Production reachability note: under the FetchSlotScheduler's worst-case
  // inter-grant gap of O(scanline) ≈ ~858 pixel-clock cycles, emit-clear
  // bound is ≈ ~30 cycles worst-case (per BrightForge analysis). Safety
  // ratio is ≈ 28.6×; race is naturally unreachable in production.
  // Option α provides defense-in-depth for pathological stress cases.
  val pendingFlip = Reg(Bool()) init False
  when(fetchStartRise) {
    pendingFlip := True
  }
  when(pendingFlip && !emitting && !wordFifo.io.pop.valid) {
    writeBuf    := !writeBuf
    pendingFlip := False
  }

  // P3 CP-B(3) #10791 Risk #3 validation canary (REVISED from CP-B(1) form).
  //
  // CP-B(1) checked the INPUT condition `!fetchStartRise || !emitting` —
  // useful before the fix (it reproduced ~30 trips in PlanarWriteBufRaceSim
  // and triggered CP-B(3)). Post-fix, fetchStartRise during emitting is the
  // EXACT scenario Option α handles, so the input-edge assert would trip on
  // every legitimate latched-flip event.
  //
  // The canary that matters post-fix is the OUTPUT invariant: writeBuf must
  // not change while emitting=True. Option α holds writeBuf stable across
  // the entire emission window; if a future refactor reintroduced an early
  // flip, this assert catches it. PlanarWriteBufRaceSim re-run under all 3
  // stress patterns is the proof that the canary stays silent.
  val writeBufPrev = RegNext(writeBuf) init False
  assert(
    assertion = !emitting || (writeBuf === writeBufPrev),
    message   = "P3 Risk #3 (post-fix): writeBuf changed while emitting=True (drain-complete gate broken)",
    severity  = ERROR
  )

  wordFifo.io.pop.ready := !emitting
  when(wordFifo.io.pop.fire) {
    unpackRow  := wordFifo.io.pop.payload(63 downto 0)
    unpackBank := wordFifo.io.pop.payload(66 downto 64).asUInt
    unpackPrio := wordFifo.io.pop.payload(67)
    unpackIdx  := 0
    emitting   := True
  }

  when(emitting) {
    // R4.1b: per-pixel decode branches on tileDecodeMode.
    //   packed (mode=0): row = 16 × 4bpp pixels in unpackRow(63:0)
    //   planar (mode=1): plane0 = unpackRow(15:0), plane1 = unpackRow(47:32)
    //                    pixel x = {plane1(15-x), plane0(15-x)} (2 bits, 0..3)
    // R4.1d Checkpoint B: shuffled mode uses the same pixel reconstruction
    // as planar — the difference is only at the fetch layer (plane 1 read
    // redirected to Plane1SdramBase). Bit[1] OR bit[0] selects the 2bpp
    // decode path.
    // H-3: planar reconstruction now lives in the `planarRecon` instance
    // outside this when-block; output is `px4PlanarOut`.
    val px4Packed   = unpackRow.subdivideIn(4 bits)(unpackIdx)
    val useTwoPlane = io.tileDecodeMode(0) || io.tileDecodeMode(1)
    val px4         = Mux(useTwoPlane, px4PlanarOut, px4Packed)
    val pxPacked = (unpackPrio ## unpackBank.asBits ## px4).asBits
    val shifted = ((tileCountReg * U(16, 6 bits)).resize(11)
                    + unpackIdx.resize(11)
                    + U(16, 11 bits)
                    - subOffsetReg.resize(11)).resize(11)
    val writeEnable = (shifted >= U(16, 11 bits)) && (shifted < U(LineWidth + 16, 11 bits))
    val writeAddr   = (shifted - U(16, 11 bits)).resize(10)
    when(writeEnable) {
      when(writeBuf) { lineBufferA.write(writeAddr, pxPacked) }
      .otherwise    { lineBufferB.write(writeAddr, pxPacked) }
    }
    when(unpackIdx === 15) {
      emitting     := False
      tileCountReg := tileCountReg + 1
    }
    unpackIdx := unpackIdx + 1
  }

  when(emitting && io.pixelAddr === LineWidth - 1) {
    underrunR := True
  }

  // ==========================================================================
  // SDRAM domain: boot, memtest, fetch FSM, refresh scheduler
  // ==========================================================================
  val sdramArea = new ClockingArea(sdramCd) {
    // CP-A3 (Option B): refresh cadence source. useExternalRefresh=true -> latch the
    // arbiter's central refreshDue pulse (one timer, no per-engine drift). Default
    // (false) -> the engine's own internal timer (standalone-sim behavior, unchanged).
    // Either way refreshPending feeds the SAME safe-point insertion + refreshReturn FSM.
    val refreshTimer   = Reg(UInt(10 bits)) init 0
    val refreshPending = Reg(Bool()) init False
    if (useExternalRefresh) {
      when(io.refreshDue) { refreshPending := True }
    } else {
      when(refreshTimer === RefreshPeriodCycles - 1) {
        refreshTimer   := 0
        refreshPending := True
      } otherwise {
        refreshTimer := refreshTimer + 1
      }
    }

    // Pixel→SDRAM CDC of fetch controls. Per CyanPeak #6762/#6793, bundle
    // grant/slotValid/preAnnounce into a single multi-bit BufferCC so the
    // three signals stay phase-coherent across the domain boundary. Separate
    // BufferCCs previously allowed grant to cross before slotValid, causing
    // the fetch FSM to start in a closed window and stall indefinitely.
    val ctrlBundle  = (io.fetchGrant ## io.fetchSlotValid ## io.fetchPreAnnounce).asBits
    val ctrlSync    = BufferCC(ctrlBundle, init = B(0, 3 bits))
    val fetchGrantSync    = ctrlSync(2)
    val slotValidSync     = ctrlSync(1)
    val preAnnounceSync   = ctrlSync(0)
    val fetchGrantD       = RegNext(fetchGrantSync) init False
    val fetchGrantEdge    = fetchGrantSync && !fetchGrantD

    // R4.2-redo Stage 3 (#7138): SpinalHDL's BufferCC documentation explicitly
    // warns against multi-bit use — the Stage-2 "atomic bundle" was still 30
    // independent 1-bit synchronizers, and consecutive fetchLine values across
    // tileY boundaries (e.g. 239=0b11101111 → 240=0b11110000 flips 5 bits at
    // once) let BufferCC return torn values, producing wrong-tileY attribute
    // reads = the y=234-243 stripe.
    //
    // Fix: Gray-code fetchLine before CDC so only ONE bit flips per line. The
    // SDRAM side receives either the old value or the new value, never a
    // torn intermediate. scrollX/scrollY change once per frame (60 Hz), much
    // slower than per-line; they stay on simple BufferCC (acceptable risk).
    def bin2gray(b: UInt): UInt = b ^ (b >> 1).resize(b.getWidth)
    val fetchLineGrayPx = bin2gray(io.fetchLine)
    val fetchLineGraySync = BufferCC(fetchLineGrayPx, init = U(0, 10 bits))
    val fetchLineSync = UInt(10 bits)
    for (i <- 0 until 10) {
      fetchLineSync(i) := fetchLineGraySync(9 downto i).xorR
    }
    val fetchScrollXSync   = BufferCC(io.fetchScrollX, init = U(0, 10 bits))
    val fetchScrollYSync   = BufferCC(io.fetchScrollY, init = U(0, 10 bits))

    val curLine    = Reg(UInt(10 bits)) init 0
    val curScrollX = Reg(UInt(10 bits)) init 0
    val curScrollY = Reg(UInt(10 bits)) init 0

    val cmdRd      = RegInit(False)
    val cmdWr      = RegInit(False)
    val cmdRefresh = RegInit(False)
    val cmdAddr    = Reg(UInt(23 bits)) init 0
    val cmdDin     = Reg(Bits(8 bits))  init 0

    // #9026 zero-footprint (BronzeGate ruling #9133): when skipSdramInit=true the
    // boot FSM is bypassed (host populates SDRAM via SDRAM_WRITE), so these init
    // ROMs are never accessed. Drop initialContent in that mode so synthesis
    // recovers the BSRAM/LUT footprint. Length-only declarations match the
    // original depths so any unintended access reads as zero rather than
    // failing build.
    val tileMapInit = tileMapBytesOverride.map(_()).getOrElse(TileAttributeAssets.tileMapBytesInit)
    val attrMapInit = attributeMapBytesOverride.map(_()).getOrElse(TileAttributeAssets.attributeMapBytesInit)
    val tileRowInit = tileRowBytesOverride.map(_()).getOrElse(TileAttributeAssets.tileRowBytesInit)
    val tileMapRom  = if (skipSdramInit) Mem(Bits(8 bits), tileMapInit.length)
                      else              Mem(Bits(8 bits), initialContent = tileMapInit)
    val attrMapRom  = if (skipSdramInit) Mem(Bits(8 bits), attrMapInit.length)
                      else              Mem(Bits(8 bits), initialContent = attrMapInit)
    val tileRowRom  = if (skipSdramInit) Mem(Bits(8 bits), tileRowInit.length)
                      else              Mem(Bits(8 bits), initialContent = tileRowInit)
    // R4.1b stage 2: planar boot ROM lives alongside the packed ROMs and is
    // boot-copied to SDRAM at PlanarTileAssets.SdramBase (0xA000), disjoint
    // from the R4 packed regions.
    val planarRowRom = if (skipSdramInit) Mem(Bits(8 bits), PlanarTileAssets.planarRowBytesInit.length)
                       else              Mem(Bits(8 bits), initialContent = PlanarTileAssets.planarRowBytesInit)
    // R4.1d Checkpoint B: plane-1 boot ROM for Amiga-style shuffled mode. Copied
    // to SDRAM at PlanarTileAssets.Plane1SdramBase (0xB000). Same stride as
    // plane0; per row bytes[0..1] carry plane1 data at the low 16 bits of the
    // 32-bit fetch word, feeding unpackRow(47:32) via the second word fetch.
    val plane1RowRom = if (skipSdramInit) Mem(Bits(8 bits), PlanarTileAssets.plane1RowBytesInit.length)
                       else              Mem(Bits(8 bits), initialContent = PlanarTileAssets.plane1RowBytesInit)

    // FIFO push side
    val pushValid   = RegInit(False)
    val pushPayload = Reg(Bits(tilePayloadBits bits)) init 0
    wordFifo.io.push.valid   := pushValid
    wordFifo.io.push.payload := pushPayload
    when(wordFifo.io.push.fire) { pushValid := False }

    val bootDoneR    = RegInit(False)
    val memtestPassR = RegInit(False)
    val memtestFailR = RegInit(False)

    val bootCounter   = Reg(UInt(12 bits)) init 0       // up to 4096
    val tileIdx       = Reg(UInt(6 bits))  init 0
    val tileIndexReg  = Reg(UInt(log2Up(TileCount) bits)) init 0
    val attrByteReg    = Reg(Bits(8 bits)) init 0
    val bankReg        = Reg(UInt(3 bits)) init 0
    val priorityReg    = Reg(Bool()) init False
    // R4 stage-1c bring-up probe latches removed (#6767/#6768/#6769 era).
    // Outputs already pruned (no external consumer); the probe-condition
    // comparators (tileIdx==2 && tileYCoord==2 etc.) were pure cost.
    val rowWord0Reg   = Reg(Bits(32 bits)) init 0

    // Y wrap with 2×mapH double-wrap to match BasicPatternSource.
    val lineY10 = (curLine + curScrollY).resize(10)
    val wrappedY = UInt(10 bits)
    when(lineY10 >= U(MapPixelsY * 2, 10 bits)) {
      wrappedY := lineY10 - U(MapPixelsY * 2, 10 bits)
    }.elsewhen(lineY10 >= U(MapPixelsY, 10 bits)) {
      wrappedY := lineY10 - U(MapPixelsY, 10 bits)
    }.otherwise {
      wrappedY := lineY10
    }
    val tileYCoord   = wrappedY(8 downto 4)
    val pixelYInTile = wrappedY(3 downto 0)

    // R5.4: shared ScrollWrap primitive for this domain's tileIdx*16 +
    // curScrollX wrap. Produces the current tile's wrapped X pixel coord
    // for BOTH sFetchMapRq and sFetchAttrRq.
    val pxXWrap = ScrollWrap(mapWidth = MapPixelsX, coordWidth = 10, scrollWidth = 10)
    pxXWrap.io.coord  := (tileIdx.resize(10) * U(16, 6 bits)).resize(10)
    pxXWrap.io.scroll := curScrollX
    val pxXWrapped = pxXWrap.io.result

    // Explicit intermediate widths per CyanPeak #6764 / CoralReef #6767 — the
    // pre-fix version let SpinalHDL infer narrow intermediate widths from the
    // operand widths, which is bit-accurate in sim but may yield a different
    // synthesis path on the Gowin inference. Forcing every intermediate to a
    // wide-enough constant width closes that gap.
    def tileMapByteAddr(tx: UInt, ty: UInt): UInt = {
      val offset = (ty.resize(16) * U(MapTilesX, 16 bits) + tx.resize(16)).resize(23)
      (U(tileMapBaseAddr, 23 bits) + offset).resize(23)
    }
    def attrMapByteAddr(tx: UInt, ty: UInt): UInt = {
      val offset = (ty.resize(16) * U(MapTilesX, 16 bits) + tx.resize(16)).resize(23)
      (U(attributeMapBaseAddr, 23 bits) + offset).resize(23)
    }
    // R4.1b: planar mode reads tile rows from PlanarTileAssets.SdramBase
    // instead of TileRowBase. Tile row layout is identical (16 rows × 8 bytes
    // per tile), so only the base address swaps.
    // R4.1d Checkpoint B: shuffled mode also uses PlanarTileAssets.SdramBase
    // for plane 0 reads (word 0); word 1 is redirected to Plane1SdramBase
    // inside tileRowByteAddr when shuffled mode is active.
    val planarSel    = io.tileDecodeMode(0)
    val shuffledSel  = io.tileDecodeMode(1)
    val tileRowBaseSel = Mux(planarSel || shuffledSel,
                             U(PlanarTileAssets.SdramBase, 23 bits),
                             U(tileRowBaseAddr, 23 bits))
    val tileRowBaseSelSync = BufferCC(tileRowBaseSel, init = U(tileRowBaseAddr, 23 bits))
    val shuffledSync       = BufferCC(shuffledSel,    init = False)

    // R4.1c: packed-attribute mode select, 1-bit CDC (slow-changing register).
    val attributeModeSync = BufferCC(io.attributeMode(0), init = False)
    def tileRowByteAddr(tIdx: UInt, py: UInt, wordIdx: UInt): UInt = {
      // Per-row offset within a plane buffer: tIdx*128 + py*8. In shuffled mode
      // the wordIdx*4 byte offset is dropped because each plane lives in its
      // own buffer (word 0 = plane0 buffer; word 1 = plane1 buffer).
      val rowOffset = ((tIdx.resize(16) * U(TileHeight * TileRowBytes, 16 bits)) +
                       (py.resize(16)   * U(TileRowBytes, 16 bits))).resize(23)
      val linearOffset = (rowOffset + (wordIdx.resize(16) * U(4, 16 bits)).resize(23)).resize(23)
      val effOffset = Mux(shuffledSync, rowOffset, linearOffset)
      // In shuffled mode, word 1 comes from the plane-1 base (0xB000); word 0
      // uses the normal planar base (0xA000) via tileRowBaseSelSync.
      val plane1Addr = (U(PlanarTileAssets.Plane1SdramBase, 23 bits) + effOffset).resize(23)
      val baseAddr   = (tileRowBaseSelSync + effOffset).resize(23)
      // P3 CP-B(1) #10791 Risk #1 (OOB planar memory): when this address is
      // used for a planar/shuffled-mode plane buffer read (either bank), the
      // effOffset must stay within TotalBytes so plane0 stays in [SdramBase,
      // SdramBase+TotalBytes) and plane1 stays in [Plane1SdramBase, +
      // TotalBytes). For packed-mode reads (tileRowBaseSel = TileRowBase)
      // the constraint does not apply (the legacy region has its own depth).
      val planarPathActive = shuffledSync ||
        (tileRowBaseSelSync === U(PlanarTileAssets.SdramBase, 23 bits))
      assert(
        assertion = !planarPathActive ||
          (effOffset < U(PlanarTileAssets.TotalBytes, 23 bits)),
        message   = "P3 Risk #1: planar tileRowByteAddr effOffset exceeds TotalBytes",
        severity  = ERROR
      )
      Mux(shuffledSync && wordIdx(0), plane1Addr, baseAddr)
    }

    cmdRd      := False
    cmdWr      := False
    cmdRefresh := False

    // R4.1: enable slotValid gating per TASK_R4_1 §3. When slotValid drops,
    // fetch-state Rq transitions stall and the FSM holds position; when it
    // rises again, reads resume from the same state. The bundled CDC above
    // keeps grant/slotValid/preAnnounce phase-coherent so the FSM never
    // observes an "open grant, closed window" race.
    val readGate = slotValidSync

    val fsm = new StateMachine {
      val sPowerWait     = new State with EntryPoint
      val sBootTileMap   = new State
      val sBootAttrMap   = new State
      val sBootTileRows  = new State
      val sBootPlanar    = new State    // R4.1b stage 2
      val sBootPlane1    = new State    // R4.1d Checkpoint B
      val sMemtestWrite  = new State
      val sMemtestReadRq = new State
      val sMemtestCheck  = new State
      val sIdle          = new State
      val sFetchMapRq    = new State
      val sFetchMapWait  = new State
      val sFetchAttrRq   = new State
      val sFetchAttrWait = new State
      val sFetchRowRq0   = new State
      val sFetchRowWait0 = new State
      val sFetchRowRq1   = new State
      val sFetchRowWait1 = new State
      val sPushTile      = new State
      val sRefresh       = new State

      val refreshReturn = Reg(UInt(4 bits)) init 0

      sPowerWait.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          if (skipSdramInit) {
            // #9026 zero-footprint: host owns SDRAM population. Skip the
            // boot-init copy from on-chip ROMs and the memtest, jump
            // straight to fetch-idle.
            bootDoneR    := True
            memtestPassR := True
            goto(sIdle)
          } else {
            goto(sBootTileMap)
          }
        }
      }

      // Boot copy: tile map → attribute map → tile rows. Not gated by readGate
      // so boot completes regardless of scheduler state.
      sBootTileMap.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 0; goto(sRefresh) }
          .elsewhen(bootCounter < TotalTileBytes) {
            cmdWr   := True
            cmdAddr := (U(tileMapBaseAddr, 23 bits) + bootCounter.resize(23)).resized
            // readAsync — AUDIT #10772: Class 1 (boot ROM, same-cycle FSM) —
            // boot copy emits cmdWr + cmdAddr + cmdDin in the SAME cycle as the
            // tileMapRom read. readSync would mis-align every SDRAM byte by 1
            // entry. Conversion requires lookahead-address FSM rework
            // (BlitterEngine srcRam pattern).
            cmdDin  := tileMapRom.readAsync(bootCounter.resize(log2Up(TotalTileBytes)))
            bootCounter := bootCounter + 1
          }.otherwise { bootCounter := 0; goto(sBootAttrMap) }
        }
      }

      sBootAttrMap.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 8; goto(sRefresh) }
          .elsewhen(bootCounter < TotalAttrBytes) {
            cmdWr   := True
            cmdAddr := (U(attributeMapBaseAddr, 23 bits) + bootCounter.resize(23)).resized
            // readAsync — AUDIT #10772: Class 1 (boot ROM, same-cycle FSM) —
            // attr-map boot copy; same pattern as sBootTileMap above.
            cmdDin  := attrMapRom.readAsync(bootCounter.resize(log2Up(TotalAttrBytes)))
            bootCounter := bootCounter + 1
          }.otherwise { bootCounter := 0; goto(sBootTileRows) }
        }
      }

      sBootTileRows.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 1; goto(sRefresh) }
          .elsewhen(bootCounter < TotalRowBytes) {
            cmdWr   := True
            cmdAddr := (U(tileRowBaseAddr, 23 bits) + bootCounter.resize(23)).resized
            // readAsync — AUDIT #10772: Class 1 (boot ROM, same-cycle FSM) —
            // tile-row boot copy; same pattern as sBootTileMap above.
            cmdDin  := tileRowRom.readAsync(bootCounter.resize(log2Up(TotalRowBytes)))
            bootCounter := bootCounter + 1
          }.otherwise {
            bootCounter := 0
            if (bootPlanarAssets) {
              goto(sBootPlanar)
            } else {
              // Task 56 Checkpoint B: L1 instance skips planar/plane1 boot
              // (L0 already populates PlanarTileAssets.SdramBase / Plane1SdramBase;
              // a second copy would double-write the same region).
              bootDoneR := True
              if (runMemtest) {
                goto(sMemtestWrite)
              } else {
                memtestPassR := True
                goto(sIdle)
              }
            }
          }
        }
      }

      // R4.1b stage 2: copy planar test rows into SDRAM at PlanarTileAssets.SdramBase.
      sBootPlanar.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 10; goto(sRefresh) }
          .elsewhen(bootCounter < PlanarTileAssets.TotalBytes) {
            cmdWr   := True
            cmdAddr := (U(PlanarTileAssets.SdramBase, 23 bits) + bootCounter.resize(23)).resized
            // readAsync — AUDIT #10772: Class 1 (boot ROM, same-cycle FSM) —
            // planar row boot copy (plane 0); same pattern as sBootTileMap above.
            cmdDin  := planarRowRom.readAsync(bootCounter.resize(log2Up(PlanarTileAssets.TotalBytes)))
            bootCounter := bootCounter + 1
          }.otherwise { bootCounter := 0; goto(sBootPlane1) }
        }
      }

      // R4.1d Checkpoint B: copy plane-1 ROM into SDRAM at Plane1SdramBase
      // (0xB000). Same stride as plane 0, carries plane 1 bits at byte[0..1]
      // of each row so the word-1 32-bit fetch in shuffled mode lands plane 1
      // data in the low 16 bits of the second row word.
      sBootPlane1.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 11; goto(sRefresh) }
          .elsewhen(bootCounter < PlanarTileAssets.TotalBytes) {
            cmdWr   := True
            cmdAddr := (U(PlanarTileAssets.Plane1SdramBase, 23 bits) + bootCounter.resize(23)).resized
            // readAsync — AUDIT #10772: Class 1 (boot ROM, same-cycle FSM) —
            // planar row boot copy (plane 1); same pattern as sBootTileMap above.
            cmdDin  := plane1RowRom.readAsync(bootCounter.resize(log2Up(PlanarTileAssets.TotalBytes)))
            bootCounter := bootCounter + 1
          }.otherwise {
            bootCounter := 0
            bootDoneR := True
            if (runMemtest) {
              goto(sMemtestWrite)
            } else {
              memtestPassR := True
              goto(sIdle)
            }
          }
        }
      }

      sMemtestWrite.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 6; goto(sRefresh) }
          .elsewhen(bootCounter < MemtestSize) {
            cmdWr   := True
            cmdAddr := (U(MemtestBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := memtestByte(bootCounter.resize(8))
            bootCounter := bootCounter + 1
          }.otherwise { bootCounter := 0; goto(sMemtestReadRq) }
        }
      }

      sMemtestReadRq.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 7; goto(sRefresh) }
          .elsewhen(bootCounter < MemtestSize) {
            cmdRd   := True
            cmdAddr := (U(MemtestBase, 23 bits) + bootCounter.resize(23)).resized
            goto(sMemtestCheck)
          }.otherwise { memtestPassR := True; goto(sIdle) }
        }
      }

      sMemtestCheck.whenIsActive {
        when(io.sdramDataReady) {
          val expected = memtestByte(bootCounter.resize(8))
          when(io.sdramDout =/= expected) {
            memtestFailR := True
            goto(sIdle)
          }.otherwise {
            bootCounter := bootCounter + 1
            goto(sMemtestReadRq)
          }
        }
      }

      // P3 CP-B(1) #10791 Risk #5 (bootDoneR monotone). Once asserted, the
      // boot-done flag must never clear — every fetch downstream assumes the
      // SDRAM contents are stable. Catches accidental Reg re-init or stray
      // assignment in a future refactor of the boot FSM.
      val bootDoneRPrev = RegNext(bootDoneR) init False
      assert(
        assertion = !bootDoneRPrev || bootDoneR,
        message   = "P3 Risk #5: bootDoneR cleared after being set",
        severity  = ERROR
      )

      // P3 CP-B(1) #10791 Risk #6 (fetchGrantEdge before settled memtestPassR).
      // memtestPassR is set in the sdram domain; fetchGrantEdge derives from a
      // BufferCC'd fetchGrant. The when-gate below checks memtestPassR but does
      // not require it to have been stable for ≥1 cycle. Catches a 1-cycle
      // glitch where memtestPassR rises in the same cycle as fetchGrantEdge.
      val memtestPassRPrev = RegNext(memtestPassR) init False
      assert(
        assertion = !fetchGrantEdge || (memtestPassR === memtestPassRPrev),
        message   = "P3 Risk #6: fetchGrantEdge fired in same cycle memtestPassR changed",
        severity  = ERROR
      )

      sIdle.whenIsActive {
        when(fetchGrantEdge && memtestPassR) {
          curLine    := fetchLineSync
          curScrollX := fetchScrollXSync
          curScrollY := fetchScrollYSync
          tileIdx    := 0
          goto(sFetchMapRq)
        }.elsewhen(refreshPending && !io.sdramBusy) {
          cmdRefresh := True; refreshPending := False; refreshReturn := 2; goto(sRefresh)
        }
      }

      // Fetch: gated by readGate. When slotValid drops we stall in the Rq
      // states; when it rises again we resume.
      sFetchMapRq.whenIsActive {
        when(readGate && !io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 3; goto(sRefresh) }
          .otherwise {
            // R4.2-redo bug #2 fix (CyanPeak #7124): keep the full 11-bit
            // sum so values 1024..1663 don't truncate to 0..639, which
            // previously caused wrong-tileX attribute reads = bank-bleeding
            // stripes. Handle both single-wrap (>=640) and double-wrap
            // (>=1280) cases since max sum is 640+1023=1663.
            // R5.4: use the shared ScrollWrap output
            val txCoord = pxXWrapped(log2Up(MapPixelsX) - 1 downto 4)
            cmdRd   := True
            cmdAddr := tileMapByteAddr(txCoord.resize(8), tileYCoord.resize(8))
            goto(sFetchMapWait)
          }
        }
      }

      sFetchMapWait.whenIsActive {
        when(io.sdramDataReady) {
          tileIndexReg := io.sdramDout.resize(log2Up(TileCount)).asUInt
          goto(sFetchAttrRq)
        }
      }

      sFetchAttrRq.whenIsActive {
        when(readGate && !io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 9; goto(sRefresh) }
          .otherwise {
            // R4.2-redo bug #2 fix (CyanPeak #7124): keep the full 11-bit
            // sum so values 1024..1663 don't truncate to 0..639, which
            // previously caused wrong-tileX attribute reads = bank-bleeding
            // stripes. Handle both single-wrap (>=640) and double-wrap
            // (>=1280) cases since max sum is 640+1023=1663.
            // R5.4: use the shared ScrollWrap output
            val txCoord = pxXWrapped(log2Up(MapPixelsX) - 1 downto 4)
            // R4.1c: in packed 2×2 mode, one attr byte covers a 2×2 tile block,
            // so the address uses block coords (tileX>>1, tileY>>1). Linear
            // mode uses per-tile coords as before.
            val attrTx = Mux(attributeModeSync, (txCoord    >> 1).resize(8), txCoord.resize(8))
            val attrTy = Mux(attributeModeSync, (tileYCoord >> 1).resize(8), tileYCoord.resize(8))
            cmdRd   := True
            cmdAddr := attrMapByteAddr(attrTx, attrTy)
            goto(sFetchAttrWait)
          }
        }
      }

      sFetchAttrWait.whenIsActive {
        when(io.sdramDataReady) {
          attrByteReg := io.sdramDout
          // R4.1c: linear mode uses bits[2:0]=bank, bit[3]=priority.
          // Packed 2×2 mode extracts a 2-bit field selected by (subX,subY)
          // within the 2×2 block: TL=[1:0], TR=[3:2], BL=[5:4], BR=[7:6].
          // Priority is not encoded in packed bytes (forced False).
          val subX = pxXWrapped(4)             // tileX(0) == bit 4 of pixel coord
          val subY = tileYCoord(0)
          val packedSel = (subY ## subX).asUInt   // 0=TL, 1=TR, 2=BL, 3=BR
          val packedField = io.sdramDout.subdivideIn(2 bits)(packedSel)
          when(attributeModeSync) {
            bankReg     := packedField.asUInt.resize(3)
            priorityReg := False
          }.otherwise {
            bankReg     := io.sdramDout(2 downto 0).asUInt
            priorityReg := io.sdramDout(3)
          }
          // Telemetry probe latches removed — see debugAttrTL/BR comment above.
          goto(sFetchRowRq0)
        }
      }

      sFetchRowRq0.whenIsActive {
        when(readGate && !io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 4; goto(sRefresh) }
          .otherwise {
            cmdRd   := True
            cmdAddr := tileRowByteAddr(tileIndexReg.resize(8), pixelYInTile.resize(8), U(0, 2 bits))
            goto(sFetchRowWait0)
          }
        }
      }

      sFetchRowWait0.whenIsActive {
        when(io.sdramDataReady) {
          rowWord0Reg := io.sdramDout32
          goto(sFetchRowRq1)
        }
      }

      sFetchRowRq1.whenIsActive {
        when(readGate && !io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 5; goto(sRefresh) }
          .otherwise {
            cmdRd   := True
            cmdAddr := tileRowByteAddr(tileIndexReg.resize(8), pixelYInTile.resize(8), U(1, 2 bits))
            goto(sFetchRowWait1)
          }
        }
      }

      sFetchRowWait1.whenIsActive {
        when(io.sdramDataReady) {
          pushPayload := (priorityReg ## bankReg.asBits ## io.sdramDout32 ## rowWord0Reg).asBits.resize(tilePayloadBits)
          pushValid   := True
          goto(sPushTile)
        }
      }

      sPushTile.whenIsActive {
        when(wordFifo.io.push.fire) {
          when(tileIdx === TilesPerLine - 1) {
            goto(sIdle)
          }.otherwise {
            tileIdx := tileIdx + 1
            goto(sFetchMapRq)
          }
        }
      }

      sRefresh.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          switch(refreshReturn) {
            is(0) { goto(sBootTileMap) }
            is(1) { goto(sBootTileRows) }
            is(2) { goto(sIdle) }
            is(3) { goto(sFetchMapRq) }
            is(4) { goto(sFetchRowRq0) }
            is(5) { goto(sFetchRowRq1) }
            is(6) { goto(sMemtestWrite) }
            is(7) { goto(sMemtestReadRq) }
            is(8) { goto(sBootAttrMap) }
            is(9) { goto(sFetchAttrRq) }
            is(10) { goto(sBootPlanar) }   // R4.1b stage 2
            is(11) { goto(sBootPlane1) }   // R4.1d Checkpoint B
            default { goto(sIdle) }
          }
        }
      }
    }

    io.sdramRd      := cmdRd
    io.sdramWr      := cmdWr
    io.sdramRefresh := cmdRefresh
    io.sdramAddr    := cmdAddr
    io.sdramDin     := cmdDin
    // #11246 F2 (GT-17, CyanPeak #11256): cmdRd/Wr/Refresh are REGISTERS — they
    // assert the cycle AFTER the FSM commits to a transaction. A top-level upload
    // gate that samples the registered sdramRd can fire a write the same cycle the
    // engine is about to read; sdram.v's rd|wr ternary then takes the read and the
    // upload write is silently lost. Expose the register INPUTS (next-cycle value)
    // so the upload canAccept can look one cycle ahead and never collide.
    io.sdramRdNext      := cmdRd.getAheadValue()
    io.sdramWrNext      := cmdWr.getAheadValue()
    io.sdramRefreshNext := cmdRefresh.getAheadValue()
  }

  io.bootDone    := BufferCC(sdramArea.bootDoneR,    init = False)
  io.memtestPass := BufferCC(sdramArea.memtestPassR, init = False)
  io.memtestFail := BufferCC(sdramArea.memtestFailR, init = False)

  // flipBlinkCounter (16-bit free-running, drove debugWriteBuf LED) and
  // debugAttrTL/BR BufferCCs removed — their outputs have no external
  // consumer, the producing logic was pure bring-up overhead. Tie outputs
  // to their stuck-sentinel contract values.
  io.debugWriteBuf := False
  io.debugAttrTL   := U(7, 3 bits)
  io.debugAttrBR   := U(7, 3 bits)
}

object SdramTileAttributeFetchVerilog extends App {
  Config.spinal.generateVerilog {
    val sdramCd = ClockDomain.external(
      "sdram",
      frequency = FixedFrequency(40500000 Hz)
    )
    SdramTileAttributeFetch(sdramCd)
  }
}
