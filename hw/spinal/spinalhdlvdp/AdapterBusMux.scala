package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 1 (#9154) — AdapterBusMux.
  *
  * Per `MODE_SELECT_ARCHITECTURE.md` v1.1 §4.5: muxes the bus outputs
  * of N runtime-instantiated platform adapters into a single
  * `Mode0RegBus` that feeds **master 2** of the existing 3-master
  * `RegBusArbiter` (priority bootstrap > qspi > adapter).
  *
  * Each adapter is *already* mode-gated at its own outputs (Phase 2 —
  * arch §4.4 quiescence: inactive adapters drive `busAddr=0`,
  * `busData=0`, `busWr=False`). This mux therefore exists for two
  * reasons:
  *
  *   1. **Defense in depth.** If an adapter ever fails to gate its
  *      outputs correctly, the mux's `modeSelect`-decoded selection
  *      ensures that only the active adapter's bus reaches master 2.
  *      Spurious enables from the wrong adapter cannot land on the
  *      arbiter.
  *
  *   2. **Address/data hygiene at master 2.** Addr/data lines fall
  *      through to 0 when no adapter matches `modeSelect` — important
  *      for synthesis-time x-prop avoidance and for the Native Mode0
  *      case (`modeSelect = 0x0`, no adapter active).
  *
  * Wiring contract (per arch §4.5):
  * {{{
  *   val adapterMux = AdapterBusMux(Seq(0x1, 0x2))   // C64=0x1, ZX=0x2
  *   adapterMux.io.modeSelect    := vdpTop.io.modeSelect
  *   adapterMux.io.adapters(0)   <> c64Adapter.io.bus      // bus out bundle
  *   adapterMux.io.adapters(1)   <> zxAdapter.io.bus
  *   regBusArbiter.io.masters(2) <> adapterMux.io.out
  * }}}
  *
  * NOTE: existing `C64Adapter` / `ZXSpectrumAdapter` expose
  * `busAddr`/`busData`/`busWr` as separate IO signals rather than a
  * `Mode0RegBus` bundle. The wiring at `TopTang20kHdmi` in Phase 5
  * builds the bundle inline:
  * {{{
  *   adapterMux.io.adapters(0).addr   := c64Adapter.io.busAddr
  *   adapterMux.io.adapters(0).data   := c64Adapter.io.busData
  *   adapterMux.io.adapters(0).enable := c64Adapter.io.busWr
  * }}}
  *
  * Per-adapter modeId list is a build-time parameter so future
  * adapters (NES=0x3, SMS=0x4, ...) plug in cleanly.
  */
case class AdapterBusMux(adapterModeIds: Seq[Int]) extends Component {
  require(adapterModeIds.nonEmpty, "AdapterBusMux needs at least one adapter")
  require(adapterModeIds.distinct.length == adapterModeIds.length,
    "AdapterBusMux mode-id list must be unique")
  require(adapterModeIds.forall(id => id >= 0 && id <= 0xF),
    "AdapterBusMux mode ids must fit in 4 bits (0x0..0xF)")

  val io = new Bundle {
    val modeSelect = in UInt(4 bits)
    val adapters   = in Vec(Mode0RegBus(), adapterModeIds.length)
    val mixed      = out(Mode0RegBus())
  }

  // Priority-fold pattern (matches arch §4.5 illustration semantics and
  // mirrors RegBusArbiter's Mux chain). Default branch (no adapter
  // active, e.g. Native Mode0 modeSelect=0x0) drives 0/False so master 2
  // of RegBusArbiter sees a quiescent input.
  val cases: Seq[(Bool, Mode0RegBus)] = adapterModeIds.zipWithIndex.map {
    case (id, i) => ((io.modeSelect === U(id, 4 bits)), io.adapters(i))
  }

  io.mixed.addr   := cases.foldRight(U(0, 15 bits))  { case ((sel, bus), acc) => Mux(sel, bus.addr,   acc) }
  io.mixed.data   := cases.foldRight(B(0, 16 bits))  { case ((sel, bus), acc) => Mux(sel, bus.data,   acc) }
  io.mixed.enable := cases.foldRight(False)          { case ((sel, bus), acc) => Mux(sel, bus.enable, acc) }
}
