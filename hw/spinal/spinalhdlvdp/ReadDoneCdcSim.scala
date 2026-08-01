package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Lane qspi-upload-si-hardening option-4 (#14568/#14574) — CDC co-sim for the sel=0x0C `READ_DONE`
  * completion-poll readback handshake.
  *
  * Models the EXACT hardened handoff from TopTang20kHdmi:
  *   - sdram domain (40.5 MHz): `dataReg` set when a read completes, `resultToggle` flipped ONE
  *     sdram cycle later (the marginal source lead that causes the confirmed sel=8 1-read lag).
  *   - pixel domain (25.2 MHz): the hardened `dbgResultPixArea` — 2-cycle-settled latch after the
  *     synchronized toggle edge, `READ_DONE` set after the settled latch and cleared on the arm.
  *
  * Proves LOGICAL correctness: after `arm → complete(value) → poll READ_DONE`, the held word equals
  * the just-completed value (never a stale prior value), and READ_DONE sequences correctly.
  *
  * HONESTY CAVEAT: Verilator models `BufferCC` as ideal 2-FF (no metastability / real-timing margin),
  * so this proves the handshake LOGIC, not the real-HW timing margin — the hardware test at
  * 0x100008/0x101000 is the arbiter of whether the lag is actually eliminated on silicon.
  *
  * Run: sbt "runMain spinalhdlvdp.ReadDoneCdcSim"
  */
object ReadDoneCdcSim extends App {

  class Dut extends Component {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    val io = new Bundle {
      // sdram-domain stimulus: a read completed with `completeVal` (models dbgReadArea capturing dout32)
      val doComplete  = in Bool()
      val completeVal = in Bits(32 bits)
      // pixel-domain (default): the 0x0327 arm write, plus the host-visible outputs
      val arm         = in Bool()
      val readDone    = out Bool()   // sel=0x0C bit0
      val resultWord  = out Bits(32 bits)   // sel=8 data
    }

    // --- sdram domain: mirrors dbgReadArea's dataReg + resultToggle (marginal 1-cycle lead) ---
    val sdramArea = new ClockingArea(sdramCd) {
      val dataReg      = Reg(Bits(32 bits)) init 0
      val resultToggle = Reg(Bool()) init False
      when(io.doComplete) { dataReg := io.completeVal }
      when(RegNext(io.doComplete) init False) { resultToggle := !resultToggle }  // toggle 1 cyc after dataReg
    }

    // --- pixel domain (default): the HARDENED dbgResultPixArea (verbatim logic) ---
    val resultToggleSync = BufferCC(sdramArea.resultToggle, False)
    val resultTogglePrev = RegNext(resultToggleSync) init False
    val dataSync         = BufferCC(sdramArea.dataReg, B(0, 32 bits))
    val edge             = resultToggleSync =/= resultTogglePrev
    val edgeReady        = RegNext(RegNext(edge, False), False)   // 2-cycle settle after edge
    val dbgResultHold    = Reg(Bits(32 bits)) init 0
    when(edgeReady) { dbgResultHold := dataSync }
    val readDone = Reg(Bool()) init False
    when(edgeReady) { readDone := True }
    when(io.arm)    { readDone := False }

    io.readDone   := readDone
    io.resultWord := dbgResultHold
  }

  var pass = true
  Config.sim.compile(new Dut()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 40)   // pixel ~25 MHz
    dut.sdramCd.forkStimulus(period = 24)        // sdram ~40.5 MHz
    dut.io.doComplete #= false
    dut.io.completeVal #= 0
    dut.io.arm #= false
    dut.clockDomain.waitSampling(10)
    dut.sdramCd.waitSampling(10)

    // One armed read: pulse arm (pixel), then complete the read in the sdram domain with `value`,
    // then poll READ_DONE (pixel) and check the held word == value. Mirrors the host sequence.
    def armReadCheck(value: Long, label: String): Unit = {
      // arm (clears READ_DONE)
      dut.io.arm #= true; dut.clockDomain.waitSampling(); dut.io.arm #= false
      dut.clockDomain.waitSampling(3)
      if (dut.io.readDone.toBoolean) { println(s"  [FAIL] $label: READ_DONE not cleared by arm"); pass = false }
      // complete the SDRAM read (sdram domain)
      dut.sdramCd.waitSampling(2)
      dut.io.completeVal #= value
      dut.io.doComplete #= true; dut.sdramCd.waitSampling(); dut.io.doComplete #= false
      // poll READ_DONE until set (pixel domain)
      var g = 0
      while (!dut.io.readDone.toBoolean && g < 200) { dut.clockDomain.waitSampling(); g += 1 }
      val done = dut.io.readDone.toBoolean
      val got  = dut.io.resultWord.toLong & 0xFFFFFFFFL
      val ok   = done && (got == value)
      if (!ok) pass = false
      println(f"    $label%-10s: READ_DONE=$done got=0x$got%08X exp=0x$value%08X pollcyc=$g  ${if (ok) "OK" else "FAIL"}")
    }

    println("=== ReadDoneCdcSim: arm -> complete -> poll READ_DONE -> read (hardened handshake) ===")
    // Deliberately alternate values so a 1-read lag (returning the PRIOR value) would be caught:
    armReadCheck(0x55555555L, "white")
    armReadCheck(0x00000000L, "black")
    armReadCheck(0x55555555L, "white2")
    armReadCheck(0xDEADBEEFL, "sentinel")
    armReadCheck(0x55555555L, "white3")
    // Stale-poll guard: after arm (READ_DONE=0), the host must NOT read until READ_DONE=1.
    dut.io.arm #= true; dut.clockDomain.waitSampling(); dut.io.arm #= false
    dut.clockDomain.waitSampling(2)
    if (dut.io.readDone.toBoolean) { println("  [FAIL] stale-poll guard: READ_DONE high before completion"); pass = false }
    else println("    stale-guard: READ_DONE=false before completion (host correctly waits)  OK")

    println(if (pass) "=== ReadDoneCdcSim: ALL PASS (handshake logically correct; HW is the timing arbiter) ==="
            else       "=== ReadDoneCdcSim: FAIL ===")
  }
  if (!pass) { println("ReadDoneCdcSim FAILED"); sys.exit(1) }
}
