package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** SDRAM-backed tile fetch engine — Task 15 rebuild.
  *
  * Replaces the earlier single-clock-domain / byte-at-a-time scaffold.
  *
  * Architecture:
  *   - the fetch FSM runs in an explicit SDRAM clock domain (`sdramCd`, 64.8 MHz class)
  *   - reads from the reused `fpga/tang20k/third_party/sdram/sdram.v` controller via its
  *     32-bit `dout32` port (still ~5-cycle per transaction, but 32-bit wide)
  *   - external refresh scheduler (~15 µs tick) drives the controller's `refresh` input,
  *     since sdram.v does not self-schedule refresh
  *   - cross-domain handoff to the pixel clock uses `StreamFifoCC`, avoiding dual-clock BRAM
  *   - pixel-clock side holds the line buffer (640 × 3-bit) consumed by the existing
  *     VdpTop fill path
  *
  * SDRAM data layout (byte address, shared with `BasicPatternSource.tileMapInit/tileRowInit`):
  *   - tileMap  @ 0x0000 : 1200 bytes, one 3-bit tile index per byte (low 3 bits)
  *   - tileRows @ 0x1000 : 128 rows × 8 bytes (48-bit row zero-padded to 64 bits)
  *       tile `t` row `y` at 0x1000 + ((t * 16) + y) * 8
  *
  * Open items (to be resolved in simulation):
  *   - exact PLL math for 64.8 MHz CLKOUT + 180° CLKOUTP (GT-006)
  *   - registered handoff for data_ready / dout32 (GT-012)
  *   - memtest bounded range choice
  */
case class SdramTileFetch(sdramCd: ClockDomain) extends Component {
  import BasicPatternSource._

  // --------------------------------------------------------------------------
  // Parameters
  // --------------------------------------------------------------------------
  val LineWidth       = MapTilesX * TileWidth          // 640
  // Option 1 for sub-tile horizontal scroll: fetch one extra tile so the first
  // and last screen tiles can be shifted by `subOffset = scrollX % 16`. The
  // pixel-domain unpacker drops writes that land outside [0, LineWidth).
  val TilesPerLine    = MapTilesX + 1                  // 41
  // Sequence 2 diagnostic: shifted from (0x0000, 0x1000) per address-shift probe
  // (mail 6621). Memtest base stays at 0x2000 as a fixed control.
  val TileMapBase     = 0x4000
  val TileRowBase     = 0x5000
  val TileRowStride   = 8                               // bytes per row slot
  val TotalTileBytes  = 1200                            // tileMap size in bytes
  val TotalRowBytes   = TileCount * TileHeight * TileRowStride  // 8 * 16 * 8 = 1024

  // Memtest covers 256 bytes at 0x2000 (past tile data) with a bit-toggling
  // pattern — enough to flush bus read/write paths and catch a stuck-bit or
  // line-crossed controller issue before render-ready is asserted.
  val MemtestBase = 0x2000
  val MemtestSize = 256
  def memtestByte(i: UInt): Bits = (i.resize(8) ^ U(0xA5, 8 bits)).asBits

  // Refresh cadence: 15 µs at 64.8 MHz = 972 cycles. Use 950 for margin.
  val RefreshPeriodCycles = 950

  // Async FIFO depth: 32-bit words, two per tile (low half / high half of 48-bit row).
  // Depth 16 buffers 8 tiles of row data — plenty vs the ~3× per-scanline slack.
  val FifoDepth = 16

  // --------------------------------------------------------------------------
  // IO
  // --------------------------------------------------------------------------
  val io = new Bundle {
    // SDRAM controller interface (all signals in sdramCd)
    val sdramAddr      = out UInt(23 bits)
    val sdramDin       = out Bits(8 bits)
    val sdramRd        = out Bool()
    val sdramWr        = out Bool()
    val sdramRefresh   = out Bool()
    val sdramDout      = in  Bits(8 bits)
    val sdramDout32    = in  Bits(32 bits)
    val sdramDataReady = in  Bool()
    val sdramBusy      = in  Bool()

    // Pixel-clock-domain API (implicit clock = pixel clock)
    val fetchStart     = in  Bool()              // pulse at hblank for next line
    val fetchLine      = in  UInt(10 bits)
    val fetchScrollX   = in  UInt(10 bits)
    val fetchScrollY   = in  UInt(10 bits)

    val pixelAddr      = in  UInt(10 bits)       // 0..639
    val pixelIndex     = out Bits(PixelBits bits)

    val bootDone       = out Bool()
    val memtestPass    = out Bool()
    val memtestFail    = out Bool()              // sticky: memtest read-back mismatch
    val underrun       = out Bool()              // per-line: cleared on fetchStart
    val debugPushStalls = out UInt(16 bits)      // cum. SDRAM-side FIFO full-stall cycles
    val debugWriteBuf   = out Bool()             // ping-pong select (flips each fetchStart)
  }

  // --------------------------------------------------------------------------
  // Pixel-domain side: line buffer + FIFO pop
  // --------------------------------------------------------------------------
  // Ping-pong line buffers. The single-buffer design raced on hardware because
  // SDRAM-domain fetch (64.8 MHz) overtakes pixel-domain read (25.2 MHz) within
  // ~30 pixels of each line, so L0 saw next-line data for most of the line.
  // writeBuf selects the buffer being overwritten by the current fetch; L0
  // always reads the OTHER buffer (which was just filled by the previous fetch
  // and stays stable until the next fetchStart swaps the roles).
  val lineBufferA = Mem(Bits(PixelBits bits), LineWidth)
  val lineBufferB = Mem(Bits(PixelBits bits), LineWidth)
  val writeBuf    = Reg(Bool()) init False
  val readA       = lineBufferA.readSync(io.pixelAddr)
  val readB       = lineBufferB.readSync(io.pixelAddr)
  // Read the buffer that the CURRENT fetch is NOT writing — that's the one the
  // previous fetch just filled. Guarantees the race is broken at the cost of a
  // one-fetch pipeline: reader sees fetch(N-1)'s data while fetch(N) runs.
  io.pixelIndex := Mux(writeBuf, readB, readA)

  // Cross-domain FIFO: SDRAM-side pushes 32-bit tile-row words (two per tile).
  // Pixel-domain side combines (word0, word1) → 48-bit packed row and unpacks 16×3-bit
  // pixels into the line buffer. Lower CDC traffic than pixel-granularity FIFO.
  val wordFifo = StreamFifoCC(
    dataType  = Bits(32 bits),
    depth     = FifoDepth,
    pushClock = sdramCd,
    popClock  = ClockDomain.current
  )

  // Pixel-domain unpack state. Writes 16 pixels per tile pair into the line
  // buffer. Option 1: for sub-tile horizontal scroll, the screen x of tile `t`
  // pixel `k` is `t*16 + k - subOffset`. Writes outside [0, LineWidth) are
  // discarded; the SDRAM side fetches one extra tile so first-tile head and
  // last-tile tail both land in-range.
  val unpackPhase  = Reg(UInt(1 bit))    init 0
  val unpackWord0  = Reg(Bits(32 bits))  init 0
  val unpackRow    = Reg(Bits(48 bits))  init 0
  val tileCountReg = Reg(UInt(6 bits))   init 0        // 0..TilesPerLine-1
  val subOffsetReg = Reg(UInt(4 bits))   init 0        // scrollX % 16 latched at line start
  val unpackIdx    = Reg(UInt(4 bits))   init 0
  val emitting     = Reg(Bool())         init False
  val underrunR    = Reg(Bool())         init False
  io.underrun := underrunR

  // Edge-detect fetchStart so the buffer swap happens once per pulse, regardless
  // of how many cycles the upstream holds the level high.
  val fetchStartPixD = RegNext(io.fetchStart) init False
  val fetchStartRise = io.fetchStart && !fetchStartPixD

  when(io.fetchStart) {
    unpackPhase  := 0
    tileCountReg := 0
    subOffsetReg := io.fetchScrollX(3 downto 0)
    unpackIdx    := 0
    emitting     := False
    underrunR    := False   // underrun is per-line, not sticky across lines
  }
  when(fetchStartRise) {
    writeBuf := !writeBuf   // ping-pong: swap on the rising edge only
  }

  wordFifo.io.pop.ready := !emitting
  when(wordFifo.io.pop.fire) {
    when(unpackPhase === 0) {
      unpackWord0 := wordFifo.io.pop.payload
      unpackPhase := 1
    }.otherwise {
      unpackRow   := (wordFifo.io.pop.payload(15 downto 0) ## unpackWord0).asBits
      unpackIdx   := 0
      emitting    := True
      unpackPhase := 0
    }
  }

  when(emitting) {
    val px = unpackRow.subdivideIn(PixelBits bits)(unpackIdx)
    // Shifted-write address with overflow safety. shifted = t*16 + k + 16 - subOffset
    // is always > 0 (subOffset ≤ 15). writeEnable fences writes outside the
    // visible [0, LineWidth) span.
    val shifted = ((tileCountReg * U(16, 6 bits)).resize(11)
                    + unpackIdx.resize(11)
                    + U(16, 11 bits)
                    - subOffsetReg.resize(11)).resize(11)
    val writeEnable = (shifted >= U(16, 11 bits)) && (shifted < U(LineWidth + 16, 11 bits))
    val writeAddr   = (shifted - U(16, 11 bits)).resize(10)
    when(writeEnable) {
      // Route write to whichever ping-pong buffer this fetch owns.
      when(writeBuf) { lineBufferA.write(writeAddr, px) }
      .otherwise    { lineBufferB.write(writeAddr, px) }
    }
    when(unpackIdx === 15) {
      emitting     := False
      tileCountReg := tileCountReg + 1
    }
    unpackIdx := unpackIdx + 1
  }

  // Underrun detection: pixel read reaches line-end while fill still in progress.
  when(emitting && io.pixelAddr === LineWidth - 1) {
    underrunR := True
  }

  // --------------------------------------------------------------------------
  // SDRAM-clock-domain side: fetch FSM + refresh scheduler
  // --------------------------------------------------------------------------
  val sdramArea = new ClockingArea(sdramCd) {

    // ----- Refresh scheduler (runs continuously) --------------------------
    val refreshTimer  = Reg(UInt(10 bits)) init 0
    val refreshPending = Reg(Bool()) init False
    when(refreshTimer === RefreshPeriodCycles - 1) {
      refreshTimer   := 0
      refreshPending := True
    } otherwise {
      refreshTimer := refreshTimer + 1
    }

    // ----- Fetch-start handoff (pixel → SDRAM domain) ---------------------
    // Level-based start request, edge-detected on SDRAM side (GT-017).
    val fetchStartSync = BufferCC(io.fetchStart, init = False)
    val fetchStartD    = RegNext(fetchStartSync) init False
    val fetchStartEdge = fetchStartSync && !fetchStartD

    val fetchLineSync    = BufferCC(io.fetchLine,    init = U(0, 10 bits))
    val fetchScrollXSync = BufferCC(io.fetchScrollX, init = U(0, 10 bits))
    val fetchScrollYSync = BufferCC(io.fetchScrollY, init = U(0, 10 bits))

    // Latched for the duration of a fetch cycle
    val curLine    = Reg(UInt(10 bits)) init 0
    val curScrollX = Reg(UInt(10 bits)) init 0
    val curScrollY = Reg(UInt(10 bits)) init 0

    // ----- Command lines (drive controller) -------------------------------
    val cmdRd      = RegInit(False)
    val cmdWr      = RegInit(False)
    val cmdRefresh = RegInit(False)
    val cmdAddr    = Reg(UInt(23 bits)) init 0
    val cmdDin     = Reg(Bits(8 bits))  init 0

    // ----- On-chip ROMs used for boot-copy --------------------------------
    val tileMapRom = Mem(Bits(8 bits), initialContent = tileMapBytesInit)
    val tileRowRom = Mem(Bits(8 bits), initialContent = tileRowBytesInit)

    // ----- FIFO push side (32-bit words) ---------------------------------
    val pushValid   = RegInit(False)
    val pushPayload = Reg(Bits(32 bits)) init 0
    wordFifo.io.push.valid   := pushValid
    wordFifo.io.push.payload := pushPayload
    when(wordFifo.io.push.fire) { pushValid := False }

    // FIFO back-pressure telemetry: cycles where the SDRAM-side tried to push
    // but the async FIFO was full. Any sustained stall means the FIFO depth
    // (or pop-side drain rate) is too small.
    val pushStalls = Reg(UInt(16 bits)) init 0
    when(pushValid && !wordFifo.io.push.ready && !pushStalls.andR) {
      pushStalls := pushStalls + 1
    }

    // ----- Status flags ---------------------------------------------------
    val bootDoneR    = RegInit(False)
    val memtestPassR = RegInit(False)
    val memtestFailR = RegInit(False)

    // ----- Fetch working state -------------------------------------------
    val bootCounter   = Reg(UInt(12 bits)) init 0       // up to 4096 bytes
    val tileIdx       = Reg(UInt(6 bits))  init 0       // 0..39
    val tileIndexReg  = Reg(UInt(TileIndexBits bits)) init 0

    // Wrapped Y coordinate for the current line
    val lineY10 = (curLine + curScrollY).resize(10)
    val wrappedY = UInt(10 bits)
    when(lineY10 >= U(MapTilesY * TileHeight * 2, 10 bits)) {
      wrappedY := lineY10 - U(MapTilesY * TileHeight * 2, 10 bits)
    }.elsewhen(lineY10 >= U(MapTilesY * TileHeight, 10 bits)) {
      wrappedY := lineY10 - U(MapTilesY * TileHeight, 10 bits)
    }.otherwise {
      wrappedY := lineY10
    }
    val tileYCoord = wrappedY(8 downto 4)
    val pixelYInTile = wrappedY(3 downto 0)

    // Address helpers
    def tileMapByteAddr(tx: UInt, ty: UInt): UInt =
      (U(TileMapBase, 23 bits) + (ty * U(MapTilesX, 8 bits) + tx).resize(23)).resized
    def tileRowByteAddr(tIdx: UInt, py: UInt, wordIdx: UInt): UInt =
      (U(TileRowBase, 23 bits) +
        ((tIdx.resize(8) * U(TileHeight * TileRowStride, 12 bits) +
          py.resize(8)   * U(TileRowStride, 8 bits) +
          (wordIdx * 4).resize(8)).resize(23))).resized

    // ----- Defaults for single-cycle command pulses (overridden by FSM) ---
    cmdRd      := False
    cmdWr      := False
    cmdRefresh := False

    // ----- FSM ------------------------------------------------------------
    val fsm = new StateMachine {
      val sPowerWait     = new State with EntryPoint
      val sBootTileMap   = new State
      val sBootTileRows  = new State
      val sMemtestWrite  = new State
      val sMemtestReadRq = new State
      val sMemtestCheck  = new State
      val sIdle          = new State
      val sFetchMapRq    = new State
      val sFetchMapWait  = new State
      val sFetchRowRq0   = new State
      val sFetchRowWait0 = new State
      val sPushWord0     = new State
      val sFetchRowRq1   = new State
      val sFetchRowWait1 = new State
      val sPushWord1     = new State
      val sRefresh       = new State

      // Helper: if a refresh is pending and controller is idle, preempt
      def maybeRefresh(returnState: State): Bool = {
        val took = refreshPending && !io.sdramBusy
        when(took) {
          cmdRefresh     := True
          refreshPending := False
          goto(sRefresh)
          // stash return path by latching; see sRefresh below
        }
        took
      }
      val refreshReturn = Reg(UInt(4 bits)) init 0  // encoded return state id

      sPowerWait.whenIsActive {
        // Controller self-handles 200 µs init via resetn; wait for !busy after reset.
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) { goto(sBootTileMap) }
      }

      sBootTileMap.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) {
            cmdRefresh     := True
            refreshPending := False
            refreshReturn  := 0  // return here
            goto(sRefresh)
          }.elsewhen(bootCounter < TotalTileBytes) {
            cmdWr   := True
            cmdAddr := (U(TileMapBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := tileMapRom.readAsync(bootCounter.resize(log2Up(TotalTileBytes)))
            bootCounter := bootCounter + 1
          }.otherwise {
            bootCounter := 0
            goto(sBootTileRows)
          }
        }
      }

      sBootTileRows.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) {
            cmdRefresh     := True
            refreshPending := False
            refreshReturn  := 1
            goto(sRefresh)
          }.elsewhen(bootCounter < TotalRowBytes) {
            cmdWr   := True
            cmdAddr := (U(TileRowBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := tileRowRom.readAsync(bootCounter.resize(log2Up(TotalRowBytes)))
            bootCounter := bootCounter + 1
          }.otherwise {
            bootCounter := 0
            bootDoneR   := True
            goto(sMemtestWrite)
          }
        }
      }

      sIdle.whenIsActive {
        // Gate fetches on memtest success. If memtest failed, stay idle — the
        // top-level should see !memtestPass and keep the on-chip fallback.
        when(fetchStartEdge && memtestPassR) {
          curLine    := fetchLineSync
          curScrollX := fetchScrollXSync
          curScrollY := fetchScrollYSync
          tileIdx    := 0
          goto(sFetchMapRq)
        }.elsewhen(refreshPending && !io.sdramBusy) {
          cmdRefresh     := True
          refreshPending := False
          refreshReturn  := 2
          goto(sRefresh)
        }
      }

      // --- Tile map byte read ---
      sFetchMapRq.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) {
            cmdRefresh     := True
            refreshPending := False
            refreshReturn  := 3
            goto(sRefresh)
          }.otherwise {
            // Scroll semantics must match BasicPatternSource, which does
            //   (x + scrollX).resize(10) followed by a single -640 wrap.
            // The 10-bit truncation is effectively `mod 1024` and is part of
            // the comparison baseline — even though it produces unusual results
            // for scrolls that push the sum past 1023, the SDRAM path has to
            // reproduce it to stay byte-identical with the on-chip reference.
            val rawX = ((tileIdx * U(16, 6 bits)) + curScrollX.resize(11)).resize(10)
            val wrappedPxX = UInt(10 bits)
            when(rawX >= U(MapTilesX * TileWidth, 10 bits)) {
              wrappedPxX := (rawX - U(MapTilesX * TileWidth, 10 bits)).resize(10)
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
          // GT-012: register dout immediately to avoid same-cycle race
          tileIndexReg := io.sdramDout.resize(TileIndexBits).asUInt
          goto(sFetchRowRq0)
        }
      }

      // --- Tile row first 32-bit word ---
      sFetchRowRq0.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) {
            cmdRefresh     := True
            refreshPending := False
            refreshReturn  := 4
            goto(sRefresh)
          }.otherwise {
            cmdRd   := True
            cmdAddr := tileRowByteAddr(tileIndexReg.resize(8),
                                       pixelYInTile.resize(8),
                                       U(0, 2 bits))
            goto(sFetchRowWait0)
          }
        }
      }

      sFetchRowWait0.whenIsActive {
        when(io.sdramDataReady) {
          pushPayload := io.sdramDout32
          pushValid   := True
          goto(sPushWord0)
        }
      }

      sPushWord0.whenIsActive {
        when(wordFifo.io.push.fire) {
          goto(sFetchRowRq1)
        }
      }

      // --- Tile row second 32-bit word ---
      sFetchRowRq1.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) {
            cmdRefresh     := True
            refreshPending := False
            refreshReturn  := 5
            goto(sRefresh)
          }.otherwise {
            cmdRd   := True
            cmdAddr := tileRowByteAddr(tileIndexReg.resize(8),
                                       pixelYInTile.resize(8),
                                       U(1, 2 bits))
            goto(sFetchRowWait1)
          }
        }
      }

      sFetchRowWait1.whenIsActive {
        when(io.sdramDataReady) {
          pushPayload := io.sdramDout32
          pushValid   := True
          goto(sPushWord1)
        }
      }

      sPushWord1.whenIsActive {
        when(wordFifo.io.push.fire) {
          when(tileIdx === TilesPerLine - 1) {
            goto(sIdle)
          }.otherwise {
            tileIdx := tileIdx + 1
            goto(sFetchMapRq)
          }
        }
      }

      // --- Refresh wait: block until controller idle again, then return ---
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
            default { goto(sIdle) }
          }
        }
      }

      // ----- Explicit memtest: write pattern, read back, compare -----------
      // Gates render-ready so a broken SDRAM path can't silently pass first boot.
      sMemtestWrite.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) {
            cmdRefresh     := True
            refreshPending := False
            refreshReturn  := 6
            goto(sRefresh)
          }.elsewhen(bootCounter < MemtestSize) {
            cmdWr   := True
            cmdAddr := (U(MemtestBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := memtestByte(bootCounter.resize(8))
            bootCounter := bootCounter + 1
          }.otherwise {
            bootCounter := 0
            goto(sMemtestReadRq)
          }
        }
      }

      sMemtestReadRq.whenIsActive {
        when(!io.sdramBusy && !cmdRd && !cmdWr && !cmdRefresh) {
          when(refreshPending) {
            cmdRefresh     := True
            refreshPending := False
            refreshReturn  := 7
            goto(sRefresh)
          }.elsewhen(bootCounter < MemtestSize) {
            cmdRd   := True
            cmdAddr := (U(MemtestBase, 23 bits) + bootCounter.resize(23)).resized
            goto(sMemtestCheck)
          }.otherwise {
            memtestPassR := True   // every byte matched
            goto(sIdle)
          }
        }
      }

      sMemtestCheck.whenIsActive {
        when(io.sdramDataReady) {
          val expected = memtestByte(bootCounter.resize(8))
          when(io.sdramDout =/= expected) {
            memtestFailR := True
            // park here: do NOT set memtestPassR, do NOT proceed to fetch
            goto(sIdle)
          }.otherwise {
            bootCounter := bootCounter + 1
            goto(sMemtestReadRq)
          }
        }
      }
    }

    // ----- Drive controller outputs --------------------------------------
    io.sdramRd      := cmdRd
    io.sdramWr      := cmdWr
    io.sdramRefresh := cmdRefresh
    io.sdramAddr    := cmdAddr
    io.sdramDin     := cmdDin

    // Status → pixel domain (CDC done implicitly by Spinal since IOs are level signals
    // on the component boundary; consumer must BufferCC if sampling from pixel clock).
  }

  // Pixel-domain side sees status flags via CC
  io.bootDone       := BufferCC(sdramArea.bootDoneR,    init = False)
  io.memtestPass    := BufferCC(sdramArea.memtestPassR, init = False)
  io.memtestFail    := BufferCC(sdramArea.memtestFailR, init = False)
  io.debugPushStalls := BufferCC(sdramArea.pushStalls, init = U(0, 16 bits))
  // Downsample writeBuf's flip-rate to ~0.25 Hz so the user can unambiguously
  // see blinking vs stuck. fetchStartRise occurs ~31.5 kHz; /65536 → ~0.48 Hz
  // ping (so the LED cycles on/off every ~2s). A steady (not blinking) LED
  // definitively means fetchStart isn't pulsing on hardware.
  val flipBlinkCounter = Reg(UInt(16 bits)) init 0
  when(fetchStartRise) { flipBlinkCounter := flipBlinkCounter + 1 }
  io.debugWriteBuf := flipBlinkCounter(15)
}

object SdramTileFetchVerilog extends App {
  // Force RTL elaboration of the rebuilt fetch engine using a stub SDRAM clock domain.
  Config.spinal.generateVerilog {
    val sdramCd = ClockDomain.external(
      "sdram",
      frequency = FixedFrequency(64800000 Hz)
    )
    SdramTileFetch(sdramCd)
  }
}
