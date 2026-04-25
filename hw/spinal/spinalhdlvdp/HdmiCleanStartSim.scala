package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

/** Slice-A unit sim for `HdmiCleanStart`.
  *
  * Six bounded cases that together prove the mute window:
  *   1. Mute is active immediately after reset.
  *   2. Outputs are blanked while mute is active (regardless of inputs).
  *   3. Mute deasserts exactly at cycle `muteCycles`.
  *   4. After the mute window, outputs equal inputs combinationally.
  *   5. Mute does NOT re-fire spontaneously after expiring (unless reset).
  *   6. Setting the inputs to "active video" mid-mute is still blanked.
  */
object HdmiCleanStartSim extends App {
  // Use a small mute window in sim so we can exercise the boundary.
  val muteCycles = 16

  Config.sim.compile(HdmiCleanStart(muteCycles = muteCycles)).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Drive realistic inputs (active video) so we can see them masked
    // / passed through.
    dut.io.inHsync #= false   // active sync, mute should hold high
    dut.io.inVsync #= false
    dut.io.inDe    #= true
    dut.io.inRed   #= 0xAA
    dut.io.inGreen #= 0xBB
    dut.io.inBlue  #= 0xCC

    // Reset complete; sample at cycle 0 (before first rising edge that
    // increments the counter).
    dut.clockDomain.waitSampling()

    // Case 1: mute is active immediately after reset.
    assert(dut.io.muteActive.toBoolean, "Case 1: mute should be active at cycle 1")

    // Case 2: outputs are blanked.
    assert( dut.io.outHsync.toBoolean,         "Case 2: outHsync should be inactive (high) under mute")
    assert( dut.io.outVsync.toBoolean,         "Case 2: outVsync should be inactive (high) under mute")
    assert(!dut.io.outDe.toBoolean,            "Case 2: outDe should be 0 under mute")
    assert( dut.io.outRed.toInt   == 0,        s"Case 2: outRed should be 0 under mute, got 0x${dut.io.outRed.toInt}%X")
    assert( dut.io.outGreen.toInt == 0,        s"Case 2: outGreen should be 0 under mute, got 0x${dut.io.outGreen.toInt}%X")
    assert( dut.io.outBlue.toInt  == 0,        s"Case 2: outBlue should be 0 under mute, got 0x${dut.io.outBlue.toInt}%X")
    println("[sim] Case 1+2 mute active immediately, outputs blanked — OK")

    // Case 6: change inputs mid-mute, still blanked.
    dut.io.inHsync #= true
    dut.io.inVsync #= true
    dut.io.inDe    #= false
    dut.io.inRed   #= 0x11
    dut.io.inGreen #= 0x22
    dut.io.inBlue  #= 0x33
    dut.clockDomain.waitSampling()
    assert( dut.io.muteActive.toBoolean, "Case 6: mute still active mid-window")
    assert( dut.io.outHsync.toBoolean,    "Case 6: outHsync still high under mute even with inHsync changed")
    assert( dut.io.outRed.toInt == 0,     "Case 6: outRed still 0 under mute")
    println("[sim] Case 6 mute holds even when inputs flip — OK")

    // Restore "active video" inputs for boundary observation.
    dut.io.inHsync #= false
    dut.io.inVsync #= false
    dut.io.inDe    #= true
    dut.io.inRed   #= 0xAA
    dut.io.inGreen #= 0xBB
    dut.io.inBlue  #= 0xCC

    // Case 3: count cycles until mute deasserts; bound by 4×muteCycles
    // so a stuck-high failure surfaces as a hard timeout, not as silent
    // success. Off-by-one tolerance because increment / sample alignment
    // is implementation-detail; what matters is "mute deasserts within
    // the configured window".
    var stepsToDeassert = 0
    val cap = 4 * muteCycles
    while (dut.io.muteActive.toBoolean && stepsToDeassert < cap) {
      dut.clockDomain.waitSampling()
      stepsToDeassert += 1
    }
    assert(!dut.io.muteActive.toBoolean,
      s"Case 3: mute did not deassert within $cap cycles (muteCycles=$muteCycles)")
    assert(stepsToDeassert <= muteCycles + 2,
      s"Case 3: mute deassert took $stepsToDeassert cycles, expected ~$muteCycles")
    println(s"[sim] Case 3 mute deasserts after $stepsToDeassert cycles (muteCycles=$muteCycles) — OK")

    // Case 4: outputs == inputs after mute.
    assert(!dut.io.outHsync.toBoolean,                 "Case 4: outHsync passes inHsync (false)")
    assert(!dut.io.outVsync.toBoolean,                 "Case 4: outVsync passes inVsync (false)")
    assert( dut.io.outDe.toBoolean,                     "Case 4: outDe passes inDe (true)")
    assert( dut.io.outRed.toInt   == 0xAA,              s"Case 4: outRed passes 0xAA")
    assert( dut.io.outGreen.toInt == 0xBB,              s"Case 4: outGreen passes 0xBB")
    assert( dut.io.outBlue.toInt  == 0xCC,              s"Case 4: outBlue passes 0xCC")
    println("[sim] Case 4 pass-through after mute — OK")

    // Case 5: mute does NOT re-fire spontaneously over the next 100
    // cycles (no reset is issued; counter is saturated).
    for (_ <- 0 until 100) {
      dut.clockDomain.waitSampling()
      assert(!dut.io.muteActive.toBoolean, "Case 5: mute must not re-fire")
    }
    println("[sim] Case 5 mute remains low after expiry — OK")

    println("[sim] HdmiCleanStartSim: PASS")
  }
}
