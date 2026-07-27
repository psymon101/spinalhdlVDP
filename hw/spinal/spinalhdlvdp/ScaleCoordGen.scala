package spinalhdlvdp

import spinal.core._

/** ScaleCoordGen — source-coordinate scaler (external-review-scaler-rewrite,
  * external static review Priority 4/5; task PROJECT_PLAN/TASKS/external-review-scaler-rewrite.md).
  *
  * Replaces the sink-side `PixelRepeatScaler` ("scale after the compositor") with a
  * physical->logical COORDINATE GENERATOR placed BEFORE the renderer. The renderer
  * consumes (sourceX, sourceY) so the fetch/compositor advances at the LOGICAL rate:
  * each source pixel is naturally emitted for scaleX physical columns / scaleY
  * physical lines. This fixes the sink-side skip (compositor advances 1 source-px per
  * physical clock, so the sink samples P0 P2 P4 -> wrong P0 P0 P2 P2; source-coord =
  * correct P0 P0 P1 P1).
  *
  * IDENTITY at 1x: with scaleXEff==scaleYEff==1 and autoCenter off, sourceX equals the
  * physical active column and sourceY the physical active line -> byte-identical 1x
  * (the VdpTop integration muxes to the exact current hCounter/fillLine path in that case).
  *
  * Counter-based, NO divider: an x sub-counter 0..scaleXEff-1 gates sourceX steps; a
  * per-line y sub-counter gates sourceY. Centering via the bezel offset (offX/offY).
  * The clamp/fit/auto-center math is carried over UNCHANGED from PixelRepeatScaler
  * (already reviewed correct); only the coordinate generation is new.
  *
  * Checkpoint A (this file): compiling module with IO, carried clamp/fit/center math,
  * and the counter-based coordinate generation. Checkpoint B: golden-vector unit
  * co-sim (ScaleCoordGenSim) validates the exact reset/present timing (1x identity,
  * 2x/3x step + centering, silent clamp) and fixes any counter-phase off-by-one before
  * the VdpTop integration (P1). Until B passes, do NOT wire this into VdpTop.
  */
case class ScaleCoordGen() extends Component {
  val io = new Bundle {
    // --- physical scan position (from VdpTop hCounter/vCounter) ---
    val hCounter    = in UInt(10 bits)
    val vCounter    = in UInt(10 bits)
    // --- active-region constants (generic; not hardwired 640x480) ---
    val hActive     = in UInt(11 bits)
    val vActive     = in UInt(11 bits)
    // --- configuration (SCALE_CTRL fields + logical canvas dims) ---
    val scaleXReg   = in UInt(3 bits)   // raw 0..7; 0 and 1 both mean 1x
    val scaleYReg   = in UInt(3 bits)
    val autoCenter  = in Bool()
    val logicWidth  = in UInt(11 bits)  // 1..640
    val logicHeight = in UInt(11 bits)  // 1..480
    // --- logical coordinate outputs (consumed by the renderer) ---
    val sourceX     = out UInt(10 bits)
    val sourceY     = out UInt(10 bits)
    val sourceValid = out Bool()
    // --- border / effective-scale outputs (mirror PixelRepeatScaler for the border mux) ---
    val borderX0    = out UInt(10 bits)
    val borderX1    = out UInt(10 bits)
    val borderY0    = out UInt(10 bits)
    val borderY1    = out UInt(10 bits)
    val scaleXEffOut = out UInt(3 bits)
    val scaleYEffOut = out UInt(3 bits)
    val autoCenterActive = out Bool()
  }

  // --- Silent clamp + fit (carried verbatim from PixelRepeatScaler) ---
  // raw 0/1 -> 1x; 2..6 -> 2..6x; >6 clamps to 6. Then walk down until scale*logic <= active.
  private def liftScale(raw: UInt): UInt = {
    val sat = Mux(raw > U(6, 3 bits), U(6, 3 bits), raw)
    Mux(sat < U(1, 3 bits), U(1, 3 bits), sat).resize(3)
  }
  private val rawScaleX = liftScale(io.scaleXReg)
  private val rawScaleY = liftScale(io.scaleYReg)
  private def fitScale(req: UInt, logic: UInt, active: UInt): UInt = {
    val out = UInt(3 bits)
    out := U(1, 3 bits)
    for (k <- 1 to 6) {
      when((req >= U(k, 3 bits)) && ((logic * U(k, 4 bits)) <= active.resize(15))) {
        out := U(k, 3 bits)
      }
    }
    out
  }
  val scaleXEff = fitScale(rawScaleX, io.logicWidth,  io.hActive)
  val scaleYEff = fitScale(rawScaleY, io.logicHeight, io.vActive)
  // --- Auto-center bezel (combinational, config-derived) ---
  val visibleW = (io.logicWidth  * scaleXEff.resize(4)).resize(11)
  val visibleH = (io.logicHeight * scaleYEff.resize(4)).resize(11)
  val offX = Mux(io.autoCenter, ((io.hActive - visibleW) >> 1).resize(10), U(0, 10 bits))
  val offY = Mux(io.autoCenter, ((io.vActive - visibleH) >> 1).resize(10), U(0, 10 bits))
  val borderX1c = (offX + visibleW.resize(10)).resize(10)
  val borderY1c = (offY + visibleH.resize(10)).resize(10)

  // Reciprocal-multiply table (P4 timing-closure fix, replaces the per-pixel divide).
  // floor(x / s) == (x * ceil(2^18 / s)) >> 18 EXACTLY for x <= 1023 and s in 2..6: the
  // rounding error x*frac/2^18 < 1023/2^18 ~= 0.004, while the fractional parts of x/s are
  // multiples of 1/s >= 1/6 ~= 0.167, so floor is never perturbed. s==1 is identity
  // (bypassed). A 10x18 multiply is a short DSP path; the prior LUT divide was ~82 levels
  // and broke clk_pixel timing (14.7 MHz vs 25.2, TNS -435 ns).
  def recipOf(s: UInt): UInt = {
    val r = UInt(18 bits); r := U(131072, 18 bits)  // s=2 default
    switch(s) {
      is(U(2, 3 bits)) { r := U(131072, 18 bits) }
      is(U(3, 3 bits)) { r := U(87382,  18 bits) }
      is(U(4, 3 bits)) { r := U(65536,  18 bits) }
      is(U(5, 3 bits)) { r := U(52429,  18 bits) }
      is(U(6, 3 bits)) { r := U(43691,  18 bits) }
      default          { r := U(131072, 18 bits) }
    }
    r
  }

  // ===== Stage 1: register config-derived terms (change only on host config writes) =====
  // Registering these keeps the per-pixel path short — the fitScale compare chain and the
  // reciprocal select are OFF the critical path. A 1-cycle settle on config change is
  // harmless (config is written during setup, not mid-active-frame; sims use safe-boundary
  // waits). scaleXEff/scaleYEff are >=1 (fitScale floor).
  val scaleXEffR = RegNext(scaleXEff)          init U(1, 3 bits)
  val scaleYEffR = RegNext(scaleYEff)          init U(1, 3 bits)
  val offXR      = RegNext(offX)               init U(0, 10 bits)
  val offYR      = RegNext(offY)               init U(0, 10 bits)
  val recipXR    = RegNext(recipOf(scaleXEff)) init U(131072, 18 bits)
  val recipYR    = RegNext(recipOf(scaleYEff)) init U(131072, 18 bits)
  val logicWR    = RegNext(io.logicWidth.resize(10))  init U(640, 10 bits)
  val logicHR    = RegNext(io.logicHeight.resize(10)) init U(480, 10 bits)
  val borderX0R  = RegNext(offX)               init U(0, 10 bits)
  val borderX1R  = RegNext(borderX1c)          init U(640, 10 bits)
  val borderY0R  = RegNext(offY)               init U(0, 10 bits)
  val borderY1R  = RegNext(borderY1c)          init U(480, 10 bits)
  val autoCenterR = RegNext(io.autoCenter)     init False

  io.scaleXEffOut := scaleXEffR
  io.scaleYEffOut := scaleYEffR
  io.borderX0 := borderX0R
  io.borderX1 := borderX1R
  io.borderY0 := borderY0R
  io.borderY1 := borderY1R
  io.autoCenterActive := autoCenterR

  // ===== Stage 2: per-pixel physical->logical mapping (COMBINATIONAL, zero added latency) =====
  // sourceX = clamp( floor((hCounter - offX) / scaleXEff), 0, logicWidth-1 ) via reciprocal-mult.
  // COMBINATIONAL so at 1x (scaleXEff=1, offX=0) sourceX==hCounter exactly — the VdpTop
  // integration muxes to this identity path for byte-identical 1x. Uses the registered config
  // terms so only the short mult+clamp is on the per-pixel timing path.
  val pastBezelX = io.hCounter >= offXR
  val pastBezelY = io.vCounter >= offYR
  val relX = Mux(pastBezelX, (io.hCounter - offXR).resize(10), U(0, 10 bits))
  val relY = Mux(pastBezelY, (io.vCounter - offYR).resize(10), U(0, 10 bits))
  val divX = Mux(scaleXEffR === U(1, 3 bits), relX, ((relX * recipXR) >> 18).resize(10))
  val divY = Mux(scaleYEffR === U(1, 3 bits), relY, ((relY * recipYR) >> 18).resize(10))
  val sourceXc = Mux(divX >= logicWR, (logicWR - U(1, 10 bits)).resize(10), divX)
  val sourceYc = Mux(divY >= logicHR, (logicHR - U(1, 10 bits)).resize(10), divY)

  // Valid = inside the centered scaled active rectangle [offX, offX+visibleW) x [offY, offY+visibleH).
  val validX = pastBezelX && (io.hCounter < borderX1R)
  val validY = pastBezelY && (io.vCounter < borderY1R)

  // Register the coordinate outputs (P4 timing-closure): isolates the reciprocal multiply
  // between registers so the mult and the downstream renderer each fit the pixel clock
  // (combinational Y mult -> lineBuf was -2.2 ns). +1 cycle of latency in SCALED modes only;
  // at 1x the VdpTop integration muxes to hCounter/fillLine (sourceX/Y unused) so 1x stays
  // byte-identical, and the >1x proof is phase-independent (run-lengths), so a uniform +1
  // shift is immaterial. Within an active line vCounter is stable, so sourceY carries no
  // intra-line shift. The unit co-sim reads one cycle after each poke, so vectors are exact.
  io.sourceX := RegNext(sourceXc) init U(0, 10 bits)
  io.sourceY := RegNext(sourceYc) init U(0, 10 bits)
  io.sourceValid := RegNext(validX && validY) init False
}
