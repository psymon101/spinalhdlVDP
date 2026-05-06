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

    // Slot read port (CyanPeak HOLD #9325 refactor): consumer drives
    // `slotIdx` to select one of `readsPerPlane` 32-bit slots; the
    // module returns one 32-bit word per plane combinationally. This
    // single read port enables LUTRAM/BSRAM inference (vs the prior
    // wide `planeRows` IO which forced multi-read fan-out and FF
    // storage). For sim/legacy compatibility, `planeRows` is also
    // driven (combinationally from per-r readAsync calls); when
    // unused at the top level it prunes cleanly.
    val slotIdx        = in  UInt(readIdxBits bits)
    val slotWord       = out Vec(Bits(32 bits), planeCount)

    // Legacy wide-row output (preserved for `BitplaneRowFetchSim`
    // and any other consumer that expects the full row at once).
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
  // Task 3 #9349 discriminator (PM ruling): pre-initialize planeMems
  // with PlanarProofAssets SMPTE-bar pattern. This bypasses the SDRAM
  // fetch path so we can isolate fetch/render correctness from
  // host-upload correctness. If captured HW shows SMPTE bars with this
  // bypass, fetch/render is alive and the remaining blocker is the
  // host-upload path (#9335). If still gray, fetch/render is still
  // broken. Revert this change after the discriminator answers.
  val planeMems = Seq.tabulate(planeCount) { p =>
    val initWords =
      if (planeCount == PlanarProofAssets.PlaneCount &&
          readsPerPlane == PlanarProofAssets.DwordsPerPlane &&
          p < PlanarProofAssets.PlaneCount) {
        PlanarProofAssets.planeInit(p).map(v => B(v, 32 bits))
      } else {
        Seq.fill(readsPerPlane)(B(0, 32 bits))
      }
    Mem(Bits(32 bits), initialContent = initWords.toArray)
  }
  // Primary slot read port — single readAsync per Mem for the consumer
  // to use. This is the LUTRAM-inference-friendly path.
  for (p <- 0 until planeCount) {
    io.slotWord(p) := planeMems(p).readAsync(io.slotIdx)
  }
  // Legacy wide-row output: combinationally assembled via per-slot
  // readAsync. Spinal will prune this when `planeRows` is unused at
  // the top level. When consumed (e.g. BitplaneRowFetchSim probing
  // the full row), the synth tool may need to duplicate the LUTRAM —
  // accepted overhead for sim transparency.
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

  // Mem-side write logic: gated per plane so only the currently-active
  // plane's Mem captures the dout32 word. Outside the FSM block so the
  // write port can sit at the Mem's normal write interface (rather than
  // inside a switch that would create a clocked-write inference issue).
  val memWriteEn = (state === State.WaitData) && io.sdramDataReady
  for (p <- 0 until planeCount) {
    planeMems(p).write(
      address = readIdx,
      data    = io.sdramDout32,
      enable  = memWriteEn && (planeIdx === U(p, planeIdxBits bits))
    )
  }
}
