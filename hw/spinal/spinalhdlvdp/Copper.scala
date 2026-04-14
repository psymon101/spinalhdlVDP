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
  io.regAddr := regAddrR
  io.regData := regDataR
  io.regWr   := regWrR
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
}
