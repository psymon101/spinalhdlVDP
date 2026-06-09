package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Handshake-driven pin-continuity walker (lane P21 side-lane, owner-directed).
  *
  * Tests each data wire individually with a 2-wire handshake instead of LEDs:
  *   - D0 (ESP->FPGA) = ADVANCE strobe: a rising edge moves the test index to the
  *     next pin and the FPGA drives THAT pin high (one-hot on D2..D7).
  *   - D1 (ESP->FPGA) = PASS ack: a rising edge bumps a pass counter (visibility).
  *   - D2..D7 (FPGA->ESP) = driven one-hot by the current test index; the ESP reads
  *     its matching pin and confirms it is high => that wire is continuous.
  *   - D0 AND D1 both high = RESYNC: index back to idle (1), pass counter cleared.
  *
  * D0/D1 are proven-good implicitly: if the walk progresses at all, those two wires
  * carry the handshake. Sequence walked: D2,D3,D4,D5,D6,D7 (idx 2..7, wraps 7->2).
  * Pure FPGA->ESP direction per data pin — sufficient for continuity (a connected
  * wire carries either way). Control pins (CS/WR/RD/DC) are a separate phase.
  */
case class HandshakeWalker() extends Component {
  val io = new Bundle {
    val d0       = in  Bool()              // advance strobe (raw pad level; synced here)
    val d1       = in  Bool()              // pass ack
    val dOut     = out Bits(8 bits)        // value to drive on D2..D7 (bits 0,1 unused)
    val testIdx  = out UInt(3 bits)        // current pin under test (2..7; 1 = idle)
    val passCount= out UInt(4 bits)
    val led      = out Bits(6 bits)
  }
  val d0s = BufferCC(io.d0, False)
  val d1s = BufferCC(io.d1, False)
  val d0rise = d0s && !RegNext(d0s).init(False)
  val d1rise = d1s && !RegNext(d1s).init(False)

  val idx  = Reg(UInt(3 bits)) init 1      // 1 = idle/none driven, 2..7 = pin index
  val pass = Reg(UInt(4 bits)) init 0
  when(d0s && d1s) {                        // both high = resync/reset
    idx  := 1
    pass := 0
  } otherwise {
    when(d0rise) {
      when(idx < 2)        { idx := 2 }     // start at D2
        .elsewhen(idx >= 7){ idx := 2 }     // wrap D7 -> D2
        .otherwise         { idx := idx + 1 }
    }
    when(d1rise) { pass := pass + 1 }
  }

  val drive = Bits(8 bits)
  for (i <- 0 until 8) drive(i) := (if (i < 2) False else (idx === U(i, 3 bits)))
  io.dOut      := drive
  io.testIdx   := idx
  io.passCount := pass

  val hb = Reg(UInt(24 bits)) init 0
  hb := hb + 1
  io.led := hb.msb ## d0s ## d1s ## idx.asBits(2 downto 0)   // led5=hb, 4=d0, 3=d1, 2..0=idx
}

/** Top wrapper: wires D0/D1 as inputs and D2..D7 as one-hot outputs over the i80 pad
  * bus, reusing the tang20k_i80 pin map (same harness as the host build). */
case class TopTang20kI80Handshake() extends Component {
  setDefinitionName("top_tang20k_i80_hs")
  noIoPrefix()

  val I_clk    = in  Bool()
  val O_led    = out Bits(6 bits)
  val IO_i80_d = inout(Analog(Bits(8 bits)))

  val core = new ClockingArea(ClockDomain(clock = I_clk,
      config = ClockDomainConfig(resetKind = BOOT))) {
    val walker = HandshakeWalker()
    // per-bit tri-state: D0/D1 are inputs (hi-Z, read .O), D2..D7 drive one-hot.
    val iobuf = Seq.tabulate(8) { i =>
      val buf = GowinIobuf()
      if (i < 2) { buf.I := False;            buf.OEN := True }   // input: high-Z
      else       { buf.I := walker.io.dOut(i); buf.OEN := False } // output: drive
      buf
    }
    for (i <- 0 until 8) iobuf(i).IO <> IO_i80_d(i)
    walker.io.d0 := iobuf(0).O
    walker.io.d1 := iobuf(1).O
  }
  O_led := core.walker.io.led
}

object TopTang20kI80HandshakeVerilog extends App {
  Config.spinal.generateVerilog(TopTang20kI80Handshake())
}
