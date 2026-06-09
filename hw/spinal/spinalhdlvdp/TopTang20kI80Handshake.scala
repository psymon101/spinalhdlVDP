package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Handshake-driven pin-continuity walker (lane P21 side-lane, owner-directed).
  * Tests EVERY harness wire — data bus AND control lines — with a 2-wire handshake,
  * no LEDs/camera.
  *
  * Handshake (both ESP->FPGA, proven-good by the test progressing at all):
  *   - D0 = ADVANCE strobe: rising edge moves the test index to the next pin.
  *   - D1 = PASS ack: rising edge bumps a pass counter (visibility).
  *   - D0 AND D1 both high = RESYNC to idle.
  *
  * Two phases, selected by the index (idle=1):
  *   - DATA walk (idx 2..7): FPGA drives D2..D7 high one-hot; ESP reads its matching
  *     pin to confirm continuity (and that ONLY that pin is high => no shorts).
  *   - CTRL echo (idx 8): FPGA mirrors the four control inputs onto the data bus —
  *     D2=CS#, D3=WR#, D4=RD#, D5=DC#. The ESP drives each control and reads the
  *     matching data pin back => control-wire continuity. Wraps 8 -> 2.
  *
  * Direction coverage: D0/D1 (ESP->FPGA, handshake), D2-D7 (FPGA->ESP, walk),
  * CS/WR/RD/DC (ESP->FPGA, echoed back). Every harness wire exercised.
  */
case class HandshakeWalker() extends Component {
  val io = new Bundle {
    val d0  = in  Bool()
    val d1  = in  Bool()
    val cs  = in  Bool()
    val wr  = in  Bool()
    val rd  = in  Bool()
    val dc  = in  Bool()
    val dOut      = out Bits(8 bits)        // drive value for D2..D7 (bits 0,1 unused)
    val testIdx   = out UInt(4 bits)        // 1 idle, 2..7 data walk, 8 ctrl echo
    val passCount = out UInt(4 bits)
    val led       = out Bits(6 bits)
  }
  val d0s = BufferCC(io.d0, False)
  val d1s = BufferCC(io.d1, False)
  val csS = BufferCC(io.cs, False)
  val wrS = BufferCC(io.wr, False)
  val rdS = BufferCC(io.rd, False)
  val dcS = BufferCC(io.dc, False)
  val d0rise = d0s && !RegNext(d0s).init(False)
  val d1rise = d1s && !RegNext(d1s).init(False)

  val idx  = Reg(UInt(4 bits)) init 1
  val pass = Reg(UInt(4 bits)) init 0
  when(d0s && d1s) {                        // both high = resync
    idx  := 1
    pass := 0
  } otherwise {
    when(d0rise) {
      when(idx < 2)        { idx := 2 }     // start data walk at D2
        .elsewhen(idx >= 8){ idx := 2 }     // after CTRL echo, wrap to D2
        .otherwise         { idx := idx + 1 }  // ...7 -> 8 enters CTRL echo
    }
    when(d1rise) { pass := pass + 1 }
  }

  val drive = Bits(8 bits)
  drive := 0
  when(idx === 8) {                          // CTRL echo: mirror controls onto D2..D5
    drive(2) := csS
    drive(3) := wrS
    drive(4) := rdS
    drive(5) := dcS
  } otherwise {                              // DATA walk: one-hot D2..D7
    for (i <- 2 until 8) drive(i) := (idx === U(i, 4 bits))
  }
  io.dOut      := drive
  io.testIdx   := idx
  io.passCount := pass

  val hb = Reg(UInt(24 bits)) init 0
  hb := hb + 1
  io.led := hb.msb ## d0s ## d1s ## idx.asBits(2 downto 0)
}

/** Top wrapper: D0/D1 are hi-Z inputs and D2..D7 are driven over the i80 pad bus;
  * CS/WR/RD/DC come in on their own pads. Reuses the tang20k_i80 harness pins. */
case class TopTang20kI80Handshake() extends Component {
  setDefinitionName("top_tang20k_i80_hs")
  noIoPrefix()

  val I_clk    = in  Bool()
  val O_led    = out Bits(6 bits)
  val IO_i80_d = inout(Analog(Bits(8 bits)))
  val I_i80_cs = in  Bool()
  val I_i80_wr = in  Bool()
  val I_i80_rd = in  Bool()
  val I_i80_dc = in  Bool()

  val core = new ClockingArea(ClockDomain(clock = I_clk,
      config = ClockDomainConfig(resetKind = BOOT))) {
    val walker = HandshakeWalker()
    walker.io.cs := I_i80_cs
    walker.io.wr := I_i80_wr
    walker.io.rd := I_i80_rd
    walker.io.dc := I_i80_dc
    // D0/D1 are inputs (hi-Z), D2..D7 drive the walk/echo value.
    val iobuf = Seq.tabulate(8) { i =>
      val buf = GowinIobuf()
      if (i < 2) { buf.I := False;             buf.OEN := True }
      else       { buf.I := walker.io.dOut(i);  buf.OEN := False }
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
