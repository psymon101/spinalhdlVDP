package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 1 (#9154) — AdapterRegRouter.
  *
  * Per `MODE_SELECT_ARCHITECTURE.md` v1.1 §4.1 critical correction
  * (BrightForge #8685 §2.1): the router lives **inside `VdpTop` scope on
  * the unified post-arbitration bus**, not as a QSPI-only splitter.
  * Copper, HDMA, and bootstrap can all generate writes that fall in
  * adapter-local address ranges; the router must be mode-aware for ALL
  * writers, or the quiescence claim is false.
  *
  * Wiring contract (interposed between RegBusArbiter and the VdpTop
  * regBus consumer):
  * {{{
  *   regBusArbiter.io.mixed → router.io.in
  *   router.io.passThru     → vdpTop.io.regBus    // global Mode0 regs only
  *   router.io.adapters(i)  → adapter_i.regWrite  // adapter-local pulses
  * }}}
  *
  * Per arch §4.3:
  *   - C64        adapter range = 0x0E00..0x0EFF (page = addr[14:8] == 0x0E)
  *   - ZX         adapter range = 0x0F00..0x0FFF (page == 0x0F)
  *   - NES (fut.) adapter range = 0x1000..0x10FF (page == 0x10)
  *   - SMS (fut.) adapter range = 0x1100..0x11FF (page == 0x11)
  *   - ...
  *
  * Behavioural rules (arch §4.3):
  *   1. Writes to an adapter range → translate to that adapter's
  *      `regAddr/regData/regWr` (regAddr = in.addr[7:0], regData =
  *      in.data[7:0]) only when that adapter is the active mode.
  *   2. Writes to an adapter range when the adapter is **inactive**
  *      → silently dropped (no `regWr` pulse).
  *   3. Writes to an unallocated adapter range → silently dropped.
  *   4. ALL adapter-range writes are removed from `passThru` (the
  *      global Mode0 bus consumer never sees them), regardless of
  *      whether the active adapter consumed them. This prevents an
  *      inactive-adapter address from being misinterpreted by the
  *      Mode0 substrate (which has no decode for adapter pages).
  *
  * Note on `MODE_SELECT` (arch §4.2): `0x0313` is a Mode0 global
  * register, NOT in any adapter range, so it always passes through
  * to `VdpTop`. The "Copper/HDMA writes to 0x0313 are silently
  * dropped" rule is enforced inside `VdpTop` itself (it can observe
  * the master source via the bootstrap-vs-qspi-vs-adapter master
  * partition); enforcing it here would require carrying a `source`
  * tag on `Mode0RegBus`, which is out of scope for v1. The router
  * does NOT special-case `0x0313`.
  *
  * Adapter table parameter: `(modeId, base16bit)` pairs. Base must be
  * page-aligned (low 8 bits = 0).
  */
case class AdapterRegRouter(adapters: Seq[(Int, Int)]) extends Component {
  require(adapters.nonEmpty, "AdapterRegRouter needs at least one adapter")
  require(adapters.map(_._1).distinct.length == adapters.length,
    "AdapterRegRouter mode ids must be unique")
  require(adapters.map(_._2).distinct.length == adapters.length,
    "AdapterRegRouter base addresses must be unique")
  require(adapters.forall { case (id, _)   => id >= 0 && id <= 0xF },
    "AdapterRegRouter mode ids must fit in 4 bits")
  require(adapters.forall { case (_, base) => (base & 0xFF) == 0 && base >= 0 && base <= 0x7F00 },
    "AdapterRegRouter base addresses must be page-aligned and within 15-bit space")

  val n = adapters.length

  case class AdapterPulse() extends Bundle {
    val regAddr = UInt(8 bits)
    val regData = Bits(8 bits)
    val regWr   = Bool()
  }

  val io = new Bundle {
    val modeSelect = in UInt(4 bits)
    val mixedIn    = in (Mode0RegBus())
    val passThru   = out(Mode0RegBus())
    val adapters   = out Vec(AdapterPulse(), n)
  }

  // Per-adapter range hit (high 7 bits of address == base page).
  val pages: Seq[(Bool, Bool)] = adapters.zipWithIndex.map { case ((modeId, base), i) =>
    val pageHit = io.mixedIn.addr(14 downto 8) === U(base >> 8, 7 bits)
    val active  = io.modeSelect === U(modeId, 4 bits)
    pageHit -> active
  }

  // Per-adapter pulse: enable && pageHit && active.
  // regAddr/regData are simply the low 8 bits of the bus address/data.
  pages.zipWithIndex.foreach { case ((pageHit, active), i) =>
    io.adapters(i).regAddr := io.mixedIn.addr(7 downto 0)
    io.adapters(i).regData := io.mixedIn.data(7 downto 0)
    io.adapters(i).regWr   := io.mixedIn.enable && pageHit && active
  }

  // Adapter-range hit (any adapter's page) — used to suppress
  // passThru.enable so the Mode0 substrate never sees adapter writes.
  // Includes inactive-adapter and (per arch §4.3 rule 3) ranges that
  // happen to match a known base even when the matching mode is not
  // selected — both are silently dropped from the global path.
  val anyPageHit = pages.map(_._1).reduce(_ || _)

  io.passThru.addr   := io.mixedIn.addr
  io.passThru.data   := io.mixedIn.data
  io.passThru.enable := io.mixedIn.enable && !anyPageHit
}
