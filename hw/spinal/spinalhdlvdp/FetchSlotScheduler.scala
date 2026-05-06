package spinalhdlvdp

import spinal.core._
import spinal.lib._

case class FetchSlot() extends Bundle {
  val enabled  = Bool()
  val clientId = UInt(2 bits)
  val startH   = UInt(10 bits)
  val endH     = UInt(10 bits)
}

/** Static fetch-slot scheduler with BA-style pre-announce (R3, Reading B).
  *
  * `slotCount` is power-of-two (GT-022). Each slot declares an inclusive
  * H-window [startH, endH] and a clientId. The scheduler:
  *   - asserts `slotValid` whenever any enabled slot's window covers hCounter
  *   - emits a 1-cycle `grant` at hCounter==startH (slot entry)
  *   - emits a 1-cycle `preAnnounce` at hCounter==startH-1 (client prep)
  *   - counts grants per line, cleared by `lineStart`
  *
  * Priority between slots with overlapping windows: lowest index wins.
  */
case class FetchSlotScheduler(slotCount: Int = 8) extends Component {
  require(isPow2(slotCount), s"slotCount must be power-of-two (GT-022), got $slotCount")

  val io = new Bundle {
    val hCounter        = in  UInt(10 bits)
    val lineStart       = in  Bool()
    val schedule        = in  Vec(FetchSlot(), slotCount)
    val currentSlot     = out UInt(log2Up(slotCount) bits)
    val slotValid       = out Bool()
    val preAnnounce     = out Bool()
    val grant           = out Bool()
    val grantClientId   = out UInt(2 bits)
    val lineGrantCount  = out UInt(log2Up(slotCount + 1) bits)
  }

  val inWindow  = Vec(Bool(), slotCount)
  val entering  = Vec(Bool(), slotCount)
  val preEnter  = Vec(Bool(), slotCount)
  for (i <- 0 until slotCount) {
    val s = io.schedule(i)
    inWindow(i) := s.enabled && (io.hCounter >= s.startH) && (io.hCounter <= s.endH)
    entering(i) := s.enabled && (io.hCounter === s.startH)
    // Suppress preAnnounce if startH==0 (no "cycle before" without wrap semantics).
    preEnter(i) := s.enabled && (s.startH =/= 0) && (io.hCounter === (s.startH - 1))
  }

  // Lowest-index priority: later-iterated (lower i) overrides.
  val curSlot = UInt(log2Up(slotCount) bits)
  val grantSlot = UInt(log2Up(slotCount) bits)
  curSlot := 0
  grantSlot := 0
  for (i <- (slotCount - 1) to 0 by -1) {
    when(inWindow(i)) { curSlot := U(i, log2Up(slotCount) bits) }
    when(entering(i)) { grantSlot := U(i, log2Up(slotCount) bits) }
  }

  io.currentSlot   := curSlot
  io.slotValid     := inWindow.asBits.orR
  io.grant         := entering.asBits.orR
  io.preAnnounce   := preEnter.asBits.orR
  // Task 3 #9350 fix (CyanPeak audit, BronzeGate convergence): the prior
  // `io.schedule(grantSlot).clientId` was correct only on the one-cycle
  // `entering` pulse — `grantSlot` is a combinational signal latched
  // only when entering(i) fires. Outside that pulse it reverted to 0,
  // and SDRAM `data_ready` arriving 5 cycles after the read issue was
  // qualified against a stale grantClientId and silently discarded
  // (causing the FSM deadlock observed in the planar fetch).
  //
  // Replacement: latch the last-entered slot's index in a register that
  // HOLDS its value until the next entering event. Between entries
  // grantClientId remains the last-granted slot's clientId, so the
  // SDRAM arbiter correctly qualifies in-flight read responses for the
  // duration of each transaction.
  //
  // Using `curSlot` directly (the in-window slot) was wrong here because
  // multiple slots can overlap in window (e.g. slot 1 full-line +
  // slot 2 planar), and the existing reverse-iter priority gave the
  // lowest index, breaking slot 2's grant during the overlap.
  val grantSlotHeld = Reg(UInt(log2Up(slotCount) bits)) init 0
  val grantSlotNow  = UInt(log2Up(slotCount) bits)
  grantSlotNow := grantSlotHeld
  for (i <- 0 until slotCount) {
    when(entering(i)) { grantSlotNow := U(i, log2Up(slotCount) bits) }
  }
  grantSlotHeld := grantSlotNow
  io.grantClientId := io.schedule(grantSlotNow).clientId

  val count = Reg(UInt(log2Up(slotCount + 1) bits)) init 0
  when(io.lineStart) {
    count := 0
  }.elsewhen(io.grant) {
    count := count + 1
  }
  io.lineGrantCount := count
}
