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
  io.scaleXEffOut := scaleXEff
  io.scaleYEffOut := scaleYEff

  // --- Auto-center bezel (carried from PixelRepeatScaler) ---
  val visibleW = (io.logicWidth  * scaleXEff.resize(4)).resize(11)
  val visibleH = (io.logicHeight * scaleYEff.resize(4)).resize(11)
  val offX = Mux(io.autoCenter, ((io.hActive - visibleW) >> 1).resize(10), U(0, 10 bits))
  val offY = Mux(io.autoCenter, ((io.vActive - visibleH) >> 1).resize(10), U(0, 10 bits))
  io.borderX0 := offX
  io.borderX1 := (offX + visibleW.resize(10)).resize(10)
  io.borderY0 := offY
  io.borderY1 := (offY + visibleH.resize(10)).resize(10)
  io.autoCenterActive := io.autoCenter

  // --- Horizontal source-coordinate counter ---
  // Reset at the start of each physical line (hCounter==0). Once past the left bezel,
  // advance a sub-counter 0..scaleXEff-1; step srcX on wrap; hold at logicWidth (right bezel).
  val lineStart  = io.hCounter === U(0, 10 bits)
  val pastBezelX = io.hCounter >= offX
  val xSub = Reg(UInt(3 bits))  init 0
  val srcX = Reg(UInt(10 bits)) init 0
  when(lineStart) {
    xSub := 0
    srcX := 0
  } elsewhen(pastBezelX && (srcX < io.logicWidth.resize(10))) {
    when(xSub === (scaleXEff - U(1, 3 bits)).resize(3)) {
      xSub := 0
      srcX := srcX + 1
    } otherwise {
      xSub := xSub + 1
    }
  }
  io.sourceX := Mux(srcX >= io.logicWidth.resize(10),
                    (io.logicWidth.resize(10) - U(1, 10 bits)).resize(10),
                    srcX)

  // --- Vertical source-coordinate counter (advances once per physical line) ---
  val frameStart = lineStart && (io.vCounter === U(0, 10 bits))
  val pastBezelY = io.vCounter >= offY
  val ySub = Reg(UInt(3 bits))  init 0
  val srcY = Reg(UInt(10 bits)) init 0
  when(frameStart) {
    ySub := 0
    srcY := 0
  } elsewhen(lineStart && pastBezelY && (srcY < io.logicHeight.resize(10))) {
    when(ySub === (scaleYEff - U(1, 3 bits)).resize(3)) {
      ySub := 0
      srcY := srcY + 1
    } otherwise {
      ySub := ySub + 1
    }
  }
  io.sourceY := Mux(srcY >= io.logicHeight.resize(10),
                    (io.logicHeight.resize(10) - U(1, 10 bits)).resize(10),
                    srcY)

  // Valid = inside the centered scaled active rectangle [offX, offX+visibleW) x [offY, offY+visibleH).
  val validX = pastBezelX && (io.hCounter < io.borderX1)
  val validY = pastBezelY && (io.vCounter < io.borderY1)
  io.sourceValid := validX && validY
}
