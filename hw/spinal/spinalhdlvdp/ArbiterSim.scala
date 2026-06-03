package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Task 30 Checkpoint A sim — multi-client SdramArbiter correctness.
  *
  * Cases:
  *   1. Single-client (client 0) identity: when grantClientId === 0, the
  *      arbiter's SDRAM outputs equal client(0)'s inputs exactly. This is
  *      the Task 30 baseline bit-identity guarantee.
  *   2. Two-client alternation: grantClientId toggles 0 ↔ 1 across
  *      cycles; SDRAM outputs track whichever client is granted, even
  *      when both clients drive their rd high.
  *   3. Grant fan-out: exactly one clientGrant(i) pulses when grant is
  *      asserted; all others stay low.
  *   4. Slot-valid fan-out: clientSlotValid(i) == slotValid &&
  *      (grantClientId === i).
  */
object ArbiterSim extends App {
  Config.sim.compile(SdramArbiter(clientCount = 4, addrWidth = 23, dataWidth = 8))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize all inputs.
      dut.io.grantClientId #= 0
      dut.io.slotValid     #= false
      dut.io.grant         #= false
      for (i <- 0 until 4) {
        dut.io.clientRd(i)   #= false
        dut.io.clientWr(i)   #= false
        dut.io.clientAddr(i) #= 0
        dut.io.clientDin(i)  #= 0
      }
      dut.clockDomain.waitSampling(3)

      // === Case 1: identity for client 0 ===
      dut.io.clientRd(0)   #= true
      dut.io.clientWr(0)   #= false
      dut.io.clientAddr(0) #= 0x12345
      dut.io.clientDin(0)  #= 0xAB
      dut.io.grantClientId #= 0
      dut.io.slotValid     #= true
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.sdramRd.toBoolean, "case1: sdramRd must pass through client 0")
      assert(!dut.io.sdramWr.toBoolean, "case1: sdramWr must match client 0")
      assert(dut.io.sdramAddr.toBigInt == 0x12345, s"case1: addr got 0x${dut.io.sdramAddr.toBigInt.toString(16)}")
      assert(dut.io.sdramDin.toInt == 0xAB, s"case1: din got ${dut.io.sdramDin.toInt}")
      println("[sim] case1 client-0 identity — OK")

      // === Case 2: two-client alternation ===
      dut.io.clientRd(0)   #= true
      dut.io.clientAddr(0) #= 0x00001
      dut.io.clientDin(0)  #= 0x10
      dut.io.clientRd(1)   #= true
      dut.io.clientAddr(1) #= 0x22222
      dut.io.clientDin(1)  #= 0x22
      dut.io.grantClientId #= 0
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.sdramAddr.toBigInt == 0x00001, "case2: sel=0 addr mismatch")
      assert(dut.io.sdramDin.toInt == 0x10, "case2: sel=0 din mismatch")
      dut.io.grantClientId #= 1
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.sdramAddr.toBigInt == 0x22222, "case2: sel=1 addr mismatch")
      assert(dut.io.sdramDin.toInt == 0x22, "case2: sel=1 din mismatch")
      println("[sim] case2 two-client alternation — OK")

      // === Case 3: grant fan-out ===
      for (gcid <- 0 until 4) {
        dut.io.grantClientId #= gcid
        dut.io.grant         #= true
        dut.clockDomain.waitSampling(); sleep(1)
        for (i <- 0 until 4) {
          val expected = (i == gcid)
          assert(dut.io.clientGrant(i).toBoolean == expected,
                 s"case3: gcid=$gcid clientGrant($i) expected $expected")
        }
      }
      dut.io.grant #= false
      println("[sim] case3 grant fan-out — OK")

      // === Case 4: slotValid fan-out ===
      dut.io.slotValid #= true
      for (gcid <- 0 until 4) {
        dut.io.grantClientId #= gcid
        dut.clockDomain.waitSampling(); sleep(1)
        for (i <- 0 until 4) {
          val expected = (i == gcid)
          assert(dut.io.clientSlotValid(i).toBoolean == expected,
                 s"case4: gcid=$gcid clientSlotValid($i) expected $expected")
        }
      }
      println("[sim] case4 slotValid fan-out — OK")

      // === Case 5: CP-A3 central refresh cadence ===
      // The arbiter owns the single refresh timer; refreshDue must pulse exactly
      // every refreshPeriodCycles (default 593). Align to one pulse, then measure
      // the gap to the next.
      dut.clockDomain.waitSamplingWhere(dut.io.refreshDue.toBoolean)
      var period = 0
      do { dut.clockDomain.waitSampling(); period += 1 } while (!dut.io.refreshDue.toBoolean)
      assert(period == 593, s"case5: refreshDue period got $period expected 593")
      println(s"[sim] case5 refreshDue cadence = $period cycles (expected 593) — OK")

      println("[sim] ArbiterSim: PASS")
    }
}
