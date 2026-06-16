package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** VDP-SOFT-RESET-135 #3 — SDRAM-model cosim: proves the SdramZeroFill engine
  * actually zeroes real SDRAM cells THROUGH the real controller (sdram.v +
  * sdram_model.v), with interleaved refresh, and leaves untouched cells intact.
  *
  * Sequence: init controller → preset region cells + one untouched cell to
  * non-zero (real writes) → read back to confirm preset → pulse fillStart →
  * wait fillDone → read region cells (expect 0) + untouched cell (expect
  * unchanged). This is the read-back-0 proof TopazCliff/CyanPeak require.
  * The VdpTop controller↔fill handshake (busy holds/clears) is proven in
  * SoftResetHandshakeSim; the FSM coverage/refresh/re-arm in SdramZeroFillSim.
  */
object SdramZeroFillCoSim extends App {
  class Harness extends Component {
    val io = new Bundle {
      val clkSdram   = in  Bool()
      val resetn     = in  Bool()
      val testRd     = in  Bool()
      val testWr     = in  Bool()
      val testAddr   = in  UInt(23 bits)
      val testDin    = in  Bits(8 bits)
      val fillStart  = in  Bool()
      val regBase    = in  UInt(23 bits)
      val regRowBytes= in  UInt(16 bits)
      val regRowCount= in  UInt(11 bits)
      val regEnable  = in  Bool()
      val fillActive = out Bool()
      val fillDone   = out Bool()
      val dout32     = out Bits(32 bits)
      val data_ready = out Bool()
      val busy       = out Bool()
    }
    val bb   = SdramWithModel()
    val fill = SdramZeroFill(addrWidth = 23, regionCount = 1, refreshInterval = 8)
    bb.io.clk       := ClockDomain.current.readClockWire
    bb.io.clk_sdram := io.clkSdram
    bb.io.resetn    := io.resetn
    fill.io.start             := io.fillStart
    fill.io.regions(0).base     := io.regBase
    fill.io.regions(0).rowBytes := io.regRowBytes
    fill.io.regions(0).rowCount := io.regRowCount
    fill.io.regions(0).enable   := io.regEnable
    fill.io.ctrlBusy := bb.io.busy
    // command mux: fill owns the bus when active, else the sim's test driver.
    bb.io.rd       := Mux(fill.io.active, False, io.testRd)
    bb.io.wr       := Mux(fill.io.active, fill.io.wr, io.testWr)
    bb.io.refresh  := Mux(fill.io.active, fill.io.refresh, False)
    bb.io.addr     := Mux(fill.io.active, fill.io.addr, io.testAddr)
    bb.io.din      := Mux(fill.io.active, fill.io.din, io.testDin)
    bb.io.burstLen := U(1, 4 bits)
    io.fillActive := fill.io.active
    io.fillDone   := fill.io.done
    io.dout32     := bb.io.dout32
    io.data_ready := bb.io.data_ready
    io.busy       := bb.io.busy
  }

  Config.sim.addSimulatorFlag("-Wno-CASEX").addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .compile(new Harness()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 20)
    dut.io.clkSdram #= false
    fork { sleep(10); while (true) { dut.io.clkSdram #= !dut.io.clkSdram.toBoolean; sleep(10) } }

    def waitNotBusy(max: Int = 2000): Boolean = { var i = 0; while (dut.io.busy.toBoolean && i < max) { dut.clockDomain.waitSampling(); i += 1 }; !dut.io.busy.toBoolean }
    // NB: after a wr/rd pulse, busy RISES a cycle or two later — so settle
    // (waitSampling(2)) BEFORE waitNotBusy, else we'd observe stale busy=0 and
    // race the operation. Matches the proven BurstRefreshDataSurvivalSim handshake.
    def writeByte(addr: Int, data: Int): Unit = {
      waitNotBusy(); dut.io.testAddr #= addr; dut.io.testDin #= data & 0xFF; dut.io.testWr #= true
      dut.clockDomain.waitSampling(); dut.io.testWr #= false
      dut.clockDomain.waitSampling(2); waitNotBusy(); dut.clockDomain.waitSampling(2)
    }
    def readWord(addr: Int): Long = {
      waitNotBusy(); dut.io.testAddr #= addr; dut.io.testRd #= true
      dut.clockDomain.waitSampling(); dut.io.testRd #= false
      var i = 0; while (!dut.io.data_ready.toBoolean && i < 600) { dut.clockDomain.waitSampling(); i += 1 }
      val v = dut.io.dout32.toLong & 0xFFFFFFFFL
      dut.clockDomain.waitSampling(2); waitNotBusy(); dut.clockDomain.waitSampling(2); v
    }

    dut.io.testRd #= false; dut.io.testWr #= false; dut.io.testAddr #= 0; dut.io.testDin #= 0
    dut.io.fillStart #= false; dut.io.regEnable #= false
    dut.io.regBase #= 0x100; dut.io.regRowBytes #= 8; dut.io.regRowCount #= 4   // [0x100,0x120): 32 bytes
    dut.io.resetn #= false
    dut.clockDomain.waitSampling(4); dut.io.resetn #= true
    if (!waitNotBusy(8000)) { println("[sim] FAIL: controller never left init"); simSuccess() }

    var fail = false
    // Preset: fill region words + one untouched word (0x200) to non-zero.
    val regWords     = List(0x100, 0x104, 0x108, 0x11C)   // inside [0x100,0x120)
    val untouchedW   = 0x200
    for (a <- regWords) { writeByte(a, 0xAA); writeByte(a+1, 0xBB); writeByte(a+2, 0xCC); writeByte(a+3, 0xDD) }
    writeByte(untouchedW, 0x11); writeByte(untouchedW+1, 0x22); writeByte(untouchedW+2, 0x33); writeByte(untouchedW+3, 0x44)
    // confirm preset took (priming read first — the read FSM/dout32 latch needs
    // one warm-up read after a write burst before it returns settled data).
    readWord(regWords.head)
    for (a <- regWords) if (readWord(a) == 0) { println(f"[sim] FAIL: preset of 0x$a%X did not take"); fail = true }
    val untouchedBefore = readWord(untouchedW)
    if (untouchedBefore == 0) { println("[sim] FAIL: untouched preset did not take"); fail = true }

    // Trigger the occupied-region zero-fill.
    dut.io.regEnable #= true
    dut.io.fillStart #= true
    var g = 0; while (!dut.io.fillDone.toBoolean && g < 200000) { dut.clockDomain.waitSampling(); g += 1 }
    if (!dut.io.fillDone.toBoolean) { println("[sim] FAIL: fillDone never asserted (deadlock)"); fail = true }
    dut.io.fillStart #= false
    var g2 = 0; while (dut.io.fillActive.toBoolean && g2 < 200) { dut.clockDomain.waitSampling(); g2 += 1 }
    if (dut.io.fillActive.toBoolean) { println("[sim] FAIL: fillActive stuck after start deassert"); fail = true }

    // Verify: region words read 0, untouched word unchanged (priming read first).
    readWord(regWords.head)
    for (a <- regWords) { val v = readWord(a); if (v != 0) { println(f"[sim] FAIL: region 0x$a%X = 0x$v%08X after fill (expected 0)"); fail = true } }
    val untouchedAfter = readWord(untouchedW)
    if (untouchedAfter != untouchedBefore) { println(f"[sim] FAIL: untouched 0x$untouchedW%X changed 0x$untouchedBefore%08X -> 0x$untouchedAfter%08X"); fail = true }

    println(f"[sim] fill cycles=$g, untouched before/after=0x$untouchedBefore%08X/0x$untouchedAfter%08X")
    println(if (fail) "[sim] SdramZeroFillCoSim: FAIL"
            else "[sim] SdramZeroFillCoSim: PASS — occupied region zeroed in real SDRAM through the controller; untouched cells survive")
  }
}
