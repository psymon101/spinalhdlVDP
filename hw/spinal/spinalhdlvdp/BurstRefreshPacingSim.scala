package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** BurstRefreshPacingSim — SDRAM-BURST-REFRESH P16, cosim req #3 (the make-or-break).
  *
  * Drives the REAL SdramTileAttributeFetch refresh path with a vblank-style BURST
  * of refreshDue pulses paced `PERIOD` cycles apart, models the controller's
  * sdramBusy (busy `BUSY_CYC` cycles after each issued command), and counts
  * refreshDue pulses IN vs AUTO_REFRESH (sdramRefresh) pulses OUT. The fetch
  * latches refreshDue into a SINGLE-DEEP refreshPending Bool, so if PERIOD is
  * shorter than the service latency, pulses are DROPPED (out < in). PASS = out==in.
  *
  * The fetch's SDRAM FSM (incl. refresh) lives in the external `sdramCd` domain,
  * so everything here ticks on dut.sdramCd (the pixel-side dut.clockDomain only
  * carries the idle, tied-off fetch-grant inputs). Idle fetch (no fetchGrant) =
  * the vblank case where the SDRAM bus is free for refresh.
  */
object BurstRefreshPacingSim extends App {
  val PERIOD = 24; val BURST = 50; val BUSY_CYC = 8
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    SdramTileAttributeFetch(sdramCd, skipSdramInit = true, runMemtest = false, useExternalRefresh = true)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    dut.io.refreshDue #= false
    dut.io.sdramDout #= 0; dut.io.sdramDout32 #= 0; dut.io.sdramDataReady #= false; dut.io.sdramBusy #= false
    dut.io.fetchGrant #= false; dut.io.fetchSlotValid #= false; dut.io.fetchPreAnnounce #= false
    dut.io.fetchLine #= 0; dut.io.fetchScrollX #= 0; dut.io.fetchScrollY #= 0
    dut.io.pixelAddr #= 0; dut.io.tileDecodeMode #= 0; dut.io.attributeMode #= 0
    dut.sdramCd.waitSampling(5)

    var busyTimer = 0
    var refreshOut = 0
    var prevRefresh = false
    // one SDRAM clock with the behavioural controller-busy model + refresh-out counter.
    def tick(): Unit = {
      dut.sdramCd.waitSampling()
      if (dut.io.sdramRd.toBoolean || dut.io.sdramWr.toBoolean || dut.io.sdramRefresh.toBoolean) busyTimer = BUSY_CYC
      dut.io.sdramBusy #= (busyTimer > 0)
      if (busyTimer > 0) busyTimer -= 1
      val r = dut.io.sdramRefresh.toBoolean
      if (r && !prevRefresh) refreshOut += 1
      prevRefresh = r
    }

    // boot: ~200us SDRAM power-up wait (~8100 cyc @40.5MHz) even with skipSdramInit.
    var guard = 0
    while (!dut.io.bootDone.toBoolean && guard < 20000) { tick(); guard += 1 }
    assert(dut.io.bootDone.toBoolean, s"fetch never reached idle (bootDone) after $guard cycles")
    println(s"[sim] bootDone after ~$guard cycles; fetch idle")
    refreshOut = 0  // ignore any boot-time refresh

    // Burst: BURST refreshDue pulses, PERIOD apart (vblank cadence).
    var refreshIn = 0
    for (_ <- 0 until BURST) {
      dut.io.refreshDue #= true; tick(); dut.io.refreshDue #= false; refreshIn += 1
      for (_ <- 0 until PERIOD - 1) tick()
    }
    for (_ <- 0 until 400) tick()  // drain in-flight refreshes

    println(s"[sim] PACING: refreshDue IN=$refreshIn, AUTO_REFRESH OUT=$refreshOut  (period=$PERIOD cyc, busy model=$BUSY_CYC cyc)")
    assert(refreshOut == refreshIn,
      s"PACING FAIL: only $refreshOut/$refreshIn refreshes serviced — pulses DROPPED at period $PERIOD (single-deep refreshPending)")
    println(s"BurstRefreshPacingSim: PASS — all $refreshIn burst refreshes serviced through the real fetch, 0 dropped at period $PERIOD")
  }
}
