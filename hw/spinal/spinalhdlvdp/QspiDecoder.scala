package spinalhdlvdp

import spinal.core._

/** QSPI Decoder — turns the `QspiSlave` byte stream into VDP register-write
  * pulses and assembles the READ_STATUS response nibble stream.
  *
  * Packet format (from `QSPI_HOST_CONTROL_PLAN.md` §3):
  *   Header = [CMD:1] [ADDR:3] [LEN:2]  (little-endian)
  *   REG_WRITE (`CMD=0x01`): LEN pairs of little-endian 16-bit words; each
  *     pair emits one `regWriteAddr`/`regWriteData` pulse.  `addr` advances
  *     by 1 per word.
  *   READ_STATUS (`CMD=0x04`): LEN=0; FPGA drives `sel` bytes back to host.
  *     `sel` is the low byte of the incoming address.
  *
  * Checkpoint A responsibility: clean structural Verilog + stable control
  * contract.  Behavioural coverage lives in Checkpoint B sims.
  */
case class QspiDecoder() extends Component {
  val io = new Bundle {
    // Stream from QspiSlave.
    val cmd_opcode    = in Bits (8 bits)
    val cmd_addr      = in UInt (24 bits)
    val cmd_len       = in UInt (16 bits)
    val cmd_valid     = in Bool()
    val payload_byte  = in Bits (8 bits)
    val payload_valid = in Bool()
    // #13888 structural drain fix — word-granular payload path. The QSPI transport
    // packs 2 payload bytes into one 16-bit FIFO token SCLK-side and pops one WORD
    // per clk_sys cycle, so the drain (27 Mword/s = 54 MB/s) outpaces the 80 MHz quad
    // push (40 MB/s) and the CDC token FIFO can never overflow. ADDITIVE + mutually
    // exclusive with the byte path above: a consumer drives exactly one. payload_word
    // is a fully-assembled little-endian word (hi ## lo).
    val payload_word       = in Bits (16 bits)
    val payload_word_valid = in Bool()
    val tx_byte       = out Bits (8 bits)
    val tx_load       = out Bool()
    val tx_byte_sent  = in Bool()
    val active        = in Bool()

    // Task 32b: register bus output — bundle replaces the prior
    // regWriteAddr/Data/Enable triple.
    val regBus = out (Mode0RegBus())

    // Diagnostics / status echo.
    // Diagnostic-only outputs (sel=1/2/3 in READ_STATUS surface) removed
    // for fit budget — only `test_mode0_bad_apple` reads them, and the
    // production sketches (one_dot / starfield / zx_smoke) use sel=0/4/5/6/7
    // exclusively. `last_error` stays — it's the only diagnostic with a
    // live consumer (statusEvQspiError sticky bit).
    val last_error = out Bits (8 bits)
    // Task 35 — host-readable status sticky bits routed from VdpTop.
    val status_sticky = in Bits (16 bits)
    // Task 1 (#9154) — LIVE_MODE: committed MODE_SELECT value, observable
    // via READ_STATUS sel=7 per MODE_SELECT_ARCHITECTURE.md v1.1 §4.2 / Q6
    // (CyanPeak #9161 audit correction).
    val live_mode = in UInt (4 bits)
    // DIAG #10908 (P4 Task A): host-visible SDRAM readback. 32-bit word fetched
    // from a debug-configurable SDRAM address (regs 0x0326/0x0327 in TopTang),
    // surfaced over READ_STATUS sel=8. Diagnostic-only; remove with the lane.
    val debug_sdram_data = in Bits (32 bits)

    // Task 34 — SDRAM_WRITE bridge interface.
    val sdramHeaderValid = out Bool()
    val sdramAddrInit    = out UInt(23 bits)
    val sdramLenBytes    = out UInt(17 bits)
    val sdramByteOut     = out Bits(8 bits)
    val sdramByteValid   = out Bool()
    // #13888 — word-granular SDRAM_WRITE egress (paired with payload_word). One 16-bit
    // word per clk_sys cycle so the SDRAM path drains at the same word rate as REG_WRITE
    // (aligns with the 32-bit SDRAM word too). VdpTop-184 consumes this; the bring-up
    // top only lights the everSdram LED off sdramWordValid.
    val sdramWordOut     = out Bits(16 bits)
    val sdramWordValid   = out Bool()
    val upload_busy      = in Bool()
    val upload_done      = in Bool()
    // CP-A1 (Phase A #11411/#11419): sticky bridge watchdog-abort flag, surfaced
    // on READ_STATUS sel=6 bit2 so the host can detect an aborted upload + resync.
    val upload_error     = in Bool()
    // CP-A4 (#11443): sticky ingress-FIFO overflow flag, surfaced on sel=6 bit3 so
    // the host can detect a transport-ceiling drop (out-pacing the arbiter drain).
    val upload_overflow  = in Bool()

    // QSPI-pivot: expose the full 32-bit READ_STATUS word + a valid pulse so the
    // phase-based synchronous slave (QspiSlaveSync) can shift the response out
    // directly, without the byte-serial tx_byte/tx_load handshake. Additive — the
    // legacy tx_byte path is unchanged, so existing sims/consumers are unaffected.
    val rx_word       = out Bits(32 bits)
    val rx_word_valid = out Bool()
  }

  object Op {
    val REG_WRITE   = B"8'h01"
    val SDRAM_WRITE = B"8'h02"     // Task 34
    val READ_STATUS = B"8'h04"
  }

  // Word-assembly state: collect low byte then high byte, then emit.
  val dataLo    = Reg(Bits(8 bits)) init 0
  val haveLo    = Reg(Bool()) init False
  val writeAddr = Reg(UInt(15 bits)) init 0
  val writeData = Reg(Bits(16 bits)) init 0
  val writePulse = Reg(Bool()) init False
  writePulse := False

  val opcodeReg  = Reg(Bits(8 bits)) init 0
  val lenReg     = Reg(UInt(16 bits)) init 0
  val wordsLeft  = Reg(UInt(16 bits)) init 0
  val activeWrite = Reg(Bool()) init False
  val activeSdramWrite = Reg(Bool()) init False   // Task 34

  // Task 34 — SDRAM_WRITE bridge output registers.
  val sdramHeaderValidReg = Reg(Bool()) init False
  val sdramAddrInitReg    = Reg(UInt(23 bits)) init 0
  val sdramLenBytesReg    = Reg(UInt(17 bits)) init 0
  val sdramByteOutReg     = Reg(Bits(8 bits)) init 0
  val sdramByteValidReg   = Reg(Bool()) init False
  // #13888 — word-granular SDRAM egress registers.
  val sdramWordOutReg     = Reg(Bits(16 bits)) init 0
  val sdramWordValidReg   = Reg(Bool()) init False
  // #11308 hardening: bound the SDRAM_WRITE payload to LEN so trailing/padding/glitch
  // bytes past the declared length are IGNORED (not forwarded as spurious writes that
  // desync the address stream — the libvdp 4-byte-padding corruption, #11297/#11305).
  // Mirrors wordsLeft for REG_WRITE; counts payload BYTES (LEN = 2*words).
  val sdramBytesLeft = Reg(UInt(17 bits)) init 0
  sdramHeaderValidReg := False
  sdramByteValidReg   := False
  sdramWordValidReg   := False

  // Last bus-error diagnostic — read by `statusEvQspiError` for the
  // QSPI_ERROR sticky bit (Task 35). Other diagnostic Regs removed for
  // fit budget; their READ_STATUS sels return zero (handled by the
  // switch's `default` case after their `is` arms are stripped).
  val last_error = Reg(Bits(8 bits))  init 0

  // On a new header, latch opcode/len and reset the word-assembly state.
  when(io.cmd_valid) {
    opcodeReg := io.cmd_opcode
    lenReg    := io.cmd_len
    wordsLeft := io.cmd_len
    writeAddr := io.cmd_addr(14 downto 0)
    haveLo    := False
    activeWrite := io.cmd_opcode === Op.REG_WRITE
    // Task 34 — SDRAM_WRITE dispatch.
    activeSdramWrite := io.cmd_opcode === Op.SDRAM_WRITE
    when(io.cmd_opcode === Op.SDRAM_WRITE) {
      sdramAddrInitReg    := io.cmd_addr(22 downto 0)
      sdramLenBytesReg    := (io.cmd_len << 1).resize(17)   // bytes = 2 * words
      sdramBytesLeft      := (io.cmd_len << 1).resize(17)   // #11308: payload byte budget
      sdramHeaderValidReg := True
    }
  }

  // Each payload byte arrives on `payload_valid`. Assemble low then high.
  when(io.payload_valid) {
    when(activeWrite) {
      // #13838/#13843 hardening: bound REG_WRITE assembly to LEN, mirroring the
      // SDRAM_WRITE sdramBytesLeft guard (#11308). Without this, trailing/padding/
      // glitch bytes past the declared LEN words (or any payload while LEN=0) keep
      // assembling 16-bit words and pulsing regBus.enable onto the auto-incrementing
      // writeAddr, clobbering registers past the intended range. wordsLeft counts
      // WORDS; gating the whole assembly on wordsLeft>0 drops the lo-byte of a
      // would-be extra word too, so exactly LEN writes fire and no more.
      when(wordsLeft > U(0, 16 bits)) {
        when(!haveLo) {
          dataLo := io.payload_byte
          haveLo := True
        } otherwise {
          val word = io.payload_byte ## dataLo
          writeData  := word
          writePulse := True
          haveLo     := False
          wordsLeft  := wordsLeft - 1
        }
      }
    } elsewhen(activeSdramWrite) {
      // Task 34 — raw byte forwarded to the bridge; no word assembly here.
      // #11308: only forward while within the declared LEN budget. Bytes beyond
      // LEN (host 4-byte padding, or any trailing/glitch byte before CS-deassert)
      // are dropped so they cannot become spurious writes past addrInit+LEN.
      when(sdramBytesLeft > U(0, 17 bits)) {
        sdramByteOutReg   := io.payload_byte
        sdramByteValidReg := True
        sdramBytesLeft    := sdramBytesLeft - 1
      }
    } otherwise {
      // Unknown opcode — record error but drop the byte.
      last_error := opcodeReg
    }
  }

  // #13888 — word-granular payload path. Delivers a full 16-bit word per clk_sys
  // cycle (no lo/hi byte assembly here — the transport packed it SCLK-side). Mutually
  // exclusive with the byte path above (a consumer asserts payload_valid OR
  // payload_word_valid, never both), so the two guarded blocks never collide on the
  // shared wordsLeft/writeData/writePulse/sdramBytesLeft registers.
  when(io.payload_word_valid) {
    when(activeWrite) {
      // Same LEN bound as the byte path (#13838/#13843): drop words past LEN.
      when(wordsLeft > U(0, 16 bits)) {
        writeData  := io.payload_word
        writePulse := True
        wordsLeft  := wordsLeft - 1
      }
    } elsewhen(activeSdramWrite) {
      // Same LEN bound as #11308. sdramBytesLeft counts BYTES and (LEN=words) is always
      // even. Guard on >=2 (not >0) so the -2 can never underflow even if a future
      // LEN-injection bug left the counter odd — CoralReef #13893 hardening.
      when(sdramBytesLeft >= U(2, 17 bits)) {
        sdramWordOutReg   := io.payload_word
        sdramWordValidReg := True
        sdramBytesLeft    := sdramBytesLeft - 2
      }
    } otherwise {
      last_error := opcodeReg
    }
  }

  // Auto-increment writeAddr one cycle AFTER the pulse fires, so the pulse
  // itself carries the pre-increment address on the regWrite bus.
  when(writePulse) {
    writeAddr := writeAddr + 1
  }

  io.regBus.addr   := writeAddr
  io.regBus.data   := writeData
  io.regBus.enable := writePulse

  io.last_error := last_error

  // -------------------------------------------------------------------
  // READ_STATUS response FSM (Task 38b — expanded status surface).
  //
  // Plan §3.3 — on CMD=0x04 LEN=0, drive 4 bytes back to the host after
  // the slave's 2-edge turnaround. `sel` = low byte of cmd_addr.
  //
  //   sel=0 → magic 0x51560002 (host transport identification, retained
  //           from Task 27)
  //   sel=1 → rx_cmd_cnt in byte 0, upper 24 bits zero
  //   sel=2 → last_addr low byte in byte 0, high byte in byte 1,
  //           upper 16 bits zero
  //   sel=3 → last_data low byte in byte 0, high byte in byte 1,
  //           upper 16 bits zero
  //   sel=4 → last_error in byte 0, upper 24 bits zero
  //   sel>4 → zeroed word (reserved for future expansion)
  //
  // Load-time snapshot: rxWord is captured once on cmd_valid, never
  // mutated while the response walks Load→Wait→Shift. If rx_cmd_cnt /
  // last_addr / last_data / last_error update mid-response (e.g. a new
  // REG_WRITE lands while the READ_STATUS response is still shifting),
  // the in-flight response is not corrupted.
  // -------------------------------------------------------------------
  object RxState extends SpinalEnum { val Idle, Load, Wait = newElement() }
  val rxState = Reg(RxState()) init RxState.Idle
  val rxByteIdx = Reg(UInt(2 bits)) init 0
  val rxWord    = Reg(Bits(32 bits)) init 0
  val rxLoad    = Reg(Bool()) init False
  val rxTxByte  = Reg(Bits(8 bits)) init 0
  val rxWordValid = Reg(Bool()) init False   // QSPI-pivot: pulse when rxWord latched
  rxLoad := False
  rxWordValid := False

  // Kick off READ_STATUS on header pulse. rxWord is sampled atomically
  // from the current diagnostic state; later changes don't leak in.
  when(io.cmd_valid && io.cmd_opcode === Op.READ_STATUS && io.cmd_len === U(0, 16 bits)) {
    val sel = io.cmd_addr(7 downto 0)
    switch(sel) {
      is(U(0, 8 bits)) { rxWord := B"32'h51560002" }
      // sels 1/2/3 (rx_cmd_cnt / last_addr / last_data) removed; default
      // returns 0. Production sketches don't read them; only the retired
      // `test_mode0_bad_apple` Pico-era diagnostic did.
      is(U(4, 8 bits)) { rxWord := B(0, 24 bits) ## last_error }
      is(U(5, 8 bits)) { rxWord := B(0, 16 bits) ## io.status_sticky }   // Task 35
      is(U(6, 8 bits)) {                                                  // Task 34
        // sel=6 upload status: byte0[0]=upload_busy, byte0[1]=upload_done (latched),
        // byte0[2]=upload_error (CP-A1 sticky watchdog-abort), byte0[3]=upload_overflow
        // (CP-A4 sticky ingress-FIFO overflow).
        val statBits = B(0, 4 bits) ## io.upload_overflow ## io.upload_error ## io.upload_done ## io.upload_busy
        rxWord := B(0, 24 bits) ## statBits
      }
      is(U(7, 8 bits)) {                                                  // Task 1 (#9154)
        // sel=7 LIVE_MODE: byte0[3:0] = committed MODE_SELECT, upper bits zero.
        // Host polls this after a MODE_SELECT write to confirm V=0 commit
        // (alternative to STATUS_STICKY bit 11 MODE_SELECT_CHANGED).
        rxWord := B(0, 28 bits) ## io.live_mode.asBits
      }
      is(U(8, 8 bits)) {                                                  // DIAG #10908
        // sel=8 SDRAM readback: the 32-bit word the SDRAM controller returned
        // for the debug address armed via regs 0x0326/0x0327. Byte order matches
        // dout32 (little-endian: byte0 in [7:0]).
        rxWord := io.debug_sdram_data
      }
      default          { rxWord := B(0, 32 bits) }
    }
    rxByteIdx := 0
    rxState   := RxState.Load
    rxWordValid := True                        // QSPI-pivot: rxWord is now valid
  }

  switch(rxState) {
    is(RxState.Idle) { /* no-op */ }
    is(RxState.Load) {
      rxTxByte := rxWord.subdivideIn(8 bits)(rxByteIdx)
      rxLoad   := True
      rxState  := RxState.Wait
    }
    is(RxState.Wait) {
      when(io.tx_byte_sent) {
        when(rxByteIdx === U(3, 2 bits)) {
          rxState := RxState.Idle
        } otherwise {
          rxByteIdx := rxByteIdx + 1
          rxState   := RxState.Load
        }
      }
    }
  }

  io.tx_byte := rxTxByte
  io.tx_load := rxLoad
  io.rx_word       := rxWord
  io.rx_word_valid := rxWordValid

  // Task 34 — SDRAM bridge outputs
  io.sdramHeaderValid := sdramHeaderValidReg
  io.sdramAddrInit    := sdramAddrInitReg
  io.sdramLenBytes    := sdramLenBytesReg
  io.sdramByteOut     := sdramByteOutReg
  io.sdramByteValid   := sdramByteValidReg
  io.sdramWordOut     := sdramWordOutReg
  io.sdramWordValid   := sdramWordValidReg
}
