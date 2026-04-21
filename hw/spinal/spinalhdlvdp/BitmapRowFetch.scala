package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Task 44b — linear bitmap + attribute row fetch with SDRAM backing.
  *
  * Architecture (per BronzeGate #8023 — StreamFifoCC CDC bridge):
  *
  *   sdramCd:
  *     FSM does power-up init (writes test pattern into SDRAM regions),
  *     then on each CDC'd `fetchGrant` pulse reads one bitmap row + one
  *     attribute row from SDRAM and pushes each byte as a Stream
  *     element into `StreamFifoCC(pushCd=sdramCd, popCd=pixelCd)`.
  *     Stream payload = (kind, idx, data) where kind picks bitmap vs
  *     attribute buffer, idx is the target line-buffer index, data is
  *     the SDRAM byte.
  *
  *   pixelCd:
  *     Line buffers `bitmapLineBuf` / `attrLineBuf` are plain pixel-
  *     domain `Mem`s. A consumer pops the FIFO and writes the byte to
  *     the selected buffer at the indicated index. `BitmapFetch`
  *     reads these buffers via `readAsync`.
  *
  * The FIFO is the single CDC primitive — no dual-clock `Mem` and no
  * `addAttribute("crossClockDomain")` escape hatch.
  *
  * SDRAM layout:
  *   0x3000 .. 0x3000 + BitmapBytesPerRow*MaxLines - 1   bitmap region
  *   0x4000 .. 0x4000 + AttrBytesPerRow  *MaxLines - 1   attribute region
  */
case class BitmapRowFetch(sdramCd: ClockDomain) extends Component {

  val BitmapSdramBase    = 0x3000
  val AttrSdramBase      = 0x4000
  val BitmapBytesPerRow  = 80
  val AttrBytesPerRow    = 80
  val BitmapBufferDepth  = 128
  val AttrBufferDepth    = 128
  val MaxLines           = 32
  val TotalBitmapBytes   = BitmapBytesPerRow * MaxLines
  val TotalAttrBytes     = AttrBytesPerRow   * MaxLines
  val FifoDepth          = 256

  require(isPow2(BitmapBufferDepth))
  require(isPow2(AttrBufferDepth))
  require(isPow2(FifoDepth))

  val io = new Bundle {
    val sdramAddr      = out UInt(23 bits)
    val sdramDin       = out Bits(8 bits)
    val sdramRd        = out Bool()
    val sdramWr        = out Bool()
    val sdramDout      = in  Bits(8 bits)
    val sdramDataReady = in  Bool()
    val sdramBusy      = in  Bool()
    val fetchGrant     = in  Bool()
    val fetchLine      = in  UInt(10 bits)
    val col            = in  UInt(10 bits)
    val enable         = in  Bool()    // pixel-domain bitmap-mode enable
    val bitmapByte     = out Bits(8 bits)
    val attrByte       = out Bits(8 bits)
    val bootDone       = out Bool()
    val sdramActive    = out Bool()    // pulses whenever SDRAM FSM wants the bus
    val fifoActiveEver = out Bool()    // CP-B canary: sticky-high once FIFO pop has fired at least once
    // CP-B debug iteration 2 (CyanPeak #8032) — all sticky-latched in pixelCd.
    val dbgBusyDroppedEver   = out Bool()  // sticky: !sdramBusy observed by FSM at least once
    val dbgDataReadyEver     = out Bool()  // sticky: sdramDataReady observed at least once
    val dbgWrAssertedEver    = out Bool()  // sticky: cmdWr asserted at least once
  }

  case class RowByte() extends Bundle {
    val kind = Bool()
    val idx  = UInt(log2Up(BitmapBufferDepth) bits)
    val data = Bits(8 bits)
  }

  val byteFifo = StreamFifoCC(
    dataType  = RowByte(),
    depth     = FifoDepth,
    pushClock = sdramCd,
    popClock  = ClockDomain.current)

  val bitmapLineBuf = Mem(Bits(8 bits), BitmapBufferDepth)
  val attrLineBuf   = Mem(Bits(8 bits), AttrBufferDepth)

  val bitmapReadAddr = io.col(log2Up(BitmapBufferDepth * 8) - 1 downto 3)
  val attrReadAddr   = io.col(log2Up(AttrBufferDepth   * 8) - 1 downto 3)
  io.bitmapByte := bitmapLineBuf.readAsync(bitmapReadAddr)
  io.attrByte   := attrLineBuf.readAsync(attrReadAddr)

  byteFifo.io.pop.ready := True
  val popFiredSticky = RegInit(False)
  when(byteFifo.io.pop.fire) {
    popFiredSticky := True
    when(byteFifo.io.pop.payload.kind) {
      attrLineBuf.write(
        address = byteFifo.io.pop.payload.idx,
        data    = byteFifo.io.pop.payload.data,
        enable  = True)
    } otherwise {
      bitmapLineBuf.write(
        address = byteFifo.io.pop.payload.idx,
        data    = byteFifo.io.pop.payload.data,
        enable  = True)
    }
  }
  io.fifoActiveEver := popFiredSticky

  val sd = new ClockingArea(sdramCd) {
    val fetchGrantSync = BufferCC(io.fetchGrant, False)
    val fetchGrantPrev = RegNext(fetchGrantSync) init False
    val fetchGrantEdge = fetchGrantSync && !fetchGrantPrev
    val fetchLineSync  = BufferCC(io.fetchLine, U(0, 10 bits))
    val enableSync     = BufferCC(io.enable, False)

    val cmdAddr = Reg(UInt(23 bits)) init 0
    val cmdDin  = Reg(Bits(8 bits))  init 0
    val cmdRd   = RegInit(False)
    val cmdWr   = RegInit(False)
    val bootDoneR = RegInit(False)

    val bootCounter = Reg(UInt(log2Up(TotalBitmapBytes + 1) bits)) init 0
    val byteIdx     = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0
    val lineReg     = Reg(UInt(10 bits)) init 0

    val initLine  = (bootCounter / U(BitmapBytesPerRow, bootCounter.getWidth bits)).resize(8)
    val initCol   = (bootCounter % U(BitmapBytesPerRow, bootCounter.getWidth bits)).resize(8)
    val initBitmapByte = (initLine + initCol).resize(8).asBits
    val attrPaper = initLine(2 downto 0)
    val attrInk   = (initLine(2 downto 0) + initCol(2 downto 0))(2 downto 0)
    val initAttrByte = (B(0, 2 bits) ## attrPaper.asBits ## attrInk.asBits)

    // Registered-push pattern (per BronzeGate #8039, mirrors
    // SdramTileAttributeFetch's FIFO push). When sdramDataReady fires
    // we latch the payload into {pendingKind, pendingIdx, pendingData}
    // and assert pushPending. pushPending holds until push.fire
    // clears it. This removes the same-cycle `dataReady && push.ready`
    // dependency that silently dropped every read in earlier iters.
    val pushPending = RegInit(False)
    val pendingKind = Reg(Bool())     init False
    val pendingIdx  = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0
    val pendingData = Reg(Bits(8 bits)) init 0
    byteFifo.io.push.valid         := pushPending
    byteFifo.io.push.payload.kind  := pendingKind
    byteFifo.io.push.payload.idx   := pendingIdx
    byteFifo.io.push.payload.data  := pendingData
    when(byteFifo.io.push.fire) { pushPending := False }

    val dbgPushPendingEver = RegInit(False)
    when(pushPending) { dbgPushPendingEver := True }

    val fsm = new StateMachine {
      val sWaitEnable      = new State with EntryPoint
      val sInitBitmap      = new State
      val sInitAttr        = new State
      val sIdle            = new State
      val sFetchBitmap     = new State
      val sFetchBitmapWait = new State
      val sFetchAttr       = new State
      val sFetchAttrWait   = new State

      // Wait for bitmap mode to be enabled before starting init. This
      // gates the SDRAM init writes behind BITMAP_CTRL[0] rising, which
      // also causes the top-level arbiter to route client-1 requests to
      // the SDRAM controller. Without this gate the init writes are
      // dropped by the arbiter (client-0 wins) and SDRAM stays
      // uninitialised, leading to a black render.
      sWaitEnable.whenIsActive {
        cmdRd := False; cmdWr := False
        sdramActiveR := False
        when(enableSync && !io.sdramBusy) {
          bootCounter := 0
          sdramActiveR := True
          goto(sInitBitmap)
        }
      }

      sInitBitmap.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(bootCounter < U(TotalBitmapBytes, bootCounter.getWidth bits)) {
            cmdWr   := True
            cmdAddr := (U(BitmapSdramBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := initBitmapByte
            bootCounter := bootCounter + 1
          } otherwise {
            bootCounter := 0
            goto(sInitAttr)
          }
        }
      }

      sInitAttr.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(bootCounter < U(TotalAttrBytes, bootCounter.getWidth bits)) {
            cmdWr   := True
            cmdAddr := (U(AttrSdramBase, 23 bits) + bootCounter.resize(23)).resized
            cmdDin  := initAttrByte
            bootCounter := bootCounter + 1
          } otherwise {
            bootCounter := 0
            bootDoneR   := True
            goto(sIdle)
          }
        }
      }

      sIdle.whenIsActive {
        cmdRd := False; cmdWr := False
        sdramActiveR := False
        when(fetchGrantEdge) {
          lineReg := fetchLineSync & U(MaxLines - 1, 10 bits)
          byteIdx := 0
          sdramActiveR := True
          goto(sFetchBitmap)
        }
      }

      sFetchBitmap.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(byteIdx < U(BitmapBytesPerRow, byteIdx.getWidth bits)) {
            cmdRd   := True
            cmdAddr := (U(BitmapSdramBase, 23 bits) +
                        lineReg.resize(23) * U(BitmapBytesPerRow, 23 bits) +
                        byteIdx.resize(23)).resized
            goto(sFetchBitmapWait)
          } otherwise {
            byteIdx := 0
            goto(sFetchAttr)
          }
        }
      }

      sFetchBitmapWait.whenIsActive {
        sdramActiveR := True
        cmdRd := False
        // Latch on dataReady (if no prior pending pending).
        when(io.sdramDataReady && !pushPending) {
          pendingKind := False
          pendingIdx  := byteIdx
          pendingData := io.sdramDout
          pushPending := True
        }
        // Advance to next byte once the pending push is accepted.
        when(byteFifo.io.push.fire) {
          byteIdx := byteIdx + 1
          goto(sFetchBitmap)
        }
      }

      sFetchAttr.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(byteIdx < U(AttrBytesPerRow, byteIdx.getWidth bits)) {
            cmdRd   := True
            cmdAddr := (U(AttrSdramBase, 23 bits) +
                        lineReg.resize(23) * U(AttrBytesPerRow, 23 bits) +
                        byteIdx.resize(23)).resized
            goto(sFetchAttrWait)
          } otherwise {
            goto(sIdle)
          }
        }
      }

      sFetchAttrWait.whenIsActive {
        sdramActiveR := True
        cmdRd := False
        when(io.sdramDataReady && !pushPending) {
          pendingKind := True
          pendingIdx  := byteIdx
          pendingData := io.sdramDout
          pushPending := True
        }
        when(byteFifo.io.push.fire) {
          byteIdx := byteIdx + 1
          goto(sFetchAttr)
        }
      }
    }

    io.sdramAddr := cmdAddr
    io.sdramDin  := cmdDin
    io.sdramRd   := cmdRd
    io.sdramWr   := cmdWr

    // Level-high sdramActive: True across all non-idle states. Pulsing
    // on cmdRd/cmdWr alone is too narrow for the top-level pixelCd
    // BufferCC — arbiter would miss the window and drop writes. Gets
    // set when enable first sees high, stays high until fetch loop
    // quiesces in sIdle (with no new grant).
    val sdramActiveR = RegInit(False)

    // CP-B iter 4 sticky debug latches (sdramCd-resident). Per
    // BronzeGate #8039 evidence requirement: dataReady observed while
    // OUR sdramActive is high (filters out tile-fetch traffic);
    // pushPending ever; FIFO pop.fire ever.
    val dbgDataReadyOursR     = RegInit(False)
    val dbgPushPendingEverR   = RegInit(False)
    when(io.sdramDataReady && sdramActiveR) { dbgDataReadyOursR   := True }
  }

  // Iter 4 canaries: dataReady-ours, pushPending-ever, pop-fire-ever.
  io.dbgBusyDroppedEver := BufferCC(sd.dbgDataReadyOursR, False)
  io.dbgDataReadyEver   := BufferCC(sd.dbgPushPendingEver, False)
  io.dbgWrAssertedEver  := popFiredSticky

  io.bootDone    := BufferCC(sd.bootDoneR, False)
  io.sdramActive := BufferCC(sd.sdramActiveR, False)
}
