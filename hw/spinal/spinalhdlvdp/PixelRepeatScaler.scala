package spinalhdlvdp

import spinal.core._

/** PixelRepeatScaler — integer pixel-repetition scaler for the VDP output path
  * (lane #10590, Path B "scale at sink" per TopazCliff #10599).
  *
  * Sits between the compositor/border-mux output and the HDMI TX RGB input.
  * When bypass is asserted (or scaleX=scaleY=1) the scaler is fully transparent:
  * the compositor RGB passes through with the same timing the VDP has always
  * produced.
  *
  * When scaling is active:
  *   - Compositor still renders 640x480 native (no compositor changes — that
  *     would be Path A and a different lane). The scaler captures a fresh
  *     line into a BSRAM line buffer once every `scaleY` physical lines, then
  *     replays the buffered line for the remaining `scaleY-1` lines.
  *   - Horizontal repeat: the scaler latches each new logical pixel boundary
  *     and outputs the same RGB value for `scaleX` consecutive physical
  *     pixels.
  *
  * Sync (`hsync`/`vsync`/`de`) is NOT stretched — HDMI TX expects fixed
  * 640x480@60 VESA timing.
  *
  * Checkpoint A: this file is a compiling skeleton with the IO bundle, clamp
  * math, and bypass mux declared. Body fill (line buffer + counters + autocenter
  * border overrides) is Checkpoint B.
  */
case class PixelRepeatScaler() extends Component {
  val io = new Bundle {
    // --- Pixel stream from compositor/border-mux ---
    val inRgb       = in Bits(24 bits)

    // --- Physical timing position (from VdpTop hCounter/vCounter) ---
    val hCounter    = in UInt(10 bits)
    val vCounter    = in UInt(10 bits)
    val hsyncRising = in Bool()
    val vsyncRising = in Bool()

    // --- Active-region timing constants (passed in so the scaler is generic
    //     and doesn't hardwire 640x480) ---
    val hActive     = in UInt(11 bits)
    val vActive     = in UInt(11 bits)

    // --- Configuration (from VdpTop register file) ---
    val scaleXReg     = in UInt(3 bits)   // raw 0..7; 0 and 1 both mean 1x
    val scaleYReg     = in UInt(3 bits)
    val autoCenter    = in Bool()
    val logicWidth    = in UInt(11 bits)  // 1..640
    val logicHeight   = in UInt(11 bits)  // 1..480

    // --- Outputs ---
    val outRgb        = out Bits(24 bits)

    // --- Auto-center hints for the border-mux (driven combinationally from
    //     the clamped scale + logic dims; commit-on-vsync handled here so the
    //     border-mux can use these values for the next active region). ---
    val acBorderX0    = out UInt(10 bits)
    val acBorderX1    = out UInt(10 bits)
    val acBorderY0    = out UInt(10 bits)
    val acBorderY1    = out UInt(10 bits)
    val acBorderActive = out Bool()
  }

  // --- Silent clamp (CyanPeak #10596 item 4) ---
  // Interpret raw register values: 0 or 1 → 1x passthrough. 2..6 → 2..6x.
  // Anything >6 (only 7 is reachable since the field is 3 bits) is clamped
  // to 6. Then apply the "scale*logic must fit in hActive" silent clamp.
  private def liftScale(raw: UInt): UInt = {
    val sat = Mux(raw > U(6, 3 bits), U(6, 3 bits), raw)
    Mux(sat < U(1, 3 bits), U(1, 3 bits), sat).resize(3)
  }
  private val rawScaleX = liftScale(io.scaleXReg)
  private val rawScaleY = liftScale(io.scaleYReg)

  // scale * logicWidth must be <= hActive — if it would overflow we walk
  // scaleX downward until it fits. Combinational priority encoder over the
  // legal range (1..6) is fine — it's six terms.
  private def fitScale(req: UInt, logic: UInt, active: UInt): UInt = {
    val out = UInt(3 bits)
    val cand = (1 to 6).reverse.map { k =>
      (req >= U(k, 3 bits)) && ((logic * U(k, 4 bits)) <= active)
    }
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

  // --- Bypass condition: 1x in both axes AND autoCenter is off ---
  val bypass = (scaleXEff === U(1, 3 bits)) &&
               (scaleYEff === U(1, 3 bits)) &&
               !io.autoCenter

  // --- Auto-center math (combinational; latched on vsyncRising in the host) ---
  // visibleW = scaleXEff * logicWidth; bezelW = hActive - visibleW; offsetX = bezelW/2.
  // Inside the bezel the border-mux should paint the BACKDROP. Outside the
  // bezel (the centered active region) the scaled image renders.
  val visibleW = (io.logicWidth  * scaleXEff.resize(4)).resize(11)
  val visibleH = (io.logicHeight * scaleYEff.resize(4)).resize(11)
  val bezelW   = (io.hActive - visibleW).resize(11)
  val bezelH   = (io.vActive - visibleH).resize(11)
  val offX     = (bezelW >> 1).resize(10)
  val offY     = (bezelH >> 1).resize(10)
  io.acBorderX0     := offX
  io.acBorderX1     := (offX + visibleW.resize(10)).resize(10)
  io.acBorderY0     := offY
  io.acBorderY1     := (offY + visibleH.resize(10)).resize(10)
  io.acBorderActive := io.autoCenter

  // --- Checkpoint A skeleton: bypass-only output path. Checkpoint B fills
  //     the line buffer + horizontal/vertical repeat counters. ---
  // TODO(#10590 Checkpoint B): replace with scaled path under !bypass.
  io.outRgb := io.inRgb
}
