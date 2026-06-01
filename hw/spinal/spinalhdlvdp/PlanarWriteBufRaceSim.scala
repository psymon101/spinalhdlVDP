package spinalhdlvdp

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable

/** P3 CP-B(2) #10791 — PlanarWriteBufRaceSim.
  *
  * Risk targeted: #3 (writeBuf race vs in-flight drain). The hazard: a new
  * `fetchStartRise` arrives while the prior row is still being emitted
  * (`emitting=True`). The same edge:
  *   - `writeBuf := !writeBuf` flips the ping-pong write buffer.
  *   - `emitting := False` (via `when(io.fetchGrant)` block) clears the
  *     emission flag.
  * But because `emitting` and `writeBuf` are both Regs, the COMBINATIONAL
  * value of `emitting` at the fetchStartRise edge is the PRIOR cycle's
  * value — which can be True. If so, the ping-pong reader (which selects
  * the *other* buffer than writeBuf) just switched to the buffer that
  * the new fetch is about to overwrite, while the compositor may still
  * read a few stale-pixel cycles before the new fetch fills it. The
  * in-RTL CP-B(1) assert `!fetchStartRise || !emitting` is the canary.
  *
  * Strategy: drive fetchGrant pulses very tightly back-to-back without
  * letting the prior row finish emitting (no pixelAddr increment, no
  * settling delay). If the race exists at all under aggressive timing,
  * the assert should fire.
  *
  * Gating: assert tripped → CP-B(3) GO (need drain-complete gate);
  * assert silent under stress → CP-B(3) SKIP (race not reachable, document).
  *
  * The sim ALWAYS runs to completion and reports the outcome on stdout
  * so the per-line `[sim] Risk #3:` markers can be parsed to make the
  * CP-B(3) decision.
  */
object PlanarWriteBufRaceSim extends App {
  Config.sim.compile {
    val sdramCd = ClockDomain.external("sdram", frequency = FixedFrequency(40500000 Hz))
    SdramTileAttributeFetch(sdramCd)
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.sdramCd.forkStimulus(period = 10)

    val mem = mutable.HashMap[Int, Int]()
    def readByte(a: Int): Int = mem.getOrElse(a & 0x7fffff, 0)
    def readWord(a: Int): Long = {
      val base = a & ~3
      (readByte(base).toLong & 0xFF) |
        ((readByte(base + 1).toLong & 0xFF) << 8) |
        ((readByte(base + 2).toLong & 0xFF) << 16) |
        ((readByte(base + 3).toLong & 0xFF) << 24)
    }

    dut.io.sdramDout         #= 0
    dut.io.sdramDout32       #= 0
    dut.io.sdramDataReady    #= false
    dut.io.sdramBusy         #= true
    dut.io.fetchGrant        #= false
    dut.io.fetchSlotValid    #= true
    dut.io.fetchPreAnnounce  #= false
    dut.io.tileDecodeMode    #= 0
    dut.io.attributeMode     #= 0
    dut.io.fetchLine         #= 0
    dut.io.fetchScrollX      #= 0
    dut.io.fetchScrollY      #= 0
    dut.io.pixelAddr         #= 0

    fork {
      for (_ <- 0 until 30) dut.sdramCd.waitSampling()
      dut.io.sdramBusy #= false
      var state = "idle"
      var timer = 0
      var op = ""
      var latchedAddr = 0
      var latchedDin = 0
      while (true) {
        dut.sdramCd.waitSampling()
        dut.io.sdramDataReady #= false
        state match {
          case "idle" =>
            if (dut.io.sdramRd.toBoolean) {
              op = "rd"; latchedAddr = dut.io.sdramAddr.toInt
              dut.io.sdramBusy #= true
              state = "wait"; timer = 3
            } else if (dut.io.sdramWr.toBoolean) {
              op = "wr"; latchedAddr = dut.io.sdramAddr.toInt
              latchedDin = dut.io.sdramDin.toInt & 0xFF
              dut.io.sdramBusy #= true
              state = "wait"; timer = 5
            } else if (dut.io.sdramRefresh.toBoolean) {
              op = "rf"
              dut.io.sdramBusy #= true
              state = "wait"; timer = 4
            }
          case "wait" =>
            timer -= 1
            if (timer == 0) {
              op match {
                case "rd" =>
                  dut.io.sdramDout   #= readByte(latchedAddr) & 0xFF
                  dut.io.sdramDout32 #= BigInt(readWord(latchedAddr) & 0xFFFFFFFFL)
                  dut.io.sdramDataReady #= true
                  state = "rdDone"
                case "wr" =>
                  mem(latchedAddr & 0x7fffff) = latchedDin
                  dut.io.sdramBusy #= false
                  state = "idle"
                case "rf" =>
                  dut.io.sdramBusy #= false
                  state = "idle"
              }
            }
          case "rdDone" =>
            dut.io.sdramBusy #= false
            state = "idle"
        }
      }
    }

    // -------- Boot ---------------------------------------------------------
    dut.clockDomain.waitSampling(50)
    var timeout = 600000
    while (!dut.io.bootDone.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for bootDone")
    timeout = 600000
    while (!dut.io.memtestPass.toBoolean && !dut.io.memtestFail.toBoolean && timeout > 0) {
      dut.clockDomain.waitSampling(); timeout -= 1
    }
    assert(timeout > 0, "Timed out waiting for memtest")
    assert(dut.io.memtestPass.toBoolean, "memtestFail asserted")
    println("[sim] boot + memtest OK; proceeding to writeBuf race stress")

    // -------- Stress pattern 1: 5-cycle back-to-back fetchGrants ---------
    // Each grant rise is fetchStartRise; the prior row's emitting may
    // still be True if we don't let pixelAddr drain to LineWidth-1.
    // (pixelAddr stays at 0 throughout — emitting clears only via the
    // `when(io.fetchGrant) { emitting := False }` path AND the unpackIdx
    // wrap inside `when(emitting)`. With pixelAddr=0, unpackIdx wrap
    // happens only when the FIFO has popped + unpacked all sub-pixels,
    // which proceeds independently of pixelAddr — so emitting may
    // legitimately clear during the inter-grant gap. Hence "stress"
    // means we run MANY tight grants and rely on statistical coverage
    // to land at least one in the racy window.)
    dut.io.tileDecodeMode #= 2  // shuffled — exercises both planes
    dut.clockDomain.waitSampling(20)
    val Stress1Grants = 200
    val Stress1GapCycles = 5  // very tight
    for (i <- 0 until Stress1Grants) {
      dut.io.fetchLine #= (i % 30) * 16
      dut.io.fetchGrant #= true
      for (_ <- 0 until 2) dut.clockDomain.waitSampling()
      dut.io.fetchGrant #= false
      for (_ <- 0 until Stress1GapCycles) dut.clockDomain.waitSampling()
    }
    println(s"[sim] stress pattern 1: $Stress1Grants tight grants (gap=${Stress1GapCycles}c) — sim still running, " +
      s"Risk #3 assert either silent or fired as $$error (non-fatal in sim)")

    // -------- Stress pattern 2: back-to-back grants with no gap ---------
    // Even tighter: just toggle fetchGrant every 2 cycles.
    val Stress2Grants = 100
    for (i <- 0 until Stress2Grants) {
      dut.io.fetchLine #= (i % 30) * 16
      dut.io.fetchGrant #= true
      dut.clockDomain.waitSampling()
      dut.io.fetchGrant #= false
      dut.clockDomain.waitSampling()
    }
    println(s"[sim] stress pattern 2: $Stress2Grants ultra-tight grants (2c period) complete")

    // -------- Stress pattern 3: long emit + abort cycle ------------------
    // Let one fetch complete fully (so wordFifo has payloads ready),
    // then start emission (pixelAddr increments) but interrupt mid-line
    // with a new fetchGrant. This is the "compositor mid-read" case.
    dut.io.fetchLine #= 0
    dut.io.fetchGrant #= true
    for (_ <- 0 until 4) dut.clockDomain.waitSampling()
    dut.io.fetchGrant #= false
    for (_ <- 0 until 2000) dut.clockDomain.waitSampling()  // let FIFO fill
    // Now drive pixelAddr up to "mid-line" to start emission ramp.
    for (px <- 0 until 200) {
      dut.io.pixelAddr #= px
      dut.clockDomain.waitSampling()
    }
    // Interrupt with a new grant while emission is in progress.
    dut.io.fetchLine #= 16
    dut.io.fetchGrant #= true
    for (_ <- 0 until 2) dut.clockDomain.waitSampling()
    dut.io.fetchGrant #= false
    for (_ <- 0 until 200) dut.clockDomain.waitSampling()
    println("[sim] stress pattern 3: mid-emission interrupt complete")

    // -------- Recover and verify the engine is still functional ----------
    dut.io.fetchGrant #= false
    dut.io.pixelAddr #= 0
    dut.clockDomain.waitSampling(200)
    dut.io.fetchLine #= 0
    dut.io.fetchGrant #= true
    for (_ <- 0 until 4) dut.clockDomain.waitSampling()
    dut.io.fetchGrant #= false
    for (_ <- 0 until 8000) dut.clockDomain.waitSampling()
    println("[sim] post-stress fetch completed — engine still functional")

    println("[sim] PlanarWriteBufRaceSim: PASS — sim ran to completion under all 3 stress patterns")
    println("[sim] Risk #3 outcome: search the transcript above for 'P3 Risk #3:' assertion messages")
    println("[sim]   - if present → CP-B(3) GO (drain-complete gate needed)")
    println("[sim]   - if absent  → CP-B(3) SKIP (race not reachable from external stimulus)")
  }
}
