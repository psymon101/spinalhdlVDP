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
  io.grantClientId := io.schedule(grantSlot).clientId

  val count = Reg(UInt(log2Up(slotCount + 1) bits)) init 0
  when(io.lineStart) {
    count := 0
  }.elsewhen(io.grant) {
    count := count + 1
  }
  io.lineGrantCount := count
}
