package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** R1 Raster Trigger Unit validation sim.
  *
  * Covers every bullet in TASK_R1_RASTER_TRIGGER_UNIT.md validation plan:
  *   - trigger fires on programmed line
  *   - no trigger on adjacent lines
  *   - sticky status sets on match
  *   - sticky status clears only through clear/ack
  *   - no repeated trigger within the same programmed event window
  *   - pixel-position compare exact behavior when enabled
  */
object RasterTriggerUnitSim extends App {
  Config.sim.compile(RasterTriggerUnit()).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Raster walk helpers
    val hTotal = 800
    def step(n: Int = 1): Unit = for (_ <- 0 until n) dut.clockDomain.waitSampling()
    def setBeam(x: Int, y: Int): Unit = {
      dut.io.hCounter #= x
      dut.io.vCounter #= y
    }

    // Defaults
    dut.io.hCounter       #= 0
    dut.io.vCounter       #= 0
    dut.io.triggerLine    #= 240
    dut.io.triggerPixel   #= 0
    dut.io.pixelCmpEnable #= false
    dut.io.enable         #= true
    dut.io.clear          #= false
    step(2)

    // --- Case 1: trigger fires on programmed line (240), line-only compare ---
    var sawPulse = 0
    // Walk hCounter across line 239 (no fire expected)
    setBeam(0, 239); step()
    for (x <- 0 until hTotal) {
      setBeam(x, 239); step()
      if (dut.io.triggerPulse.toBoolean) sawPulse += 1
    }
    assert(sawPulse == 0, s"trigger fired on adjacent line 239: count=$sawPulse")
    assert(!dut.io.pending.toBoolean, "pending latched on non-matching line 239")

    // Walk hCounter across line 240 — expect exactly one pulse at hCounter=0
    sawPulse = 0
    setBeam(0, 240); step()
    if (dut.io.triggerPulse.toBoolean) sawPulse += 1
    for (x <- 1 until hTotal) {
      setBeam(x, 240); step()
      if (dut.io.triggerPulse.toBoolean) sawPulse += 1
    }
    assert(sawPulse == 1, s"line-only compare: expected exactly 1 pulse on line 240, got $sawPulse")
    assert(dut.io.pending.toBoolean, "pending did not latch after match on line 240")
    println("[sim] line-only compare: 1 pulse on line 240, 0 on adjacent line 239 — OK")

    // --- Case 2: pending stays until clear/ack ---
    // Advance many lines; pending should remain set because no clear
    for (y <- 241 until 260) {
      setBeam(0, y); step(4)
    }
    assert(dut.io.pending.toBoolean, "pending cleared without clear/ack")

    // Now ack
    dut.io.clear #= true; step(); dut.io.clear #= false; step()
    assert(!dut.io.pending.toBoolean, "pending did not clear on clear/ack")
    println("[sim] sticky status clears only through clear/ack — OK")

    // --- Case 3: no re-trigger while beam is stuck at the match coordinate ---
    // Hold the exact match cell (hCounter=0, vCounter=240) for many cycles
    // and confirm exactly one pulse regardless of how long the condition
    // holds. This isolates the edge-detection from raster-advance behavior.
    setBeam(0, 239); step(2)       // leave match to reset edge state
    sawPulse = 0
    setBeam(0, 240); step()         // (re-entry: should fire once)
    if (dut.io.triggerPulse.toBoolean) sawPulse += 1
    // Beam parked at (0, 240) for many more cycles — match_ stays True, but
    // the edge detect must not let any further pulses through.
    for (_ <- 0 until 50) {
      step()
      if (dut.io.triggerPulse.toBoolean) sawPulse += 1
    }
    assert(sawPulse == 1, s"beam stuck at match cell produced $sawPulse pulses (expected 1)")
    println("[sim] single-pulse-per-event (stuck at match) — OK")

    // Raster re-entry IS a new event by design: leave and come back produces
    // a fresh pulse, matching per-frame raster-IRQ semantics.
    setBeam(0, 241); step(2)
    sawPulse = 0
    setBeam(0, 240); step()
    if (dut.io.triggerPulse.toBoolean) sawPulse += 1
    assert(sawPulse == 1, s"re-entry after leaving line 240: expected 1 pulse, got $sawPulse")
    println("[sim] raster re-entry fires a new pulse (per-frame IRQ behavior) — OK")

    // --- Case 4: pixel-position compare exact behavior ---
    dut.io.pixelCmpEnable #= true
    dut.io.triggerPixel   #= 320
    dut.io.clear          #= true; step(); dut.io.clear #= false; step()
    // Walk line 240 with pixel compare enabled; expect exactly 1 pulse at x=320
    var pulseAt = -1
    for (x <- 0 until hTotal) {
      setBeam(x, 240); step()
      if (dut.io.triggerPulse.toBoolean) {
        if (pulseAt >= 0) assert(false, s"pixel compare: extra pulse at x=$x (already fired at $pulseAt)")
        pulseAt = x
      }
    }
    assert(pulseAt == 320, s"pixel compare: expected pulse at x=320, got x=$pulseAt")
    println("[sim] pixel-position compare (line=240, pixel=320) fires exactly once at (320, 240) — OK")

    // --- Case 5: enable=False suppresses triggers ---
    dut.io.clear #= true; step(); dut.io.clear #= false; step()
    dut.io.enable #= false
    sawPulse = 0
    setBeam(0, 239); step(2)
    for (x <- 0 until hTotal) {
      setBeam(x, 240); step()
      if (dut.io.triggerPulse.toBoolean) sawPulse += 1
    }
    assert(sawPulse == 0, s"enable=False allowed $sawPulse pulses")
    assert(!dut.io.pending.toBoolean, "enable=False allowed pending latch")
    println("[sim] enable=False suppresses trigger and pending — OK")

    println("[sim] RasterTriggerUnitSim: PASS")
  }
}
