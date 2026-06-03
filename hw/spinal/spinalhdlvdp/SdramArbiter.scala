package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 30 — multi-client SDRAM arbiter.
  *
  * Takes the `FetchSlotScheduler`'s {grant, slotValid, grantClientId}
  * pre-announce/grant signals and routes a single client's SDRAM request
  * (rd, wr, addr, din) to the SDRAM controller on each cycle. Clients
  * whose slot is not currently granted present their requests on their
  * own port, but only the granted client's signals reach the SDRAM.
  *
  * Fan-out: `clientGrant(i)` pulses when the scheduler's grant pulse
  * fires with `grantClientId === i`. `clientSlotValid(i)` is the
  * scheduler's `slotValid` gated by `grantClientId === i` — clients can
  * use it to stall their internal FSM between their assigned windows.
  *
  * Bit-identical guarantee for the Task 30 baseline: with only client 0
  * wired and the current 2-slot schedule (both slots clientId=0), the
  * scheduler's `grantClientId` resolves to 0 on every cycle, so the
  * arbiter's mux output == client(0)'s input exactly.
  */
case class SdramArbiter(
    clientCount: Int = 4,
    addrWidth:   Int = 23,
    dataWidth:   Int = 8,
    refreshPeriodCycles: Int = 593   // CP-A3: central refresh cadence (593 cyc = 14.64µs @40.5MHz)
) extends Component {
  require(clientCount >= 1, "clientCount ≥ 1")

  val idBits = if (clientCount > 1) log2Up(clientCount) else 1

  val io = new Bundle {
    val grantClientId = in UInt(idBits bits)
    val slotValid     = in Bool()
    val grant         = in Bool()

    // Per-client request bundles.
    val clientRd   = in Vec(Bool(), clientCount)
    val clientWr   = in Vec(Bool(), clientCount)
    val clientAddr = in Vec(UInt(addrWidth bits), clientCount)
    val clientDin  = in Vec(Bits(dataWidth bits), clientCount)

    // Per-client grant / slot-valid fan-out.
    val clientGrant     = out Vec(Bool(), clientCount)
    val clientSlotValid = out Vec(Bool(), clientCount)

    // Arbitrated SDRAM request going to the controller.
    val sdramRd   = out Bool()
    val sdramWr   = out Bool()
    val sdramAddr = out UInt(addrWidth bits)
    val sdramDin  = out Bits(dataWidth bits)

    // CP-A3 (Phase A #11438/#11439, Option B): central refresh cadence. The arbiter
    // owns the single refresh timer (Priority-0 accounting); `refreshDue` pulses one
    // cycle every refreshPeriodCycles. Fetch engines consume it (replacing their own
    // per-engine timers) and insert the AUTO_REFRESH at their next safe point — one
    // timer, no per-engine drift, both layers on the same cadence.
    val refreshDue = out Bool()
  }

  // Central refresh timer.
  val refreshTimer = Reg(UInt(log2Up(refreshPeriodCycles) bits)) init 0
  val refreshDueR  = Reg(Bool()) init False
  refreshDueR := False
  when(refreshTimer === U(refreshPeriodCycles - 1, log2Up(refreshPeriodCycles) bits)) {
    refreshTimer := 0
    refreshDueR  := True
  } otherwise {
    refreshTimer := refreshTimer + 1
  }
  io.refreshDue := refreshDueR

  // Per-client fan-out — scheduler grants one client per cycle.
  for (i <- 0 until clientCount) {
    val selected = io.grantClientId === U(i, idBits bits)
    io.clientGrant(i)     := io.grant     && selected
    io.clientSlotValid(i) := io.slotValid && selected
  }

  // Mux client signals to SDRAM based on grantClientId.
  io.sdramRd   := io.clientRd(io.grantClientId)
  io.sdramWr   := io.clientWr(io.grantClientId)
  io.sdramAddr := io.clientAddr(io.grantClientId)
  io.sdramDin  := io.clientDin(io.grantClientId)
}
