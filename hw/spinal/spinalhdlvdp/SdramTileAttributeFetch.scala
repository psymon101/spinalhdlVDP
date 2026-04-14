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
case class SdramTileAttributeFetch(sdramCd: ClockDomain) extends Component {
  import TileAttributeAssets._

  val LineWidth      = MapPixelsX                      // 640
  val TilesPerLine   = MapTilesX + 1                    // 41 (one extra for sub-tile hscroll)
  val TotalTileBytes = MapEntries                       // 1200
  val TotalAttrBytes = MapEntries                       // 1200
  val TotalRowBytes  = TileCount * TileHeight * TileRowBytes  // 4*16*8 = 512

  val MemtestBase = 0x2000
  val MemtestSize = 256
  def memtestByte(i: UInt): Bits = (i.resize(8) ^ U(0xA5, 8 bits)).asBits

  val RefreshPeriodCycles = 950
  val FifoDepth = 32              // 4 words per tile now, double the previous depth
  val PixelLineBits = 8           // {priority[7], bank[6:4], index[3:0]}

  val io = new Bundle {
    // SDRAM controller interface
    val sdramAddr      = out UInt(23 bits)
    val sdramDin       = out Bits(8 bits)
    val sdramRd        = out Bool()
    val sdramWr        = out Bool()
    val sdramRefresh   = out Bool()
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

    // R4.1b stage 1 (#7098): tile-row decode mode.
    //   0 = packed 4bpp (R4 baseline) — 16 pixels per 64-bit row
    //   1 = NES-style 2-plane 2bpp planar — word0[15:0] = plane 0,
    //       word1[15:0] = plane 1. Pixel = {plane1[x], plane0[x]}, range 0..3.
    val tileDecodeMode  = in  Bits(1 bits)

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
    val debugAttrTL  = out UInt(3 bits)   // top-left (tileIdx=2,  tileY=2 ) — exp 1
    val debugAttrBR  = out UInt(3 bits)   // bot-right(tileIdx=30, tileY=25) — exp 4
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

  when(io.fetchGrant) {
    tileCountReg := 0
    subOffsetReg := io.fetchScrollX(3 downto 0)
    unpackIdx    := 0
    emitting     := False
    underrunR    := False
  }
  when(fetchStartRise) {
    writeBuf := !writeBuf
  }

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
    val px4Packed = unpackRow.subdivideIn(4 bits)(unpackIdx)
    val plane0Bits = unpackRow(15 downto 0)
    val plane1Bits = unpackRow(47 downto 32)
    val planarBitIdx = (U(15, 4 bits) - unpackIdx).resize(4)
    val px2Planar = (plane1Bits(planarBitIdx) ## plane0Bits(planarBitIdx)).asBits
    val px4Planar = px2Planar.resize(4)
    val px4 = Mux(io.tileDecodeMode(0), px4Planar, px4Packed)
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
    val refreshTimer   = Reg(UInt(10 bits)) init 0
    val refreshPending = Reg(Bool()) init False
    when(refreshTimer === RefreshPeriodCycles - 1) {
      refreshTimer   := 0
      refreshPending := True
    } otherwise {
      refreshTimer := refreshTimer + 1
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

    val tileMapRom  = Mem(Bits(8 bits), initialContent = TileAttributeAssets.tileMapBytesInit)
    val attrMapRom  = Mem(Bits(8 bits), initialContent = TileAttributeAssets.attributeMapBytesInit)
    val tileRowRom  = Mem(Bits(8 bits), initialContent = TileAttributeAssets.tileRowBytesInit)
    // R4.1b stage 2: planar boot ROM lives alongside the packed ROMs and is
    // boot-copied to SDRAM at PlanarTileAssets.SdramBase (0xA000), disjoint
    // from the R4 packed regions.
    val planarRowRom = Mem(Bits(8 bits), initialContent = PlanarTileAssets.planarRowBytesInit)

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
    val debugAttrTLReg = Reg(UInt(3 bits)) init 7   // init to "7" so a stuck
    val debugAttrBRReg = Reg(UInt(3 bits)) init 7   // engine is distinguishable
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
      (U(TileMapBase, 23 bits) + offset).resize(23)
    }
    def attrMapByteAddr(tx: UInt, ty: UInt): UInt = {
      val offset = (ty.resize(16) * U(MapTilesX, 16 bits) + tx.resize(16)).resize(23)
      (U(AttributeMapBase, 23 bits) + offset).resize(23)
    }
    // R4.1b: planar mode reads tile rows from PlanarTileAssets.SdramBase
    // instead of TileRowBase. Tile row layout is identical (16 rows × 8 bytes
    // per tile), so only the base address swaps.
    val tileRowBaseSel = Mux(io.tileDecodeMode(0),
                             U(PlanarTileAssets.SdramBase, 23 bits),
                             U(TileRowBase, 23 bits))
    val tileRowBaseSelSync = BufferCC(tileRowBaseSel, init = U(TileRowBase, 23 bits))

    // R4.1c: packed-attribute mode select, 1-bit CDC (slow-changing register).
    val attributeModeSync = BufferCC(io.attributeMode(0), init = False)
    def tileRowByteAddr(tIdx: UInt, py: UInt, wordIdx: UInt): UInt = {
      val offset = ((tIdx.resize(16) * U(TileHeight * TileRowBytes, 16 bits)) +
                    (py.resize(16)   * U(TileRowBytes, 16 bits)) +
                    (wordIdx.resize(16) * U(4, 16 bits))).resize(23)
      (tileRowBaseSelSync + offset).resize(23)
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
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) { goto(sBootTileMap) }
      }

      // Boot copy: tile map → attribute map → tile rows. Not gated by readGate
      // so boot completes regardless of scheduler state.
      sBootTileMap.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 0; goto(sRefresh) }
          .elsewhen(bootCounter < TotalTileBytes) {
            cmdWr   := True
            cmdAddr := (U(TileMapBase, 23 bits) + bootCounter.resize(23)).resized
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
            cmdAddr := (U(AttributeMapBase, 23 bits) + bootCounter.resize(23)).resized
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
            cmdAddr := (U(TileRowBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := tileRowRom.readAsync(bootCounter.resize(log2Up(TotalRowBytes)))
            bootCounter := bootCounter + 1
          }.otherwise { bootCounter := 0; goto(sBootPlanar) }
        }
      }

      // R4.1b stage 2: copy planar test rows into SDRAM at PlanarTileAssets.SdramBase.
      sBootPlanar.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) { cmdRefresh := True; refreshPending := False; refreshReturn := 10; goto(sRefresh) }
          .elsewhen(bootCounter < PlanarTileAssets.TotalBytes) {
            cmdWr   := True
            cmdAddr := (U(PlanarTileAssets.SdramBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := planarRowRom.readAsync(bootCounter.resize(log2Up(PlanarTileAssets.TotalBytes)))
            bootCounter := bootCounter + 1
          }.otherwise { bootCounter := 0; bootDoneR := True; goto(sMemtestWrite) }
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
          // Telemetry: latch attr[2:0] at the two probe tile positions so
          // the LEDs report bank-values on real SDRAM. These regs are
          // sticky — last-sampled value holds between frames.
          when(tileIdx === U(2, 6 bits) && tileYCoord === U(2, 5 bits)) {
            debugAttrTLReg := io.sdramDout(2 downto 0).asUInt
          }
          when(tileIdx === U(30, 6 bits) && tileYCoord === U(25, 5 bits)) {
            debugAttrBRReg := io.sdramDout(2 downto 0).asUInt
          }
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
  }

  io.bootDone    := BufferCC(sdramArea.bootDoneR,    init = False)
  io.memtestPass := BufferCC(sdramArea.memtestPassR, init = False)
  io.memtestFail := BufferCC(sdramArea.memtestFailR, init = False)

  val flipBlinkCounter = Reg(UInt(16 bits)) init 0
  when(fetchStartRise) { flipBlinkCounter := flipBlinkCounter + 1 }
  io.debugWriteBuf := flipBlinkCounter(15)

  // CDC telemetry registers to pixel domain for LED display.
  io.debugAttrTL := BufferCC(sdramArea.debugAttrTLReg, init = U(7, 3 bits))
  io.debugAttrBR := BufferCC(sdramArea.debugAttrBRReg, init = U(7, 3 bits))
}

object SdramTileAttributeFetchVerilog extends App {
  Config.spinal.generateVerilog {
    val sdramCd = ClockDomain.external(
      "sdram",
      frequency = FixedFrequency(64800000 Hz)
    )
    SdramTileAttributeFetch(sdramCd)
  }
}
