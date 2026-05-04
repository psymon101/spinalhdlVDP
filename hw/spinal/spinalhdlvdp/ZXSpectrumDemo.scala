package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 50 v2 Slice B — ZX Spectrum demo animator (Scenario 50).
  *
  * Mirrors the C64DemoAnimator pattern: wraps `ZXSpectrumAdapter`
  * with a tiny bootstrap+per-frame state machine that drives the
  * adapter's register port. The adapter's bus output feeds a
  * peer-master slot on `RegBusArbiter` in `TopTang20kHdmi`.
  *
  * Demo program:
  *   - Cycle 0..N (boot warmup): hold all adapter regs at reset.
  *   - First vsync after boot: write ZX_CTRL=0x01 (rising edge →
  *     adapter emits LAYER_ENABLE=0x0001 over the bus, turning the
  *     bitmap path on through the substrate). One-shot.
  *   - Subsequent vsyncs: increment a frame counter; every 30
  *     frames update ZX_BORDER from a 0..7 wraparound, driving the
  *     adapter's 3-word palette emitter at the rate of one color
  *     change per ~0.5s (at 60 Hz). The 8-color cycle exercises
  *     every entry of the adapter's hardcoded LUT (artifact §8).
  *
  * Note: the demo writes to ZX_BORDER's palette slot 24 — this slot
  * is NOT addressed by the BitmapFetch ink/paper path on the v1/v2
  * scenes (BitmapFetch uses 0..7 and 16..23 only via
  * {paletteBank, ink/paper}), so the cycling is observable in the
  * Mode0 palette RAM but not visible on screen until v3 wires up
  * window-driven border visibility per artifact §9. The point of v2
  * is the bus-master integration: the adapter actually drives the
  * Mode0 bus through RegBusArbiter, not just sim-only.
  */
case class ZXSpectrumDemo() extends Component {
  val io = new Bundle {
    val vsyncRising = in  Bool()

    // Mode0 register bus (peer master slot on RegBusArbiter).
    val busAddr = out UInt(15 bits)
    val busData = out Bits(16 bits)
    val busWr   = out Bool()

    // Direct status outputs (handy for sims / scenario probing).
    val borderColor = out UInt(3 bits)
    val adapterOn   = out Bool()
  }

  val adapter = ZXSpectrumAdapter()
  // Task 1 (#9154) — Demo wrapper hardcodes adapter to its native mode so
  // legacy scenario behavior is preserved (arch §4.5 demo wrapper note).
  adapter.io.modeSelect := U(adapter.myModeId, 4 bits)
  io.busAddr     := adapter.io.busAddr
  io.busData     := adapter.io.busData
  io.busWr       := adapter.io.busWr
  io.borderColor := adapter.io.borderColor
  io.adapterOn   := adapter.io.adapterOn

  // ------------------------------------------------------------------
  // Driver state machine.
  // ------------------------------------------------------------------
  // `bootDone` rises on the first vsync. Until then we hold the
  // adapter inputs quiescent. After the first vsync, drive ZX_CTRL=1
  // for one cycle to fire the LAYER_ENABLE emit.
  val bootDone = Reg(Bool()) init False
  val ctrlSent = Reg(Bool()) init False
  when(io.vsyncRising) { bootDone := True }

  // Border color counter: 6-bit so we get a /8-frame wrap on the low
  // 3 bits when the upper 3 bits cycle.
  val borderCounter = Reg(UInt(8 bits)) init 0
  when(io.vsyncRising) { borderCounter := borderCounter + 1 }
  val borderTick   = io.vsyncRising && (borderCounter(4 downto 0) === U(0, 5 bits))
  // Border phases through 0..7 then wraps. Update only on borderTick.
  val borderColorReg = Reg(UInt(3 bits)) init 0
  when(borderTick) { borderColorReg := borderColorReg + 1 }

  // Drive the adapter's regAddr / regData / regWr port. Mux between
  // the one-shot ZX_CTRL=1 write and the per-tick ZX_BORDER updates.
  val regAddr = UInt(8 bits)
  val regData = Bits(8 bits)
  val regWr   = Bool()
  regAddr := 0
  regData := 0
  regWr   := False

  when(bootDone && !ctrlSent) {
    // First post-boot cycle: assert ZX_CTRL=0x01.
    regAddr := U(0x03, 8 bits)        // ZX_CTRL
    regData := B(0x01, 8 bits)
    regWr   := True
    ctrlSent := True
  } elsewhen(borderTick) {
    // Per-tick: write ZX_BORDER with the current code in the cycle.
    regAddr := U(0x00, 8 bits)        // ZX_BORDER
    regData := B(0, 5 bits) ## borderColorReg.asBits
    regWr   := True
  }

  adapter.io.regAddr := regAddr
  adapter.io.regData := regData
  adapter.io.regWr   := regWr
}
