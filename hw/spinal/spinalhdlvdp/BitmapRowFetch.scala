package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
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
    // RGB565-FULLFRAME-132 Phase 0: SDRAM read burst length (words) for THIS client's
    // reads, forwarded to the arbiter (which muxes it to sdram.v). Direct-color row
    // fetch drives 8 (one Activate → 8 consecutive column reads, ~4× the throughput of
    // single reads → closes the 40.5 MHz refresh-ON bandwidth wall); indexed/1bpp/2bpp
    // drives 1 (bit-identical legacy single read).
    val sdramBurstLen  = out UInt(4 bits)
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
    val sdramActive    = out Bool()    // pulses whenever SDRAM FSM wants the bus (pixel domain, BufferCC'd)
    // RGB565-FULLFRAME-132 Phase 0: raw sdramCd-domain fetch-active level (= sd.sdramActiveR,
    // no CDC). High across every fetch/init state, low only when the FSM is idle between
    // source rows. A same-domain refresh sequencer uses this to insert AUTO_REFRESH ONLY at
    // an idle (safe) boundary — never racing the FSM's registered cmdRd mid-fetch.
    val sdramActiveRaw = out Bool()
  }

  // RGB565-FULLFRAME-132 B.2 (#12309): the FIFO carries one 32-bit SDRAM word
  // (4 bytes) per entry. `idx` is the base line-buffer BYTE index of byte 0 of
  // the word (word reads are 4-aligned). The line buffers are now 32-bit wide so
  // the pop side stores one whole word per cycle — the old 4-byte expander cost
  // ~4 pixel-clocks/word and made a full line take ~920 pixel-clocks vs the 800
  // available (the bandwidth wall measured in #12306). They are also
  // double-buffered (readSync) so the compositor never reads the bank the
  // fetcher is filling.
  case class RowByte() extends Bundle {
    val kind = Bool()
    val idx  = UInt(log2Up(BitmapBufferDepth) bits)
    val data = Bits(32 bits)
    // RGB565-FULLFRAME-132 B.2 (#12350): target line-buffer BANK travels WITH the
    // word through the FIFO. With the grant queue the FSM fetch (sdramCd) is
    // decoupled from the pixel-domain display-bank rotation, so the fill bank must
    // be the one the FSM chose when it fetched this row — carried here, not a
    // separate pixel-side register that could drift out of sync.
    val bank = UInt(2 bits)
    // 2bpp-bank-completion-rtl (PROJECT-SYSTEM-MIGRATION-001 pilot): the source
    // row this word belongs to (`rowTag`) and whether it is the FINAL word of the
    // row fetch (`last`, = last attr word). Both travel with the word so the
    // pixel-domain completion tracker sets bankReady/bankRowTag exactly when the
    // last word of a row lands in the line-buffer bank — no separate sd-domain
    // completion signal to CDC (writes already happen pixel-side at pop).
    val rowTag = UInt(10 bits)
    val last   = Bool()
  }

  val byteFifo = StreamFifoCC(
    dataType  = RowByte(),
    depth     = FifoDepth,
    pushClock = sdramCd,
    popClock  = ClockDomain.current)

  // 32-bit-wide, TRIPLE-buffered line buffers (Option B, #12346). WordDepth =
  // byte depth / 4. Three banks per plane so a source row is fetched TWO rows
  // ahead of its display: at any time one bank displays, one holds the next row
  // already complete, and one is filling. That gives the ~1566-pixel-clock fetch
  // up to ~2 source-row windows (~3200 pixel-clocks) of lead — ample slack so an
  // AUTO_REFRESH landing inside the fetch cannot push it past the budget (the
  // failure mode that sank the 2-bank/2-line-window Option A under refresh).
  val WordDepth = BitmapBufferDepth / 4
  require(WordDepth * 4 == BitmapBufferDepth)
  val NBanks    = 3
  val bitmapBuf = Seq.fill(NBanks)(Mem(Bits(32 bits), WordDepth))
  val attrBuf   = Seq.fill(NBanks)(Mem(Bits(32 bits), WordDepth))

  // Bank rotation (pixel domain). The fetchGrant pulse (driven from VdpTop once
  // per SOURCE ROW at hTotal-1) advances `dispBank` mod 3 to present the row that
  // finished filling, while `fillBankReg` (held 2 banks ahead) targets the bank
  // for the row two ahead. Advancing at hTotal-1 lands the new bank in `dispBankD`
  // (RegNext) exactly for the next row's pixel 0, absorbing the readSync latency.
  def inc3(x: UInt): UInt = Mux(x === U(NBanks - 1, 2 bits), U(0, 2 bits), x + 1)
  val fetchGrantPixPrev = RegNext(io.fetchGrant) init False
  val fetchGrantPixEdge = io.fetchGrant && !fetchGrantPixPrev
  val dispBank = RegInit(U(0, 2 bits))

  // 2bpp-bank-completion-rtl (BITMAP_ENGINE.md §Open hardening): gate display-bank
  // rotation on completion + row-tag match. Previously a fetchGrant edge advanced
  // dispBank UNCONDITIONALLY — even onto a bank still filling (incomplete) or holding
  // a stale row when the fetch fell behind (hazard proven reachable by
  // Indexed2bppBacklogCoSim forced-late @5efe049: grantOverflow=25, wrong-row 214/480).
  // Now dispBank advances only when the next bank is READY (its last word has landed)
  // AND holds the CONSECUTIVE next row (bankRowTag == current+1); otherwise it HOLDS the
  // last-good bank (graceful degradation: repeat a valid row rather than show garbage)
  // and counts the miss. The tag check is RELATIVE (consecutive), so the nominal phase
  // (bestDv==3) is unchanged — with ample lead every next bank is ready + consecutive,
  // giving rotation identical to the pre-hardening design.
  val bankReady        = Vec.fill(NBanks)(RegInit(False))
  val bankRowTag       = Vec.fill(NBanks)(Reg(UInt(10 bits)) init 0)
  val everReady        = Vec.fill(NBanks)(RegInit(False))          // each bank has completed >= 1 fill (startup priming)
  val primed           = everReady.reduce(_ && _)                  // pipeline primed: safe to enforce gating without shifting the offset
  val dispValid        = (RegInit(False)).simPublic()             // dispBank validly loaded >= once
  val displayUnderflow = (Reg(UInt(16 bits)) init 0).simPublic()  // rotation held: next bank incomplete
  val rowTagMismatch   = (Reg(UInt(16 bits)) init 0).simPublic()  // rotation held: next bank complete but non-consecutive
  val nextBank         = inc3(dispBank)
  // The fetch line runs once per source row (VdpTop:1627 grant cadence) as 1..bitmapHeight
  // then WRAPS to the top of the next frame. So a legitimate consecutive display step is
  // either tag+1 OR the frame wrap (a large decrease, prevTag-nextTag > bitmapHeight/2).
  // Genuine starvation staleness is a SMALL decrease (a few rows behind) or a skip-ahead,
  // which fails both clauses and is held. (A naive tag+1-only check false-holds on the
  // frame wrap every frame and permanently shifts the line-doubling offset — bestDv 3->2.)
  val expectTag        = (bankRowTag(dispBank) + 1).resize(10)
  val tagStepOk        = (bankRowTag(nextBank) === expectTag) ||
                         ((bankRowTag(dispBank) > bankRowTag(nextBank)) &&
                          ((bankRowTag(dispBank) - bankRowTag(nextBank)) > (io.bitmapHeight >> 1)))
  when(fetchGrantPixEdge) {
    when(!primed) {
      // Startup priming: advance unconditionally, exactly as the pre-hardening design,
      // so the steady-state line-doubling offset (bestDv==3) is preserved. Enforcing the
      // gate before the 3-bank pipeline is filled would hold at the first grants and
      // permanently shift the offset. Once every bank has completed one fill, the fetch
      // is running ahead and the gate below never holds in the nominal case.
      dispBank  := nextBank
      dispValid := True
    } elsewhen(!bankReady(nextBank)) {
      displayUnderflow := displayUnderflow + 1   // hold last-good bank: next row incomplete
    } elsewhen(!tagStepOk) {
      rowTagMismatch := rowTagMismatch + 1       // hold last-good bank: next bank complete but stale (non-consecutive, non-wrap)
    } otherwise {
      dispBank  := nextBank        // steady state: rotate only onto a complete, consecutive (or frame-wrap) bank
    }
  }

  // Compositor read. Indexed 1bpp/2bpp pack 8 hCounter values per byte → byte =
  // col/8; directcolor stores one byte per source pixel shown at 2 HDMI columns
  // → byte = col/2. The byte index splits into a 32-bit word address and a byte
  // lane. readSync adds one cycle of latency, so the byte-lane and bank selects
  // are delayed one cycle to stay aligned with the registered word.
  val indexedRdAddr = io.col(9 downto 3).resize(log2Up(BitmapBufferDepth))
  val directRdAddr  = io.col(9 downto 1).resize(log2Up(BitmapBufferDepth))
  val lineRdByte    = Mux(io.directColor, directRdAddr, indexedRdAddr)
  val rdWordAddr    = (lineRdByte >> 2).resize(log2Up(WordDepth))
  val rdLane        = lineRdByte(1 downto 0)
  val bmW = Vec(bitmapBuf.map(_.readSync(rdWordAddr)))
  val atW = Vec(attrBuf.map(_.readSync(rdWordAddr)))
  val rdLaneD   = RegNext(rdLane) init 0
  val dispBankD = RegNext(dispBank) init 0
  val bmWord = bmW(dispBankD)
  val atWord = atW(dispBankD)
  io.bitmapByte := bmWord.subdivideIn(8 bits)(rdLaneD)
  io.attrByte   := atWord.subdivideIn(8 bits)(rdLaneD)

  // Pop side: one 32-bit word per cycle into the fill bank, routed by kind.
  // No 4-cycle byte expansion and no stale-state startup write — the old popBusy
  // expander emitted one spurious write (idx 116) before the first real word.
  byteFifo.io.pop.ready := True
  val popFire     = byteFifo.io.pop.fire
  val popData     = byteFifo.io.pop.payload.data
  val popKind     = byteFifo.io.pop.payload.kind
  val popBank     = byteFifo.io.pop.payload.bank   // FSM-chosen target bank, carried with the word
  val popWordAddr = (byteFifo.io.pop.payload.idx >> 2).resize(log2Up(WordDepth))
  val popLast     = byteFifo.io.pop.payload.last
  val popRowTag   = byteFifo.io.pop.payload.rowTag
  val popIdx      = byteFifo.io.pop.payload.idx
  for (b <- 0 until NBanks) {
    val isFillBank = popBank === U(b, 2 bits)
    bitmapBuf(b).write(popWordAddr, popData, enable = popFire && !popKind && isFillBank)
    attrBuf(b).write  (popWordAddr, popData, enable = popFire &&  popKind && isFillBank)
  }
  // 2bpp-bank-completion-rtl: pixel-domain per-bank completion. A bank goes NOT-ready
  // the moment its refill starts (first bitmap word, idx 0) and READY when the row's
  // final word lands (`last`), latching the source row it now holds. Writes already
  // happen in this (pixel) domain at pop, so completion needs no extra CDC.
  when(popFire) {
    when(popIdx === 0 && !popKind) { bankReady(popBank) := False }
    when(popLast) {
      bankReady(popBank)  := True
      bankRowTag(popBank) := popRowTag
      everReady(popBank)  := True   // this bank has completed a full fill (used for startup priming)
    }
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

    // RGB565-FULLFRAME-132 (CoralReef #12355 cond.5, Option a): a burst-8 read must
    // start on a 32-byte boundary and stay inside one 1KB SDRAM row. Direct-color is
    // the only burst client, so when directColor is active we hard-enforce 32-byte
    // alignment on the host-programmable base AND stride by masking their low 5 bits.
    // (The POR defaults — base 0x3000/0x4000, stride 512 — are already aligned, so
    // this is a no-op for the demo; it only guards a mis-programmed host.) Indexed
    // 1bpp/2bpp uses single reads (no alignment requirement) and is left untouched.
    // Documented in MODE0_REGISTER_BUS_SPEC §3.1.3 (CoralReef owns the doc update).
    val Align32Mask     = ~U(0x1F, 23 bits)
    val bitmapBaseAln   = bitmapBaseCdc & Align32Mask
    val attrBaseAln     = attrBaseCdc   & Align32Mask
    val bitmapStrideAln = bitmapStrideCdc & ~U(0x1F, 16 bits)
    val attrStrideAln   = attrStrideCdc   & ~U(0x1F, 16 bits)
    // Base used by the row fetch: 32-byte-aligned in direct-color (burst), raw
    // otherwise (indexed single reads have no alignment constraint).
    val bitmapBaseUse   = Mux(directColorSync, bitmapBaseAln, bitmapBaseCdc)
    val attrBaseUse     = Mux(directColorSync, attrBaseAln,   attrBaseCdc)
    // Burst length for THIS client's reads: 8 words in direct-color, 1 otherwise.
    val burstWords      = Mux(directColorSync, U(8, 4 bits), U(1, 4 bits))

    val cmdAddr = Reg(UInt(23 bits)) init 0
    val cmdDin  = Reg(Bits(8 bits))  init 0
    val cmdRd   = RegInit(False)
    val cmdWr   = RegInit(False)
    val bootDoneR = RegInit(False)

    val bootCounter = Reg(UInt(log2Up(TotalBitmapBytes + 1) bits)) init 0
    val byteIdx     = Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0
    val lineReg     = (Reg(UInt(10 bits)) init 0).simPublic()  // BUG1-174: observe fetched row in grant-FIFO sim

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
                          (lineReg * bitmapStrideAln).resize(23),
                          (lineReg << 7).resize(23))) init 0
    val attrRowByteBase   = RegNext(Mux(directColorSync,
                          (lineReg * attrStrideAln).resize(23),
                          (lineReg << 7).resize(23))) init 0

    // Task 44b iter 6d (CyanPeak audit correction): replace dividers with
    // counters to ensure timing closure at the 40.5 MHz SDRAM clock.
    val initLineReg = Reg(UInt(8 bits)) init 0
    val initColReg  = Reg(UInt(8 bits)) init 0
    val initBitmapByte = (initLineReg + initColReg).resize(8).asBits
    val attrPaper = initLineReg(2 downto 0)
    val attrInk   = (initLineReg(2 downto 0) + initColReg(2 downto 0))(2 downto 0)
    val initAttrByte = (B(0, 2 bits) ## attrPaper.asBits ## attrInk.asBits)

    // Task 44b iter 6d (CyanPeak audit correction): pipeline metadata.
    // Latch the kind and index of the IN-FLIGHT request so we don't
    // rely on FSM registers being stable when dataReady eventually pulses.
    val inflightKind = (Reg(Bool())     init False).simPublic()
    val inflightIdx  = (Reg(UInt(log2Up(BitmapBufferDepth) bits)) init 0).simPublic()

    // RGB565-FULLFRAME-132 Phase 0: BURST capture. A burst-N read returns N words on
    // N consecutive `data_ready` pulses (one per sdramCd cycle). The previous depth-1
    // `pushPending` latch held only ONE word, so the 2nd pulse of a burst arrived while
    // pushPending was still set and was silently dropped. Instead push each word
    // straight into byteFifo: it is a StreamFifoCC of depth 256 whose pop side drains a
    // word every pixel-clk, so it is never near full during a ≤8-word burst and
    // push.ready stays high. `burstCnt` counts words within the current read and
    // offsets the target line-buffer index by 4 bytes per word. A push refused mid-burst
    // would drop a word and is caught by the proof gate (cosim 0-mismatch). The single
    // read (indexed/1bpp/2bpp, burstWords=1) is the N=1 special case — bit-for-bit the
    // old one-word-per-read behavior, minus the now-unnecessary 1-cycle latch delay.
    val burstCnt = (Reg(UInt(4 bits)) init 0).simPublic()  // words received in current read

    // Forward-declared so the FSM (below) and the push logic can reference it.
    val sdramActiveR = RegInit(False)
    // `fetchBank` = the line-buffer bank for the row the FSM is currently fetching;
    // advanced once per fetch in sIdle (below), 2 banks ahead of the display bank.
    val fetchBank = Reg(UInt(2 bits)) init 2   // 2 banks ahead of dispBank (init 0)

    // Push is enabled only in the fetch-WAIT states (set True there in the FSM). The
    // controller registers `rd` one cycle AFTER the issue state asserts cmdRd, by which
    // point the FSM is already in the WAIT state, so data_ready (≥ T_RCD+CAS+1 cycles
    // later) is always observed with pushEnable high — no early-data race.
    val pushEnable = Bool()
    pushEnable := False
    byteFifo.io.push.valid         := io.sdramDataReady && sdramActiveR && pushEnable
    byteFifo.io.push.payload.kind  := inflightKind
    byteFifo.io.push.payload.idx   := (inflightIdx + (burstCnt << 2)).resize(log2Up(BitmapBufferDepth))
    byteFifo.io.push.payload.data  := io.sdramDout32   // current burst word (dq_in_r), valid at data_ready
    byteFifo.io.push.payload.bank  := fetchBank        // FSM-chosen target bank, carried with the word
    // 2bpp-bank-completion-rtl: the source row this word carries, and whether it is
    // the FINAL word of the row fetch (last attr word = highest byteIdx, last burst
    // word, kind=attr). The pixel-domain completion tracker uses these to set
    // bankReady/bankRowTag exactly when a bank's last word lands.
    byteFifo.io.push.payload.rowTag := lineReg
    byteFifo.io.push.payload.last   := inflightKind &&
      (inflightIdx === (fetchCount - (burstWords << 2)).resize(inflightIdx.getWidth)) &&
      (burstCnt === (burstWords - 1))

    // RGB565-FULLFRAME-132 B.2 (#12350): grant QUEUE. At 40.5 MHz a row fetch
    // (1566 pixel-clocks) + an AUTO_REFRESH can overrun the ~1600 pixel-clock
    // per-source-row grant period. Without a queue the FSM (busy, not in sIdle)
    // DROPS that grant and the row is never fetched. Every fetchGrant pulse is pushed
    // into a depth-2 FIFO; sIdle pops it (immediately, no wait for a fresh pulse) so
    // the FSM does back-to-back catch-up fetches. BUG1-GRANT-FIFO-174: depth raised
    // 1→2 so a 2nd grant arriving while one is already pending is BUFFERED (not lost);
    // only a 3rd-while-2-pending (FSM >2 rows behind) is a true overflow → grantOverflow.
    // BUG1-GRANT-FIFO-174 (external-AI Bug 1): true 2-entry grant FIFO. The prior
    // single-bit `grantPending` latch OVERWROTE `pendingLine` if a 2nd grant arrived
    // while one was already pending (a fetch overrunning its scanline under SDRAM
    // contention) → that row was silently DROPPED (visible tearing / stale rows). A
    // depth-2 FIFO buffers up to two pending lines without loss. Pointers are 2-bit
    // (one wrap bit beyond the 1-bit slot index) so empty (wr==rd) and full (count==2)
    // are unambiguous — a 1-bit pointer can only represent depth-1. Push (wr) and pop
    // (rd) touch DIFFERENT pointers, so a same-cycle consume+grant needs no ordering
    // trick (both happen cleanly).
    val grantFifo     = Vec.fill(2)(Reg(UInt(10 bits)) init 0)
    val grantWrPtr    = (Reg(UInt(2 bits)) init 0).simPublic()
    val grantRdPtr    = (Reg(UInt(2 bits)) init 0).simPublic()
    val grantEmpty    = grantWrPtr === grantRdPtr
    val grantFull     = (grantWrPtr - grantRdPtr) === U(2, 2 bits)
    val grantOverflow = (Reg(UInt(8 bits)) init 0).simPublic()

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
        // Consume a QUEUED grant (latched below) — services both a fresh grant and
        // a grant that arrived while the previous fetch was still running.
        when(!grantEmpty) {
          // Each source row is displayed for two screen lines so a 240-row
          // bitmap fills the 480-line HDMI output without adding a scaler.
          lineReg := (grantFifo(grantRdPtr.resize(1)) >> 1).resize(10)
          fetchBank := inc3(fetchBank)   // advance to this fetch's target bank
          grantRdPtr := grantRdPtr + 1   // pop the consumed grant
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

      // RGB565-FULLFRAME-132 B.2 (#12318): INTERLEAVED bitmap/attr fetch —
      // bm@idx, at@idx, idx+=4, repeat. The old serial order (all 80 bitmap words
      // then all 80 attr words) left attr as the fetch tail, so the scanout beam
      // read attr's early pixels before they were fetched (cosim attr-lag, #12317).
      // Interleaving keeps both planes at the same fill-ahead distance; the total
      // line fetch time (~373 pixel-cycles) is unchanged.
      // RGB565-FULLFRAME-132 B.2 (#12318) + Phase 0 burst: INTERLEAVED bitmap/attr
      // fetch at BURST granularity — bm-burst@idx, at-burst@idx, idx += 8 words,
      // repeat. Direct-color issues 10 burst-8 reads/plane (byteIdx 0,32,..,288);
      // indexed issues single-word reads as before (byteIdx 0,4,..). Interleaving at
      // burst granularity keeps both planes at the same fill-ahead distance (the old
      // serial order left attr as a tail the beam outran, #12317). One burst-8 read
      // delivers 8 words back-to-back, so the whole 320×240 row fetch drops from
      // ~1566 to ~370 pixel-clocks — comfortably inside the ~1600/source-row budget
      // even with an AUTO_REFRESH landing mid-row.
      sFetchBitmap.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          when(byteIdx < fetchCount) {
            cmdRd   := True
            cmdAddr := (bitmapBaseUse +
                        bitmapRowByteBase +
                        byteIdx.resize(23)).resized
            inflightKind := False
            inflightIdx  := byteIdx
            burstCnt     := 0          // first word of this burst lands at idx+0
            goto(sFetchBitmapWait)
          } otherwise {
            // Both planes for the whole row are done (attr was fetched in lockstep).
            goto(sIdle)
          }
        }
      }

      sFetchBitmapWait.whenIsActive {
        sdramActiveR := True
        cmdRd := False
        pushEnable := True   // capture each burst word as data_ready pulses arrive
        // Count words within the burst; after the LAST word the bitmap burst is done —
        // fetch the attr burst at the SAME idx before advancing, so the planes fill together.
        when(byteFifo.io.push.fire) {
          when(burstCnt === (burstWords - 1)) {
            burstCnt := 0
            goto(sFetchAttr)
          } otherwise {
            burstCnt := burstCnt + 1
          }
        }
      }

      sFetchAttr.whenIsActive {
        sdramActiveR := True
        cmdRd := False; cmdWr := False
        when(!io.sdramBusy) {
          cmdRd   := True
          cmdAddr := (attrBaseUse +
                      attrRowByteBase +
                      byteIdx.resize(23)).resized
          inflightKind := True
          inflightIdx  := byteIdx
          burstCnt     := 0
          goto(sFetchAttrWait)
        }
      }

      sFetchAttrWait.whenIsActive {
        sdramActiveR := True
        cmdRd := False
        pushEnable := True
        // After the LAST attr word, advance the word index by one burst (burstWords*4
        // bytes) and loop back to the bitmap read, interleaving the two planes.
        when(byteFifo.io.push.fire) {
          when(burstCnt === (burstWords - 1)) {
            burstCnt := 0
            byteIdx  := byteIdx + (burstWords << 2).resize(byteIdx.getWidth)
            goto(sFetchBitmap)
          } otherwise {
            burstCnt := burstCnt + 1
          }
        }
      }
    }

    // Grant FIFO push. Push (wr ptr) is independent of the sIdle pop (rd ptr), so a
    // same-cycle consume+grant just advances both pointers — no ordering trick needed.
    // A grant arriving while the FIFO is FULL (2 already queued = FSM fell >2 rows
    // behind) is a genuine depth-2 overflow — counted for the proof, not silently
    // overwriting as the old depth-1 latch did.
    when(fetchGrantEdge) {
      when(grantFull) {
        grantOverflow := grantOverflow + 1
      } otherwise {
        grantFifo(grantWrPtr.resize(1)) := fetchLineSync
        grantWrPtr := grantWrPtr + 1
      }
    }

    io.sdramAddr := cmdAddr
    io.sdramDin  := cmdDin
    io.sdramRd   := cmdRd
    io.sdramWr   := cmdWr
    io.sdramRdNext := cmdRd.getAheadValue()   // #11246 F2 defensive look-ahead
    io.sdramWrNext := cmdWr.getAheadValue()
    // RGB565-FULLFRAME-132 Phase 0: this client's read burst length (quasi-static with
    // directColor). The arbiter forwards the granted client's value to sdram.v, which
    // latches it at the rd pulse. burstWords=1 outside direct-color → legacy single read.
    io.sdramBurstLen := burstWords

    // Level-high sdramActive: True across all non-idle states. Pulsing
    // on cmdRd/cmdWr alone is too narrow for the top-level pixelCd
    // BufferCC — arbiter would miss the window and drop writes. Gets
    // set when enable first sees high, stays high until fetch loop
    // quiesces in sIdle (with no new grant). Declared earlier now
    // so iter-5 always-on latch can reference it.
  }

  io.bootDone    := BufferCC(sd.bootDoneR, False)
  io.sdramActive := BufferCC(sd.sdramActiveR, False)
  io.sdramActiveRaw := sd.sdramActiveR   // raw sdramCd-domain level (no CDC) for same-domain refresh gating
}
