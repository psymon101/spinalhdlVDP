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
    val tx_byte       = out Bits (8 bits)
    val tx_load       = out Bool()
    val tx_byte_sent  = in Bool()
    val active        = in Bool()

    // VDP register-write bus (asserted one cycle per 16-bit word).
    val regWriteAddr   = out UInt (15 bits)
    val regWriteData   = out Bits (16 bits)
    val regWriteEnable = out Bool()

    // Diagnostics / status echo.
    val last_addr  = out UInt (16 bits)
    val last_data  = out Bits (16 bits)
    val last_error = out Bits (8 bits)
    val rx_cmd_cnt = out UInt (8 bits)
    // Task 35 — host-readable status sticky bits routed from VdpTop.
    val status_sticky = in Bits (16 bits)
  }

  object Op {
    val REG_WRITE = B"8'h01"
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

  // Latched diagnostics.
  val last_addr  = Reg(UInt(16 bits)) init 0
  val last_data  = Reg(Bits(16 bits)) init 0
  val last_error = Reg(Bits(8 bits))  init 0
  val rx_cmd_cnt = Reg(UInt(8 bits))  init 0

  // On a new header, latch opcode/len and reset the word-assembly state.
  when(io.cmd_valid) {
    opcodeReg := io.cmd_opcode
    lenReg    := io.cmd_len
    wordsLeft := io.cmd_len
    writeAddr := io.cmd_addr(14 downto 0)
    haveLo    := False
    rx_cmd_cnt := rx_cmd_cnt + 1
    activeWrite := io.cmd_opcode === Op.REG_WRITE
  }

  // Each payload byte arrives on `payload_valid`. Assemble low then high.
  when(io.payload_valid) {
    when(activeWrite) {
      when(!haveLo) {
        dataLo := io.payload_byte
        haveLo := True
      } otherwise {
        val word = io.payload_byte ## dataLo
        writeData  := word
        writePulse := True
        last_addr  := writeAddr.resize(16)
        last_data  := word
        haveLo     := False
        when(wordsLeft > U(0, 16 bits)) {
          wordsLeft := wordsLeft - 1
        }
      }
    } otherwise {
      // Unknown opcode — record error but drop the byte.
      last_error := opcodeReg
    }
  }

  // Auto-increment writeAddr one cycle AFTER the pulse fires, so the pulse
  // itself carries the pre-increment address on the regWrite bus.
  when(writePulse) {
    writeAddr := writeAddr + 1
  }

  io.regWriteAddr   := writeAddr
  io.regWriteData   := writeData
  io.regWriteEnable := writePulse

  io.last_addr  := last_addr
  io.last_data  := last_data
  io.last_error := last_error
  io.rx_cmd_cnt := rx_cmd_cnt

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
  rxLoad := False

  // Kick off READ_STATUS on header pulse. rxWord is sampled atomically
  // from the current diagnostic state; later changes don't leak in.
  when(io.cmd_valid && io.cmd_opcode === Op.READ_STATUS && io.cmd_len === U(0, 16 bits)) {
    val sel = io.cmd_addr(7 downto 0)
    switch(sel) {
      is(U(0, 8 bits)) { rxWord := B"32'h51560002" }
      is(U(1, 8 bits)) { rxWord := B(0, 24 bits) ## rx_cmd_cnt.asBits }
      is(U(2, 8 bits)) { rxWord := B(0, 16 bits) ## last_addr.asBits }
      is(U(3, 8 bits)) { rxWord := B(0, 16 bits) ## last_data }
      is(U(4, 8 bits)) { rxWord := B(0, 24 bits) ## last_error }
      is(U(5, 8 bits)) { rxWord := B(0, 16 bits) ## io.status_sticky }   // Task 35
      default          { rxWord := B(0, 32 bits) }
    }
    rxByteIdx := 0
    rxState   := RxState.Load
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
}
