package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** QspiSlaveSync — true SCLK-domain synchronous QSPI slave front-end for the P4↔Tang
  * transport (QSPI pivot, contract QSPI_LINK_CONTRACT.md).
  *
  * Replaces the retired oversampled QspiSlave. ALL logic here is clocked by the
  * external SCLK; CS# (active-low) is the domain reset — the FSM restarts at bit 0
  * every transaction, and the reset release is naturally synchronized by the master's
  * CS#-to-first-SCLK setup (`cs_ena_pretrans`). Downstream feeds the EXISTING
  * `QspiDecoder` byte contract unchanged.
  *
  * Framing (phase-based 1-1-4, locked #13765; P4 has no SOC_SPI_HD_BOTH_INOUT):
  *   CS#↓ → CMD(8, IO0, MSB-first) → ADDR(40, IO0, MSB-first = {ADDR[23:0],LEN[15:0]})
  *        → WRITE: QIO-TX payload (IO0-3, high-nibble-first) → CS#↑
  *        → READ : DUMMY(N) → QIO-RX (FPGA drives IO0-3, high-nibble-first) → CS#↑
  *
  * Read data: the whole 32-bit status word is taken as `io.rxWord` (decoder computes
  * it combinationally from sel=addr[7:0] at cmdValid) and shifted out locally — no
  * byte-at-a-time handshake race. Byte order matches the decoder: byte0 (LSB) first,
  * high nibble first within each byte.
  *
  * v1 drives read outputs registered on the SCLK RISING edge — fine at 20 MHz (50 ns).
  * TODO(40 MHz): move the output register to the FALLING edge for a half-cycle of Tco
  * margin. Contention-safe: `ioOe` asserts ONLY in the RDATA phase (after the dummy
  * turnaround window), never while the master could still be driving.
  */
case class QspiSlaveSync(dummyCycles: Int = 2, extSclkCd: ClockDomain = null, hdrParity: Boolean = false) extends Component {
  require(dummyCycles >= 1, "need >=1 dummy cycle for read turnaround")
  val io = new Bundle {
    // ---- QSPI pads (SCLK domain derived internally) ----
    val sclk  = in  Bool()
    val csn   = in  Bool()               // active-low chip select
    val ioIn  = in  Bits(4 bits)         // captured pad inputs (IOBUF .O)
    val ioOut = out Bits(4 bits)         // to IOBUF .I
    val ioOe  = out Bool()               // drive shared bus when high (IOBUF !OEN)

    // ---- header out (feeds QspiDecoder cmd_* contract) ----
    val cmdOpcode = out Bits(8 bits)
    val cmdAddr   = out UInt(24 bits)
    val cmdLen    = out UInt(16 bits)
    val cmdValid  = out Bool()

    // ---- write payload out (feeds QspiDecoder payload_* contract) ----
    val payloadByte  = out Bits(8 bits)
    val payloadValid = out Bool()

    // ---- read data in: the 32-bit READ_STATUS word (decoder rxWord, sel-selected) ----
    val rxWord = in Bits(32 bits)

    val active = out Bool()               // CS# asserted (in a transaction)

    // ---- header integrity: pulse at header-complete when ADDR[23] parity mismatches
    // (only meaningful when hdrParity=true; else held False) ----
    val hdrErr = out Bool()
  }

  // ===========================================================================
  // SCLK clock domain. Reset = CS# HIGH (idle) → every transaction starts fresh
  // at bit 0. Sample on rising edge (SPI mode 0). CS#-low is stable before the
  // first SCLK edge (master cs_ena_pretrans), so async reset release is clean.
  // ===========================================================================
  val sclkCd = if (extSclkCd != null) extSclkCd else ClockDomain(
    clock = io.sclk,
    reset = io.csn,
    config = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = HIGH)
  )

  object Phase extends SpinalEnum { val CMD, ADDR, LENCAP, WDATA, DUMMY, RDATA = newElement() }

  // nibble k of the 32-bit read word: byte0 first, high nibble first (flash convention)
  def rdNibble(word: Bits, k: UInt): Bits = {
    val byteSel = (k >> 1).resize(2 bits)  // 0..3
    val hi      = !k(0)                     // even k = high nibble
    val b       = word.subdivideIn(8 bits)(byteSel)
    hi ? b(7 downto 4) | b(3 downto 0)
  }

  val area = new ClockingArea(sclkCd) {
    val phase = Reg(Phase()) init Phase.CMD
    val bitc  = Reg(UInt(6 bits)) init 0

    val cmdSh  = Reg(Bits(8 bits))  init 0
    val addrSh = Reg(Bits(24 bits)) init 0     // P4 caps address phase at 32b; we use 24

    val opcodeR = Reg(Bits(8 bits))  init 0
    val addrR   = Reg(UInt(24 bits)) init 0
    val lenR    = Reg(UInt(16 bits)) init 0
    val cmdValidR = Reg(Bool()) init False
    val hdrErrR   = Reg(Bool()) init False     // header parity mismatch pulse (hdrParity only)
    // LEN capture (writes): the P4 can't carry 40-bit address, so LEN arrives as the
    // first 2 quad-data bytes (LEN_lo, LEN_hi) after the 24-bit ADDR phase.
    val lenLo      = Reg(Bits(8 bits)) init 0
    val lenByteCnt = Reg(UInt(1 bits)) init 0

    // write nibble assembly (high nibble first). payload is COMBINATIONAL so the
    // decoder commits the byte ON the low-nibble edge — no trailing SCLK edge
    // needed (the last write byte would otherwise be lost when CS# deasserts).
    val nibHigh   = Reg(Bool()) init True
    val hiNib     = Reg(Bits(4 bits)) init 0

    // read shift-out. Output is launched on the FALLING edge (outArea below) — a
    // mode-0 master samples MISO on the RISING edge, so a rising-edge launch races
    // the sample (CoralReef review #13772). rdWord/rdNib/phase are read by the
    // falling-edge area (same SCLK, opposite edge = half-cycle path).
    val rdWord   = Reg(Bits(32 bits)) init 0
    val rdNib    = Reg(UInt(4 bits)) init 0     // 0..7 nibbles = 4 bytes
    rdWord.addTag(crossClockDomain)
    rdNib.addTag(crossClockDomain)
    phase.addTag(crossClockDomain)

    cmdValidR := False
    hdrErrR   := False

    switch(phase) {
      is(Phase.CMD) {
        cmdSh := cmdSh(6 downto 0) ## io.ioIn(0)
        when(bitc === 7) { bitc := 0; phase := Phase.ADDR } otherwise { bitc := bitc + 1 }
      }
      is(Phase.ADDR) {
        val a24 = addrSh(22 downto 0) ## io.ioIn(0)   // full 24 bits on the last bit
        addrSh := a24
        when(bitc === 23) {
          bitc    := 0
          opcodeR := cmdSh
          addrR   := a24.asUInt
          // header parity: bit 23 = even parity over {opcode, addr[22:0]}
          if (hdrParity) { hdrErrR := a24(23) =/= (cmdSh.xorR ^ a24(22 downto 0).xorR) }
          when(cmdSh === B"8'h04") {           // READ_STATUS → no LEN, decode now
            lenR      := 0
            cmdValidR := True
            phase     := Phase.DUMMY
          } otherwise {                        // WRITE → LEN comes as first 2 data bytes
            phase      := Phase.LENCAP
            nibHigh    := True
            lenByteCnt := 0
          }
        } otherwise { bitc := bitc + 1 }
      }
      is(Phase.LENCAP) {
        // quad TX-in: grab 2 bytes (LEN_lo, LEN_hi), then decode + go to payload
        when(nibHigh) { hiNib := io.ioIn; nibHigh := False } otherwise {
          val b = hiNib ## io.ioIn
          nibHigh := True
          when(lenByteCnt === 0) { lenLo := b; lenByteCnt := 1 } otherwise {
            lenR      := (b ## lenLo).asUInt   // {LEN_hi, LEN_lo}
            cmdValidR := True
            phase     := Phase.WDATA
          }
        }
      }
      is(Phase.WDATA) {
        // quad TX-in: capture high nibble, toggle. The byte + valid are driven
        // combinationally below so the decoder consumes on the low-nibble edge.
        when(nibHigh) { hiNib := io.ioIn; nibHigh := False } otherwise { nibHigh := True }
      }
      is(Phase.DUMMY) {
        when(bitc === (dummyCycles - 1)) {
          bitc   := 0
          phase  := Phase.RDATA
          rdWord := io.rxWord         // stable now (sel decoded 2+ cycles ago)
          rdNib  := 0
        } otherwise { bitc := bitc + 1 }
      }
      is(Phase.RDATA) {
        // advance the nibble pointer each rising edge; the falling-edge area drives
        // the pad. hold the last nibble after 8.
        when(rdNib < 7) { rdNib := rdNib + 1 }
      }
    }
  }

  io.cmdOpcode    := area.opcodeR
  io.cmdAddr      := area.addrR
  io.cmdLen       := area.lenR
  io.cmdValid     := area.cmdValidR
  // combinational payload: valid during the low-nibble cycle; byte = hiNib##ioIn.
  // Consumed by the decoder ON the low-nibble edge (no trailing-edge dependency).
  io.payloadByte  := area.hiNib ## io.ioIn
  io.payloadValid := (area.phase === Phase.WDATA) && !area.nibHigh
  // ===========================================================================
  // FALLING-edge output launch (SPI mode-0 read timing). Presenting read data on
  // the falling edge gives the master a half-cycle of setup before it samples on
  // the next rising edge — no race, no contention (ioOe also asserts on the falling
  // edge after the dummy window). Reads rdWord/rdNib/phase from the rising area.
  // ===========================================================================
  val outCd = ClockDomain(
    clock = io.sclk,
    reset = io.csn,
    config = ClockDomainConfig(clockEdge = FALLING, resetKind = ASYNC, resetActiveLevel = HIGH)
  )
  val outArea = new ClockingArea(outCd) {
    val ioOutF = Reg(Bits(4 bits)) init 0
    val ioOeF  = Reg(Bool()) init False
    when(area.phase === Phase.RDATA) {
      ioOutF := rdNibble(area.rdWord, area.rdNib)
      ioOeF  := True
    } otherwise {
      ioOeF := False
    }
  }

  io.ioOut  := outArea.ioOutF
  io.ioOe   := outArea.ioOeF
  io.active := !io.csn
  io.hdrErr := area.hdrErrR
}
