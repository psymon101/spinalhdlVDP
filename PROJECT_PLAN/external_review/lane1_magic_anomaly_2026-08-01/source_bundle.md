# Source bundle for lane 1 first-cycle magic anomaly

Generated: 2026-08-01

This bundle contains the SpinalHDL sources, generated Verilog RTL excerpts, and ESP32-P4 firmware sources relevant to the QSPI transport and the first-cycle magic=0x22222222 anomaly.

---

## SpinalHDL: hw/spinal/spinalhdlvdp/QspiTransportCore.scala

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
    // Lane qspi-upload-si-hardening option-4 (#14568/#14574): host-pollable completion flag for the
    // sel=8 SDRAM debug read. Generated in the pixel domain (dbgResultPixArea), cleared on the 0x0327
    // arm write, set only after the settled result latch. Surfaced high-true at READ_STATUS sel=0x0C
    // bit 0 so the host arms → polls sel=0x0C until 1 → reads the coherent word via sel=8 (kills the
    // 1-read CDC lag on the sel=8 result). A single-bit level → a 2FF BufferCC is safe.
    val debug_read_done = in Bool() default False
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
  slave.io.crcBad.addTag(crossClockDomain)
  // QSPI-CRC8-185 (#14274): the CRC byte is the last frame byte (no trailing SCLK edge), so the
  // mismatch arrives as a combinational LEVEL (`slave.io.crcBad`) held through the CS#-hold
  // window. Capture it in the CONTINUOUS clk_sys domain (the SCLK/loop domains stop clocking
  // once SCLK idles), then surface {sticky,count} back to the SCLK read responder via BufferCC.
  val crcCap = new ClockingArea(sysCd) {
    val badSync    = BufferCC(slave.io.crcBad, False)
    val badPrev    = RegNext(badSync) init False
    val errSticky  = Reg(Bool()) init False
    val errCount   = Reg(UInt(16 bits)) init 0
    when(badSync && !badPrev) { errSticky := True; errCount := errCount + 1 }   // one per rising edge
  }
  val loop = new ClockingArea(sclkGlobalCd) {
    val lastDataCC = BufferCC(sys.lastRegData, B(0, 16 bits))
    val lastAddrCC = BufferCC(sys.lastRegAddr, U(0, 16 bits))
    // sel=8 SDRAM readback: quasi-static debug word (armed via 0x0326/0x0327), 2FF-synced
    // into the SCLK responder — same static-value CDC justification as the loopback above.
    val dbgSdramCC = BufferCC(io.debug_sdram_data, B(0, 32 bits))
    // option-4 (#14568/#14574): sel=0x0C READ_DONE completion flag (single-bit level, 2FF-safe).
    val readDoneCC = BufferCC(io.debug_read_done, False)
    // header parity error: sticky flag + running count (survive CS# on the global reset)
    val hdrErrSticky = Reg(Bool()) init False
    val hdrErrCount  = Reg(UInt(16 bits)) init 0
    when(slave.io.hdrErr) { hdrErrSticky := True; hdrErrCount := hdrErrCount + 1 }
    // QSPI-CRC8-185 (#14274): surface the clk_sys-captured CRC {sticky,count} into the SCLK read
    // responder via BufferCC (static once set — 2FF sync is safe, same as the sel=9 loopback).
    val crcErrSticky = BufferCC(crcCap.errSticky, False)
    val crcErrCount  = BufferCC(crcCap.errCount, U(0, 16 bits))
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
    is(U(11, 8 bits)){ rxWordSel := B(0, 15 bits) ## loop.crcErrSticky ## loop.crcErrCount.asBits } // CRC8 {sticky, count} (#14274)
    is(U(12, 8 bits)){ rxWordSel := B(0, 31 bits) ## loop.readDoneCC }                  // READ_DONE bit0 high-true (option-4 #14568/#14574; poll then read sel=8)
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

---

## SpinalHDL: hw/spinal/spinalhdlvdp/QspiSlave.scala (legacy pixel-oversampled slave, for reference)

package spinalhdlvdp

import spinal.core._

/** QSPI slave — quad-width, SCK-oversampled in the pixel-clock domain.
  *
  * Ported from the previous VDP project's proven `m0_qspi_slave.v`. Zero CDC —
  * everything runs on `clk_pixel` with 2-stage synchronisers on the async QSPI
  * pins. Quad mode: each SCK rising edge transfers one 4-bit nibble on
  * IO[3:0]; one byte = 2 edges (high nibble first).
  *
  * Protocol per CS assertion:
  *   [CMD:1] [ADDR:3] [LEN:2] [PAYLOAD:N]
  *   — multi-byte fields are little-endian
  *
  * For READ_STATUS (LEN=0): after the 6-byte header, 2 dummy SCK edges of
  * turnaround, then the FPGA drives IO[3:0] with the response nibbles.
  */
case class QspiSlave() extends Component {
  val io = new Bundle {
    // Async QSPI pins (directly from pads).
    val spi_cs_n  = in Bool()
    val spi_sck   = in Bool()
    val spi_io_in = in Bits (4 bits)
    // Driven back to the pads.
    val spi_io_out = out Bits (4 bits)
    val spi_io_oe  = out Bool()

    // Decoded header (pulses for one cycle when the full header is in).
    val cmd_opcode = out Bits (8 bits)
    val cmd_addr   = out UInt (24 bits)
    val cmd_len    = out UInt (16 bits)
    val cmd_valid  = out Bool()

    // Payload byte stream (one pulse per received byte after the header).
    val payload_byte  = out Bits (8 bits)
    val payload_valid = out Bool()

    // Response byte stream (for READ_STATUS).
    val tx_byte      = in Bits (8 bits)
    val tx_load      = in Bool()
    val tx_byte_sent = out Bool()

    // Status / diagnostics.
    val active     = out Bool()
    val byte_count = out UInt (16 bits)
  }

  // ---- 2-stage async synchronisers -------------------------------------
  val cs_n_sync = Reg(Bits(2 bits)) init B"11"
  val sck_sync  = Reg(Bits(2 bits)) init B"00"
  val io_sync_0 = Reg(Bits(4 bits)) init 0
  val io_sync_1 = Reg(Bits(4 bits)) init 0

  cs_n_sync := (cs_n_sync(0) ## io.spi_cs_n)
  sck_sync  := (sck_sync(0)  ## io.spi_sck)
  io_sync_0 := io.spi_io_in
  io_sync_1 := io_sync_0

  val cs_n_s = cs_n_sync(1)
  val sck_s  = sck_sync(1)
  val io_s   = io_sync_1

  // ---- SCK / CS edge detection -----------------------------------------
  val sck_prev  = Reg(Bool()) init False
  val cs_n_prev = Reg(Bool()) init True
  sck_prev  := sck_s
  cs_n_prev := cs_n_s

  val sck_rising  =  sck_s  && !sck_prev
  val sck_falling = !sck_s  &&  sck_prev
  val cs_start    =  cs_n_prev && !cs_n_s
  val cs_end      = !cs_n_prev &&  cs_n_s

  // ---- FSM + shift registers + header buffer ---------------------------
  object State extends SpinalEnum {
    val Idle, Header, Payload, Turnaround, Respond = newElement()
  }
  val state = Reg(State()) init State.Idle

  val rx_shift   = Reg(Bits(8 bits)) init 0
  val nibble_cnt = Reg(Bool()) init False      // false = waiting high nibble

  val hdr     = Vec(Reg(Bits(8 bits)) init 0, 6)
  val hdr_idx = Reg(UInt(3 bits)) init 0

  val tx_shift      = Reg(Bits(8 bits)) init 0
  val tx_nibble_cnt = Reg(Bool()) init False

  val turnaround_cnt = Reg(UInt(2 bits)) init 0
  val payload_remaining = Reg(UInt(16 bits)) init 0
  val respond_sampled   = Reg(Bool()) init False

  val active = Reg(Bool()) init False
  val cmd_opcode = Reg(Bits(8 bits))  init 0
  val cmd_addr   = Reg(UInt(24 bits)) init 0
  val cmd_len    = Reg(UInt(16 bits)) init 0
  val cmd_valid     = Reg(Bool()) init False
  val payload_byte  = Reg(Bits(8 bits)) init 0
  val payload_valid = Reg(Bool()) init False
  val byte_count    = Reg(UInt(16 bits)) init 0
  val tx_byte_sent  = Reg(Bool()) init False
  val spi_io_out    = Reg(Bits(4 bits)) init 0
  val spi_io_oe     = Reg(Bool()) init False

  // Default pulses low each cycle.
  cmd_valid     := False
  payload_valid := False
  tx_byte_sent  := False

  // TX byte load (can happen any time from the decoder).
  when(io.tx_load) {
    tx_shift      := io.tx_byte
    tx_nibble_cnt := False
    spi_io_out    := io.tx_byte(7 downto 4)   // preload high nibble
  }

  // Transaction start / end.
  when(cs_start) {
    active           := True
    state            := State.Header
    hdr_idx          := 0
    byte_count       := 0
    spi_io_oe        := False
    turnaround_cnt   := 0
    respond_sampled  := False
    // Start-edge race fix: if SCK rises in the same cycle as CS falls,
    // grab the nibble now so we don't lose it.
    when(sck_rising) {
      rx_shift   := io_s ## B"0000"
      nibble_cnt := True
    } otherwise {
      rx_shift   := 0
      nibble_cnt := False
    }
  }
  when(cs_end) {
    active     := False
    state      := State.Idle
    spi_io_oe  := False
  }

  // SCK rising edge: sample IO[3:0] (during RX states).
  when(sck_rising && !cs_n_s) {
    switch(state) {
      is(State.Header, State.Payload) {
        when(!nibble_cnt) {
          rx_shift   := io_s ## B"0000"
          nibble_cnt := True
        } otherwise {
          nibble_cnt := False
          byte_count := byte_count + 1
          val byteAssembled = (rx_shift(7 downto 4) ## io_s).asBits
          switch(state) {
            is(State.Header) {
              hdr(hdr_idx) := byteAssembled
              when(hdr_idx === U(5, 3 bits)) {
                // Header complete — decode.
                cmd_opcode := hdr(0)
                cmd_addr   := (hdr(3) ## hdr(2) ## hdr(1)).asUInt
                val lenFull = (byteAssembled ## hdr(4)).asUInt
                cmd_len    := lenFull
                cmd_valid  := True
                // Plan §3.1: LEN is the number of 16-bit words.  Each word
                // consumes 2 payload bytes, so the byte-level remaining
                // counter is `len << 1`.
                payload_remaining := (lenFull << 1).resize(16)
                when(lenFull === U(0, 16 bits)) {
                  state          := State.Turnaround
                  turnaround_cnt := 0
                } otherwise {
                  state := State.Payload
                }
              } otherwise {
                hdr_idx := hdr_idx + 1
              }
            }
            is(State.Payload) {
              payload_byte  := byteAssembled
              payload_valid := True
              when(payload_remaining > U(1, 16 bits)) {
                payload_remaining := payload_remaining - 1
              } otherwise {
                // Last payload byte — turnaround then respond.
                state          := State.Turnaround
                turnaround_cnt := 0
              }
            }
            default {}
          }
        }
      }
      is(State.Turnaround) {
        when(turnaround_cnt === U(1, 2 bits)) {
          state     := State.Respond
          spi_io_oe := True
        } otherwise {
          turnaround_cnt := turnaround_cnt + 1
        }
      }
      is(State.Respond) {
        respond_sampled := True
      }
      default {}
    }
  }

  // SCK falling edge: update IO[3:0] in S_RESPOND after master has sampled.
  when(sck_falling && !cs_n_s && state === State.Respond && respond_sampled) {
    respond_sampled := False
    when(!tx_nibble_cnt) {
      spi_io_out    := tx_shift(3 downto 0)
      tx_nibble_cnt := True
    } otherwise {
      tx_byte_sent  := True
      tx_nibble_cnt := False
    }
  }

  io.cmd_opcode    := cmd_opcode
  io.cmd_addr      := cmd_addr
  io.cmd_len       := cmd_len
  io.cmd_valid     := cmd_valid
  io.payload_byte  := payload_byte
  io.payload_valid := payload_valid
  io.tx_byte_sent  := tx_byte_sent
  io.active        := active
  io.byte_count    := byte_count
  io.spi_io_out    := spi_io_out
  io.spi_io_oe     := spi_io_oe
}

---

## SpinalHDL: hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala (QSPI frontend instantiation only, lines ~387-430)

    // Platform-agnosticism purge: bootstrap affine animator removed.
    // Host owns matrix/affine register writes at runtime.
    val (animWriteAddr, animWriteData, animWriteActive): (UInt, Bits, Bool) =
      (U(0, 15 bits), B(0, 16 bits), False)

    // QSPI host-control frontend — Option A (#13973/#13974): the synchronous
    // word-drain QspiTransportCore (SCLK-domain capture + CDC token FIFO + an
    // internal QspiDecoder) replaces the legacy pixel-oversampled QspiSlave/QspiDecoder
    // pair. It fixes the READ_STATUS read-header framing mismatch (#13966: legacy
    // required a QUAD header with a LEN phase; the P4 firmware sends a single-lane
    // header with no LEN on reads) that stalled the legacy slave at 0x22222222.
    // Instantiated unconditionally (idle-tied in the i80 build) so RegBusArbiter
    // master(1) always has a driver. Its sys domain runs on the pixel clock
    // (clkdiv.CLKOUT) — the same edge as pixelClockDomain, so the core's
    // sysCd(BOOT) -> pixelClockDomain(ASYNC) signal crossings are same-clock
    // synchronous, not CDC.
    val qspiCore = QspiTransportCore()
    qspiCore.io.clk := clkdiv.CLKOUT
    if (!hostI80) {
      qspiCore.io.csn  := I_qspi_cs
      qspiCore.io.sclk := I_qspi_sck
    } else {
      qspiCore.io.csn  := True    // QSPI host removed in the i80 build; hold idle
      qspiCore.io.sclk := False
    }
    // Bidirectional quad IO via Gowin IOBUF primitives. The core drives ioOut when
    // ioOe=1 (READ_STATUS respond, answered SCLK-side); high-Z otherwise so the host's
    // drive is sensed back on .O into ioIn. Pin order IO3 high .. IO0 low.
    if (!hostI80) {
      val qspiIobuf = Seq.tabulate(4) { i =>
        val buf = GowinIobuf()
        buf.I   := qspiCore.io.ioOut(i)
        buf.OEN := !qspiCore.io.ioOe
        buf
      }
      qspiIobuf(0).IO <> IO_qspi_io0
      qspiIobuf(1).IO <> IO_qspi_io1
      qspiIobuf(2).IO <> IO_qspi_io2
      qspiIobuf(3).IO <> IO_qspi_io3
      qspiCore.io.ioIn := (qspiIobuf(3).O ## qspiIobuf(2).O ## qspiIobuf(1).O ## qspiIobuf(0).O)
    } else {
      qspiCore.io.ioIn := B(0, 4 bits)   // no pads; host drive sensed as 0
    }


---

## Generated Verilog: hw/gen/top_tang20k.v — QspiTransportCore module

module QspiTransportCore (
  input  wire          io_clk,
  input  wire          io_sclk,
  input  wire          io_csn,
  input  wire [3:0]    io_ioIn,
  output wire [3:0]    io_ioOut,
  output wire          io_ioOe,
  output wire [14:0]   io_regBus_addr,
  output wire [15:0]   io_regBus_data,
  output wire          io_regBus_enable,
  output wire [7:0]    io_sdramByteOut,
  output wire          io_sdramByteValid,
  output wire [15:0]   io_sdramWordOut,
  output wire          io_sdramWordValid,
  output wire          io_sdramHeaderValid,
  output wire [22:0]   io_sdramAddrInit,
  output wire [16:0]   io_sdramLenBytes,
  output wire          io_overflow,
  output wire          io_malformed,
  output wire          io_hdrErr,
  input  wire [31:0]   io_debug_sdram_data,
  input  wire          io_debug_read_done
);

  wire                sys_dec_io_cmd_valid;
  wire       [7:0]    sys_dec_io_payload_byte;
  wire                fifo_io_push_valid;
  wire                fifo_io_pop_ready;
  wire       [3:0]    slave_io_ioOut;
  wire                slave_io_ioOe;
  wire       [7:0]    slave_io_cmdOpcode;
  wire       [23:0]   slave_io_cmdAddr;
  wire       [15:0]   slave_io_cmdLen;
  wire                slave_io_cmdValid;
  wire       [7:0]    slave_io_payloadByte;
  wire                slave_io_payloadValid;
  wire                slave_io_active;
  wire                slave_io_hdrErr;
  wire                slave_io_crcBad;
  wire       [7:0]    sys_dec_io_tx_byte;
  wire                sys_dec_io_tx_load;
  wire       [14:0]   sys_dec_io_regBus_addr;
  wire       [15:0]   sys_dec_io_regBus_data;
  wire                sys_dec_io_regBus_enable;
  wire       [7:0]    sys_dec_io_last_error;
  wire                sys_dec_io_sdramHeaderValid;
  wire       [22:0]   sys_dec_io_sdramAddrInit;
  wire       [16:0]   sys_dec_io_sdramLenBytes;
  wire       [7:0]    sys_dec_io_sdramByteOut;
  wire                sys_dec_io_sdramByteValid;
  wire       [15:0]   sys_dec_io_sdramWordOut;
  wire                sys_dec_io_sdramWordValid;
  wire       [31:0]   sys_dec_io_rx_word;
  wire                sys_dec_io_rx_word_valid;
  wire                slave_io_crcBad_buffercc_io_dataOut;
  wire       [15:0]   sys_lastRegData_buffercc_io_dataOut;
  wire       [15:0]   sys_lastRegAddr_buffercc_io_dataOut;
  wire       [31:0]   io_debug_sdram_data_buffercc_io_dataOut;
  wire                io_debug_read_done_buffercc_io_dataOut;
  wire                crcCap_errSticky_buffercc_io_dataOut;
  wire       [15:0]   crcCap_errCount_buffercc_io_dataOut;
  wire                fifo_io_push_ready;
  wire                fifo_io_pop_valid;
  wire                fifo_io_pop_payload_isHeader;
  wire       [7:0]    fifo_io_pop_payload_opcode;
  wire       [23:0]   fifo_io_pop_payload_addr;
  wire       [15:0]   fifo_io_pop_payload_len;
  wire       [15:0]   fifo_io_pop_payload_word;
  wire       [9:0]    fifo_io_pushOccupancy;
  wire       [9:0]    fifo_io_popOccupancy;
  wire                push_overflow_buffercc_io_dataOut;
  wire                push_malformed_buffercc_io_dataOut;
  reg        [15:0]   sys_lastRegAddr;
  reg        [15:0]   sys_lastRegData;
  wire                crcCap_badSync;
  reg                 crcCap_badPrev;
  reg                 crcCap_errSticky;
  reg        [15:0]   crcCap_errCount;
  wire                when_QspiTransportCore_l112;
  wire       [15:0]   loop_lastDataCC;
  wire       [15:0]   loop_lastAddrCC;
  wire       [31:0]   loop_dbgSdramCC;
  wire                loop_readDoneCC;
  reg                 loop_hdrErrSticky;
  reg        [15:0]   loop_hdrErrCount;
  wire                loop_crcErrSticky;
  wire       [15:0]   loop_crcErrCount;
  reg        [7:0]    push_loByte;
  reg                 push_haveLo;
  reg                 push_malformed;
  wire                push_wordComplete;
  wire       [15:0]   push_assembled;
  wire                push_tok_isHeader;
  wire       [7:0]    push_tok_opcode;
  wire       [23:0]   push_tok_addr;
  wire       [15:0]   push_tok_len;
  wire       [15:0]   push_tok_word;
  reg                 push_overflow;
  wire                when_QspiTransportCore_l179;
  wire       [7:0]    sel;
  reg        [31:0]   rxWordSel;
  reg                 pop_hiPhase;
  wire                pop_isPayload;
  wire                pop_overflowCC;
  wire                pop_malformedCC;

  QspiSlaveSync slave (
    .io_sclk         (io_sclk                  ), //i
    .io_csn          (io_csn                   ), //i
    .io_ioIn         (io_ioIn[3:0]             ), //i
    .io_ioOut        (slave_io_ioOut[3:0]      ), //o
    .io_ioOe         (slave_io_ioOe            ), //o
    .io_cmdOpcode    (slave_io_cmdOpcode[7:0]  ), //o
    .io_cmdAddr      (slave_io_cmdAddr[23:0]   ), //o
    .io_cmdLen       (slave_io_cmdLen[15:0]    ), //o
    .io_cmdValid     (slave_io_cmdValid        ), //o
    .io_payloadByte  (slave_io_payloadByte[7:0]), //o
    .io_payloadValid (slave_io_payloadValid    ), //o
    .io_rxWord       (rxWordSel[31:0]          ), //i
    .io_active       (slave_io_active          ), //o
    .io_hdrErr       (slave_io_hdrErr          ), //o
    .io_crcBad       (slave_io_crcBad          )  //o
  );
  QspiDecoder sys_dec (
    .io_cmd_opcode         (fifo_io_pop_payload_opcode[7:0]), //i
    .io_cmd_addr           (fifo_io_pop_payload_addr[23:0] ), //i
    .io_cmd_len            (fifo_io_pop_payload_len[15:0]  ), //i
    .io_cmd_valid          (sys_dec_io_cmd_valid           ), //i
    .io_payload_byte       (sys_dec_io_payload_byte[7:0]   ), //i
    .io_payload_valid      (pop_isPayload                  ), //i
    .io_payload_word       (16'h0                          ), //i
    .io_payload_word_valid (1'b0                           ), //i
    .io_tx_byte            (sys_dec_io_tx_byte[7:0]        ), //o
    .io_tx_load            (sys_dec_io_tx_load             ), //o
    .io_tx_byte_sent       (1'b0                           ), //i
    .io_active             (                               ), //i
    .io_regBus_addr        (sys_dec_io_regBus_addr[14:0]   ), //o
    .io_regBus_data        (sys_dec_io_regBus_data[15:0]   ), //o
    .io_regBus_enable      (sys_dec_io_regBus_enable       ), //o
    .io_last_error         (sys_dec_io_last_error[7:0]     ), //o
    .io_status_sticky      (16'h0                          ), //i
    .io_live_mode          (4'b0000                        ), //i
    .io_debug_sdram_data   (32'h0                          ), //i
    .io_sdramHeaderValid   (sys_dec_io_sdramHeaderValid    ), //o
    .io_sdramAddrInit      (sys_dec_io_sdramAddrInit[22:0] ), //o
    .io_sdramLenBytes      (sys_dec_io_sdramLenBytes[16:0] ), //o
    .io_sdramByteOut       (sys_dec_io_sdramByteOut[7:0]   ), //o
    .io_sdramByteValid     (sys_dec_io_sdramByteValid      ), //o
    .io_sdramWordOut       (sys_dec_io_sdramWordOut[15:0]  ), //o
    .io_sdramWordValid     (sys_dec_io_sdramWordValid      ), //o
    .io_upload_busy        (1'b0                           ), //i
    .io_upload_done        (1'b0                           ), //i
    .io_upload_error       (1'b0                           ), //i
    .io_upload_overflow    (1'b0                           ), //i
    .io_rx_word            (sys_dec_io_rx_word[31:0]       ), //o
    .io_rx_word_valid      (sys_dec_io_rx_word_valid       ), //o
    .io_clk                (io_clk                         )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_12 slave_io_crcBad_buffercc (
    .io_dataIn  (slave_io_crcBad                    ), //i
    .io_dataOut (slave_io_crcBad_buffercc_io_dataOut), //o
    .io_clk     (io_clk                             )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_13 sys_lastRegData_buffercc (
    .io_dataIn  (sys_lastRegData[15:0]                    ), //i
    .io_dataOut (sys_lastRegData_buffercc_io_dataOut[15:0]), //o
    .io_sclk    (io_sclk                                  )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_13 sys_lastRegAddr_buffercc (
    .io_dataIn  (sys_lastRegAddr[15:0]                    ), //i
    .io_dataOut (sys_lastRegAddr_buffercc_io_dataOut[15:0]), //o
    .io_sclk    (io_sclk                                  )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_15 io_debug_sdram_data_buffercc (
    .io_dataIn  (io_debug_sdram_data[31:0]                    ), //i
    .io_dataOut (io_debug_sdram_data_buffercc_io_dataOut[31:0]), //o
    .io_sclk    (io_sclk                                      )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_16 io_debug_read_done_buffercc (
    .io_dataIn  (io_debug_read_done                    ), //i
    .io_dataOut (io_debug_read_done_buffercc_io_dataOut), //o
    .io_sclk    (io_sclk                               )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_16 crcCap_errSticky_buffercc (
    .io_dataIn  (crcCap_errSticky                    ), //i
    .io_dataOut (crcCap_errSticky_buffercc_io_dataOut), //o
    .io_sclk    (io_sclk                             )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_13 crcCap_errCount_buffercc (
    .io_dataIn  (crcCap_errCount[15:0]                    ), //i
    .io_dataOut (crcCap_errCount_buffercc_io_dataOut[15:0]), //o
    .io_sclk    (io_sclk                                  )  //i
  );
  StreamFifoCC_1 fifo (
    .io_push_valid            (fifo_io_push_valid             ), //i
    .io_push_ready            (fifo_io_push_ready             ), //o
    .io_push_payload_isHeader (push_tok_isHeader              ), //i
    .io_push_payload_opcode   (push_tok_opcode[7:0]           ), //i
    .io_push_payload_addr     (push_tok_addr[23:0]            ), //i
    .io_push_payload_len      (push_tok_len[15:0]             ), //i
    .io_push_payload_word     (push_tok_word[15:0]            ), //i
    .io_pop_valid             (fifo_io_pop_valid              ), //o
    .io_pop_ready             (fifo_io_pop_ready              ), //i
    .io_pop_payload_isHeader  (fifo_io_pop_payload_isHeader   ), //o
    .io_pop_payload_opcode    (fifo_io_pop_payload_opcode[7:0]), //o
    .io_pop_payload_addr      (fifo_io_pop_payload_addr[23:0] ), //o
    .io_pop_payload_len       (fifo_io_pop_payload_len[15:0]  ), //o
    .io_pop_payload_word      (fifo_io_pop_payload_word[15:0] ), //o
    .io_pushOccupancy         (fifo_io_pushOccupancy[9:0]     ), //o
    .io_popOccupancy          (fifo_io_popOccupancy[9:0]      ), //o
    .io_sclk                  (io_sclk                        ), //i
    .io_clk                   (io_clk                         )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_12 push_overflow_buffercc (
    .io_dataIn  (push_overflow                    ), //i
    .io_dataOut (push_overflow_buffercc_io_dataOut), //o
    .io_clk     (io_clk                           )  //i
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_12 push_malformed_buffercc (
    .io_dataIn  (push_malformed                    ), //i
    .io_dataOut (push_malformed_buffercc_io_dataOut), //o
    .io_clk     (io_clk                            )  //i
  );
  initial begin
    sys_lastRegAddr = 16'h0;
    sys_lastRegData = 16'h0;
    crcCap_badPrev = 1'b0;
    crcCap_errSticky = 1'b0;
    crcCap_errCount = 16'h0;
    loop_hdrErrSticky = 1'b0;
    loop_hdrErrCount = 16'h0;
    push_loByte = 8'h0;
    push_haveLo = 1'b0;
    push_malformed = 1'b0;
    push_overflow = 1'b0;
    pop_hiPhase = 1'b0;
  end

  assign io_ioOut = slave_io_ioOut;
  assign io_ioOe = slave_io_ioOe;
  assign crcCap_badSync = slave_io_crcBad_buffercc_io_dataOut;
  assign when_QspiTransportCore_l112 = (crcCap_badSync && (! crcCap_badPrev));
  assign loop_lastDataCC = sys_lastRegData_buffercc_io_dataOut;
  assign loop_lastAddrCC = sys_lastRegAddr_buffercc_io_dataOut;
  assign loop_dbgSdramCC = io_debug_sdram_data_buffercc_io_dataOut;
  assign loop_readDoneCC = io_debug_read_done_buffercc_io_dataOut;
  assign loop_crcErrSticky = crcCap_errSticky_buffercc_io_dataOut;
  assign loop_crcErrCount = crcCap_errCount_buffercc_io_dataOut;
  assign push_wordComplete = (slave_io_payloadValid && push_haveLo);
  assign push_assembled = {slave_io_payloadByte,push_loByte};
  assign push_tok_isHeader = slave_io_cmdValid;
  assign push_tok_opcode = slave_io_cmdOpcode;
  assign push_tok_addr = slave_io_cmdAddr;
  assign push_tok_len = slave_io_cmdLen;
  assign push_tok_word = push_assembled;
  assign fifo_io_push_valid = (slave_io_cmdValid || push_wordComplete);
  assign when_QspiTransportCore_l179 = (fifo_io_push_valid && (! fifo_io_push_ready));
  assign sel = slave_io_cmdAddr[7 : 0];
  always @(*) begin
    rxWordSel = 32'h0;
    case(sel)
      8'h0 : begin
        rxWordSel = 32'h51560002;
      end
      8'h07 : begin
        rxWordSel = {{15'h0,loop_hdrErrSticky},loop_hdrErrCount};
      end
      8'h08 : begin
        rxWordSel = loop_dbgSdramCC;
      end
      8'h09 : begin
        rxWordSel = {loop_lastDataCC,loop_lastAddrCC};
      end
      8'h0a : begin
        rxWordSel = {{30'h0,push_malformed},push_overflow};
      end
      8'h0b : begin
        rxWordSel = {{15'h0,loop_crcErrSticky},loop_crcErrCount};
      end
      8'h0c : begin
        rxWordSel = {31'h0,loop_readDoneCC};
      end
      default : begin
        rxWordSel = 32'h0;
      end
    endcase
  end

  assign pop_isPayload = (fifo_io_pop_valid && (! fifo_io_pop_payload_isHeader));
  assign fifo_io_pop_ready = (pop_isPayload ? pop_hiPhase : fifo_io_pop_valid);
  assign sys_dec_io_cmd_valid = (fifo_io_pop_valid && fifo_io_pop_payload_isHeader);
  assign sys_dec_io_payload_byte = (pop_hiPhase ? fifo_io_pop_payload_word[15 : 8] : fifo_io_pop_payload_word[7 : 0]);
  assign pop_overflowCC = push_overflow_buffercc_io_dataOut;
  assign pop_malformedCC = push_malformed_buffercc_io_dataOut;
  assign io_regBus_addr = sys_dec_io_regBus_addr;
  assign io_regBus_data = sys_dec_io_regBus_data;
  assign io_regBus_enable = sys_dec_io_regBus_enable;
  assign io_sdramByteOut = sys_dec_io_sdramByteOut;
  assign io_sdramByteValid = sys_dec_io_sdramByteValid;
  assign io_sdramWordOut = sys_dec_io_sdramWordOut;
  assign io_sdramWordValid = sys_dec_io_sdramWordValid;
  assign io_sdramHeaderValid = sys_dec_io_sdramHeaderValid;
  assign io_sdramAddrInit = sys_dec_io_sdramAddrInit;
  assign io_sdramLenBytes = sys_dec_io_sdramLenBytes;
  assign io_overflow = pop_overflowCC;
  assign io_malformed = pop_malformedCC;
  assign io_hdrErr = loop_hdrErrSticky;
  always @(posedge io_clk) begin
    if(sys_dec_io_regBus_enable) begin
      sys_lastRegAddr <= {1'd0, sys_dec_io_regBus_addr};
      sys_lastRegData <= sys_dec_io_regBus_data;
    end
    crcCap_badPrev <= crcCap_badSync;
    if(when_QspiTransportCore_l112) begin
      crcCap_errSticky <= 1'b1;
      crcCap_errCount <= (crcCap_errCount + 16'h0001);
    end
    if(pop_isPayload) begin
      pop_hiPhase <= (! pop_hiPhase);
    end
  end

  always @(posedge io_sclk) begin
    if(slave_io_hdrErr) begin
      loop_hdrErrSticky <= 1'b1;
      loop_hdrErrCount <= (loop_hdrErrCount + 16'h0001);
    end
    if(slave_io_cmdValid) begin
      if(push_haveLo) begin
        push_malformed <= 1'b1;
      end
      push_haveLo <= 1'b0;
    end else begin
      if(slave_io_payloadValid) begin
        if(push_haveLo) begin
          push_haveLo <= 1'b0;
        end else begin
          push_loByte <= slave_io_payloadByte;
          push_haveLo <= 1'b1;
        end
      end
    end
    if(when_QspiTransportCore_l179) begin
      push_overflow <= 1'b1;
    end
  end


endmodule

//ScrollWrap_6 replaced by ScrollWrap

//ScrollWrap_5 replaced by ScrollWrap


---

## Generated Verilog: hw/gen/top_tang20k.v — QspiSlaveSync module

module QspiSlaveSync (
  input  wire          io_sclk,
  input  wire          io_csn,
  input  wire [3:0]    io_ioIn,
  output wire [3:0]    io_ioOut,
  output wire          io_ioOe,
  output wire [7:0]    io_cmdOpcode,
  output wire [23:0]   io_cmdAddr,
  output wire [15:0]   io_cmdLen,
  output wire          io_cmdValid,
  output wire [7:0]    io_payloadByte,
  output wire          io_payloadValid,
  input  wire [31:0]   io_rxWord,
  output wire          io_active,
  output wire          io_hdrErr,
  output wire          io_crcBad
);
  localparam Phase_CMD = 3'd0;
  localparam Phase_ADDR = 3'd1;
  localparam Phase_LENCAP = 3'd2;
  localparam Phase_WDATA = 3'd3;
  localparam Phase_DUMMY = 3'd4;
  localparam Phase_RDATA = 3'd5;

  wire       [16:0]   _zz_when_QspiSlaveSync_l203;
  wire       [16:0]   _zz_io_payloadValid;
  reg        [7:0]    _zz__zz_outArea_ioOutF;
  wire       [1:0]    _zz__zz_outArea_ioOutF_1;
  wire       [2:0]    _zz__zz_outArea_ioOutF_2;
  (* async_reg = "true" *) reg        [2:0]    area_phase;
  reg        [5:0]    area_bitc;
  reg        [7:0]    area_cmdSh;
  reg        [23:0]   area_addrSh;
  reg        [7:0]    area_opcodeR;
  reg        [23:0]   area_addrR;
  reg        [15:0]   area_lenR;
  reg                 area_cmdValidR;
  reg                 area_hdrErrR;
  reg        [7:0]    area_lenLo;
  reg        [0:0]    area_lenByteCnt;
  reg        [7:0]    area_crcReg;
  reg        [16:0]   area_payloadByteCnt;
  reg        [7:0]    area_rxCrcR;
  reg                 area_crcSeenR;
  reg                 area_nibHigh;
  reg        [3:0]    area_hiNib;
  (* async_reg = "true" *) reg        [31:0]   area_rdWord;
  (* async_reg = "true" *) reg        [3:0]    area_rdNib;
  wire                when_QspiSlaveSync_l153;
  wire       [23:0]   _zz_area_addrSh;
  wire                when_QspiSlaveSync_l158;
  wire                when_QspiSlaveSync_l164;
  wire       [7:0]    _zz_area_crcReg;
  wire       [7:0]    _zz_area_crcReg_1;
  wire       [7:0]    _zz_area_crcReg_2;
  wire       [7:0]    _zz_area_crcReg_3;
  wire       [7:0]    _zz_area_crcReg_4;
  wire       [7:0]    _zz_area_crcReg_5;
  wire       [7:0]    _zz_area_crcReg_6;
  wire       [7:0]    _zz_area_crcReg_7;
  wire       [7:0]    _zz_area_crcReg_8;
  wire       [7:0]    _zz_area_crcReg_9;
  wire       [7:0]    _zz_area_crcReg_10;
  wire       [7:0]    _zz_area_crcReg_11;
  wire       [7:0]    _zz_area_crcReg_12;
  wire       [7:0]    _zz_area_crcReg_13;
  wire       [7:0]    _zz_area_crcReg_14;
  wire       [7:0]    _zz_area_crcReg_15;
  wire       [7:0]    _zz_area_crcReg_16;
  wire       [7:0]    _zz_area_crcReg_17;
  wire       [7:0]    _zz_area_crcReg_18;
  wire       [7:0]    _zz_area_crcReg_19;
  wire       [7:0]    _zz_area_crcReg_20;
  wire       [7:0]    _zz_area_crcReg_21;
  wire       [7:0]    _zz_area_crcReg_22;
  wire       [7:0]    _zz_area_crcReg_23;
  wire       [7:0]    _zz_area_crcReg_24;
  wire       [7:0]    _zz_area_crcReg_25;
  wire       [7:0]    _zz_area_crcReg_26;
  wire       [7:0]    _zz_area_crcReg_27;
  wire       [7:0]    _zz_area_crcReg_28;
  wire       [7:0]    _zz_area_crcReg_29;
  wire       [7:0]    _zz_area_crcReg_30;
  wire       [7:0]    _zz_area_crcReg_31;
  wire       [7:0]    _zz_area_lenR;
  wire       [7:0]    _zz_area_crcReg_32;
  wire       [7:0]    _zz_area_crcReg_33;
  wire       [7:0]    _zz_area_crcReg_34;
  wire       [7:0]    _zz_area_crcReg_35;
  wire       [7:0]    _zz_area_crcReg_36;
  wire       [7:0]    _zz_area_crcReg_37;
  wire       [7:0]    _zz_area_crcReg_38;
  wire       [7:0]    _zz_area_crcReg_39;
  wire                when_QspiSlaveSync_l186;
  wire       [7:0]    _zz_area_rxCrcR;
  wire                when_QspiSlaveSync_l203;
  wire       [7:0]    _zz_area_crcReg_40;
  wire       [7:0]    _zz_area_crcReg_41;
  wire       [7:0]    _zz_area_crcReg_42;
  wire       [7:0]    _zz_area_crcReg_43;
  wire       [7:0]    _zz_area_crcReg_44;
  wire       [7:0]    _zz_area_crcReg_45;
  wire       [7:0]    _zz_area_crcReg_46;
  wire       [7:0]    _zz_area_crcReg_47;
  wire                when_QspiSlaveSync_l216;
  wire                when_QspiSlaveSync_l226;
  reg        [3:0]    outArea_ioOutF;
  reg                 outArea_ioOeF;
  wire                when_QspiSlaveSync_l256;
  wire       [7:0]    _zz_outArea_ioOutF;
  `ifndef SYNTHESIS
  reg [47:0] area_phase_string;
  `endif


  assign _zz_when_QspiSlaveSync_l203 = ({1'd0,area_lenR} <<< 1'd1);
  assign _zz_io_payloadValid = ({1'd0,area_lenR} <<< 1'd1);
  assign _zz__zz_outArea_ioOutF_2 = (area_rdNib >>> 1'd1);
  assign _zz__zz_outArea_ioOutF_1 = _zz__zz_outArea_ioOutF_2[1:0];
  always @(*) begin
    case(_zz__zz_outArea_ioOutF_1)
      2'b00 : _zz__zz_outArea_ioOutF = area_rdWord[7 : 0];
      2'b01 : _zz__zz_outArea_ioOutF = area_rdWord[15 : 8];
      2'b10 : _zz__zz_outArea_ioOutF = area_rdWord[23 : 16];
      default : _zz__zz_outArea_ioOutF = area_rdWord[31 : 24];
    endcase
  end

  `ifndef SYNTHESIS
  always @(*) begin
    case(area_phase)
      Phase_CMD : area_phase_string = "CMD   ";
      Phase_ADDR : area_phase_string = "ADDR  ";
      Phase_LENCAP : area_phase_string = "LENCAP";
      Phase_WDATA : area_phase_string = "WDATA ";
      Phase_DUMMY : area_phase_string = "DUMMY ";
      Phase_RDATA : area_phase_string = "RDATA ";
      default : area_phase_string = "??????";
    endcase
  end
  `endif

  assign when_QspiSlaveSync_l153 = (area_bitc == 6'h07);
  assign _zz_area_addrSh = {area_addrSh[22 : 0],io_ioIn[0]};
  assign when_QspiSlaveSync_l158 = (area_bitc == 6'h17);
  assign when_QspiSlaveSync_l164 = (area_cmdSh == 8'h04);
  assign _zz_area_crcReg = (8'h0 ^ area_cmdSh);
  assign _zz_area_crcReg_1 = (_zz_area_crcReg[7] ? ({_zz_area_crcReg[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg[6 : 0],1'b0});
  assign _zz_area_crcReg_2 = (_zz_area_crcReg_1[7] ? ({_zz_area_crcReg_1[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_1[6 : 0],1'b0});
  assign _zz_area_crcReg_3 = (_zz_area_crcReg_2[7] ? ({_zz_area_crcReg_2[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_2[6 : 0],1'b0});
  assign _zz_area_crcReg_4 = (_zz_area_crcReg_3[7] ? ({_zz_area_crcReg_3[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_3[6 : 0],1'b0});
  assign _zz_area_crcReg_5 = (_zz_area_crcReg_4[7] ? ({_zz_area_crcReg_4[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_4[6 : 0],1'b0});
  assign _zz_area_crcReg_6 = (_zz_area_crcReg_5[7] ? ({_zz_area_crcReg_5[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_5[6 : 0],1'b0});
  assign _zz_area_crcReg_7 = (_zz_area_crcReg_6[7] ? ({_zz_area_crcReg_6[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_6[6 : 0],1'b0});
  assign _zz_area_crcReg_8 = ((_zz_area_crcReg_7[7] ? ({_zz_area_crcReg_7[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_7[6 : 0],1'b0}) ^ _zz_area_addrSh[23 : 16]);
  assign _zz_area_crcReg_9 = (_zz_area_crcReg_8[7] ? ({_zz_area_crcReg_8[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_8[6 : 0],1'b0});
  assign _zz_area_crcReg_10 = (_zz_area_crcReg_9[7] ? ({_zz_area_crcReg_9[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_9[6 : 0],1'b0});
  assign _zz_area_crcReg_11 = (_zz_area_crcReg_10[7] ? ({_zz_area_crcReg_10[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_10[6 : 0],1'b0});
  assign _zz_area_crcReg_12 = (_zz_area_crcReg_11[7] ? ({_zz_area_crcReg_11[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_11[6 : 0],1'b0});
  assign _zz_area_crcReg_13 = (_zz_area_crcReg_12[7] ? ({_zz_area_crcReg_12[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_12[6 : 0],1'b0});
  assign _zz_area_crcReg_14 = (_zz_area_crcReg_13[7] ? ({_zz_area_crcReg_13[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_13[6 : 0],1'b0});
  assign _zz_area_crcReg_15 = (_zz_area_crcReg_14[7] ? ({_zz_area_crcReg_14[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_14[6 : 0],1'b0});
  assign _zz_area_crcReg_16 = ((_zz_area_crcReg_15[7] ? ({_zz_area_crcReg_15[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_15[6 : 0],1'b0}) ^ _zz_area_addrSh[15 : 8]);
  assign _zz_area_crcReg_17 = (_zz_area_crcReg_16[7] ? ({_zz_area_crcReg_16[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_16[6 : 0],1'b0});
  assign _zz_area_crcReg_18 = (_zz_area_crcReg_17[7] ? ({_zz_area_crcReg_17[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_17[6 : 0],1'b0});
  assign _zz_area_crcReg_19 = (_zz_area_crcReg_18[7] ? ({_zz_area_crcReg_18[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_18[6 : 0],1'b0});
  assign _zz_area_crcReg_20 = (_zz_area_crcReg_19[7] ? ({_zz_area_crcReg_19[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_19[6 : 0],1'b0});
  assign _zz_area_crcReg_21 = (_zz_area_crcReg_20[7] ? ({_zz_area_crcReg_20[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_20[6 : 0],1'b0});
  assign _zz_area_crcReg_22 = (_zz_area_crcReg_21[7] ? ({_zz_area_crcReg_21[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_21[6 : 0],1'b0});
  assign _zz_area_crcReg_23 = (_zz_area_crcReg_22[7] ? ({_zz_area_crcReg_22[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_22[6 : 0],1'b0});
  assign _zz_area_crcReg_24 = ((_zz_area_crcReg_23[7] ? ({_zz_area_crcReg_23[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_23[6 : 0],1'b0}) ^ _zz_area_addrSh[7 : 0]);
  assign _zz_area_crcReg_25 = (_zz_area_crcReg_24[7] ? ({_zz_area_crcReg_24[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_24[6 : 0],1'b0});
  assign _zz_area_crcReg_26 = (_zz_area_crcReg_25[7] ? ({_zz_area_crcReg_25[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_25[6 : 0],1'b0});
  assign _zz_area_crcReg_27 = (_zz_area_crcReg_26[7] ? ({_zz_area_crcReg_26[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_26[6 : 0],1'b0});
  assign _zz_area_crcReg_28 = (_zz_area_crcReg_27[7] ? ({_zz_area_crcReg_27[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_27[6 : 0],1'b0});
  assign _zz_area_crcReg_29 = (_zz_area_crcReg_28[7] ? ({_zz_area_crcReg_28[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_28[6 : 0],1'b0});
  assign _zz_area_crcReg_30 = (_zz_area_crcReg_29[7] ? ({_zz_area_crcReg_29[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_29[6 : 0],1'b0});
  assign _zz_area_crcReg_31 = (_zz_area_crcReg_30[7] ? ({_zz_area_crcReg_30[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_30[6 : 0],1'b0});
  assign _zz_area_lenR = {area_hiNib,io_ioIn};
  assign _zz_area_crcReg_32 = (area_crcReg ^ _zz_area_lenR);
  assign _zz_area_crcReg_33 = (_zz_area_crcReg_32[7] ? ({_zz_area_crcReg_32[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_32[6 : 0],1'b0});
  assign _zz_area_crcReg_34 = (_zz_area_crcReg_33[7] ? ({_zz_area_crcReg_33[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_33[6 : 0],1'b0});
  assign _zz_area_crcReg_35 = (_zz_area_crcReg_34[7] ? ({_zz_area_crcReg_34[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_34[6 : 0],1'b0});
  assign _zz_area_crcReg_36 = (_zz_area_crcReg_35[7] ? ({_zz_area_crcReg_35[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_35[6 : 0],1'b0});
  assign _zz_area_crcReg_37 = (_zz_area_crcReg_36[7] ? ({_zz_area_crcReg_36[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_36[6 : 0],1'b0});
  assign _zz_area_crcReg_38 = (_zz_area_crcReg_37[7] ? ({_zz_area_crcReg_37[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_37[6 : 0],1'b0});
  assign _zz_area_crcReg_39 = (_zz_area_crcReg_38[7] ? ({_zz_area_crcReg_38[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_38[6 : 0],1'b0});
  assign when_QspiSlaveSync_l186 = (area_lenByteCnt == 1'b0);
  assign _zz_area_rxCrcR = {area_hiNib,io_ioIn};
  assign when_QspiSlaveSync_l203 = (area_payloadByteCnt < _zz_when_QspiSlaveSync_l203);
  assign _zz_area_crcReg_40 = (area_crcReg ^ _zz_area_rxCrcR);
  assign _zz_area_crcReg_41 = (_zz_area_crcReg_40[7] ? ({_zz_area_crcReg_40[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_40[6 : 0],1'b0});
  assign _zz_area_crcReg_42 = (_zz_area_crcReg_41[7] ? ({_zz_area_crcReg_41[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_41[6 : 0],1'b0});
  assign _zz_area_crcReg_43 = (_zz_area_crcReg_42[7] ? ({_zz_area_crcReg_42[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_42[6 : 0],1'b0});
  assign _zz_area_crcReg_44 = (_zz_area_crcReg_43[7] ? ({_zz_area_crcReg_43[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_43[6 : 0],1'b0});
  assign _zz_area_crcReg_45 = (_zz_area_crcReg_44[7] ? ({_zz_area_crcReg_44[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_44[6 : 0],1'b0});
  assign _zz_area_crcReg_46 = (_zz_area_crcReg_45[7] ? ({_zz_area_crcReg_45[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_45[6 : 0],1'b0});
  assign _zz_area_crcReg_47 = (_zz_area_crcReg_46[7] ? ({_zz_area_crcReg_46[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_46[6 : 0],1'b0});
  assign when_QspiSlaveSync_l216 = (area_bitc == 6'h01);
  assign when_QspiSlaveSync_l226 = (area_rdNib < 4'b0111);
  assign io_cmdOpcode = area_opcodeR;
  assign io_cmdAddr = area_addrR;
  assign io_cmdLen = area_lenR;
  assign io_cmdValid = area_cmdValidR;
  assign io_payloadByte = {area_hiNib,io_ioIn};
  assign io_payloadValid = (((area_phase == Phase_WDATA) && (! area_nibHigh)) && (area_payloadByteCnt < _zz_io_payloadValid));
  assign when_QspiSlaveSync_l256 = (area_phase == Phase_RDATA);
  assign _zz_outArea_ioOutF = _zz__zz_outArea_ioOutF;
  assign io_ioOut = outArea_ioOutF;
  assign io_ioOe = outArea_ioOeF;
  assign io_active = (! io_csn);
  assign io_hdrErr = area_hdrErrR;
  assign io_crcBad = (area_crcSeenR && (area_rxCrcR != area_crcReg));
  always @(posedge io_sclk or posedge io_csn) begin
    if(io_csn) begin
      area_phase <= Phase_CMD;
      area_bitc <= 6'h0;
      area_cmdSh <= 8'h0;
      area_addrSh <= 24'h0;
      area_opcodeR <= 8'h0;
      area_addrR <= 24'h0;
      area_lenR <= 16'h0;
      area_cmdValidR <= 1'b0;
      area_hdrErrR <= 1'b0;
      area_lenLo <= 8'h0;
      area_lenByteCnt <= 1'b0;
      area_crcReg <= 8'h0;
      area_payloadByteCnt <= 17'h0;
      area_rxCrcR <= 8'h0;
      area_crcSeenR <= 1'b0;
      area_nibHigh <= 1'b1;
      area_hiNib <= 4'b0000;
      area_rdWord <= 32'h0;
      area_rdNib <= 4'b0000;
    end else begin
      area_cmdValidR <= 1'b0;
      area_hdrErrR <= 1'b0;
      case(area_phase)
        Phase_CMD : begin
          area_cmdSh <= {area_cmdSh[6 : 0],io_ioIn[0]};
          if(when_QspiSlaveSync_l153) begin
            area_bitc <= 6'h0;
            area_phase <= Phase_ADDR;
          end else begin
            area_bitc <= (area_bitc + 6'h01);
          end
        end
        Phase_ADDR : begin
          area_addrSh <= _zz_area_addrSh;
          if(when_QspiSlaveSync_l158) begin
            area_bitc <= 6'h0;
            area_opcodeR <= area_cmdSh;
            area_addrR <= _zz_area_addrSh;
            if(when_QspiSlaveSync_l164) begin
              area_lenR <= 16'h0;
              area_cmdValidR <= 1'b1;
              area_phase <= Phase_DUMMY;
            end else begin
              area_phase <= Phase_LENCAP;
              area_nibHigh <= 1'b1;
              area_lenByteCnt <= 1'b0;
              area_crcReg <= (_zz_area_crcReg_31[7] ? ({_zz_area_crcReg_31[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_31[6 : 0],1'b0});
              area_payloadByteCnt <= 17'h0;
            end
          end else begin
            area_bitc <= (area_bitc + 6'h01);
          end
        end
        Phase_LENCAP : begin
          if(area_nibHigh) begin
            area_hiNib <= io_ioIn;
            area_nibHigh <= 1'b0;
          end else begin
            area_nibHigh <= 1'b1;
            area_crcReg <= (_zz_area_crcReg_39[7] ? ({_zz_area_crcReg_39[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_39[6 : 0],1'b0});
            if(when_QspiSlaveSync_l186) begin
              area_lenLo <= _zz_area_lenR;
              area_lenByteCnt <= 1'b1;
            end else begin
              area_lenR <= {_zz_area_lenR,area_lenLo};
              area_cmdValidR <= 1'b1;
              area_phase <= Phase_WDATA;
            end
          end
        end
        Phase_WDATA : begin
          if(area_nibHigh) begin
            area_hiNib <= io_ioIn;
            area_nibHigh <= 1'b0;
          end else begin
            area_nibHigh <= 1'b1;
            if(when_QspiSlaveSync_l203) begin
              area_crcReg <= (_zz_area_crcReg_47[7] ? ({_zz_area_crcReg_47[6 : 0],1'b0} ^ 8'h07) : {_zz_area_crcReg_47[6 : 0],1'b0});
              area_payloadByteCnt <= (area_payloadByteCnt + 17'h00001);
            end else begin
              area_rxCrcR <= _zz_area_rxCrcR;
              area_crcSeenR <= 1'b1;
            end
          end
        end
        Phase_DUMMY : begin
          if(when_QspiSlaveSync_l216) begin
            area_bitc <= 6'h0;
            area_phase <= Phase_RDATA;
            area_rdWord <= io_rxWord;
            area_rdNib <= 4'b0000;
          end else begin
            area_bitc <= (area_bitc + 6'h01);
          end
        end
        default : begin
          if(when_QspiSlaveSync_l226) begin
            area_rdNib <= (area_rdNib + 4'b0001);
          end
        end
      endcase
    end
  end

  always @(negedge io_sclk or posedge io_csn) begin
    if(io_csn) begin
      outArea_ioOutF <= 4'b0000;
      outArea_ioOeF <= 1'b0;
    end else begin
      if(when_QspiSlaveSync_l256) begin
        outArea_ioOutF <= ((! area_rdNib[0]) ? _zz_outArea_ioOutF[7 : 4] : _zz_outArea_ioOutF[3 : 0]);
        outArea_ioOeF <= 1'b1;
      end else begin
        outArea_ioOeF <= 1'b0;
      end
    end
  end


endmodule


---

## Firmware: firmware/libvdp/vdp_host_p4.c

/**
 * vdp_host_p4.c — ESP32-P4 QSPI backend for libvdp.
 *
 * The P4 host is the canonical Tang Nano 20K transport.  This backend keeps
 * the SPI framing in libvdp while allowing P4 applications to use the same
 * vdp_mode0_* helpers as the Arduino/Pico ports.
 */
#include "vdp_host.h"
#include "vdp_crc8.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "driver/spi_master.h"
#include "esp_heap_caps.h"
#include "esp_rom_sys.h"

enum {
    PIN_SCLK = 21,
    PIN_CS = 20,
    PIN_IO0 = 32,
    PIN_IO1 = 33,
    PIN_IO2 = 22,
    PIN_IO3 = 23,
    CMD_READ_STATUS = 0x04,
    CMD_REG_WRITE = 0x01,
    CMD_SDRAM_WRITE = 0x02,
    SEL_CRC8_STATUS = 0x0Bu,
    DMA_BUF_SIZE = 65536,
    MAX_WRITE_WORDS = 253,
};

static spi_device_handle_t s_spi;
static uint8_t *s_tx_buf;
static uint8_t *s_rx_buf;
static bool s_initialized;
static int s_last_error;
static uint32_t s_clock_hz;

static uint8_t parity31(uint8_t cmd, uint32_t addr)
{
    uint32_t bits = ((uint32_t)cmd << 23) | (addr & 0x7FFFFFu);
    uint8_t parity = 0;
    while (bits != 0u) {
        parity ^= (uint8_t)(bits & 1u);
        bits >>= 1;
    }
    return parity;
}

static uint32_t wire_addr(uint8_t cmd, uint32_t addr)
{
    (void)cmd;
    return addr | ((uint32_t)parity31(cmd, addr) << 23);
}

static esp_err_t add_device(uint32_t clock_hz)
{
    spi_device_interface_config_t cfg = {
        .clock_speed_hz = (int)clock_hz,
        .clock_source = SPI_CLK_SRC_SPLL,
        .mode = 0,
        .spics_io_num = PIN_CS,
        .queue_size = 4,
        .command_bits = 8,
        .address_bits = 24,
        .dummy_bits = 2,
        .input_delay_ns = 0,
        .cs_ena_pretrans = 2,
        .cs_ena_posttrans = 8,
        .flags = SPI_DEVICE_HALFDUPLEX | SPI_DEVICE_NO_DUMMY,
    };
    esp_err_t err = spi_bus_add_device(SPI2_HOST, &cfg, &s_spi);
    if (err == ESP_OK) s_clock_hz = clock_hz;
    return err;
}

static esp_err_t tx_frame(uint8_t cmd, uint32_t addr, const uint8_t *payload,
                          size_t payload_len)
{
    if (!s_spi || !payload || payload_len == 0u || payload_len > DMA_BUF_SIZE) {
        return ESP_ERR_INVALID_ARG;
    }
    spi_transaction_ext_t tx = {0};
    tx.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    tx.base.cmd = cmd;
    tx.base.addr = wire_addr(cmd, addr);
    tx.base.length = payload_len * 8u;
    tx.base.tx_buffer = payload;
    tx.dummy_bits = 0;
    return spi_device_polling_transmit(s_spi, (spi_transaction_t *)&tx);
}

static esp_err_t rx_status(uint8_t sel, uint32_t *value)
{
    if (!s_spi || !s_rx_buf || !value) return ESP_ERR_INVALID_ARG;
    spi_transaction_ext_t rx = {0};
    rx.base.flags = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    rx.base.cmd = CMD_READ_STATUS;
    rx.base.addr = wire_addr(CMD_READ_STATUS, sel);
    rx.base.rxlength = 32;
    rx.base.rx_buffer = s_rx_buf;
    rx.dummy_bits = 2;
    esp_err_t err = spi_device_polling_transmit(s_spi, (spi_transaction_t *)&rx);
    if (err != ESP_OK) return err;
    *value = (uint32_t)s_rx_buf[0] |
             ((uint32_t)s_rx_buf[1] << 8) |
             ((uint32_t)s_rx_buf[2] << 16) |
             ((uint32_t)s_rx_buf[3] << 24);
    return ESP_OK;
}

static esp_err_t write_frame(uint8_t cmd, uint32_t addr, const uint8_t *frame,
                             size_t frame_len)
{
    if (!frame || frame_len < 2u || frame_len + 1u > DMA_BUF_SIZE) {
        return ESP_ERR_INVALID_ARG;
    }
    const uint8_t crc = vdp_crc8_qspi_write_frame(cmd, wire_addr(cmd, addr),
                                                   frame, frame_len);
    for (unsigned attempt = 0; attempt < 2u; ++attempt) {
        uint32_t before = 0;
        uint32_t after = 0;
        esp_err_t err = rx_status(SEL_CRC8_STATUS, &before);
        if (err != ESP_OK) return err;
        /* vdp_*_write() builds frame in s_tx_buf and passes that same buffer. */
        memmove(s_tx_buf, frame, frame_len);
        s_tx_buf[frame_len] = crc;
        err = tx_frame(cmd, addr, s_tx_buf, frame_len + 1u);
        if (err != ESP_OK) return err;
        esp_rom_delay_us(10u);
        err = rx_status(SEL_CRC8_STATUS, &after);
        if (err != ESP_OK) return err;
        if ((uint16_t)before == (uint16_t)after) return ESP_OK;
        if (attempt != 0u) return ESP_FAIL;
    }
    return ESP_FAIL;
}

int vdp_last_error(void) { return s_last_error; }

void vdp_host_init(void)
{
    if (s_initialized) return;
    spi_bus_config_t bus = {
        .data0_io_num = PIN_IO0,
        .data1_io_num = PIN_IO1,
        .sclk_io_num = PIN_SCLK,
        .data2_io_num = PIN_IO2,
        .data3_io_num = PIN_IO3,
        .data4_io_num = -1,
        .data5_io_num = -1,
        .data6_io_num = -1,
        .data7_io_num = -1,
        .max_transfer_sz = DMA_BUF_SIZE,
        .flags = SPICOMMON_BUSFLAG_MASTER | SPICOMMON_BUSFLAG_QUAD,
    };
    if (spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_BUS_INIT;
        return;
    }
    s_tx_buf = heap_caps_malloc(DMA_BUF_SIZE, MALLOC_CAP_DMA);
    s_rx_buf = heap_caps_malloc(4u, MALLOC_CAP_DMA);
    if (!s_tx_buf || !s_rx_buf || add_device(2000000u) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_BUS_INIT;
        return;
    }
    s_last_error = VDP_HOST_ERR_NONE;
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }
void vdp_pio_wait_sm_idle(void) {}

void vdp_host_set_speed_hz(uint32_t hz)
{
    if (!s_initialized || hz == 0u || hz == s_clock_hz) return;
    if (spi_bus_remove_device(s_spi) != ESP_OK || add_device(hz) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_BUS_INIT;
        return;
    }
}

void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

uint32_t vdp_read_status(uint8_t sel)
{
    uint32_t value = 0;
    if (!s_initialized || rx_status(sel, &value) != ESP_OK) {
        s_last_error = VDP_HOST_ERR_RX;
        return 0;
    }
    s_last_error = VDP_HOST_ERR_NONE;
    return value;
}

void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t count)
{
    if (!s_initialized || !words || count == 0u || count > MAX_WRITE_WORDS) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }
    s_tx_buf[0] = (uint8_t)(count & 0xFFu);
    s_tx_buf[1] = (uint8_t)(count >> 8);
    for (uint16_t i = 0; i < count; ++i) {
        s_tx_buf[2u + 2u * i] = (uint8_t)words[i];
        s_tx_buf[3u + 2u * i] = (uint8_t)(words[i] >> 8);
    }
    s_last_error = write_frame(CMD_REG_WRITE, addr, s_tx_buf, 2u + 2u * count) == ESP_OK
                       ? VDP_HOST_ERR_NONE : VDP_HOST_ERR_TX;
}

void vdp_reg_write(uint32_t addr, uint16_t data)
{
    vdp_reg_write_burst(addr, &data, 1u);
}

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t count)
{
    if (!s_initialized || !words || count == 0u || count > MAX_WRITE_WORDS) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }
    s_tx_buf[0] = (uint8_t)(count & 0xFFu);
    s_tx_buf[1] = (uint8_t)(count >> 8);
    for (uint16_t i = 0; i < count; ++i) {
        s_tx_buf[2u + 2u * i] = (uint8_t)words[i];
        s_tx_buf[3u + 2u * i] = (uint8_t)(words[i] >> 8);
    }
    s_last_error = write_frame(CMD_SDRAM_WRITE, addr, s_tx_buf, 2u + 2u * count) == ESP_OK
                       ? VDP_HOST_ERR_NONE : VDP_HOST_ERR_TX;
}

uint16_t vdp_reg_read(uint32_t addr)
{
    (void)addr;
    s_last_error = VDP_HOST_ERR_RX;
    return 0;
}

void vdp_clear_upload_status(uint16_t mask)
{
    vdp_reg_write(VDP_UPLOAD_STATUS_CLEAR_REG, (uint16_t)(mask & VDP_UPLOAD_STATUS_CLEAR_MASK));
}

---

## Firmware: firmware/esp32p4_scaler_proof/main/main.c

/*
 * ESP32-P4 scaler hardware-proof host.
 *
 * SCALER_PROOF_MODE=0: 1x checkerboard regression (explicit 640x480 / 1x reset)
 * SCALER_PROOF_MODE=2: 2x centered checkerboard, logic 300x220
 * SCALER_PROOF_MODE=3: 3x centered checkerboard, logic 200x150
 * SCALER_PROOF_MODE=4: QSPI write-vs-readback discriminator (proof only)
 * SCALER_PROOF_MODE=5: sel=8 readback SCLK sweep (proof only)
 * SCALER_PROOF_MODE=6: full readback_word double-read lag confirmation (proof only)
 * SCALER_PROOF_MODE=7: display-indirect target-word color discriminator (proof only)
 * SCALER_PROOF_MODE=8: READ_DONE completion-poll readback (proof only)
 */
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "vdp_host.h"
#include "vdp_mode0.h"

#ifndef SCALER_PROOF_MODE
#define SCALER_PROOF_MODE 0
#endif

enum {
    WIDTH = 320,
    HEIGHT = 240,
    ROW_STRIDE = 128,
    IMAGE_BYTES = HEIGHT * ROW_STRIDE,
    IMAGE_WORDS = IMAGE_BYTES / 2,
    CHECKER_SQUARE = 32,
    MAX_CHUNK_WORDS = 253,
    SEL_MAGIC = 0x00,
    SEL_SDRAM = 0x08,
    SEL_TRANSPORT_HEALTH = 0x0A,
    SEL_CRC8_STATUS = 0x0B,
    SEL_READ_DONE = 0x0C,
    BITMAP_BASE = 0x100000,
    ATTR_BASE = 0x110000,
    REG_SDRAM_READ_ADDR_LO = 0x0326,
    REG_SDRAM_READ_ADDR_HI = 0x0327,
    SWEEP_CYCLES = 30,
    READ_DONE_POLL_LIMIT = 100,
};

static const char *TAG = "p4_scaler_proof";
static uint16_t s_bitmap[IMAGE_WORDS];
static uint16_t s_attr[IMAGE_WORDS];

static void build_checkerboard(void)
{
    uint8_t *bitmap = (uint8_t *)s_bitmap;
    uint8_t *attr = (uint8_t *)s_attr;
    memset(bitmap, 0, IMAGE_BYTES);
    memset(attr, 0xE4, IMAGE_BYTES);
    for (unsigned y = 0; y < HEIGHT; ++y) {
        for (unsigned x = 0; x < WIDTH; ++x) {
            const uint8_t color = (uint8_t)(((x / CHECKER_SQUARE) ^
                                             (y / CHECKER_SQUARE)) & 1u);
            const unsigned byte_index = y * ROW_STRIDE + (x / 4u);
            const unsigned shift = 6u - ((x & 3u) * 2u);
            bitmap[byte_index] |= (uint8_t)(color << shift);
        }
    }
}

static bool health(const char *label)
{
    const uint32_t raw = vdp_read_status(SEL_TRANSPORT_HEALTH);
    const bool overflow = (raw & 0x1u) != 0u;
    const bool malformed = (raw & 0x2u) != 0u;
    ESP_LOGI(TAG, "%s raw=0x%08" PRIX32 " overflow=%u malformed=%u",
             label, raw, overflow ? 1u : 0u, malformed ? 1u : 0u);
    return vdp_last_error() == VDP_HOST_ERR_NONE && !overflow && !malformed;
}

static bool write_linestate(void)
{
    uint16_t words[480];
    for (unsigned i = 0; i < 480u; ++i) words[i] = 0x0800u;
    for (unsigned offset = 0; offset < 480u; offset += MAX_CHUNK_WORDS) {
        const uint16_t count = (uint16_t)(((480u - offset) < MAX_CHUNK_WORDS) ?
                                          (480u - offset) : MAX_CHUNK_WORDS);
        vdp_reg_write_burst(0u + offset, words + offset, count);
        if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;
    }
    ESP_LOGI(TAG, "LINESTATE PASS lines=480 chunks=2");
    return true;
}

static bool load_palette(void)
{
    vdp_mode0_palette_write_rgb888(0u, 0u, 0u, 0u);
    vdp_mode0_palette_write_rgb888(1u, 255u, 255u, 255u);
    vdp_mode0_palette_write_rgb888(2u, 255u, 0u, 0u);
    vdp_mode0_palette_write_rgb888(3u, 0u, 0u, 255u);
    return vdp_last_error() == VDP_HOST_ERR_NONE;
}

static bool upload_plane(uint32_t base, const uint16_t *words, const char *name)
{
    vdp_host_set_speed_hz(4000000u);
    for (unsigned offset = 0; offset < IMAGE_WORDS; offset += MAX_CHUNK_WORDS) {
        const uint16_t count = (uint16_t)(((IMAGE_WORDS - offset) < MAX_CHUNK_WORDS) ?
                                          (IMAGE_WORDS - offset) : MAX_CHUNK_WORDS);
        vdp_sdram_write(base + (offset * 2u), words + offset, count);
        if (vdp_last_error() != VDP_HOST_ERR_NONE) {
            ESP_LOGE(TAG, "%s upload failed offset=%u err=%d", name, offset,
                     vdp_last_error());
            return false;
        }
    }
    ESP_LOGI(TAG, "%s uploaded bytes=%u clock=4000000", name, IMAGE_BYTES);
    return true;
}

static bool upload_plane_diagnostic(uint32_t base, const uint16_t *words,
                                    const char *name)
{
    const unsigned frame_count = (IMAGE_WORDS + MAX_CHUNK_WORDS - 1u) /
                                 MAX_CHUNK_WORDS;
    for (unsigned frame = 0; frame < frame_count; ++frame) {
        const unsigned offset = frame * MAX_CHUNK_WORDS;
        const uint16_t count = (uint16_t)(((IMAGE_WORDS - offset) < MAX_CHUNK_WORDS) ?
                                          (IMAGE_WORDS - offset) : MAX_CHUNK_WORDS);
        const uint32_t frame_addr = base + offset * 2u;
        vdp_host_set_speed_hz(2000000u);
        const uint32_t crc_before = vdp_read_status(SEL_CRC8_STATUS);
        const int crc_before_err = vdp_last_error();
        vdp_host_set_speed_hz(4000000u);
        vdp_sdram_write(frame_addr, words + offset, count);
        const int write_err = vdp_last_error();
        vdp_host_set_speed_hz(2000000u);
        const uint32_t crc_after = vdp_read_status(SEL_CRC8_STATUS);
        const int crc_after_err = vdp_last_error();
        ESP_LOGI(TAG,
                 "DIAG_FRAME plane=%s frame=%u addr=0x%06" PRIX32
                 " words=%u bytes=%u crc_before=0x%08" PRIX32
                 " crc_after=0x%08" PRIX32 " crc_err=%d/%d write_err=%d",
                 name, frame, frame_addr, count, (unsigned)count * 2u,
                 crc_before, crc_after, crc_before_err, crc_after_err,
                 write_err);
        if (write_err != VDP_HOST_ERR_NONE ||
            crc_before_err != VDP_HOST_ERR_NONE ||
            crc_after_err != VDP_HOST_ERR_NONE) {
            return false;
        }
    }
    ESP_LOGI(TAG, "DIAG_UPLOAD plane=%s frames=%u chunk_words=%u chunk_bytes=%u",
             name, frame_count, MAX_CHUNK_WORDS, MAX_CHUNK_WORDS * 2u);
    return true;
}

static bool readback_word(uint32_t addr, uint32_t *value)
{
    vdp_host_set_speed_hz(2000000u);
    vdp_reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)addr);
    vdp_reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)(addr >> 16));
    if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;
    *value = vdp_read_status(SEL_SDRAM);
    return vdp_last_error() == VDP_HOST_ERR_NONE;
}

static bool readback_word_wait_done(uint32_t addr, uint32_t *value,
                                    unsigned *poll_count)
{
    vdp_host_set_speed_hz(2000000u);
    vdp_reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)addr);
    if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;
    vdp_reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)(addr >> 16));
    if (vdp_last_error() != VDP_HOST_ERR_NONE) return false;

    for (unsigned poll = 1u; poll <= READ_DONE_POLL_LIMIT; ++poll) {
        const uint32_t status = vdp_read_status(SEL_READ_DONE);
        const int error = vdp_last_error();
        const bool done = (status & 0x1u) != 0u;
        const bool reserved_zero = (status & ~0x1u) == 0u;
        ESP_LOGI(TAG,
                 "READ_DONE_POLL addr=0x%06" PRIX32 " poll=%u raw=0x%08" PRIX32
                 " done=%u reserved_zero=%u err=%d",
                 addr, poll, status, done ? 1u : 0u,
                 reserved_zero ? 1u : 0u, error);
        if (poll_count != NULL) *poll_count = poll;
        if (error != VDP_HOST_ERR_NONE || !reserved_zero) return false;
        if (done) {
            *value = vdp_read_status(SEL_SDRAM);
            return vdp_last_error() == VDP_HOST_ERR_NONE;
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }

    ESP_LOGE(TAG, "READ_DONE_TIMEOUT addr=0x%06" PRIX32 " polls=%u",
             addr, READ_DONE_POLL_LIMIT);
    if (poll_count != NULL) *poll_count = READ_DONE_POLL_LIMIT;
    return false;
}

static bool readback_word_twice(uint32_t addr, uint32_t *first,
                                uint32_t *second)
{
    /* Each full call rewrites REG_SDRAM_READ_ADDR_HI and arms a new read. */
    if (!readback_word(addr, first)) return false;
    return readback_word(addr, second);
}

static uint32_t bitmap_expected_word(uint32_t addr);

static bool readback_word_at_rate(uint32_t addr, uint32_t rate_hz,
                                  uint32_t *value, int *error)
{
    vdp_host_set_speed_hz(rate_hz);
    vdp_reg_write(REG_SDRAM_READ_ADDR_LO, (uint16_t)addr);
    if (vdp_last_error() != VDP_HOST_ERR_NONE) {
        *error = vdp_last_error();
        return false;
    }
    vdp_reg_write(REG_SDRAM_READ_ADDR_HI, (uint16_t)(addr >> 16));
    if (vdp_last_error() != VDP_HOST_ERR_NONE) {
        *error = vdp_last_error();
        return false;
    }
    *value = vdp_read_status(SEL_SDRAM);
    *error = vdp_last_error();
    return *error == VDP_HOST_ERR_NONE;
}

static bool sweep_readback(void)
{
    static const uint32_t rates_hz[] = {
        2000000u, 1000000u, 500000u, 250000u,
    };
    static const uint32_t addresses[] = {
        0x100004u, 0x100008u, 0x10000Cu,
        0x100FFCu, 0x101000u, 0x101004u,
    };
    bool pass = true;

    ESP_LOGI(TAG,
             "SWEEP_START rates=2000000,1000000,500000,250000 cycles=%u"
             " cs_post=8 targets=0x100008,0x101000 neighbors=word+-1",
             SWEEP_CYCLES);
    for (unsigned rate_index = 0;
         rate_index < sizeof(rates_hz) / sizeof(rates_hz[0]); ++rate_index) {
        const uint32_t rate_hz = rates_hz[rate_index];
        unsigned reads = 0u;
        unsigned value_pass = 0u;
        unsigned zero_values = 0u;
        unsigned errors = 0u;
        ESP_LOGI(TAG, "SWEEP_RATE_BEGIN hz=%" PRIu32, rate_hz);
        for (unsigned cycle = 0; cycle < SWEEP_CYCLES; ++cycle) {
            for (unsigned i = 0; i < sizeof(addresses) / sizeof(addresses[0]); ++i) {
                const uint32_t addr = addresses[i];
                const uint32_t expected = bitmap_expected_word(addr);
                uint32_t actual = 0u;
                int error = VDP_HOST_ERR_NONE;
                const bool read_ok = readback_word_at_rate(addr, rate_hz,
                                                           &actual, &error);
                const bool word_pass = read_ok && actual == expected;
                ++reads;
                if (word_pass) {
                    ++value_pass;
                } else {
                    pass = false;
                }
                if (actual == 0u) ++zero_values;
                if (error != VDP_HOST_ERR_NONE) ++errors;
                ESP_LOGI(TAG,
                         "SWEEP_READ hz=%" PRIu32 " cycle=%u addr=0x%06" PRIX32
                         " expected=0x%08" PRIX32 " got=0x%08" PRIX32
                         " ok=%u err=%d",
                         rate_hz, cycle, addr, expected, actual,
                         word_pass ? 1u : 0u, error);
            }
            const uint32_t health_raw = vdp_read_status(SEL_TRANSPORT_HEALTH);
            const int health_error = vdp_last_error();
            ESP_LOGI(TAG,
                     "SWEEP_HEALTH hz=%" PRIu32 " cycle=%u raw=0x%08" PRIX32
                     " overflow=%u malformed=%u err=%d",
                     rate_hz, cycle, health_raw, health_raw & 1u,
                     (health_raw >> 1) & 1u, health_error);
            if (health_error != VDP_HOST_ERR_NONE || (health_raw & 3u) != 0u) {
                pass = false;
            }
        }
        ESP_LOGI(TAG,
                 "SWEEP_SUMMARY hz=%" PRIu32 " reads=%u pass=%u zeros=%u errors=%u",
                 rate_hz, reads, value_pass, zero_values, errors);
    }
    ESP_LOGI(TAG, "SWEEP_RESULT pass=%u", pass ? 1u : 0u);
    return pass;
}

static bool verify_sample(uint32_t addr)
{
    const uint8_t *bitmap = (const uint8_t *)s_bitmap;
    const uint32_t expected = (uint32_t)bitmap[addr - BITMAP_BASE] |
                              ((uint32_t)bitmap[addr - BITMAP_BASE + 1u] << 8) |
                              ((uint32_t)bitmap[addr - BITMAP_BASE + 2u] << 16) |
                              ((uint32_t)bitmap[addr - BITMAP_BASE + 3u] << 24);
    uint32_t actual = 0;
    if (!readback_word(addr, &actual) || actual != expected) {
        ESP_LOGE(TAG, "READBACK FAIL addr=0x%06" PRIX32
                 " expected=0x%08" PRIX32 " got=0x%08" PRIX32,
                 addr, expected, actual);
        return false;
    }
    ESP_LOGI(TAG, "READBACK PASS addr=0x%06" PRIX32 " value=0x%08" PRIX32,
             addr, actual);
    return true;
}

static bool verify_readback(void)
{
    static const uint32_t offsets[] = { 0u, 8u, 16u, 32u * ROW_STRIDE,
                                        200u * ROW_STRIDE, 201u * ROW_STRIDE };
    bool pass = true;
    for (unsigned i = 0; i < sizeof(offsets) / sizeof(offsets[0]); ++i) {
        pass &= verify_sample(BITMAP_BASE + offsets[i]);
    }
    return pass;
}

static uint32_t bitmap_expected_word(uint32_t addr)
{
    const uint8_t *bitmap = (const uint8_t *)s_bitmap;
    const unsigned offset = (unsigned)(addr - BITMAP_BASE);
    return (uint32_t)bitmap[offset] |
           ((uint32_t)bitmap[offset + 1u] << 8) |
           ((uint32_t)bitmap[offset + 2u] << 16) |
           ((uint32_t)bitmap[offset + 3u] << 24);
}

static bool diagnostic_neighbor_reads(void)
{
    static const uint32_t first_window[] = {
        0x100000u, 0x100004u, 0x100008u, 0x10000Cu,
        0x100010u, 0x100014u, 0x100018u, 0x10001Cu,
    };
    static const uint32_t second_window[] = {
        0x100FF8u, 0x100FFCu, 0x101000u, 0x101004u, 0x101008u,
    };
    bool pass = true;
    ESP_LOGI(TAG, "DIAG_GEOMETRY sdram_row_bytes=1024 addr=bank[22:21],row[20:10],col[9:2],lane[1:0]");
    ESP_LOGI(TAG, "DIAG_SAMPLE_LIST first=0x100000,0x100004,0x100008,0x10000C,0x100010,0x100014,0x100018,0x10001C");
    ESP_LOGI(TAG, "DIAG_SAMPLE_LIST second=0x100FF8,0x100FFC,0x101000,0x101004,0x101008");
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        const uint32_t *windows[] = { first_window, second_window };
        const unsigned counts[] = {
            sizeof(first_window) / sizeof(first_window[0]),
            sizeof(second_window) / sizeof(second_window[0]),
        };
        for (unsigned window = 0; window < 2u; ++window) {
            for (unsigned i = 0; i < counts[window]; ++i) {
                const uint32_t addr = windows[window][i];
                uint32_t actual = 0;
                const bool read_ok = readback_word(addr, &actual);
                const uint32_t expected = bitmap_expected_word(addr);
                ESP_LOGI(TAG,
                         "DIAG_READ repeat=%u addr=0x%06" PRIX32
                         " expected=0x%08" PRIX32 " got=0x%08" PRIX32
                         " read_ok=%u err=%d",
                         repeat, addr, expected, actual, read_ok ? 1u : 0u,
                         vdp_last_error());
                if (!read_ok || actual != expected) pass = false;
            }
        }
    }
    ESP_LOGI(TAG, "DIAG_READ_RESULT pass=%u repeats=8 addresses=13",
             pass ? 1u : 0u);
    return pass;
}

static bool diagnostic_double_reads(void)
{
    static const uint32_t addresses[] = {
        0x100004u, 0x100008u, 0x10000Cu,
        0x100FFCu, 0x101000u, 0x101004u,
    };
    bool pass = true;
    ESP_LOGI(TAG,
             "DOUBLE_READ_START addresses=0x100004,0x100008,0x10000C"
             ",0x100FFC,0x101000,0x101004 repeats=8");
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        for (unsigned i = 0; i < sizeof(addresses) / sizeof(addresses[0]); ++i) {
            const uint32_t addr = addresses[i];
            const uint32_t expected = bitmap_expected_word(addr);
            uint32_t first = 0u;
            uint32_t second = 0u;
            const bool read_ok = readback_word_twice(addr, &first, &second);
            const bool second_pass = read_ok && second == expected;
            ESP_LOGI(TAG,
                     "DOUBLE_READ repeat=%u addr=0x%06" PRIX32
                     " expected=0x%08" PRIX32 " first=0x%08" PRIX32
                     " second=0x%08" PRIX32 " ok=%u err=%d",
                     repeat, addr, expected, first, second,
                     second_pass ? 1u : 0u, vdp_last_error());
            if (!second_pass) pass = false;
        }
    }
    ESP_LOGI(TAG, "DOUBLE_READ_RESULT pass=%u repeats=8 addresses=6",
             pass ? 1u : 0u);
    return pass;
}

static void diagnostic_dummy_then_target(void)
{
    static const struct {
        uint32_t dummy;
        uint32_t target;
    } pairs[] = {
        { 0x100004u, 0x100008u },
        { 0x100FFCu, 0x101000u },
    };
    unsigned lag_matches = 0u;
    unsigned target_matches = 0u;
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        for (unsigned i = 0; i < sizeof(pairs) / sizeof(pairs[0]); ++i) {
            uint32_t dummy_value = 0u;
            uint32_t target_value = 0u;
            const uint32_t dummy_expected = bitmap_expected_word(pairs[i].dummy);
            const uint32_t target_expected = bitmap_expected_word(pairs[i].target);
            const bool dummy_ok = readback_word(pairs[i].dummy, &dummy_value);
            const bool target_ok = readback_word(pairs[i].target, &target_value);
            const bool lag_match = target_ok && target_value == dummy_expected;
            const bool target_match = target_ok && target_value == target_expected;
            if (lag_match) ++lag_matches;
            if (target_match) ++target_matches;
            ESP_LOGI(TAG,
                     "DUMMY_TARGET repeat=%u dummy=0x%06" PRIX32
                     " target=0x%06" PRIX32 " dummy_expected=0x%08" PRIX32
                     " dummy_got=0x%08" PRIX32 " target_expected=0x%08" PRIX32
                     " target_got=0x%08" PRIX32 " lag_match=%u target_match=%u"
                     " ok=%u/%u err=%d",
                     repeat, pairs[i].dummy, pairs[i].target, dummy_expected,
                     dummy_value, target_expected, target_value,
                     lag_match ? 1u : 0u, target_match ? 1u : 0u,
                     dummy_ok ? 1u : 0u, target_ok ? 1u : 0u,
                     vdp_last_error());
        }
    }
    ESP_LOGI(TAG,
             "DUMMY_TARGET_RESULT repeats=8 pairs=2 lag_matches=%u"
             " target_matches=%u",
             lag_matches, target_matches);
}

static void build_display_indirect_pattern(void)
{
    uint8_t *bitmap = (uint8_t *)s_bitmap;
    static const unsigned words[] = {
        0x100004u - BITMAP_BASE, 0x100008u - BITMAP_BASE,
        0x10000Cu - BITMAP_BASE, 0x100FFCu - BITMAP_BASE,
        0x101000u - BITMAP_BASE, 0x101004u - BITMAP_BASE,
    };
    for (unsigned i = 0; i < sizeof(words) / sizeof(words[0]); ++i) {
        const unsigned offset = words[i];
        memset(bitmap + offset, 0xAA, 4u);
    }
    ESP_LOGI(TAG,
             "INDIRECT_ASSET targets=0x100008,0x101000 color=palette2"
             " byte_pattern=0xAA neighbors=word+-1");
}

static bool diagnostic_completion_poll(void)
{
    static const uint32_t addresses[] = { 0x100008u, 0x101000u };
    bool pass = true;
    unsigned total_polls = 0u;
    unsigned max_polls = 0u;
    ESP_LOGI(TAG,
             "READ_DONE_START selector=0x%02X bit=0 polarity=high"
             " arm=0x0327 data_selector=0x%02X repeats=8",
             SEL_READ_DONE, SEL_SDRAM);
    for (unsigned repeat = 0; repeat < 8u; ++repeat) {
        for (unsigned i = 0; i < sizeof(addresses) / sizeof(addresses[0]); ++i) {
            const uint32_t addr = addresses[i];
            const uint32_t expected = bitmap_expected_word(addr);
            uint32_t actual = 0u;
            unsigned polls = 0u;
            const bool read_ok = readback_word_wait_done(addr, &actual, &polls);
            const bool word_pass = read_ok && actual == expected;
            total_polls += polls;
            if (polls > max_polls) max_polls = polls;
            ESP_LOGI(TAG,
                     "READ_DONE_READ repeat=%u addr=0x%06" PRIX32
                     " expected=0x%08" PRIX32 " got=0x%08" PRIX32
                     " polls=%u pass=%u err=%d",
                     repeat, addr, expected, actual, polls,
                     word_pass ? 1u : 0u, vdp_last_error());
            if (!word_pass) pass = false;
        }
    }
    ESP_LOGI(TAG,
             "READ_DONE_RESULT pass=%u repeats=8 addresses=2 total_polls=%u"
             " max_polls=%u",
             pass ? 1u : 0u, total_polls, max_polls);
    return pass;
}

static bool configure_display(void)
{
    const vdp_mode0_bitmap_cfg_t bitmap = {
        .ctrl = 0x0002u,
        .bitmap_base = BITMAP_BASE,
        .attr_base = ATTR_BASE,
        .bitmap_stride = ROW_STRIDE,
        .attr_stride = ROW_STRIDE,
        .height = HEIGHT,
    };
    const vdp_mode0_rect_t full_frame = { 0u, 640u, 0u, 480u };

    vdp_mode0_set_layer_enable(0u);
    vdp_mode0_set_mode_select(0u);
    vdp_mode0_set_bitmap_cfg(&bitmap);
    vdp_mode0_set_border_window(&full_frame, 0x0101u);
    vdp_mode0_set_backdrop_index(0u);
    if (!load_palette() || vdp_last_error() != VDP_HOST_ERR_NONE) return false;

#if SCALER_PROOF_MODE == 2
    vdp_mode0_set_logic_size(300u, 220u);
    vdp_mode0_set_scale_ctrl(vdp_mode0_scale_ctrl(2u, 2u, true));
    ESP_LOGI(TAG, "scale=2x logic=300x220 expected_bezel=20x20 ctrl=0x%02X",
             vdp_mode0_scale_ctrl(2u, 2u, true));
#elif SCALER_PROOF_MODE == 3
    vdp_mode0_set_logic_size(200u, 150u);
    vdp_mode0_set_scale_ctrl(vdp_mode0_scale_ctrl(3u, 3u, true));
    ESP_LOGI(TAG, "scale=3x logic=200x150 expected_bezel=20x15 ctrl=0x%02X",
             vdp_mode0_scale_ctrl(3u, 3u, true));
#else
    /* SCALE_CTRL persists across MCU resets while the FPGA remains loaded. */
    vdp_mode0_set_logic_size(640u, 480u);
    vdp_mode0_set_scale_ctrl(0u);
    ESP_LOGI(TAG, "scale=1x explicit logic=640x480 ctrl=0x00");
#endif
    return vdp_last_error() == VDP_HOST_ERR_NONE;
}

void app_main(void)
{
    bool pass = true;
    vdp_host_init();
    if (vdp_last_error() != VDP_HOST_ERR_NONE) {
        ESP_LOGE(TAG, "host init failed err=%d", vdp_last_error());
        return;
    }
    ESP_LOGI(TAG, "scaler proof mode=%d magic=0x%08" PRIX32,
             SCALER_PROOF_MODE, vdp_read_status(SEL_MAGIC));

    build_checkerboard();
    pass &= configure_display();

#if SCALER_PROOF_MODE == 4
    ESP_LOGI(TAG,
             "DIAG_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X image_words=%u"
             " chunk_words=%u chunk_bytes=%u bitmap_frames=%u attr_frames=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u,
             (IMAGE_WORDS + MAX_CHUNK_WORDS - 1u) / MAX_CHUNK_WORDS,
             (IMAGE_WORDS + MAX_CHUNK_WORDS - 1u) / MAX_CHUNK_WORDS);
    pass &= health("HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane_diagnostic(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane_diagnostic(ATTR_BASE, s_attr, "attr");
    pass &= health("HEALTH_AFTER_UPLOAD");
    pass &= diagnostic_neighbor_reads();
    ESP_LOGI(TAG, "DIAG_RESULT pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 5
    ESP_LOGI(TAG,
             "SWEEP_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X image_words=%u"
             " chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("SWEEP_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("SWEEP_HEALTH_AFTER_UPLOAD");
    pass &= sweep_readback();
    ESP_LOGI(TAG, "SWEEP_DONE pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 6
    ESP_LOGI(TAG,
             "DOUBLE_READ_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X"
             " image_words=%u chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("DOUBLE_READ_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("DOUBLE_READ_HEALTH_AFTER_UPLOAD");
    pass &= diagnostic_double_reads();
    diagnostic_dummy_then_target();
    ESP_LOGI(TAG, "DOUBLE_READ_DONE pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 7
    build_display_indirect_pattern();
    ESP_LOGI(TAG,
             "INDIRECT_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X"
             " image_words=%u chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("INDIRECT_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("INDIRECT_HEALTH_AFTER_UPLOAD");
    vdp_mode0_set_bitmap_ctrl(0x0003u);
    vdp_mode0_set_layer_enable(0x0001u);
    pass &= health("INDIRECT_HEALTH_AFTER_ENABLE");
    ESP_LOGI(TAG, "INDIRECT_DISPLAY_READY pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

#if SCALER_PROOF_MODE == 8
    ESP_LOGI(TAG,
             "READ_DONE_GEOMETRY bitmap_base=0x%06X attr_base=0x%06X"
             " image_words=%u chunk_words=%u chunk_bytes=%u",
             BITMAP_BASE, ATTR_BASE, IMAGE_WORDS, MAX_CHUNK_WORDS,
             MAX_CHUNK_WORDS * 2u);
    pass &= health("READ_DONE_HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    pass &= health("READ_DONE_HEALTH_AFTER_UPLOAD");
    pass &= diagnostic_completion_poll();
    pass &= health("READ_DONE_HEALTH_AFTER_READ");
    ESP_LOGI(TAG, "READ_DONE_PROOF pass=%u", pass ? 1u : 0u);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
#endif

    pass &= health("HEALTH_BEFORE_UPLOAD");
    pass &= upload_plane(BITMAP_BASE, s_bitmap, "bitmap");
    pass &= upload_plane(ATTR_BASE, s_attr, "attr");
    vdp_host_set_speed_hz(2000000u);
    pass &= health("HEALTH_AFTER_UPLOAD");
    pass &= verify_readback();
    pass &= write_linestate();

    vdp_mode0_set_bitmap_ctrl(0x0003u);
    vdp_mode0_set_layer_enable(0x0001u);
    pass &= health("HEALTH_AFTER_ENABLE");
    ESP_LOGI(TAG, "SCALER_PROOF mode=%d pass=%u", SCALER_PROOF_MODE, pass ? 1u : 0u);

    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
}

---

## End of bundle
