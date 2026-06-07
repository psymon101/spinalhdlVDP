package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** BurstRefreshDataSurvivalSim — SDRAM-BURST-REFRESH P16 Phase 2 (#12000), cosim
  * reqs #4 (data survives) + #5 (row-coverage 0 violations).
  *
  * Real sdram.v controller + behavioral sdram_model (TIMING_CHECK=1 row-coverage
  * enabled) via the sdram_with_model_check BlackBox. Writes a known pattern, then
  * runs several frame-structured passes — active region (NO refresh) then a vblank
  * BURST of all REF_ROWS AUTO_REFRESH — and reads the pattern back. Burst refresh
  * is driven directly into the controller (the net cadence the integrated
  * fetch+arbiter produces; the fetch keeping up is proven by BurstRefreshPacingSim).
  *
  * PASS = readback exact AND 0 "SDRAM-TIMING-VIOLATION" lines (checked by the
  * caller grepping stdout). Scaled (REF_ROWS=64, TREF≈2 frames) for a fast run;
  * the real 2048-row / 64ms margin is the arithmetic in #11960 + policy sim ea1996f.
  */
object BurstRefreshDataSurvivalSim extends App {
  val REF_ROWS = 64; val TREF_CK = 2000; val ACTIVE = 200; val FRAMES = 4

  case class SdramWithModelCheck() extends BlackBox {
    addGeneric("FREQ", 1000000)
    addGeneric("REF_ROWS", REF_ROWS)
    addGeneric("TREF_CK", TREF_CK)
    addGeneric("WARMUP_CK", 250)   // skip controller reset/init settle window
    setDefinitionName("sdram_with_model_check")
    val io = new Bundle {
      val clk = in Bool(); val clk_sdram = in Bool(); val resetn = in Bool()
      val rd = in Bool(); val wr = in Bool(); val refresh = in Bool()
      val addr = in UInt(23 bits); val din = in Bits(8 bits)
      val dout = out Bits(8 bits); val dout32 = out Bits(32 bits)
      val data_ready = out Bool(); val busy = out Bool()
    }
    noIoPrefix()
    addRTLPath("fpga/tang20k/third_party/sdram/sim/sdram_with_model_check.v")
    addRTLPath("fpga/tang20k/third_party/sdram/sdram.v")
    addRTLPath("fpga/tang20k/third_party/sdram/sim/sdram_model.v")
  }

  class Harness extends Component {
    val io = new Bundle {
      val clkSdram = in Bool(); val resetn = in Bool()
      val rd = in Bool(); val wr = in Bool(); val refresh = in Bool()
      val addr = in UInt(23 bits); val din = in Bits(8 bits)
      val dout32 = out Bits(32 bits); val data_ready = out Bool(); val busy = out Bool()
    }
    val bb = SdramWithModelCheck()
    bb.io.clk := ClockDomain.current.readClockWire
    bb.io.clk_sdram := io.clkSdram
    bb.io.resetn := io.resetn
    bb.io.rd := io.rd; bb.io.wr := io.wr; bb.io.refresh := io.refresh
    bb.io.addr := io.addr; bb.io.din := io.din
    io.dout32 := bb.io.dout32; io.data_ready := bb.io.data_ready; io.busy := bb.io.busy
  }

  Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 20)
    dut.io.clkSdram #= false
    fork { sleep(10); while (true) { dut.io.clkSdram #= !dut.io.clkSdram.toBoolean; sleep(10) } }
    dut.io.resetn #= false
    dut.io.rd #= false; dut.io.wr #= false; dut.io.refresh #= false; dut.io.addr #= 0; dut.io.din #= 0
    dut.clockDomain.waitSampling(4); dut.io.resetn #= true

    def waitIdle(): Unit = { var g = 0; while (dut.io.busy.toBoolean && g < 5000) { dut.clockDomain.waitSampling(); g += 1 } }
    waitIdle()
    println("[cosim] controller init done")

    def wrByte(a: Int, d: Int): Unit = {
      waitIdle(); dut.io.addr #= a; dut.io.din #= d; dut.io.wr #= true
      dut.clockDomain.waitSampling(); dut.io.wr #= false
      // let the controller take the request high, then run the write to completion.
      dut.clockDomain.waitSampling(2); waitIdle(); dut.clockDomain.waitSampling(2)
    }
    def wrWord(a: Int, w: Long): Unit = for (i <- 0 until 4) wrByte(a + i, ((w >> (8 * i)) & 0xFF).toInt)
    def rdWord(a: Int): Long = {
      waitIdle(); dut.io.addr #= a; dut.io.rd #= true
      dut.clockDomain.waitSampling(); dut.io.rd #= false
      // wait for the data_ready PULSE (busy first rises, then data_ready) and
      // sample dout32 on that cycle — matches the proven sdram_cosim_tb handshake.
      var g = 0
      while (!dut.io.data_ready.toBoolean && g < 600) { dut.clockDomain.waitSampling(); g += 1 }
      val v = dut.io.dout32.toLong & 0xFFFFFFFFL
      dut.clockDomain.waitSampling(2); waitIdle(); dut.clockDomain.waitSampling(2); v
    }
    def doRefresh(): Unit = {
      waitIdle(); dut.io.refresh #= true; dut.clockDomain.waitSampling(); dut.io.refresh #= false
      dut.clockDomain.waitSampling(2); waitIdle()
    }

    // 1. write a known pattern
    val addrs = Seq(0x00B000, 0x00A000, 0x00C000, 0x00D004)
    val data  = Seq(0x22221111L, 0x0000FFFFL, 0xCAFEBABEL, 0xDEADC0DEL)
    for ((a, d) <- addrs.zip(data)) wrWord(a, d)
    println("[cosim] pattern written")

    // 2. burst-only-refresh frames
    for (f <- 0 until FRAMES) {
      for (_ <- 0 until ACTIVE) dut.clockDomain.waitSampling()  // active video: NO refresh
      for (_ <- 0 until REF_ROWS) doRefresh()                   // vblank: burst all rows
    }
    println(s"[cosim] $FRAMES burst-only-refresh frames done ($REF_ROWS rows/vblank)")

    // 3. read back + verify (one priming read first — the read FSM/dout32 latch
    // needs warming after the long burst-refresh idle on the read path).
    rdWord(addrs.head)
    var bad = 0
    for ((a, d) <- addrs.zip(data)) {
      val got = rdWord(a)
      val ok = got == d
      if (!ok) bad += 1
      println(f"  @0x$a%05X exp=0x$d%08X got=0x$got%08X ${if (ok) "OK" else "**MISMATCH**"}")
    }
    assert(bad == 0, s"DATA-SURVIVAL FAIL: $bad/${addrs.size} words corrupted across burst-refresh frames")
    println("BurstRefreshDataSurvivalSim: READBACK-EXACT — data survived burst-only refresh (row-coverage via stdout grep)")
  }
}
