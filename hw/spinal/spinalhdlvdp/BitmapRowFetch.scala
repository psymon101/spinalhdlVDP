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
case class BitmapRowFetch(sdramCd: ClockDomain, skipSdramInit: Boolean = false) extends Component {

  val BitmapSdramBase    = 0x3000
  val AttrSdramBase      = 0x4000
  val BitmapBytesPerRow  = 128   // Task 44b iter 6d: power-of-two for shift-addressing
  val AttrBytesPerRow    = 128
  // RGB565 directcolor (CP-1c) needs one buffer entry per source pixel
  // (320 source px shown at 2 HDMI columns each), so the line buffers
  // grew 128 → 512. Indexed 1bpp/2bpp still use only the low entries.
  val BitmapBufferDepth  = 512
  val AttrBufferDepth    = 512
  // Directcolor fetch: 320 source pixels per row, one byte per pixel in
  // each of the bitmap (lo) and attr (hi) regions; 512-byte row stride.
  val DirectColorPixels  = 320
  val DirectRowStrideLog = 9
  // Fun-demo friendly generalization: keep the existing proof fetcher shape
  // (80 active bytes inside a 128-byte row stride), but cover a full 240-row
  // source image by repeating each fetched row for two HDMI scanlines.
  val MaxLines           = 240
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
    // #11246 F2 (defensive look-ahead, PM #11260): next-cycle value of the cmd regs
    // so the top upload gate avoids the registered-rd collision for this client too.
    val sdramRdNext    = out Bool()
    val sdramWrNext    = out Bool()
    val sdramDout      = in  Bits(8 bits)
    // RGB565-FULLFRAME-132 (#12283): the SDRAM controller is 32-bit (sdram.v
    // DATA_WIDTH=32); `sdramDout` is only a byte-select of this word. The
    // direct-color fetch reads `sdramDout32` (4 bytes per ~5-cycle SDRAM read)
    // instead of one byte, cutting 640 byte-reads/row to 160 word-reads/row so
    // a full 320×240 frame fits the bus budget. Same aperture PlanarLineFetch
    // already uses. Wired from ctrl.io.dout32 at the top level.
    val sdramDout32    = in  Bits(32 bits)
    val sdramDataReady = in  Bool()
    val sdramBusy      = in  Bool()
    val fetchGrant     = in  Bool()
    val fetchLine      = in  UInt(10 bits)
    val col            = in  UInt(10 bits)
    val enable         = in  Bool()    // pixel-domain bitmap-mode enable
    val directColor    = in  Bool()    // CP-1c: RGB565 directcolor fetch mode (2 bytes/pixel)
    val tileBootDone   = in  Bool()    // iter 6: tile-fetch init complete (safe to init our SDRAM regions)
    // BITMAP-PLUMB-129 (#12169/#12205): host-programmable bitmap/attr SDRAM
    // base, row stride, and source height. Driven (pixel domain) from the
    // VdpTop register block at 0x0351..0x0357 and BufferCC'd into sdramCd
    // below. Power-on defaults reproduce the former hardcoded constants
    // (base 0x3000/0x4000, stride 512, height 240) byte-for-byte.
    val bitmapBase     = in  UInt(23 bits)
    val attrBase       = in  UInt(23 bits)
    val bitmapStride   = in  UInt(16 bits)   // direct-color row stride in bytes
    val attrStride     = in  UInt(16 bits)   // direct-color attr row stride in bytes
    val bitmapHeight   = in  UInt(10 bits)   // source image height in rows
    val bitmapByte     = out Bits(8 bits)
    val attrByte       = out Bits(8 bits)
    val bootDone       = out Bool()
    val sdramActive    = out Bool()    // pulses whenever SDRAM FSM wants the bus
  }

  // RGB565-FULLFRAME-132: FIFO now carries one 32-bit SDRAM word (4 bytes) per
  // entry. `idx` is the base line-buffer byte index for byte 0 of the word
  // (word reads are 4-aligned). The pixel-domain pop side expands each word
  // into 4 byte-writes into the line buffer.
  case class RowByte() extends Bundle {
    val kind = Bool()
    val idx  = UInt(log2Up(BitmapBufferDepth) bits)
    val data = Bits(32 bits)
  }

  val byteFifo = StreamFifoCC(
    dataType  = RowByte(),
    depth     = FifoDepth,
    pushClock = sdramCd,
    popClock  = ClockDomain.current)

  val bitmapLineBuf = Mem(Bits(8 bits), BitmapBufferDepth)
  val attrLineBuf   = Mem(Bits(8 bits), AttrBufferDepth)

  // Indexed 1bpp/2bpp pack 8 hCounter values per byte → byte = col/8.
  // Directcolor stores one byte per source pixel; 320 source pixels are
  // shown at 2 HDMI columns each → byte = col/2. CP-1c muxes the read
  // address so each directcolor column gets a distinct buffer entry
  // (CP-1b read col/8, repeating each value across an 8-column span).
  val indexedRdAddr = io.col(9 downto 3).resize(log2Up(BitmapBufferDepth))
  val directRdAddr  = io.col(9 downto 1).resize(log2Up(BitmapBufferDepth))
  val lineRdAddr    = Mux(io.directColor, directRdAddr, indexedRdAddr)
  // readAsync — AUDIT #10772: Class 2 (per-pixel) — bitmap byte fetched
  // every active pixel and driven combinationally to io.bitmapByte for the
  // compositor. Candidate for readSync conversion + downstream RegNext.
  io.bitmapByte := bitmapLineBuf.readAsync(lineRdAddr)
  // readAsync — AUDIT #10772: Class 2 (per-pixel) — bitmap-attribute byte
  // co-timed with bitmapByte above; same per-pixel compositor read pattern.
  io.attrByte   := attrLineBuf.readAsync(lineRdAddr)

  // RGB565-FULLFRAME-132: pop-side 4-byte expander. Each popped FIFO word holds
  // 4 SDRAM bytes (little-endian, byte 0 = data[7:0] at idx+0). Stall the pop
  // for 4 pixel-domain cycles while writing the 4 bytes into the line buffer.
  // The pixel domain has ample cycles per line, so this is not a bandwidth path.
  val popWord = Reg(Bits(32 bits)) init 0
  val popIdx  = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0
  val popKind = Reg(Bool()) init False
  val popCnt  = Reg(UInt(2 bits)) init 0
  val popBusy = RegInit(False)
  byteFifo.io.pop.ready := !popBusy
  when(byteFifo.io.pop.fire) {
    popWord := byteFifo.io.pop.payload.data
    popIdx  := byteFifo.io.pop.payload.idx
    popKind := byteFifo.io.pop.payload.kind
    popCnt  := 0
    popBusy := True
  }
  when(popBusy) {
    val expByte = popWord.subdivideIn(8 bits)(popCnt)
    val expAddr = (popIdx + popCnt).resize(log2Up(BitmapBufferDepth))
    when(popKind) {
      attrLineBuf.write(address = expAddr, data = expByte, enable = True)
    } otherwise {
      bitmapLineBuf.write(address = expAddr, data = expByte, enable = True)
    }
    popCnt := popCnt + 1
    when(popCnt === 3) { popBusy := False }
  }

  val sd = new ClockingArea(sdramCd) {
    val fetchGrantSync = BufferCC(io.fetchGrant, False)
    val fetchGrantPrev = RegNext(fetchGrantSync) init False
    val fetchGrantEdge = fetchGrantSync && !fetchGrantPrev
    // #11246 F1 (CyanPeak): gray-code fetchLine before the CDC so only ONE bit
    // flips per line — a raw multi-bit binary BufferCC can return a torn
    // intermediate at line/tileY boundaries (e.g. 239->240 flips 5 bits) -> wrong
    // line fetched. Same mitigation SdramTileAttributeFetch already uses (#7138);
    // this engine was missed.
    def bin2gray(b: UInt): UInt = b ^ (b >> 1).resize(b.getWidth)
    val fetchLineGraySync = BufferCC(bin2gray(io.fetchLine), init = U(0, 10 bits))
    val fetchLineSync = UInt(10 bits)
    for (i <- 0 until 10) { fetchLineSync(i) := fetchLineGraySync(9 downto i).xorR }
    val enableSync     = BufferCC(io.enable, False)
    val directColorSync = BufferCC(io.directColor, False)
    val tileBootDoneSync = BufferCC(io.tileBootDone, False)
    // BITMAP-PLUMB-129: quasi-static host config — these change only on a
    // safe-boundary register commit (0x0351..0x0357) and are then held stable
    // for many frames, so a plain multi-bit BufferCC is safe here (unlike the
    // per-line fetchLine, which is gray-coded above). Reset values reproduce
    // the legacy hardcoded constants until the host programs them.
    val bitmapBaseCdc   = BufferCC(io.bitmapBase,   U(BitmapSdramBase, 23 bits))
    val attrBaseCdc     = BufferCC(io.attrBase,     U(AttrSdramBase,   23 bits))
    val bitmapStrideCdc = BufferCC(io.bitmapStride, U(1 << DirectRowStrideLog, 16 bits))
    val attrStrideCdc   = BufferCC(io.attrStride,   U(1 << DirectRowStrideLog, 16 bits))
    val bitmapHeightCdc = BufferCC(io.bitmapHeight, U(MaxLines, 10 bits))

    val cmdAddr = Reg(UInt(23 bits)) init 0
    val cmdDin  = Reg(Bits(8 bits))  init 0
    val cmdRd   = RegInit(False)
    val cmdWr   = RegInit(False)
    val bootDoneR = RegInit(False)

    val bootCounter = Reg(UInt(log2Up(TotalBitmapBytes + 1) bits)) init 0
    val byteIdx     = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0
    val lineReg     = Reg(UInt(10 bits)) init 0

    // CP-1c: per-line fetch count and SDRAM row byte-offset. Directcolor
    // fetches 320 bytes/row (one per source pixel) on a 512-byte stride;
    // indexed 1bpp/2bpp keep the legacy 80 bytes on a 128-byte stride.
    val fetchCount  = Mux(directColorSync, U(DirectColorPixels, 10 bits), U(80, 10 bits))
    // BITMAP-PLUMB-129: per-row byte offset. Direct-color now uses the host
    // BITMAP_STRIDE/ATTR_STRIDE byte stride (default 512 == legacy <<9);
    // indexed 1/2bpp keeps its hardwired 128-byte (<<7) legacy stride per the
    // approved scope (#12205). The bitmap and attr offsets are split so the two
    // strides are independent. The lineReg×stride product is registered to keep
    // the 10×16 multiply off the SDRAM address critical path — lineReg is set in
    // sIdle and held stable through the 16-cycle sFetchSettle window before
    // sFetchBitmap/sFetchAttr consume these, so RegNext is settled in time.
    val bitmapRowByteBase = RegNext(Mux(directColorSync,
                          (lineReg * bitmapStrideCdc).resize(23),
                          (lineReg << 7).resize(23))) init 0
    val attrRowByteBase   = RegNext(Mux(directColorSync,
                          (lineReg * attrStrideCdc).resize(23),
                          (lineReg << 7).resize(23))) init 0

    // Task 44b iter 6d (CyanPeak audit correction): replace dividers with
    // counters to ensure timing closure at 64.8 MHz.
    val initLineReg = Reg(UInt(8 bits)) init 0
    val initColReg  = Reg(UInt(8 bits)) init 0
    val initBitmapByte = (initLineReg + initColReg).resize(8).asBits
    val attrPaper = initLineReg(2 downto 0)
    val attrInk   = (initLineReg(2 downto 0) + initColReg(2 downto 0))(2 downto 0)
    val initAttrByte = (B(0, 2 bits) ## attrPaper.asBits ## attrInk.asBits)

    // Task 44b iter 6d (CyanPeak audit correction): pipeline metadata.
    // Latch the kind and index of the IN-FLIGHT request so we don't
    // rely on FSM registers being stable when dataReady eventually pulses.
    val inflightKind = Reg(Bool())     init False
    val inflightIdx  = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0

    // Registered-push pattern (per BronzeGate #8039, mirrors
    // SdramTileAttributeFetch's FIFO push). When sdramDataReady fires
    // we latch the payload into {pendingKind, pendingIdx, pendingData}
    // and assert pushPending. pushPending holds until push.fire
    // clears it. This removes the same-cycle `dataReady && push.ready`
    // dependency that silently dropped every read in earlier iters.
    val pushPending = RegInit(False)
    val pendingKind = Reg(Bool())     init False
    val pendingIdx  = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0
    val pendingData = Reg(Bits(32 bits)) init 0   // RGB565-FULLFRAME-132: full dout32 word

    // Forward-declared so the always-on latch below can reference it
    // before the FSM block defines its state transitions.
    val sdramActiveR = RegInit(False)
    byteFifo.io.push.valid         := pushPending
    byteFifo.io.push.payload.kind  := pendingKind
    byteFifo.io.push.payload.idx   := pendingIdx
    byteFifo.io.push.payload.data  := pendingData
    when(byteFifo.io.push.ready) { pushPending := False }

    // Iter 5: evaluate the dataReady → pushPending latch every sdramCd
    // cycle (not only when `sFetchBitmapWait` is active). Iter-4 canary
    // evidence showed `dataReady` fired while sdramActive was True but
    // pushPending never asserted — implying dataReady arrived one cycle
    // before the FSM transitioned to sFetchBitmapWait, so the old
    // guard inside `whenIsActive` never caught the event. This
    // component-scope guard still gates on `sdramActiveR` so only our
    // own fetch windows latch data.
    when(io.sdramDataReady && sdramActiveR && !pushPending) {
      pendingKind := inflightKind
      pendingIdx  := inflightIdx
      pendingData := io.sdramDout32   // RGB565-FULLFRAME-132: capture all 4 bytes
      pushPending := True
    }

    val fsm = new StateMachine {
      val sWaitEnable      = new State with EntryPoint
      val sInitSettle      = new State
      val sInitBitmap      = new State
      val sInitAttr        = new State
      val sIdle            = new State
      val sFetchSettle     = new State
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
        // Iter 6: also wait for tile-fetch bootDone so the arbiter has
        // no competing client for our SDRAM regions (0x3000..0x4FFF).
        // Previously our init cmdWr pulses were silently dropped for
        // the first ~4-6 cycles while BufferCC was propagating
        // sdramActive=True into pixelCd, because the arbiter was still
        // routing client 0 and tile fetch had its own init writes in
        // flight. bootCounter advanced unconditionally so the FSM
        // \"completed\" with many writes missing.
        //
        // Task 44b iter 6c (CyanPeak audit correction): removed !io.sdramBusy
        // from this transition. sInitSettle provides the mandatory window
        // for sdramActive to propagate; waiting for busy here could cause
        // a deadlock if client 0 is keeping the bus busy.
        when(enableSync && tileBootDoneSync) {
          bootCounter := 0
          initLineReg := 0
          initColReg  := 0
          sdramActiveR := True
          if (skipSdramInit) {
            // #9026 zero-footprint (BronzeGate ruling #9133): host owns SDRAM
            // population for the bitmap region too. Skip the procedural
            // bitmap/attr init fill and jump straight to fetch-idle so
            // host-staged SDRAM contents are preserved.
            bootDoneR := True
            goto(sIdle)
          } else {
            goto(sInitSettle)
          }
        }
      }

      // Task 44b iter 6b (CyanPeak audit fix): pre-arm the arbiter by
      // holding sdramActiveR high for a window before issuing any writes.
      // This ensures the top-level pixel-domain Mux has observed our
      // client-1 request before cmdWr pulses arrive at the controller.
      // Iter 6c: increased from 8 -> 16 cycles to safely cover the ~3-pixel-cycle
      // BufferCC delay (3 * 3.33 = 10 SDRAM cycles).
      sInitSettle.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        bootCounter := bootCounter + 1
        when(bootCounter >= 16) {
          bootCounter := 0
          goto(sInitBitmap)
        }
      }

      sInitBitmap.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(bootCounter < (bitmapHeightCdc << 7)) {
            cmdWr   := True
            cmdAddr := (bitmapBaseCdc + bootCounter.resize(23)).resized
            cmdDin  := initBitmapByte
            bootCounter := bootCounter + 1
            // Advance counters
            when(initColReg === 127) {
              initColReg := 0
              initLineReg := initLineReg + 1
            } otherwise {
              initColReg := initColReg + 1
            }
          } otherwise {
            bootCounter := 0
            initLineReg := 0
            initColReg  := 0
            goto(sInitAttr)
          }
        }
      }

      sInitAttr.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(bootCounter < (bitmapHeightCdc << 7)) {
            cmdWr   := True
            cmdAddr := (attrBaseCdc + bootCounter.resize(23)).resized
            cmdDin  := initAttrByte
            bootCounter := bootCounter + 1
            // Advance counters
            when(initColReg === 127) {
              initColReg := 0
              initLineReg := initLineReg + 1
            } otherwise {
              initColReg := initColReg + 1
            }
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
          // Each source row is displayed for two screen lines so a 240-row
          // bitmap fills the 480-line HDMI output without adding a scaler.
          lineReg := (fetchLineSync >> 1).resize(10)
          byteIdx := 0
          bootCounter := 0
          sdramActiveR := True
          goto(sFetchSettle)
        }
      }

      // Task 44b iter 6b (CyanPeak audit fix): pre-arm the arbiter for
      // per-line fetch. Iter 6c: increased to 16 cycles.
      sFetchSettle.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        bootCounter := bootCounter + 1
        when(bootCounter >= 16) {
          bootCounter := 0
          goto(sFetchBitmap)
        }
      }

      sFetchBitmap.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(byteIdx < fetchCount) {
            cmdRd   := True
            cmdAddr := (bitmapBaseCdc +
                        bitmapRowByteBase +
                        byteIdx.resize(23)).resized
            inflightKind := False
            inflightIdx  := byteIdx
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
        // Latch done at component scope (see above). Just wait for push.fire
        // to advance byteIdx and re-enter sFetchBitmap.
        when(byteFifo.io.push.fire) {
          byteIdx := byteIdx + 4   // RGB565-FULLFRAME-132: one dout32 word = 4 bytes
          goto(sFetchBitmap)
        }
      }

      sFetchAttr.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(byteIdx < fetchCount) {
            cmdRd   := True
            cmdAddr := (attrBaseCdc +
                        attrRowByteBase +
                        byteIdx.resize(23)).resized
            inflightKind := True
            inflightIdx  := byteIdx
            goto(sFetchAttrWait)
          } otherwise {
            goto(sIdle)
          }
        }
      }

      sFetchAttrWait.whenIsActive {
        sdramActiveR := True
        cmdRd := False
        when(byteFifo.io.push.fire) {
          byteIdx := byteIdx + 4   // RGB565-FULLFRAME-132: one dout32 word = 4 bytes
          goto(sFetchAttr)
        }
      }
    }

    io.sdramAddr := cmdAddr
    io.sdramDin  := cmdDin
    io.sdramRd   := cmdRd
    io.sdramWr   := cmdWr
    io.sdramRdNext := cmdRd.getAheadValue()   // #11246 F2 defensive look-ahead
    io.sdramWrNext := cmdWr.getAheadValue()

    // Level-high sdramActive: True across all non-idle states. Pulsing
    // on cmdRd/cmdWr alone is too narrow for the top-level pixelCd
    // BufferCC — arbiter would miss the window and drop writes. Gets
    // set when enable first sees high, stays high until fetch loop
    // quiesces in sIdle (with no new grant). Declared earlier now
    // so iter-5 always-on latch can reference it.
  }

  io.bootDone    := BufferCC(sd.bootDoneR, False)
  io.sdramActive := BufferCC(sd.sdramActiveR, False)
}
