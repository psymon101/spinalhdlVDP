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

    val bootDone     = out Bool()
    val memtestPass  = out Bool()
    val memtestFail  = out Bool()
    val underrun     = out Bool()
    val debugWriteBuf = out Bool()
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
    val px4 = unpackRow.subdivideIn(4 bits)(unpackIdx)
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

    // Pixel→SDRAM CDC of fetch controls
    val fetchGrantSync     = BufferCC(io.fetchGrant,     init = False)
    val fetchGrantD        = RegNext(fetchGrantSync) init False
    val fetchGrantEdge     = fetchGrantSync && !fetchGrantD
    val slotValidSync      = BufferCC(io.fetchSlotValid, init = False)

    val fetchLineSync      = BufferCC(io.fetchLine,    init = U(0, 10 bits))
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
    val attrByteReg   = Reg(Bits(8 bits)) init 0
    val bankReg       = Reg(UInt(3 bits)) init 0
    val priorityReg   = Reg(Bool()) init False
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

    def tileMapByteAddr(tx: UInt, ty: UInt): UInt =
      (U(TileMapBase, 23 bits) + (ty * U(MapTilesX, 8 bits) + tx).resize(23)).resized
    def attrMapByteAddr(tx: UInt, ty: UInt): UInt =
      (U(AttributeMapBase, 23 bits) + (ty * U(MapTilesX, 8 bits) + tx).resize(23)).resized
    def tileRowByteAddr(tIdx: UInt, py: UInt, wordIdx: UInt): UInt =
      (U(TileRowBase, 23 bits) +
        ((tIdx.resize(8) * U(TileHeight * TileRowBytes, 12 bits) +
          py.resize(8)   * U(TileRowBytes, 8 bits) +
          (wordIdx * 4).resize(8)).resize(23))).resized

    cmdRd      := False
    cmdWr      := False
    cmdRefresh := False

    // Gate any SDRAM command issuance (except refresh) on the scheduler slot.
    // Refresh is allowed any time the controller is idle so DRAM content stays
    // valid even in long closed windows.
    val readGate = slotValidSync

    val fsm = new StateMachine {
      val sPowerWait     = new State with EntryPoint
      val sBootTileMap   = new State
      val sBootAttrMap   = new State
      val sBootTileRows  = new State
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
            val rawX = ((tileIdx * U(16, 6 bits)) + curScrollX.resize(11)).resize(10)
            val wrappedPxX = UInt(10 bits)
            when(rawX >= U(MapPixelsX, 10 bits)) {
              wrappedPxX := (rawX - U(MapPixelsX, 10 bits)).resize(10)
            }.otherwise {
              wrappedPxX := rawX
            }
            val txCoord = wrappedPxX(9 downto 4)
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
            val rawX = ((tileIdx * U(16, 6 bits)) + curScrollX.resize(11)).resize(10)
            val wrappedPxX = UInt(10 bits)
            when(rawX >= U(MapPixelsX, 10 bits)) {
              wrappedPxX := (rawX - U(MapPixelsX, 10 bits)).resize(10)
            }.otherwise {
              wrappedPxX := rawX
            }
            val txCoord = wrappedPxX(9 downto 4)
            cmdRd   := True
            cmdAddr := attrMapByteAddr(txCoord.resize(8), tileYCoord.resize(8))
            goto(sFetchAttrWait)
          }
        }
      }

      sFetchAttrWait.whenIsActive {
        when(io.sdramDataReady) {
          attrByteReg := io.sdramDout
          bankReg     := io.sdramDout(2 downto 0).asUInt   // bits 2:0 = paletteBank
          priorityReg := io.sdramDout(3)                   // bit 3   = priority
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
