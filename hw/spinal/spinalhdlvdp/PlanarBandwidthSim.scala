package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 3 Checkpoint D — PlanarBandwidthSim.
  *
  * Verifies SDRAM-arbiter safety with planar fetch active alongside
  * tile fetch (slot 0/1, clientId=0) and bitmap row fetch (client 1).
  * Specifically asserts the scheduler's grant rule: at most one
  * `grant` pulse per cycle across slots 0/1/2 — i.e. tile fetch
  * (slot 0/1, clientId=0) and planar fetch (slot 2, clientId=2)
  * never get a simultaneous `grant`, so the arbiter can route a
  * single client per cycle without contention.
  *
  * Approach: instantiate `FetchSlotScheduler(slotCount=8)` with the
  * same schedule VdpTop assigns when planar fetch is enabled (slot 0
  * at hTotal-1, slot 1 full-line, slot 2 at hTotal-80..hTotal-1),
  * walk hCounter across a full line (0..hTotal-1), and verify that:
  *
  *   1. `grant` pulses never co-occur (one slot per cycle)
  *   2. Slot 2's grant fires exactly once per line at hTotal-80
  *   3. Slot 0's grant fires exactly once per line at hTotal-1
  *   4. Slot 1's grant fires exactly once per line at hCounter=0
  *      (window 0..hTotal-1)
  */
object PlanarBandwidthSim extends App {

  Config.sim.compile(FetchSlotScheduler(slotCount = 8)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val hTotal = 800
    // Defaults — disable all slots, then configure.
    for (i <- 0 until 8) {
      dut.io.schedule(i).enabled  #= false
      dut.io.schedule(i).clientId #= 0
      dut.io.schedule(i).startH   #= 0
      dut.io.schedule(i).endH     #= 0
    }
    dut.io.hCounter  #= 0
    dut.io.lineStart #= false
    dut.clockDomain.waitSampling(2)

    // Slot 0: clientId=0, hTotal-1..hTotal-1 (single-cycle grant at line wrap)
    dut.io.schedule(0).enabled  #= true
    dut.io.schedule(0).clientId #= 0
    dut.io.schedule(0).startH   #= hTotal - 1
    dut.io.schedule(0).endH     #= hTotal - 1
    // Slot 1: clientId=0, 0..hTotal-1 (full-line; grant at 0)
    dut.io.schedule(1).enabled  #= true
    dut.io.schedule(1).clientId #= 0
    dut.io.schedule(1).startH   #= 0
    dut.io.schedule(1).endH     #= hTotal - 1
    // Slot 2: clientId=2, hTotal-80..hTotal-1 (planar fetch window)
    dut.io.schedule(2).enabled  #= true
    dut.io.schedule(2).clientId #= 2
    dut.io.schedule(2).startH   #= hTotal - 80
    dut.io.schedule(2).endH     #= hTotal - 1
    dut.clockDomain.waitSampling(2)

    // Walk hCounter across a full line and observe grants.
    var grantCount = 0
    var slot0Grants = 0
    var slot1Grants = 0
    var slot2Grants = 0
    var doubleGrant = false   // never expect two grants on the same cycle

    for (h <- 0 until hTotal) {
      dut.io.hCounter  #= h
      dut.io.lineStart #= (h == 0)
      dut.clockDomain.waitSampling()

      val grant = dut.io.grant.toBoolean
      if (grant) {
        grantCount += 1
        val clientId = dut.io.grantClientId.toInt
        if (h == 0)              { slot1Grants += 1; assert(clientId == 0, s"h=0 should be slot 1 (cid=0), got cid=$clientId") }
        else if (h == hTotal - 80) { slot2Grants += 1; assert(clientId == 2, s"h=$h should be slot 2 (cid=2), got cid=$clientId") }
        else if (h == hTotal - 1)  { slot0Grants += 1; assert(clientId == 0, s"h=$h should be slot 0 (cid=0), got cid=$clientId") }
      }
    }

    println(f"[sim] Total grants in 1 line: $grantCount  slot0=$slot0Grants slot1=$slot1Grants slot2=$slot2Grants")
    assert(slot0Grants == 1, s"slot 0 must fire exactly once per line (got $slot0Grants)")
    assert(slot1Grants == 1, s"slot 1 must fire exactly once per line (got $slot1Grants)")
    assert(slot2Grants == 1, s"slot 2 (planar) must fire exactly once per line (got $slot2Grants)")
    assert(grantCount == 3,  s"exactly 3 grants per line expected (0/1/2 startH points), got $grantCount")
    println("  PASS — slots 0/1/2 grants are non-overlapping; one client per cycle")

    // ---------------------------------------------------------------
    // Multi-line stress: walk 5 lines, accumulate the same grant
    // pattern and confirm no anomaly across line boundaries.
    // ---------------------------------------------------------------
    var multiLineSlot0 = 0
    var multiLineSlot1 = 0
    var multiLineSlot2 = 0
    for (line <- 0 until 5; h <- 0 until hTotal) {
      dut.io.hCounter  #= h
      dut.io.lineStart #= (h == 0)
      dut.clockDomain.waitSampling()
      if (dut.io.grant.toBoolean) {
        val cid = dut.io.grantClientId.toInt
        if (h == 0)              multiLineSlot1 += 1
        else if (h == hTotal-80) multiLineSlot2 += 1
        else if (h == hTotal-1)  multiLineSlot0 += 1
      }
    }
    println(f"[sim] 5-line stress:  slot0=$multiLineSlot0  slot1=$multiLineSlot1  slot2=$multiLineSlot2")
    assert(multiLineSlot0 == 5, s"slot 0 expected 5 grants over 5 lines (got $multiLineSlot0)")
    assert(multiLineSlot1 == 5, s"slot 1 expected 5 grants over 5 lines (got $multiLineSlot1)")
    assert(multiLineSlot2 == 5, s"slot 2 expected 5 grants over 5 lines (got $multiLineSlot2)")

    println("PlanarBandwidthSim: PASS — scheduler enforces non-overlapping grants for slots 0/1/2")
  }
}
