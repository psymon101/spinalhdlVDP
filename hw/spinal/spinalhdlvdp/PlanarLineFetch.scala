package spinalhdlvdp

import spinal.core._

/** End-to-end N-bitplane fetch + per-pixel reconstruction (Mode0
  * Hardening — H-3b composite primitive).
  *
  * Combines `BitplaneRowFetch` (H-2) and `BitplaneReconstruct` (H-1)
  * into a single component the substrate can integrate with as one
  * unit:
  *
  *   {{{
  *   start ─►┌────────────────────┐
  *           │  BitplaneRowFetch  │── SDRAM ──── (master)
  *           └────────┬───────────┘
  *                    │ planeRows[N] (planePixels-wide)
  *                    │
  *           pixelIdx ─►┌──────────────────┐
  *                      │ slot = idx>>5    │── word select per plane
  *                      │ subIdx = idx[4:0]│
  *                      │ BitplaneReconst. │
  *                      └────────┬─────────┘
  *                               ▼
  *                          pixel (N bits)
  *   }}}
  *
  * Why two-stage selection: a direct `BitplaneReconstruct(planeWidth =
  * planePixels)` would synthesize a `planePixels`-wide mux per plane.
  * Splitting into "word select then bit select within that 32-bit
  * word" reduces it to a `readsPerPlane`-wide mux + a 32-bit
  * `BitplaneReconstruct`, which is much cheaper and matches how the
  * `dout32` aperture stores rows naturally.
  */
case class PlanarLineFetch(
    sdramCd:     ClockDomain,
    planeCount:  Int = 5,
    planePixels: Int = 320,
    addrWidth:   Int = 23
) extends Component {
  require(planeCount >= 1 && planeCount <= 8)
  require(planePixels >= 32 && planePixels % 32 == 0)

  val readsPerPlane = planePixels / 32
  val pixelIdxBits  = log2Up(planePixels)
  val slotBits      = if (readsPerPlane > 1) log2Up(readsPerPlane) else 1
  val subIdxBits    = log2Up(32)   // 5

  val io = new Bundle {
    val start          = in  Bool()
    val planeBaseAddr  = in  Vec(UInt(addrWidth bits), planeCount)

    // SDRAM master interface (passes through to internal `BitplaneRowFetch`).
    val sdramRd        = out Bool()
    val sdramAddr      = out UInt(addrWidth bits)
    val sdramBusy      = in  Bool()
    val sdramDataReady = in  Bool()
    val sdramDout32    = in  Bits(32 bits)

    // Pixel-side consumer interface.
    val pixelIdx       = in  UInt(pixelIdxBits bits)
    val pixel          = out Bits(planeCount bits)
    val rowReady       = out Bool()
    val busy           = out Bool()
  }

  // ---- H-2 fetch ----
  val rowFetch = BitplaneRowFetch(
    sdramCd     = sdramCd,
    planeCount  = planeCount,
    planePixels = planePixels,
    addrWidth   = addrWidth
  )
  rowFetch.io.start         := io.start
  rowFetch.io.planeBaseAddr := io.planeBaseAddr
  io.sdramRd                := rowFetch.io.sdramRd
  io.sdramAddr              := rowFetch.io.sdramAddr
  rowFetch.io.sdramBusy      := io.sdramBusy
  rowFetch.io.sdramDataReady := io.sdramDataReady
  rowFetch.io.sdramDout32    := io.sdramDout32
  io.rowReady               := rowFetch.io.rowReady
  io.busy                   := rowFetch.io.busy

  // ---- Two-stage pixel selection ----
  // Slot = which dout32-word within the plane row holds this pixel.
  // Slot 0 sits at the MSB end (leftmost on screen), so:
  //   slot     = (planePixels - 1 - pixelIdx) / 32
  //   subIdxIn = (planePixels - 1 - pixelIdx) mod 32
  // For planePixels = 320 and 9-bit pixelIdx, this reduces to:
  //   reverseIdx = 319 - pixelIdx                  (9 bits)
  //   slot       = reverseIdx[8:5]                 (top bits)
  //   subIdxIn   = reverseIdx[4:0]                 (bottom 5 bits)
  // But `BitplaneReconstruct` already takes its own MSB-first bitIdx
  // (i.e. msbFirstIdx = 31 - bitIdx), so we feed the H-1 instance the
  // MSB-first sub-index by subtracting 31:
  //   subBitIdx = 31 - subIdxIn
  // To avoid the double inversion, take pixelIdx mod 32 directly as
  // subBitIdx within the word, and slot = pixelIdx / 32. This works
  // because each dout32 word stores its own bits MSB-first too —
  // pixelIdx 0 (leftmost) maps to slot 0 / bit-position 0 inside the
  // word, which `BitplaneReconstruct` correctly translates to MSB.
  val slot     = io.pixelIdx(pixelIdxBits - 1 downto subIdxBits)         // top bits
  val subIdx   = io.pixelIdx(subIdxBits - 1 downto 0)                    // bottom 5 bits

  val recon = BitplaneReconstruct(planeCount = planeCount, planeWidth = 32)
  // Drive BitplaneRowFetch's slot read port from the current slot index;
  // each plane's slot word feeds BitplaneReconstruct directly.
  // Replaces the prior wide-row slicing (`planeRows(p)(msb downto lsb)`
  // mux'd by slot) which forced multi-port read on `planeWords` and
  // blocked LUTRAM inference (CyanPeak HOLD #9325).
  rowFetch.io.slotIdx := slot
  for (p <- 0 until planeCount) {
    recon.io.planes(p) := rowFetch.io.slotWord(p)
  }
  recon.io.bitIdx := subIdx
  // Gate the pixel output until at least one row has been fetched. The
  // Mem-backed planeMems in BitplaneRowFetch (post-#9325 refactor) read
  // X/undefined before any write — so prior to the first SDRAM-driven
  // row fetch, recon.io.pixel could carry junk. PlanarIntegrationSim
  // exercises the integration boundary with a quiescent SDRAM (no
  // dataReady ever fires), and expects pixel=0 in that case.
  val hasRow = Reg(Bool()) init False
  when(rowFetch.io.rowReady) { hasRow := True }
  io.pixel := Mux(hasRow, recon.io.pixel, B(0, planeCount bits))
}
