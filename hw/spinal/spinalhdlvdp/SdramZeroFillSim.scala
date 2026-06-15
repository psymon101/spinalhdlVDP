package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** VDP-SOFT-RESET-135 #3 — unit proof of the SdramZeroFill FSM (no SDRAM model;
  * a fake controller drives `ctrlBusy`). Proves the engine logic in isolation:
  *   - every enabled region is fully zeroed: captured write addresses exactly
  *     cover [base, base+rowBytes·rowCount) for each enabled region, nothing else,
  *   - disabled regions are skipped,
  *   - interleaved refresh fires WITHOUT dropping any write,
  *   - busy-gating holds (no write while ctrlBusy),
  *   - done asserts after the sweep, holds until start deasserts, and re-arms.
  * The full SDRAM-model cosim (read-back-0) is the TopTang integration sim.
  */
object SdramZeroFillSim extends App {
  Config.sim.compile(SdramZeroFill(addrWidth = 23, regionCount = 3, refreshInterval = 16)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    // Region 0: [0x100, 0x120)  (rowBytes 8 × rowCount 4 = 32)
    // Region 1: [0x1000,0x1008) (rowBytes 4 × rowCount 2 = 8)
    // Region 2: disabled.
    def setRegion(i: Int, base: Int, rb: Int, rc: Int, en: Boolean): Unit = {
      dut.io.regions(i).base     #= base
      dut.io.regions(i).rowBytes #= rb
      dut.io.regions(i).rowCount #= rc
      dut.io.regions(i).enable   #= en
    }
    setRegion(0, 0x100, 8, 4, true)
    setRegion(1, 0x1000, 4, 2, true)
    setRegion(2, 0x2000, 16, 16, false) // disabled — must NOT be touched
    dut.io.start #= false
    dut.io.ctrlBusy #= false

    val expected = (0x100 until 0x120).toSet ++ (0x1000 until 0x1008).toSet
    val forbidden = (0x2000 until 0x2100).toSet
    val captured = scala.collection.mutable.ArrayBuffer[Int]()
    var refreshPulses = 0
    var wrWhileBusy = 0
    var cyc = 0
    dut.clockDomain.onSamplings {
      // exercise busy-gating: stall the controller every 5th cycle
      dut.io.ctrlBusy #= (cyc % 5 == 4)
      cyc += 1
      if (dut.io.wr.toBoolean) {
        captured += dut.io.addr.toInt
        if (dut.io.ctrlBusy.toBoolean) wrWhileBusy += 1
      }
      if (dut.io.refresh.toBoolean) refreshPulses += 1
    }

    dut.clockDomain.waitSampling(5)
    var fail = false
    if (dut.io.active.toBoolean) { println("[sim] FAIL: active high at rest"); fail = true }

    // trigger
    dut.io.start #= true
    var guard = 0
    while (!dut.io.done.toBoolean && guard < 5000) { dut.clockDomain.waitSampling(); guard += 1 }
    if (!dut.io.done.toBoolean) { println("[sim] FAIL: done never asserted (deadlock)"); fail = true }

    // done must HOLD while start stays high
    dut.clockDomain.waitSampling(20)
    if (!dut.io.done.toBoolean) { println("[sim] FAIL: done did not hold while start high"); fail = true }

    // coverage checks
    val capSet = captured.toSet
    val missing = expected.diff(capSet)
    val touchedForbidden = capSet.intersect(forbidden)
    val extra = capSet.diff(expected)
    if (missing.nonEmpty)         { println(f"[sim] FAIL: ${missing.size} expected cells never written (e.g. 0x${missing.min}%X)"); fail = true }
    if (touchedForbidden.nonEmpty){ println(f"[sim] FAIL: disabled region written (e.g. 0x${touchedForbidden.min}%X)"); fail = true }
    if (extra.nonEmpty)           { println(f"[sim] FAIL: ${extra.size} unexpected addresses written (e.g. 0x${extra.min}%X)"); fail = true }
    if (captured.size != captured.toSet.size) { println("[sim] WARN: some addresses written more than once") }
    if (refreshPulses < 1)        { println("[sim] FAIL: no refresh pulse — interleave missing"); fail = true }
    if (wrWhileBusy != 0)         { println(s"[sim] FAIL: $wrWhileBusy writes issued while ctrlBusy"); fail = true }

    println(f"[sim] writes=${captured.size} (expected ${expected.size}), refreshPulses=$refreshPulses")

    // release start → must return to idle (active low, done low) and re-arm
    dut.io.start #= false
    guard = 0
    while ((dut.io.done.toBoolean || dut.io.active.toBoolean) && guard < 100) { dut.clockDomain.waitSampling(); guard += 1 }
    if (dut.io.active.toBoolean || dut.io.done.toBoolean) { println("[sim] FAIL: did not return to idle after start deassert"); fail = true }

    // re-arm: second run must reproduce identical coverage
    captured.clear()
    dut.io.start #= true
    guard = 0
    while (!dut.io.done.toBoolean && guard < 5000) { dut.clockDomain.waitSampling(); guard += 1 }
    val capSet2 = captured.toSet
    if (capSet2 != expected) { println("[sim] FAIL: re-arm coverage differs from first run"); fail = true }
    dut.io.start #= false
    dut.clockDomain.waitSampling(10)

    println(if (fail) "[sim] SdramZeroFillSim: FAIL"
            else "[sim] SdramZeroFillSim: PASS — exact occupied-region coverage, disabled skipped, refresh interleaves w/o dropping writes, busy-gated, done holds + re-arms")
  }
}
