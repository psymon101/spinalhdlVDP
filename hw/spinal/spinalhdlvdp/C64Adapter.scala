package spinalhdlvdp

import spinal.core._
import spinal.lib._

/** Task 40 — First Platform Adapter (C64 Raster+Sprite Smoke).
  *
  * Thin translation layer from a VIC-II-style register interface to the
  * existing Mode0 substrate. Not a cycle-accurate VIC-II emulator; an
  * honest mapping sufficient for a "two-bar raster split + bouncing
  * sprites" hardware proof on Tang Nano 20K.
  *
  * Integration policy (Option A per artifact §3.4):
  *   - Adapter lives *outside* `VdpTop`, in the scenario wrapper.
  *   - Bus output is a peer master on `RegBusArbiter` — same class as
  *     the QSPI decoder and Copper.
  *   - Direct outputs (raster trigger, sprite 0/1 legacy IO descriptors)
  *     bypass the bus and drive `VdpTop`'s existing IO pins. This is an
  *     explicit interim wiring step (CyanPeak audit note #8257 §3):
  *     `VdpTop` currently has no bus-mapped raster-trigger or sprite-0/1
  *     registers, so the adapter drives those ports directly.
  *
  * Scope (§2.2 of the artifact):
  *   - $D000..$D00F   sprite X/Y (slots 0..7 semantically; slots 0/1 via
  *                    direct IO, slots 2..7 left for future expansion)
  *   - $D010          sprite X MSB (slots 0/1 bits consumed via IO)
  *   - $D011          control 1 — bit 4 = DEN → LAYER_ENABLE bit 0 (bus)
  *   - $D012 / $D011[7]  9-bit raster line → direct `rasterTriggerLine`
  *   - $D015          sprite enable mask — bits 0/1 to sprite 0/1 IO
  *   - $D019          IRQ status — write-1-to-clear → `rasterTriggerClear`
  *                    pulse (adapter converts C64 write to one-cycle clear)
  *   - $D01A          IRQ mask — bit 0 (RIRQ) → `rasterTriggerEnable`
  *
  * Out of scope (§7 gaps): sprite-sprite/sprite-BG collision routing,
  * $D018 bank switching, badline emulation, open borders.
  *
  * Shadow RAM: 256 × 8-bit adapter-internal register shadow at logical
  * address space 0x0E00..0x0EFF (approved CyanPeak #8257 §2 / spec §3).
  * Used to reconstruct composite bus writes (e.g., re-emitting
  * `LAYER_ENABLE` with prior bits preserved). Not exposed on the Mode0
  * read path because Mode0 is write-only.
  */
case class C64Adapter() extends Component {
  val io = new Bundle {
    // C64-style register write port (host or Copper).
    val regAddr = in  UInt(8 bits)     // $00..$FF; only $00..$2F honored
    val regData = in  Bits(8 bits)
    val regWr   = in  Bool()

    // Mode0 register bus output (merged into RegBusArbiter).
    val busAddr = out UInt(15 bits)
    val busData = out Bits(16 bits)
    val busWr   = out Bool()

    // Direct outputs bypassing the bus (see CyanPeak note §3 in #8257).
    val rasterTriggerLine   = out UInt(10 bits)
    val rasterTriggerEnable = out Bool()
    val rasterTriggerClear  = out Bool()

    // Legacy-IO sprite slots (VdpTop.io.sprite0*/sprite1*). Sprites 2..7
    // are out of scope for Task 40's first-adapter proof.
    val sprite0X       = out UInt(10 bits)
    val sprite0Y       = out UInt(10 bits)
    val sprite0Enabled = out Bool()
    val sprite1X       = out UInt(10 bits)
    val sprite1Y       = out UInt(10 bits)
    val sprite1Enabled = out Bool()
  }

  // ------------------------------------------------------------------
  // Register shadow — 48 × 8-bit registers covering $D000..$D02F.
  // Writing via the regAddr/regData port mirrors into shadow[regAddr].
  // Reads are combinational. (Adapter-internal; not bus-visible.)
  // ------------------------------------------------------------------
  val shadowDepth = 0x30
  val shadow = Vec.fill(shadowDepth)(RegInit(B(0, 8 bits)))

  val regAddrLow = io.regAddr(log2Up(shadowDepth) - 1 downto 0)
  when(io.regWr && (io.regAddr < U(shadowDepth, 8 bits))) {
    switch(regAddrLow) {
      for (i <- 0 until shadowDepth) {
        is(U(i, log2Up(shadowDepth) bits)) { shadow(i) := io.regData }
      }
    }
  }

  // Named shadow accessors for readability.
  def R(idx: Int): Bits = shadow(idx)
  val SPR0_X    = 0x00
  val SPR0_Y    = 0x01
  val SPR1_X    = 0x02
  val SPR1_Y    = 0x03
  val SPR_XMSB  = 0x10
  val CTRL1     = 0x11
  val RASTER    = 0x12
  val SPR_ENA   = 0x15
  val IRQ_STAT  = 0x19
  val IRQ_MASK  = 0x1A

  // ------------------------------------------------------------------
  // Direct outputs (combinational from shadow).
  // ------------------------------------------------------------------
  // Raster line is 9 bits on the VIC-II: $D012[7:0] + $D011[7].
  io.rasterTriggerLine   := (B(0, 1 bits) ## R(CTRL1)(7) ## R(RASTER)).asUInt.resize(10)
  io.rasterTriggerEnable := R(IRQ_MASK)(0)   // RIRQ mask bit

  // Writing $D019 on a VIC-II is write-1-to-clear. We pulse
  // rasterTriggerClear for one cycle whenever the host writes $D019 with
  // bit 0 set. This does NOT latch into shadow beyond the acknowledge.
  io.rasterTriggerClear := io.regWr && (io.regAddr === U(IRQ_STAT, 8 bits)) && io.regData(0)

  // Sprite 0/1 X: low 8 bits from $D000/$D002, high bit from $D010[0]/[1].
  io.sprite0X := (B(0, 1 bits) ## R(SPR_XMSB)(0) ## R(SPR0_X)).asUInt.resize(10)
  io.sprite1X := (B(0, 1 bits) ## R(SPR_XMSB)(1) ## R(SPR1_X)).asUInt.resize(10)
  // Sprite 0/1 Y: 8-bit, zero-extended.
  io.sprite0Y := R(SPR0_Y).asUInt.resize(10)
  io.sprite1Y := R(SPR1_Y).asUInt.resize(10)
  io.sprite0Enabled := R(SPR_ENA)(0)
  io.sprite1Enabled := R(SPR_ENA)(1)

  // ------------------------------------------------------------------
  // Bus-write emitter. Currently the only Mode0 bus target is
  // LAYER_ENABLE at 0x0300. When the host writes $D011 we emit a fresh
  // LAYER_ENABLE word whose bit 0 mirrors DEN ($D011 bit 4). The other
  // enable bits come from the *next-state* shadow (i.e., reuse the prior
  // shadowed value — this single-register case has no dependencies).
  //
  // Bus writes are registered one-cycle delayed from the triggering
  // C64 write so the shadow update (same cycle) settles first; the
  // emitter observes the new shadow on the emit cycle.
  // ------------------------------------------------------------------
  val emitPending = RegInit(False)
  val emitAddr    = Reg(UInt(15 bits)) init 0
  val emitData    = Reg(Bits(16 bits)) init 0

  // Detect writes that need a bus side-effect. Today: $D011 only.
  val trig_d011 = io.regWr && (io.regAddr === U(CTRL1, 8 bits))

  // LAYER_ENABLE word (Task 48 bit layout):
  //   bit0 = L0, bit1 = L1, bit2 = sprite, bit3 = L2, bit4 = L3.
  // Adapter maps C64 DEN ($D011 bit 4) → L0 enable; sprite layer kept on
  // so DEN re-enables both the text plane and its sprites. Scenarios
  // handle L1/L2/L3 via their own animators.
  // Concatenation widths: 11 + 1 + 1 + 1 + 1 + 1 = 16.
  val zeroTop11 = B(0, 11 bits)
  val denBit    = io.regData(4)
  val layerEnableWord: Bits =
    zeroTop11 ## False ## False ## True ## False ## denBit

  when(trig_d011) {
    emitPending := True
    emitAddr    := U(0x0300, 15 bits)
    emitData    := layerEnableWord
  } otherwise {
    when(emitPending) { emitPending := False }
  }

  io.busAddr := emitAddr
  io.busData := emitData
  io.busWr   := emitPending
}
