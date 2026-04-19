package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** R5 Copper coprocessor — mid-frame raster-effect engine.
  *
  * 512 × 16-bit program RAM (GT-022 safe). Four instructions:
  *   - **WAIT** (1 word): `00 | 0000 | Y[9:0]` — stalls until `vCounter==Y`
  *     and `hCounter==0`. Allows precise line-accurate scheduling.
  *   - **WRITE** (2 words): `01 | addr[13:0]` then `data[15:0]` — emits one
  *     register write on the unified bus.
  *   - **WRITE_SEQ** (≥2 words): `10 | count_m1[2:0] | addr[10:0]` then N
  *     data words (N = count_m1 + 1, 1..8) — emits N consecutive register
  *     writes with auto-incrementing address.
  *   - **JUMP** (1 word): `11 | 0000 | targetPC[8:0]` — unconditional jump.
  *
  * Runs in pixel clock domain. Writes are emitted as single-cycle regWrite
  * pulses, which the consumer (a multiplexer in VdpTop) merges with the
  * HostInterface regWrite bus.
  *
  * Host-side program upload: `progAddr` / `progData` / `progWr` update the
  * program RAM only when `io.enabled` is False.
  */
case class Copper() extends Component {
  val io = new Bundle {
    val hCounter = in UInt(10 bits)
    val vCounter = in UInt(10 bits)
    val enabled  = in Bool()

    // Program RAM host-write interface (only valid when enabled == False)
    val progAddr = in UInt(9 bits)
    val progData = in Bits(16 bits)
    val progWr   = in Bool()

    // Task 33: HDMA host-control write port (offset within 0x0380 block).
    val hdmaCtrlAddr = in UInt(7 bits)
    val hdmaData     = in Bits(16 bits)
    val hdmaWr       = in Bool()

    // Register-write output (pixel domain, merged upstream with HostInterface)
    val regAddr  = out UInt(15 bits)
    val regData  = out Bits(16 bits)
    val regWr    = out Bool()
  }

  val prog = Mem(Bits(16 bits), 512)
  when(io.progWr && !io.enabled) {
    prog.write(io.progAddr, io.progData)
  }

  val pc        = Reg(UInt(9 bits)) init 0
  val readAddr  = UInt(9 bits)
  readAddr := pc
  val fetchWord = prog.readAsync(readAddr)

  // Latched operand registers for multi-word instructions
  val opAddr    = Reg(UInt(15 bits)) init 0
  val seqCount  = Reg(UInt(3 bits))  init 0  // remaining data words after current

  // Default outputs
  val regAddrR = Reg(UInt(15 bits)) init 0
  val regDataR = Reg(Bits(16 bits)) init 0
  val regWrR   = Reg(Bool())         init False
  regWrR := False  // default single-cycle

  val opcode = fetchWord(15 downto 14)

  val fsm = new StateMachine {
    val sHalt      = new State with EntryPoint
    val sFetch     = new State
    val sWaitStall = new State
    val sWriteData = new State
    val sSeqData   = new State

    sHalt.whenIsActive {
      when(io.enabled) {
        pc := 0
        goto(sFetch)
      }
    }

    sFetch.whenIsActive {
      when(!io.enabled) { goto(sHalt) }.otherwise {
        switch(opcode) {
          is(B"00") {
            // WAIT Y
            goto(sWaitStall)
          }
          is(B"01") {
            // WRITE (2 words): latch addr, advance to read data word
            opAddr := fetchWord(13 downto 0).resize(15).asUInt
            pc := pc + 1
            goto(sWriteData)
          }
          is(B"10") {
            // WRITE_SEQ: latch count-1 + base addr, advance to read first data
            seqCount := fetchWord(13 downto 11).asUInt
            opAddr   := fetchWord(10 downto 0).resize(15).asUInt
            pc := pc + 1
            goto(sSeqData)
          }
          is(B"11") {
            // JUMP
            pc := fetchWord(8 downto 0).asUInt
          }
        }
      }
    }

    sWaitStall.whenIsActive {
      when(!io.enabled) { goto(sHalt) }
      .elsewhen(io.vCounter === fetchWord(9 downto 0).asUInt && io.hCounter === U(0, 10 bits)) {
        pc := pc + 1
        goto(sFetch)
      }
    }

    sWriteData.whenIsActive {
      when(!io.enabled) { goto(sHalt) }.otherwise {
        regAddrR := opAddr
        regDataR := fetchWord
        regWrR   := True
        pc := pc + 1
        goto(sFetch)
      }
    }

    sSeqData.whenIsActive {
      when(!io.enabled) { goto(sHalt) }.otherwise {
        regAddrR := opAddr
        regDataR := fetchWord
        regWrR   := True
        opAddr   := opAddr + 1
        when(seqCount === 0) {
          pc := pc + 1
          goto(sFetch)
        }.otherwise {
          seqCount := seqCount - 1
          pc := pc + 1
        }
      }
    }
  }

  // ------------------------------------------------------------------
  // Task 33 — HDMA engine (incremental add)
  // ------------------------------------------------------------------
  val NUM_CH  = 4
  val NUM_ENT = 8
  val hdmaEnable = Reg(Bool()) init False
  val hdmaChMask = Reg(Bits(4 bits)) init 0
  val hdmaDoneSt = Reg(Bool()) init False

  val chAddr0 = Reg(UInt(15 bits)) init 0
  val chAddr1 = Reg(UInt(15 bits)) init 0
  val chAddr2 = Reg(UInt(15 bits)) init 0
  val chAddr3 = Reg(UInt(15 bits)) init 0

  when(io.hdmaWr) {
    switch(io.hdmaCtrlAddr) {
      is(U(0x00, 7 bits)) {
        hdmaEnable := io.hdmaData(0)
        hdmaChMask := io.hdmaData(4 downto 1)
      }
      is(U(0x01, 7 bits)) { when(io.hdmaData(0)) { hdmaDoneSt := False } }
      is(U(0x02, 7 bits)) { chAddr0 := io.hdmaData(14 downto 0).asUInt }
      is(U(0x04, 7 bits)) { chAddr1 := io.hdmaData(14 downto 0).asUInt }
      is(U(0x06, 7 bits)) { chAddr2 := io.hdmaData(14 downto 0).asUInt }
      is(U(0x08, 7 bits)) { chAddr3 := io.hdmaData(14 downto 0).asUInt }
      default {}
    }
  }

  // Entry table: 25-bit words {valid[24], line[23:16], data[15:0]}.
  // Initialised to all-zeros so `valid==0` for every entry at power-on;
  // guarantees sweep cannot produce phantom hits from undefined Mem state
  // (Verilator models uninitialised readAsync as random).
  val tbl = Mem(Bits(25 bits), NUM_CH * NUM_ENT).initBigInt(Seq.fill(NUM_CH * NUM_ENT)(BigInt(0)))
  val tblWrAddr = UInt(log2Up(NUM_CH * NUM_ENT) bits)
  val tblWrData = Bits(25 bits)
  val tblWrEn   = Bool()
  tblWrAddr := 0
  tblWrData := B(0, 25 bits)
  tblWrEn   := False

  when(io.hdmaWr) {
    val off = io.hdmaCtrlAddr
    when(off >= U(0x0A, 7 bits) && off <= U(0x49, 7 bits)) {
      val slot   = off - U(0x0A, 7 bits)
      val ch     = slot(5 downto 4)                    // bits 5:4 ≅ ch (0..3)
      val ent    = slot(3 downto 1)                    // bits 3:1 ≅ entry (0..7)
      val isData = slot(0)
      val ix     = (ch.resize(log2Up(NUM_CH * NUM_ENT)) * U(NUM_ENT, log2Up(NUM_CH * NUM_ENT) bits) +
                    ent.resize(log2Up(NUM_CH * NUM_ENT))).resize(log2Up(NUM_CH * NUM_ENT))
      val cur       = tbl.readAsync(ix)
      val nextValid = Mux(isData, cur(24), io.hdmaData(15))
      val nextLine  = Mux(isData, cur(23 downto 16), io.hdmaData(7 downto 0))
      val nextData  = Mux(isData, io.hdmaData, cur(15 downto 0))
      tblWrAddr := ix
      tblWrData := nextValid ## nextLine ## nextData
      tblWrEn   := True
    }
  }
  tbl.write(tblWrAddr, tblWrData, tblWrEn)

  // Sweep FSM — one entry scan per cycle across NUM_CH*NUM_ENT entries per line.
  def chAddrSel(ch: UInt): UInt = ch.muxList(Seq(
    (0, chAddr0), (1, chAddr1), (2, chAddr2), (3, chAddr3)))
  def tidx(ch: UInt, ent: UInt): UInt = {
    val total = NUM_CH * NUM_ENT
    (ch.resize(log2Up(total)) * U(NUM_ENT, log2Up(total) bits) +
     ent.resize(log2Up(total))).resize(log2Up(total))
  }

  val sweepActive = Reg(Bool()) init False
  val sweepCh     = Reg(UInt(log2Up(NUM_CH + 1) bits)) init 0
  val sweepEnt    = Reg(UInt(log2Up(NUM_ENT) bits))    init 0
  val hzero       = io.hCounter === U(0, 10 bits)
  val hzeroPrev   = RegNext(hzero) init False
  val lineStart   = hzero && !hzeroPrev

  val hdmaRegAddr = Reg(UInt(15 bits)) init 0
  val hdmaRegData = Reg(Bits(16 bits)) init 0
  val hdmaRegWr   = Reg(Bool())        init False
  hdmaRegWr := False

  when(lineStart && io.vCounter === U(0, 10 bits)) { hdmaDoneSt := False }
  when(lineStart && hdmaEnable) {
    sweepActive := True
    sweepCh     := 0
    sweepEnt    := 0
  }

  val chi      = sweepCh.resize(log2Up(NUM_CH))
  val masked   = hdmaChMask(chi)
  val curEntry = tbl.readAsync(tidx(chi, sweepEnt))
  val entValid = curEntry(24)
  val entLine  = curEntry(23 downto 16).asUInt
  val entData  = curEntry(15 downto 0)
  val hit      = entValid && (entLine === io.vCounter(7 downto 0))

  when(sweepActive) {
    when(masked && hit) {
      hdmaRegAddr := chAddrSel(chi)
      hdmaRegData := entData
      hdmaRegWr   := True
    }
    when(sweepEnt === U(NUM_ENT - 1, log2Up(NUM_ENT) bits)) {
      sweepEnt := 0
      when(sweepCh === U(NUM_CH - 1, sweepCh.getWidth bits)) {
        sweepActive := False
        sweepCh     := 0
        hdmaDoneSt  := True
      } otherwise {
        sweepCh := sweepCh + 1
      }
    } otherwise {
      sweepEnt := sweepEnt + 1
    }
  }

  // Output mux — script wins over HDMA on same-cycle contention.
  io.regWr   := regWrR || hdmaRegWr
  io.regAddr := Mux(regWrR, regAddrR, hdmaRegAddr)
  io.regData := Mux(regWrR, regDataR, hdmaRegData)
}
