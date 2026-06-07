package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** BurstRefreshSim — checkpoint for BurstRefreshController (SDRAM-BURST-REFRESH
  * P16). Proves: refreshDue is SUPPRESSED during active video, BURSTED exactly
  * `burstCount` times (paced `periodCycles` apart) during vblank, and the
  * frame-scale watchdog fires if a vblank is missed. Scaled for a fast run.
  */
object BurstRefreshSim extends App {
  val BURST = 8; val PERIOD = 4; val WDOG = 300
  Config.sim.compile(BurstRefreshController(burstCount = BURST, periodCycles = PERIOD, watchdogCycles = WDOG)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.vblankActive #= false
    dut.clockDomain.waitSampling(3)

    def run(active: Boolean, cycles: Int): (Int, Seq[Int]) = {
      dut.io.vblankActive #= active
      var count = 0; val gaps = scala.collection.mutable.ArrayBuffer[Int](); var since = 0
      for (_ <- 0 until cycles) {
        dut.clockDomain.waitSampling()
        since += 1
        if (dut.io.refreshDue.toBoolean) { count += 1; gaps += since; since = 0 }
      }
      (count, gaps.toSeq)
    }

    var fail = false
    def check(name: String, ok: Boolean): Unit = {
      println(s"  ${if (ok) "PASS" else "FAIL"}  $name"); if (!ok) fail = true
    }

    println(s"=== BurstRefreshController checkpoint (burst=$BURST period=$PERIOD wdog=$WDOG) ===")

    // Phase A — active video, shorter than watchdog: expect ZERO refresh.
    val (a, _) = run(active = false, 250)
    check(s"active video (250cyc < wdog): refreshDue=$a, expect 0", a == 0)

    // Phase B — vblank: expect exactly BURST pulses, ~PERIOD apart.
    val (b, gaps) = run(active = true, BURST * PERIOD + 20)
    check(s"vblank burst: refreshDue=$b, expect $BURST", b == BURST)
    val pacedOk = gaps.drop(1).forall(_ == PERIOD)   // gaps after the first are the steady-state pace
    check(s"burst paced $PERIOD cyc apart (gaps=${gaps.mkString(",")})", pacedOk)

    // Phase C — back to active, below watchdog: expect ZERO (burst done).
    val (c, _) = run(active = false, 100)
    check(s"active after burst (100cyc): refreshDue=$c, expect 0", c == 0)

    // Phase D — active past the watchdog: expect the failsafe to fire.
    val (d, _) = run(active = false, WDOG + 50)
    check(s"watchdog failsafe past $WDOG cyc idle: refreshDue=$d, expect >=1", d >= 1)

    println("")
    assert(!fail, "BurstRefreshSim: FAIL")
    println("BurstRefreshSim: PASS — suppressed in active, bursts in vblank, watchdog backstops")
  }
}
