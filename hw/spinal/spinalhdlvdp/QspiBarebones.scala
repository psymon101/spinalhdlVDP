package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** PM #10034 mode0-barebones-stage-2: minimal 1-bit SPI receive path.
  *
  * Single-bit serial protocol. ESP32 (or any SPI master) drives:
  *   CS_n falls
  *   40 SCK cycles, host shifts data on falling edge, slave samples on
  *   rising edge, MSB first:
  *     bits [39:32] = CMD  (only 0x01 REG_WRITE supported in this slice)
  *     bits [31:16] = ADDR (16-bit)
  *     bits [15: 0] = DATA (16-bit)
  *   CS_n rises
  *
  * On CS_n rising edge AFTER a complete 40-bit frame the slave emits a
  * single-cycle (regAddr, regData, regWr) pulse on the consumer side.
  * Incomplete frames are ignored.
  *
  * Pure pixel-clock-domain implementation: inputs are sampled through
  * a 2-FF BufferCC synchronizer (so the host's asynchronous SCK is OK
  * as long as SCK frequency < pixel clock / 4, i.e. < ~6 MHz which is
  * well above the 2 MHz proven SCK from MODE0_PLANNING §10.8). No
  * separate QSPI clock domain.
  *
  * Quad-mode (4-bit IO0..IO3) is intentionally NOT supported in this
  * slice — PM #10034 explicitly bounds the scope to "smallest real QSPI
  * path that can be proven from ESP32 later". 1-bit SPI is the smallest.
  */
case class QspiBarebones() extends Component {
  val io = new Bundle {
    val cs_n = in  Bool()
    val sck  = in  Bool()
    val mosi = in  Bool()

    val regAddr = out UInt(16 bits)
    val regData = out Bits(16 bits)
    val regWr   = out Bool()
  }

  // 2-FF synchronizers for the host-driven inputs.
  val csSync   = BufferCC(io.cs_n, init = True)
  val sckSync  = BufferCC(io.sck,  init = False)
  val mosiSync = BufferCC(io.mosi, init = False)

  val sckPrev = RegNext(sckSync) init False
  val sckRise = sckSync && !sckPrev

  val csPrev   = RegNext(csSync) init True
  val csActive = !csSync

  val bitCount = Reg(UInt(6 bits)) init 0      // 0..40
  val shift    = Reg(Bits(40 bits)) init 0

  val regAddrR = Reg(UInt(16 bits)) init 0
  val regDataR = Reg(Bits(16 bits)) init 0
  val regWrR   = Reg(Bool()) init False
  regWrR := False  // default single-cycle

  when(!csActive) {
    // CS released — reset frame state. Commit any complete 40-bit frame
    // on the CS rising edge as a single-cycle reg-write pulse.
    when(csSync && !csPrev && bitCount === U(40, 6 bits)) {
      val cmd  = shift(39 downto 32)
      val addr = shift(31 downto 16).asUInt
      val data = shift(15 downto  0)
      when(cmd === B"8'h01") {
        regAddrR := addr
        regDataR := data
        regWrR   := True
      }
    }
    bitCount := 0
  } elsewhen(sckRise && bitCount < U(40, 6 bits)) {
    shift    := (shift(38 downto 0) ## mosiSync)
    bitCount := bitCount + 1
  }

  io.regAddr := regAddrR
  io.regData := regDataR
  io.regWr   := regWrR
}
