package spinalhdlvdp

import spinal.core._

/** Task 32b — Mode0 register write bus.
  *
  * Shared 3-tuple contract per `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md`
  * §1:
  *
  *   addr   : UInt(15 bits)  — 0x0000..0x7FFF write-path address
  *   data   : Bits(16 bits)  — single 16-bit register slot
  *   enable : Bool           — one-cycle pulse; frame consumes on this edge
  *
  * Masters drive this bundle. `VdpTop` and downstream consumers sample on
  * `enable=True` and commit at the safe boundary (`hCounter===0`). This
  * bundle is purely the wire contract — no semantic or safe-boundary
  * logic lives here.
  */
case class Mode0RegBus() extends Bundle {
  val addr   = UInt(15 bits)
  val data   = Bits(16 bits)
  val enable = Bool()
}

/** Task 32b — Priority-mux arbiter for the Mode0 register bus.
  *
  * `masterCount` inputs are prioritized by index: index 0 wins when
  * multiple masters assert `enable` on the same cycle. This matches the
  * spec §2.2 priority documented as `bootstrap > qspi > animator`:
  *   masters(0) = bootstrap
  *   masters(1) = qspi
  *   masters(2) = animator
  *
  * Priority is encoded via a foldLeft on addr/data and OR on enable —
  * lower-index master's addr/data flows through when any of the earlier
  * masters is also asserting enable, otherwise later masters fall
  * through. Enable is a pure OR of all masters' enables.
  *
  * Semantics match the pre-refactor ad-hoc mux:
  *   out.enable = m0.en || m1.en || ... || mN.en
  *   out.addr   = highest-priority-master-asserting-enable.addr (else 0)
  *   out.data   = same, but on data
  */
case class RegBusArbiter(masterCount: Int) extends Component {
  require(masterCount >= 1, "RegBusArbiter needs at least one master")

  val io = new Bundle {
    val masters = in  Vec(Mode0RegBus(), masterCount)
    val mixed   = out(Mode0RegBus())
  }

  /* enable = OR across all masters */
  io.mixed.enable := io.masters.map(_.enable).reduce(_ || _)

  /* Priority: scan from lowest index (highest priority) to highest index.
   * If master(i).enable is high, that master's addr/data wins; otherwise
   * fall through to master(i+1). Implemented as a reverse fold so
   * earlier masters override later fallthroughs. */
  val addrMux = Vec(UInt(15 bits), masterCount + 1)
  val dataMux = Vec(Bits(16 bits), masterCount + 1)
  addrMux(masterCount) := U(0, 15 bits)
  dataMux(masterCount) := B(0, 16 bits)
  for (i <- (0 until masterCount).reverse) {
    addrMux(i) := Mux(io.masters(i).enable, io.masters(i).addr, addrMux(i + 1))
    dataMux(i) := Mux(io.masters(i).enable, io.masters(i).data, dataMux(i + 1))
  }
  io.mixed.addr := addrMux(0)
  io.mixed.data := dataMux(0)
}
