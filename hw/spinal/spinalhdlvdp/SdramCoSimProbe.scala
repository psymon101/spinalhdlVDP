package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** CP-A4 (#11446) — Verilator-tristate feasibility probe for the handshake-proof
  * co-sim (approach A). Instantiates the logic-side wrapper (real sdram.v +
  * sdram_model, tristate hidden internally) as a SpinalHDL BlackBox and just runs
  * init: if Verilator COMPILES the wrapper and the controller leaves init (busy
  * clears), approach A is viable and I build the full bridge+arbiter handshake on it.
  * If Verilator rejects the internal tristate, fall back to approach C (iverilog).
  */
case class SdramWithModel(freq: Int = 1000000) extends BlackBox {
  addGeneric("FREQ", freq)
  setDefinitionName("sdram_with_model")
  val io = new Bundle {
    val clk        = in  Bool()
    val clk_sdram  = in  Bool()
    val resetn     = in  Bool()
    val rd         = in  Bool()
    val wr         = in  Bool()
    val refresh    = in  Bool()
    val addr       = in  UInt(23 bits)
    val din        = in  Bits(8 bits)
    val dout       = out Bits(8 bits)
    val dout32     = out Bits(32 bits)
    val data_ready = out Bool()
    val busy       = out Bool()
  }
  noIoPrefix()
  addRTLPath("fpga/tang20k/third_party/sdram/sim/sdram_with_model.v")
  addRTLPath("fpga/tang20k/third_party/sdram/sdram.v")
  addRTLPath("fpga/tang20k/third_party/sdram/sim/sdram_model.v")
}

object SdramCoSimProbe extends App {
  class Harness extends Component {
    val io = new Bundle {
      val clkSdram   = in  Bool()
      val resetn     = in  Bool()
      val rd         = in  Bool()
      val wr         = in  Bool()
      val refresh    = in  Bool()
      val addr       = in  UInt(23 bits)
      val din        = in  Bits(8 bits)
      val dout32     = out Bits(32 bits)
      val data_ready = out Bool()
      val busy       = out Bool()
    }
    val bb = SdramWithModel()
    bb.io.clk       := ClockDomain.current.readClockWire
    bb.io.clk_sdram := io.clkSdram
    bb.io.resetn    := io.resetn
    bb.io.rd        := io.rd
    bb.io.wr        := io.wr
    bb.io.refresh   := io.refresh
    bb.io.addr      := io.addr
    bb.io.din       := io.din
    io.dout32     := bb.io.dout32
    io.data_ready := bb.io.data_ready
    io.busy       := bb.io.busy
  }

  // sdram.v uses casex + has incomplete case coverage — Verilator treats these lint
  // warnings as fatal by default. They are benign in the proven third-party controller;
  // silence them (don't edit third-party RTL) so Verilator compiles the co-sim.
  Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 20)
    // clk_sdram 180-deg from clk.
    dut.io.clkSdram #= false
    fork { sleep(10); while (true) { dut.io.clkSdram #= !dut.io.clkSdram.toBoolean; sleep(10) } }
    dut.io.resetn #= false
    dut.io.rd #= false; dut.io.wr #= false; dut.io.refresh #= false
    dut.io.addr #= 0; dut.io.din #= 0
    dut.clockDomain.waitSampling(4)
    dut.io.resetn #= true

    // Wait for init/config to complete (busy clears).
    var i = 0
    while (dut.io.busy.toBoolean && i < 5000) { dut.clockDomain.waitSampling(); i += 1 }
    if (dut.io.busy.toBoolean) {
      println("[probe] FAIL: controller never left init (busy stuck)")
    } else {
      println(s"[probe] PASS: Verilator compiled the tristate wrapper; init/config done after $i cycles — approach A viable")
    }
  }
}
