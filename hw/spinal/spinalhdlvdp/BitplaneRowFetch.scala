package spinalhdlvdp

import spinal.core._

/** Per-scanline N-bitplane SDRAM fetcher (Mode0 Hardening — H-2).
  *
  * Issues sequential `dout32`-aperture reads to assemble one full row of
  * `planePixels` pixels across `planeCount` bitplanes. Output: a Vec of
  * `planeCount` plane rows, each `planePixels` bits wide, in MSB-first
  * on-screen ordering — `planeRows(p)(planePixels-1)` is the leftmost
  * pixel's contribution from plane `p`. Pair with `BitplaneReconstruct`
  * to convert bit-by-bit into pixel indices.
  *
  * Bandwidth (per assessment §6.3, with `planeCount=5, planePixels=320`):
  *   - 5 planes × 10 reads per plane = 50 dout32 reads per row
  *   - ~5 cycles per read on the nand2mario controller
  *   - ~250 SDRAM cycles per row, well within the ~2050-cycle budget
  *
  * Out of scope (deferred): integration with `SdramArbiter` as a real
  * client. This component is a primitive — its sim drives a synthetic
  * SDRAM model directly. Top-level integration belongs in a follow-on
  * sub-slice.
  *
  * @param planeCount   number of bitplanes to fetch (1..8). 5 is Amiga
  *                     OCS / ST low-res target.
  * @param planePixels  pixels per plane per row. Must be a multiple of
  *                     32 so each `dout32` read fills exactly 32 bits.
  * @param addrWidth    SDRAM address width (matches `SdramController`).
  */
case class BitplaneRowFetch(
    planeCount:   Int = 5,
    planePixels:  Int = 320,
    addrWidth:    Int = 23
) extends Component {
  require(planeCount >= 1 && planeCount <= 8,
    s"planeCount must be 1..8, got $planeCount")
  require(planePixels >= 32 && planePixels % 32 == 0,
    s"planePixels must be a positive multiple of 32, got $planePixels")

  val readsPerPlane = planePixels / 32
  val planeIdxBits  = if (planeCount > 1) log2Up(planeCount) else 1
  val readIdxBits   = if (readsPerPlane > 1) log2Up(readsPerPlane) else 1

  val io = new Bundle {
    val start          = in  Bool()                                // 1-cycle pulse
    val planeBaseAddr  = in  Vec(UInt(addrWidth bits), planeCount) // SDRAM base per plane

    // SDRAM master interface — wire to a `SdramArbiter` client port plus
    // the controller's `dout32` / `data_ready` / `busy` signals.
    val sdramRd        = out Bool()
    val sdramAddr      = out UInt(addrWidth bits)
    val sdramBusy      = in  Bool()
    val sdramDataReady = in  Bool()
    val sdramDout32    = in  Bits(32 bits)

    // Per-plane assembled row data + completion strobe.
    val planeRows      = out Vec(Bits(planePixels bits), planeCount)
    val rowReady       = out Bool()
    val busy           = out Bool()
  }

  // Per-plane row storage. CyanPeak audit HOLD #9325 mandated the
  // refactor from `Vec.fill(Reg)` to Mem-backed storage to break the
  // CLS placement wall (planeCount=5 / planePixels=320 produced 1,600
  // FFs that exceeded available CLS sites at V=32). Now: one Mem per
  // plane, each with `readsPerPlane` × 32-bit entries. SpinalHDL
  // `readAsync` drives the combinational `planeRows` slice path; Gowin
  // synthesizes these as distributed LUTRAM (small Mems with
  // `readsPerPlane`-deep × 32-bit width fit cleanly in
  // `LUT4_RAM`/`SP9KB`-style aspect ratios with each LUT cell holding
  // ~16 entries of 1 bit, so the 1,600 bit total spans ~100 LUTs of
  // LUTRAM rather than 1,600 dedicated FFs).
  val planeMems = Vec.fill(planeCount)(Mem(Bits(32 bits), readsPerPlane))
  for (p <- 0 until planeCount) {
    for (r <- 0 until readsPerPlane) {
      val msb = planePixels - 1 - r * 32
      val lsb = planePixels - (r + 1) * 32
      io.planeRows(p)(msb downto lsb) := planeMems(p).readAsync(U(r, readIdxBits bits))
    }
  }

  val planeIdx = Reg(UInt(planeIdxBits bits)) init 0
  val readIdx  = Reg(UInt(readIdxBits bits))  init 0

  object State extends SpinalEnum {
    val Idle, Issue, WaitData, Done = newElement()
  }
  val state = Reg(State()) init State.Idle

  io.sdramRd   := False
  io.sdramAddr := U(0, addrWidth bits)
  io.rowReady  := False
  io.busy      := state =/= State.Idle

  switch(state) {
    is(State.Idle) {
      when(io.start) {
        planeIdx := 0
        readIdx  := 0
        state    := State.Issue
      }
    }
    is(State.Issue) {
      // Hold off issuing while the controller is busy with a prior op.
      when(!io.sdramBusy) {
        io.sdramRd   := True
        io.sdramAddr := io.planeBaseAddr(planeIdx) +
                        (readIdx.resize(addrWidth) |<< 2)  // 4-byte stride per dout32 read
        state := State.WaitData
      }
    }
    is(State.WaitData) {
      when(io.sdramDataReady) {
        when(readIdx === U(readsPerPlane - 1, readIdxBits bits)) {
          readIdx := 0
          when(planeIdx === U(planeCount - 1, planeIdxBits bits)) {
            planeIdx := 0
            state    := State.Done
          } otherwise {
            planeIdx := planeIdx + 1
            state    := State.Issue
          }
        } otherwise {
          readIdx := readIdx + 1
          state   := State.Issue
        }
      }
    }
    is(State.Done) {
      io.rowReady := True
      state       := State.Idle
    }
  }
}
