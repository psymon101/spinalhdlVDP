package spinalhdlvdp

import spinal.core._

/** R1 Raster Trigger Unit (first roadmap-derived primitive).
  *
  * Emits a one-cycle `triggerPulse` and sets a sticky `pending` latch when the
  * current beam position matches the programmed (line, pixel) tuple. Pixel
  * compare is optional; with `pixelCmpEnable = False` the trigger fires at the
  * start of the matching line (hCounter == 0).
  *
  * Edge-detected match so the pulse is always exactly one cycle wide even when
  * the compare condition holds for many cycles (e.g. line-only compare, where
  * `vCounter == triggerLine` spans the whole scanline).
  *
  * Status/control naming is kept simple so a later Mode0 register bus can
  * adopt this interface without behavioral change.
  */
case class RasterTriggerUnit() extends Component {
  val io = new Bundle {
    // Beam inputs
    val vCounter = in UInt(10 bits)
    val hCounter = in UInt(10 bits)

    // Programmable compare
    val triggerLine    = in UInt(10 bits)
    val triggerPixel   = in UInt(10 bits)
    val pixelCmpEnable = in Bool()
    val enable         = in Bool()

    // Sticky-status clear / ack
    val clear = in Bool()

    // Outputs
    val triggerPulse = out Bool()
    val pending      = out Bool()
    val irq          = out Bool()
  }

  // Compare
  val lineMatch  = io.vCounter === io.triggerLine
  val pixelMatch = Mux(io.pixelCmpEnable,
                       io.hCounter === io.triggerPixel,
                       io.hCounter === U(0, 10 bits))
  val match_     = io.enable && lineMatch && pixelMatch

  // Edge detect (prevents retrigger across multi-cycle match windows).
  val matchPrev  = RegNext(match_) init False
  val matchRise  = match_ && !matchPrev
  io.triggerPulse := matchRise

  // Sticky status: set on rise, cleared by ack.
  val pendingReg = Reg(Bool()) init False
  when(matchRise) { pendingReg := True }
  when(io.clear)  { pendingReg := False }
  io.pending := pendingReg
  io.irq     := pendingReg
}
