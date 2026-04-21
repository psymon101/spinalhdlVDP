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
  when(byteFifo.io.pop.fire) {
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

    byteFifo.io.push.valid   := False
    byteFifo.io.push.payload.kind := False
    byteFifo.io.push.payload.idx  := 0
    byteFifo.io.push.payload.data := 0

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
        when(enableSync && !io.sdramBusy) {
          bootCounter := 0
          goto(sInitBitmap)
        }
      }

      sInitBitmap.whenIsActive {
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
        when(fetchGrantEdge) {
          lineReg := fetchLineSync & U(MaxLines - 1, 10 bits)
          byteIdx := 0
          goto(sFetchBitmap)
        }
      }

      sFetchBitmap.whenIsActive {
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
        cmdRd := False
        when(io.sdramDataReady && byteFifo.io.push.ready) {
          byteFifo.io.push.valid := True
          byteFifo.io.push.payload.kind := False
          byteFifo.io.push.payload.idx  := byteIdx
          byteFifo.io.push.payload.data := io.sdramDout
          byteIdx := byteIdx + 1
          goto(sFetchBitmap)
        }
      }

      sFetchAttr.whenIsActive {
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
        cmdRd := False
        when(io.sdramDataReady && byteFifo.io.push.ready) {
          byteFifo.io.push.valid := True
          byteFifo.io.push.payload.kind := True
          byteFifo.io.push.payload.idx  := byteIdx
          byteFifo.io.push.payload.data := io.sdramDout
          byteIdx := byteIdx + 1
          goto(sFetchAttr)
        }
      }
    }

    io.sdramAddr := cmdAddr
    io.sdramDin  := cmdDin
    io.sdramRd   := cmdRd
    io.sdramWr   := cmdWr

    val sdramActiveR = cmdRd || cmdWr
  }

  io.bootDone    := BufferCC(sd.bootDoneR, False)
  io.sdramActive := BufferCC(sd.sdramActiveR, False)
}
