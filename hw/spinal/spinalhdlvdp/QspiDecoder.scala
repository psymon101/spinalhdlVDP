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
        // Advance address for next word (auto-increment register addressing).
        writeAddr  := writeAddr + 1
        when(wordsLeft > U(0, 16 bits)) {
          wordsLeft := wordsLeft - 1
        }
      }
    } otherwise {
      // Unknown opcode — record error but drop the byte.
      last_error := opcodeReg
    }
  }

  io.regWriteAddr   := writeAddr
  io.regWriteData   := writeData
  io.regWriteEnable := writePulse

  io.last_addr  := last_addr
  io.last_data  := last_data
  io.last_error := last_error
  io.rx_cmd_cnt := rx_cmd_cnt

  // Response channel — Checkpoint A placeholder; real status mux lands in
  // Checkpoint B sim once READ_STATUS is exercised end-to-end.
  io.tx_byte := B(0, 8 bits)
  io.tx_load := False
}
