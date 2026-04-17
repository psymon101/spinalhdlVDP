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
                payload_remaining := lenFull
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
