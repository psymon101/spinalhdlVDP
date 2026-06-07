package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** SdramArbiterBurstSim — proves the SdramArbiter opt-in burst-refresh path
  * (burstRefresh=true) elaborates and routes BurstRefreshController.refreshDue:
  * suppressed during active video, bursted during vblank. (SDRAM-BURST-REFRESH
  * P16 integration regression; the controller itself is proven by BurstRefreshSim.)
  */
object SdramArbiterBurstSim extends App {
  val BURST = 4; val PERIOD = 2; val WDOG = 500
  Config.sim.compile(SdramArbiter(
    clientCount = 2, burstRefresh = true,
    burstRefreshCount = BURST, burstPeriodCycles = PERIOD, burstWatchdogCycles = WDOG
  )).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.grantClientId #= 0; dut.io.slotValid #= false; dut.io.grant #= false
    for (i <- 0 until 2) { dut.io.clientRd(i) #= false; dut.io.clientWr(i) #= false; dut.io.clientAddr(i) #= 0; dut.io.clientDin(i) #= 0 }
    dut.io.vblankActive #= false
    dut.clockDomain.waitSampling(3)

    def run(active: Boolean, cycles: Int): Int = {
      dut.io.vblankActive #= active
      var c = 0
      for (_ <- 0 until cycles) { dut.clockDomain.waitSampling(); if (dut.io.refreshDue.toBoolean) c += 1 }
      c
    }

    var fail = false
    def check(n: String, ok: Boolean): Unit = { println(s"  ${if (ok) "PASS" else "FAIL"}  $n"); if (!ok) fail = true }

    println("=== SdramArbiterBurstSim (burstRefresh=true routing) ===")
    val a = run(active = false, 200)
    check(s"active video: refreshDue=$a, expect 0", a == 0)
    val b = run(active = true, BURST * PERIOD + 10)
    check(s"vblank burst: refreshDue=$b, expect $BURST", b == BURST)

    assert(!fail, "SdramArbiterBurstSim: FAIL")
    println("SdramArbiterBurstSim: PASS — arbiter routes burst refresh correctly")
  }
}
