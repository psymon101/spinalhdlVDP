package spinalhdlvdp

import spinal.core._
import spinal.lib._

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
  * Bus emission (v1, minimal viable):
  *   - On ZX_CTRL[0] rising edge → emit `LAYER_ENABLE = 0x0001` (L0
  *     only), turning on the bitmap path.
  *
  * Honest gap (v1):
  *   - Border bus emission deferred. The scenario bootstrap (or host
  *     firmware) sets the border-region palette directly via the
  *     CW-1 protocol. Adding a `ZX_BORDER → palette write` emitter
  *     is a future enhancement that fits the same shadow+emit
  *     pattern but needs a 3-write sequence (PALETTE_PTR + 2×
  *     PALETTE_DATA). Out of scope for the smoke proof.
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
  // Detect ZX_CTRL[0] rising edge against the previous shadow value.
  // The shadow-write happens this cycle; the trigger captures the
  // pre-write state so we re-emit only on a 0→1 transition.
  val ctrlPrev    = RegNext(R(ZX_CTRL)(0)) init False
  val adapterRise = R(ZX_CTRL)(0) && !ctrlPrev

  val emitPending = RegInit(False)
  val emitAddr    = Reg(UInt(15 bits)) init 0
  val emitData    = Reg(Bits(16 bits)) init 0

  // LAYER_ENABLE word per Task 48 packing:
  //   bit0=L0, bit1=L1, bit2=sprite, bit3=L2, bit4=L3
  // ZX adapter wants L0-only bitmap mode.
  val layerEnableL0Only: Bits = B(0, 11 bits) ## False ## False ## False ## False ## True

  when(adapterRise) {
    emitPending := True
    emitAddr    := U(0x0300, 15 bits)
    emitData    := layerEnableL0Only
  } otherwise {
    when(emitPending) { emitPending := False }
  }

  io.busAddr := emitAddr
  io.busData := emitData
  io.busWr   := emitPending
}
