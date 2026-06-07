package spinalhdlvdp

import spinal.core._

/** BurstRefreshController — vblank-triggered SDRAM burst-refresh (lane
  * SDRAM-BURST-REFRESH P16, TopazCliff #11978; scheme approved #11962).
  *
  * Runs in the SDRAM clock domain. Replaces distributed refresh with a burst:
  *   - ACTIVE VIDEO (io.vblankActive=0): refreshDue is SUPPRESSED — zero refresh
  *     contention, full SDRAM bandwidth for pixel fetches.
  *   - VBLANK (io.vblankActive=1): emit `burstCount` refreshDue pulses, paced
  *     `periodCycles` apart, then idle until next vblank.
  *
  * PACING IS LOAD-BEARING: the consumer (SdramTileAttributeFetch) latches
  * refreshDue into a SINGLE-DEEP `refreshPending` Bool — extra pulses arriving
  * before the previous is serviced are DROPPED (→ under-refresh → data loss).
  * So `periodCycles` MUST be >= the fetch engine's refresh service latency. The
  * default (24 sdramCd cycles) is conservative for the idle-vblank case (no
  * fetch contention); the integration cosim must confirm no pulse is dropped.
  *
  * REFRESH MATH (#11960): sdram.v has 2048 rows; bursting all 2048 each vblank
  * gives every row a max age of one frame (16.67ms << 64ms tREF). At 40.5MHz the
  * vblank is ~57,900 cycles; 2048 * 24 = 49,152 < 57,900 -> fits with margin.
  *
  * WATCHDOG: a frame-scale failsafe — if no refresh has issued for
  * `watchdogCycles` (e.g. a vblank was missed / vblankActive never asserted),
  * force a refreshDue so the part can never silently lose retention.
  */
case class BurstRefreshController(
    burstCount:    Int = 2048,    // AUTO_REFRESH per vblank (= sdram.v row count)
    periodCycles:  Int = 24,      // sdramCd cycles between burst pulses (>= service latency)
    watchdogCycles: Int = 675000  // ~1 frame @40.5MHz failsafe (16.67ms*40.5e6)
) extends Component {
  require(burstCount >= 1 && periodCycles >= 1 && watchdogCycles >= periodCycles)

  val io = new Bundle {
    val vblankActive = in  Bool()   // 1 during vertical blanking (SDRAM-domain synced)
    val refreshDue   = out Bool()   // one-cycle pulse; feeds the fetch refresh path
    // observability for sim/debug
    val bursting     = out Bool()
  }

  val vblankPrev   = RegNext(io.vblankActive) init False
  val vblankRising = io.vblankActive && !vblankPrev

  val remaining = Reg(UInt(log2Up(burstCount + 1) bits)) init 0
  val periodCnt = Reg(UInt(log2Up(periodCycles) bits)) init 0
  val sinceRef  = Reg(UInt(log2Up(watchdogCycles + 1) bits)) init 0

  val burstPulse    = False
  val watchdogPulse = False

  // Load the per-vblank burst at vblank entry.
  when(vblankRising) { remaining := U(burstCount, remaining.getWidth bits) }

  // Pace one pulse every periodCycles while in vblank with work remaining.
  when(io.vblankActive && remaining =/= 0) {
    when(periodCnt === U(periodCycles - 1, periodCnt.getWidth bits)) {
      periodCnt := 0
      burstPulse := True
      remaining  := remaining - 1
    } otherwise {
      periodCnt := periodCnt + 1
    }
  } otherwise {
    periodCnt := 0
  }

  // Frame-scale failsafe: never let retention lapse if vblank stops delivering.
  when(sinceRef === U(watchdogCycles, sinceRef.getWidth bits)) {
    watchdogPulse := True
  }

  val refreshOut = burstPulse || watchdogPulse
  when(refreshOut) { sinceRef := 0 } otherwise { sinceRef := sinceRef + 1 }

  io.refreshDue := refreshOut
  io.bursting   := io.vblankActive && remaining =/= 0
}
