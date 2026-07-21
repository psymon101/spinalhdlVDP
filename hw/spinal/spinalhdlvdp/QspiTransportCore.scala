package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** CDC token carried through the SCLK->clk_sys StreamFifoCC. One token per header
  * or per completed 16-bit payload word (the #13888 drain-fix packing). Moved here
  * from TopTang20kQspi (Option A / #13974) so the core is self-contained when the
  * barebones transport top is not part of the build. */
case class QspiToken() extends Bundle {
  val isHeader = Bool()
  val opcode   = Bits(8 bits)
  val addr     = UInt(24 bits)
  val len      = UInt(16 bits)
  val word     = Bits(16 bits)
}

/** QspiTransportCore — the simmable domain-split transport core (no vendor IO
  * primitives). Wrapped by TopTang20kQspi with GowinIobuf tri-state + LEDs.
  *
  * [SCLK, CS#-reset]  QspiSlaveSync capture + SCLK-side read responder (magic v1).
  * [CDC token FIFO]   StreamFifoCC header+payload writes SCLK -> clk_sys, PUSH on
  *                    GLOBAL reset (survives CS# deassert).
  * [clk_sys]          QspiDecoder + regBus + SDRAM bridge outputs.
  */
case class QspiTransportCore(fifoDepth: Int = 512, dummyCycles: Int = 2, hdrParity: Boolean = false, externalSysCd: ClockDomain = null) extends Component {
  val io = new Bundle {
    val clk   = in  Bool()                 // continuous system clock
    val sclk  = in  Bool()                 // QSPI clock (gated)
    val csn   = in  Bool()
    val ioIn  = in  Bits(4 bits)
    val ioOut = out Bits(4 bits)
    val ioOe  = out Bool()
    // downstream observation (clk_sys domain)
    val regBus         = out(Mode0RegBus())
    val sdramByteOut   = out Bits(8 bits)
    val sdramByteValid = out Bool()
    // #13888 — word-granular SDRAM_WRITE egress (the drain-fix path). VdpTop-184 wires
    // this to the SDRAM bridge; the bring-up top lights everSdram off sdramWordValid.
    val sdramWordOut   = out Bits(16 bits)
    val sdramWordValid = out Bool()
    val sdramHeaderValid = out Bool()
    // Option A (#13974) — header fields the byte-granular QspiSdramBridge samples on
    // sdramHeaderValid (the barebones bring-up top never wired a real bridge, so these
    // were not surfaced). Sourced from the internal decoder.
    val sdramAddrInit  = out UInt(23 bits)
    val sdramLenBytes  = out UInt(17 bits)
    val overflow       = out Bool()        // sticky: token FIFO overflowed (should never fire post-drain-fix)
    val malformed      = out Bool()        // sticky: a header arrived with a dangling half-word (odd payload)
    val hdrErr         = out Bool()        // sticky: a header parity mismatch was seen (hdrParity only)
    // HAM6-2bpp #14246: 32-bit SDRAM debug readback word (armed via TopTang regs
    // 0x0326/0x0327, one-shot read in the sdram domain), surfaced over READ_STATUS sel=8.
    // Quasi-static in the clk_sys domain (armed once → one-shot read completes → then read
    // via sel=8), so a 2FF BufferCC into the SCLK responder is safe — same justification as
    // the sel=9 loopback. Lets the host split QSPI-upload corruption from downstream defects.
    val debug_sdram_data = in Bits(32 bits)
  }

  // DIAG #14260 sim integration: allow a parent to supply the sys clock domain so the
  // core can be wired to downstream logic (e.g. QspiSdramBridge) in the SAME domain.
  // Default preserves legacy behavior: core creates its own sysCd from io.clk.
  val sysCd = if (externalSysCd != null) externalSysCd else ClockDomain(clock = io.clk, config = ClockDomainConfig(resetKind = BOOT))

  val slave = QspiSlaveSync(dummyCycles = dummyCycles, hdrParity = hdrParity)
  slave.io.sclk := io.sclk
  slave.io.csn  := io.csn
  slave.io.ioIn := io.ioIn
  io.ioOut := slave.io.ioOut
  io.ioOe  := slave.io.ioOe

  // SCLK domain with GLOBAL (BOOT) reset — used by the FIFO push side (pointers must
  // survive CS# deassert) and the loopback status sync (persists across transactions).
  val sclkGlobalCd = ClockDomain(clock = io.sclk, config = ClockDomainConfig(resetKind = BOOT))

  val sys = new ClockingArea(sysCd) {
    val dec = QspiDecoder()
    dec.io.status_sticky    := B(0, 16 bits)
    dec.io.live_mode        := U(0, 4 bits)
    dec.io.debug_sdram_data := B(0, 32 bits)
    dec.io.upload_busy      := False
    dec.io.upload_done      := False
    dec.io.upload_error     := False
    dec.io.upload_overflow  := False
    dec.io.tx_byte_sent     := False
    // loopback latch: last register write, so the host can verify write->read (the full
    // SCLK->CDC->clk_sys->decoder path) by reading it back via READ_STATUS sel=9.
    val lastRegAddr = Reg(UInt(16 bits)) init 0
    val lastRegData = Reg(Bits(16 bits)) init 0
    when(dec.io.regBus.enable) { lastRegAddr := dec.io.regBus.addr.resize(16 bits); lastRegData := dec.io.regBus.data }
  }

  // SCLK-side read responder. Reads answered locally (no CDC round-trip): sel=0 magic,
  // sel=9 loopback (last reg write, crossed clk_sys->SCLK via BufferCC — static between
  // writes so 2FF sync is safe). Combinational off slave.cmdAddr (stable after cmdValid).
  slave.io.hdrErr.addTag(crossClockDomain)
  val loop = new ClockingArea(sclkGlobalCd) {
    val lastDataCC = BufferCC(sys.lastRegData, B(0, 16 bits))
    val lastAddrCC = BufferCC(sys.lastRegAddr, U(0, 16 bits))
    // sel=8 SDRAM readback: quasi-static debug word (armed via 0x0326/0x0327), 2FF-synced
    // into the SCLK responder — same static-value CDC justification as the loopback above.
    val dbgSdramCC = BufferCC(io.debug_sdram_data, B(0, 32 bits))
    // header parity error: sticky flag + running count (survive CS# on the global reset)
    val hdrErrSticky = Reg(Bool()) init False
    val hdrErrCount  = Reg(UInt(16 bits)) init 0
    when(slave.io.hdrErr) { hdrErrSticky := True; hdrErrCount := hdrErrCount + 1 }
  }
  // Read-responder switch is defined AFTER `push` (below) so sel=10 can surface the
  // token-FIFO overflow + malformed-length sticky flags, which live in the push area.

  val fifo = StreamFifoCC(QspiToken(), depth = fifoDepth, pushClock = sclkGlobalCd, popClock = sysCd)

  slave.io.cmdValid.addTag(crossClockDomain)
  slave.io.payloadValid.addTag(crossClockDomain)
  slave.io.cmdOpcode.addTag(crossClockDomain)
  slave.io.cmdAddr.addTag(crossClockDomain)
  slave.io.cmdLen.addTag(crossClockDomain)
  slave.io.payloadByte.addTag(crossClockDomain)

  val push = new ClockingArea(sclkGlobalCd) {
    // #13888 structural drain fix — SCLK-side 2-byte word assembler. Pack consecutive
    // payload bytes into a 16-bit word (hi ## lo) and push ONE word token per two bytes.
    // This halves the FIFO push rate (40->20 Mtok/s at 80 MHz quad) so the 27 MHz
    // word-rate pop (27 Mword/s) strictly outpaces it and the FIFO can never overflow.
    // State lives on the GLOBAL (BOOT) reset so a word straddling CS# deassert is not
    // lost; a NEW header flushes stale state (a dangling half-byte from an odd/malformed
    // payload is DISCARDED — never committed as a half-word write — and flagged sticky).
    val loByte    = Reg(Bits(8 bits)) init 0
    val haveLo    = Reg(Bool()) init False
    val malformed = Reg(Bool()) init False

    val wordComplete = slave.io.payloadValid && haveLo             // 2nd byte -> emit word
    val assembled    = slave.io.payloadByte ## loByte             // word = hi(2nd) ## lo(1st)

    when(slave.io.cmdValid) {
      when(haveLo) { malformed := True }                          // prior txn left a half-word
      haveLo := False                                             // flush/discard on new header
    } elsewhen(slave.io.payloadValid) {
      when(haveLo) {
        haveLo := False                                           // completed a word this cycle
      } otherwise {
        loByte := slave.io.payloadByte; haveLo := True            // latch low byte
      }
    }

    val tok = QspiToken()
    tok.isHeader := slave.io.cmdValid
    tok.opcode   := slave.io.cmdOpcode
    tok.addr     := slave.io.cmdAddr
    tok.len      := slave.io.cmdLen
    tok.word     := assembled
    // Push on a header OR a completed payload word (never a lone byte).
    fifo.io.push.valid   := slave.io.cmdValid || wordComplete
    fifo.io.push.payload := tok
    val overflow = Reg(Bool()) init False
    when(fifo.io.push.valid && !fifo.io.push.ready) { overflow := True }
  }

  // SCLK-side read responder. Reads answered locally (no CDC round-trip): sel=0 magic,
  // sel=7 header-parity {sticky,count}, sel=9 loopback (last reg write), sel=10 transport
  // health {malformed, overflow}. Combinational off slave.cmdAddr (stable after cmdValid).
  // overflow/malformed are read directly from the `push` area (same sclkGlobalCd domain).
  val sel = slave.io.cmdAddr(7 downto 0)
  val rxWordSel = Bits(32 bits)
  rxWordSel := B(0, 32 bits)
  switch(sel) {
    is(U(0, 8 bits)) { rxWordSel := B"32'h51560002" }                                 // magic
    is(U(7, 8 bits)) { rxWordSel := B(0, 15 bits) ## loop.hdrErrSticky ## loop.hdrErrCount.asBits }  // {sticky, count}
    is(U(8, 8 bits)) { rxWordSel := loop.dbgSdramCC }                                  // SDRAM debug readback (#14246; armed via 0x0326/0x0327)
    is(U(9, 8 bits)) { rxWordSel := loop.lastDataCC ## loop.lastAddrCC.asBits }        // loopback {data,addr}
    is(U(10, 8 bits)){ rxWordSel := B(0, 30 bits) ## push.malformed ## push.overflow } // transport health
    default          { rxWordSel := B(0, 32 bits) }
  }
  slave.io.rxWord := rxWordSel

  val pop = new ClockingArea(sysCd) {
    val t    = fifo.io.pop.payload
    // Option A (#13974) — VdpTop integration feeds the byte-granular QspiDecoder byte
    // path + the byte-addressed QspiSdramBridge (no word-capable bridge exists in-tree).
    // Unpack each popped payload word into two byte pulses (lo then hi) and HOLD the FIFO
    // token across both cycles so nothing is dropped (real backpressure, unlike a naive
    // fire-and-forget word->byte splitter). The FIFO still carries WORD tokens, so the
    // #13888 half-rate-push anti-overflow property is preserved. Headers still pop in one
    // cycle. Reg-write word assembly happens inside the decoder from these two bytes.
    val hiPhase   = Reg(Bool()) init False    // False = emit lo byte, True = emit hi byte
    val isPayload = fifo.io.pop.valid && !t.isHeader
    // Pop the token on a header (1 cycle) or after the hi byte of a payload word.
    fifo.io.pop.ready := Mux(isPayload, hiPhase, fifo.io.pop.valid)
    val fire = fifo.io.pop.valid
    sys.dec.io.cmd_valid     := fire && t.isHeader
    sys.dec.io.cmd_opcode    := t.opcode
    sys.dec.io.cmd_addr      := t.addr
    sys.dec.io.cmd_len       := t.len
    // Byte path: word = hi ## lo (assembled SCLK-side), so emit t.word[7:0] (host's
    // 1st byte) then t.word[15:8]; the decoder reassembles word = 2nd ## 1st = t.word.
    sys.dec.io.payload_valid := isPayload
    sys.dec.io.payload_byte  := Mux(hiPhase, t.word(15 downto 8), t.word(7 downto 0))
    when(isPayload) { hiPhase := !hiPhase }
    // Word path unused in the byte-bridge integration — tie off.
    sys.dec.io.payload_word       := B(0, 16 bits)
    sys.dec.io.payload_word_valid := False
    val overflowCC  = BufferCC(push.overflow, False)
    val malformedCC = BufferCC(push.malformed, False)
  }

  io.regBus           := sys.dec.io.regBus
  io.sdramByteOut     := sys.dec.io.sdramByteOut
  io.sdramByteValid   := sys.dec.io.sdramByteValid
  io.sdramWordOut     := sys.dec.io.sdramWordOut
  io.sdramWordValid   := sys.dec.io.sdramWordValid
  io.sdramHeaderValid := sys.dec.io.sdramHeaderValid
  io.sdramAddrInit    := sys.dec.io.sdramAddrInit
  io.sdramLenBytes    := sys.dec.io.sdramLenBytes
  io.overflow         := pop.overflowCC
  io.malformed        := pop.malformedCC
  io.hdrErr           := loop.hdrErrSticky
}
