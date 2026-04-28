package spinalhdlvdp

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Task 50 — ZX Spectrum Adapter.
  *
  * Thin translation layer from ULA-style adapter registers to the
  * existing Mode0 substrate. Mirrors the C64Adapter pattern (shadow
  * register file + bus-write emitter). Sits OUTSIDE `VdpTop` and
  * drives a peer master on `RegBusArbiter`.
  *
  * Scope (per artifact §6, audit PASS #8667):
  *   0x00  ZX_BORDER       [2:0] = border color (0..7)
  *   0x01  ZX_FLASH_CTRL   [0]   = flash enable
  *   0x02  ZX_FLASH_RATE   [7:0] = frames per flash toggle
  *   0x03  ZX_CTRL         [0]   = adapter enable
  *   0x10  ZX_PAL_LOAD           = (reserved for future palette load
  *                                  trigger; v1 leaves this as a
  *                                  pure shadow slot — bootstrap
  *                                  loads the palette via the CW-1
  *                                  0x0600/0x0601 protocol per
  *                                  artifact §8)
  *
  * Bus emission:
  *   - v1: On ZX_CTRL[0] rising edge → emit `LAYER_ENABLE = 0x0001`
  *     (L0 only), turning on the bitmap path. Audited PASS in #8674.
  *   - v2: On ZX_BORDER write, emit a 3-word palette-RAM update
  *     sequence (PALETTE_PTR + PALETTE_DATA low + PALETTE_DATA high)
  *     targeting the dedicated border palette slot. The 8-entry LUT
  *     maps border code 0..7 to the standard ZX Spectrum 0xCD-level
  *     RGB triples. The visible-border-via-dual-window wiring (per
  *     artifact §9) is a v3 follow-on; v2 proves the emitter shape.
  *
  * Honest gap (v2):
  *   - FLASH counter not implemented in HDL; host-driven per
  *     artifact §10.
  *
  * Adapter address space is 16 bytes (0x00..0x0F shadowable + 0x10
  * reserved); `regAddr` upper bits beyond log2 of that range are
  * ignored.
  */
case class ZXSpectrumAdapter() extends Component {
  val io = new Bundle {
    // Adapter-style register write port (host or firmware).
    val regAddr = in  UInt(8 bits)     // 0x00..0xFF; only 0x00..0x10 honored
    val regData = in  Bits(8 bits)
    val regWr   = in  Bool()

    // Mode0 register bus output (peer master on RegBusArbiter).
    val busAddr = out UInt(15 bits)
    val busData = out Bits(16 bits)
    val busWr   = out Bool()

    // Direct shadowed status outputs — useful for downstream
    // scenarios / sims that want to observe adapter state without
    // round-tripping through the bus.
    val borderColor = out UInt(3 bits)
    val flashEnable = out Bool()
    val flashRate   = out UInt(8 bits)
    val adapterOn   = out Bool()
  }

  // ---- Shadow register file -----------------------------------------
  // 16 × 8-bit slots cover offsets 0x00..0x0F. Slot 0x10 (ZX_PAL_LOAD)
  // is reserved for a future palette-load emitter — kept as a 17th
  // shadow entry so a host write can land somewhere observable.
  val shadowDepth = 0x11
  val shadow = Vec.fill(shadowDepth)(RegInit(B(0, 8 bits)))

  val regAddrLow = io.regAddr(log2Up(shadowDepth) - 1 downto 0)
  when(io.regWr && (io.regAddr < U(shadowDepth, 8 bits))) {
    switch(regAddrLow) {
      for (i <- 0 until shadowDepth) {
        is(U(i, log2Up(shadowDepth) bits)) { shadow(i) := io.regData }
      }
    }
  }

  // Named shadow indices for readability.
  val ZX_BORDER     = 0x00
  val ZX_FLASH_CTRL = 0x01
  val ZX_FLASH_RATE = 0x02
  val ZX_CTRL       = 0x03
  val ZX_PAL_LOAD   = 0x10

  def R(idx: Int): Bits = shadow(idx)

  // ---- Direct status outputs (combinational from shadow) ------------
  io.borderColor := R(ZX_BORDER)(2 downto 0).asUInt
  io.flashEnable := R(ZX_FLASH_CTRL)(0)
  io.flashRate   := R(ZX_FLASH_RATE).asUInt
  io.adapterOn   := R(ZX_CTRL)(0)

  // ---- Bus-write emitter --------------------------------------------
  // Detect edge events on shadow registers that need bus side-effects.
  // The shadow write happens this cycle; we capture the pre-write
  // value via RegNext to detect transitions.
  val ctrlPrev    = RegNext(R(ZX_CTRL)(0)) init False
  val adapterRise = R(ZX_CTRL)(0) && !ctrlPrev

  // v2 BH border-palette emitter: emit a 3-word write sequence whenever
  // ZX_BORDER changes.
  //   word 0: addr=PALETTE_PTR (0x0601), data = borderSlot * 2  (low half)
  //   word 1: addr=PALETTE_DATA(0x0600), data = G:B              (low half)
  //   word 2: addr=PALETTE_DATA(0x0600), data = R                (high half, commits)
  // borderSlot is the palette index reserved for the border color.
  // BitmapFetch only addresses palette entries 0..7 (paper=0..7) and
  // 16..23 (ink/paper × bright=1) via the {paletteBank, ink/paper}
  // path, so slot 24 is guaranteed unused by the v1 + v2 bitmap
  // pattern → border updates do not stomp on visible cells.
  val borderSlot = 24
  // 8-entry Spectrum normal-level color LUT: code 0..7 → (R, G, B) at
  // 0xCD level. Matches artifact §8 normal-level palette.
  val zxBorderLut: Vec[Bits] = Vec(
    B(0x00 << 16 | 0x00 << 8 | 0x00, 24 bits),  // 0 black
    B(0x00 << 16 | 0x00 << 8 | 0xCD, 24 bits),  // 1 blue
    B(0xCD << 16 | 0x00 << 8 | 0x00, 24 bits),  // 2 red
    B(0xCD << 16 | 0x00 << 8 | 0xCD, 24 bits),  // 3 magenta
    B(0x00 << 16 | 0xCD << 8 | 0x00, 24 bits),  // 4 green
    B(0x00 << 16 | 0xCD << 8 | 0xCD, 24 bits),  // 5 cyan
    B(0xCD << 16 | 0xCD << 8 | 0x00, 24 bits),  // 6 yellow
    B(0xCD << 16 | 0xCD << 8 | 0xCD, 24 bits)   // 7 white
  )
  // Detect ZX_BORDER write — the shadow-write `when` block above lands
  // the new value this cycle; we observe the previous value via
  // RegNext to spot the transition.
  val borderPrev   = RegNext(R(ZX_BORDER)(2 downto 0)) init B(0, 3 bits)
  // Fix v3.1: borderChange should trigger when the SHADOW register changes.
  // Since R(ZX_BORDER) is already registered, borderPrev is effectively
  // RegNext(RegNext(io.regData)) when regWr is active.
  val borderChange = R(ZX_BORDER)(2 downto 0) =/= borderPrev
  // Latch the border code at the moment of change.
  val borderLatched = Reg(UInt(3 bits)) init 0
  when(borderChange) { borderLatched := R(ZX_BORDER)(2 downto 0).asUInt }
  val borderRgb = zxBorderLut(borderLatched)
  val borderR   = borderRgb(23 downto 16)
  val borderG   = borderRgb(15 downto 8)
  val borderB   = borderRgb(7  downto 0)

  // Single-shot bus emit (v1 LAYER_ENABLE) and 3-shot border palette
  // emit (v2). Serialised through one FSM so they cannot collide on
  // the regBus output. A pending `adapterRise` and a pending
  // `borderChange` are queued — adapterRise gets priority since it's
  // cycle-critical (host expects bitmap path enabled before any
  // border updates).
  val pendingAdapterRise = RegInit(False)
  val pendingBorder      = RegInit(False)
  when(adapterRise)  { pendingAdapterRise := True }
  when(borderChange) { pendingBorder      := True }

  val emitAddr = Reg(UInt(15 bits)) init 0
  val emitData = Reg(Bits(16 bits)) init 0
  val emitWr   = RegInit(False)

  val emitFsm = new StateMachine {
    val sIdle    = new State with EntryPoint
    val sLayer   = new State                    // emit LAYER_ENABLE
    val sBordPtr = new State                    // emit PALETTE_PTR
    val sBordLo  = new State                    // emit PALETTE_DATA low
    val sBordHi  = new State                    // emit PALETTE_DATA high

    // sIdle consumes pending events. The OR with adapterRise/borderChange
    // captures same-cycle events that the top-level latches would
    // otherwise need 1 cycle to surface — and avoids the
    // assignment-clash where FSM unconditionally clearing the pending
    // reg would overwrite a fresh top-level set.
    sIdle.whenIsActive {
      emitWr := False
      when(adapterRise || pendingAdapterRise) {
        pendingAdapterRise := False
        goto(sLayer)
      } elsewhen(borderChange || pendingBorder) {
        pendingBorder := False
        goto(sBordPtr)
      }
    }

    sLayer.whenIsActive {
      emitAddr := U(0x0300, 15 bits)
      emitData := B(0, 11 bits) ## False ## False ## False ## False ## True
      emitWr   := True
      goto(sIdle)
    }

    sBordPtr.whenIsActive {
      emitAddr := U(0x0601, 15 bits)            // PALETTE_PTR
      emitData := B(borderSlot * 2, 16 bits)    // entry borderSlot, low half
      emitWr   := True
      goto(sBordLo)
    }
    sBordLo.whenIsActive {
      emitAddr := U(0x0600, 15 bits)            // PALETTE_DATA
      emitData := (borderG ## borderB).resize(16)   // low half = G:B
      emitWr   := True
      goto(sBordHi)
    }
    sBordHi.whenIsActive {
      emitAddr := U(0x0600, 15 bits)            // PALETTE_DATA
      emitData := borderR.resize(16)             // high half = R, commits entry
      emitWr   := True
      goto(sIdle)
    }
  }

  io.busAddr := emitAddr
  io.busData := emitData
  io.busWr   := emitWr
}
