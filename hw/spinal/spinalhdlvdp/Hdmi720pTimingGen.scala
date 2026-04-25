package spinalhdlvdp

import spinal.core._

/** 1280×720 @ 60 Hz CEA-861 VIC 4 timing generator.
  *
  * Pixel clock: 74.25 MHz (= 27 × 55 / 4 from the rPLL, then /5 in
  * CLKDIV). Frame rate: 74.25 MHz / (1650 × 750) = 60.000 Hz exactly.
  * TMDS serial rate: 742.5 Mbps/lane, well below the GW2AR-18 OSER10
  * 1200 Mbps maximum (CoralReef #8475 §Q2).
  *
  * Synchronisation polarity is **active high** for both hsync and
  * vsync, per CEA-861 HD modes — opposite of the project's current
  * 640×480 VESA path. The HDMI receiver picks up the polarity from
  * AVI InfoFrames (or, on legacy DVI receivers, infers from sync
  * polarity / pulse width); active-high HSYNC/VSYNC is the de-facto
  * universal HD signalling.
  *
  * Scope (BronzeGate #8482, Slice B):
  *   - Pure timing generator. No content. No connection to VdpTop.
  *   - Drives `Hdmi720pSyntheticSource` for the synthetic-source proof.
  *   - The clean-start mute (`HdmiCleanStart`, Slice A) sits in the
  *     scenario wrapper that hosts this timing generator.
  *
  * Outputs follow the project convention: `hsync`/`vsync` carry the
  * raw active-high pulses; `de` is high during the 1280×720 visible
  * region.
  */
case class Hdmi720pTimingGen() extends Component {
  // CEA-861 VIC 4 (1280×720p @ 60 Hz) horizontal/vertical totals.
  val hActive = 1280
  val hFront  = 110
  val hSync   = 40
  val hBack   = 220
  val hTotal  = hActive + hFront + hSync + hBack   // 1650

  val vActive = 720
  val vFront  = 5
  val vSync   = 5
  val vBack   = 20
  val vTotal  = vActive + vFront + vSync + vBack   // 750

  val io = new Bundle {
    val x     = out UInt(11 bits)   // visible pixel x; 0..hActive-1 when de==1
    val y     = out UInt(10 bits)   // visible pixel y; 0..vActive-1 when de==1
    val hsync = out Bool()          // active-high CEA pulse
    val vsync = out Bool()          // active-high CEA pulse
    val de    = out Bool()          // visible-area data-enable
  }

  val hCounter = Reg(UInt(log2Up(hTotal) bits)) init 0
  val vCounter = Reg(UInt(log2Up(vTotal) bits)) init 0

  when(hCounter === U(hTotal - 1, hCounter.getWidth bits)) {
    hCounter := 0
    when(vCounter === U(vTotal - 1, vCounter.getWidth bits)) {
      vCounter := 0
    } otherwise {
      vCounter := vCounter + 1
    }
  } otherwise {
    hCounter := hCounter + 1
  }

  // Pulse boundaries.
  val hSyncStart = U(hActive + hFront, hCounter.getWidth bits)
  val hSyncEnd   = U(hActive + hFront + hSync, hCounter.getWidth bits)
  val vSyncStart = U(vActive + vFront, vCounter.getWidth bits)
  val vSyncEnd   = U(vActive + vFront + vSync, vCounter.getWidth bits)

  io.hsync := hCounter >= hSyncStart && hCounter < hSyncEnd
  io.vsync := vCounter >= vSyncStart && vCounter < vSyncEnd
  io.de    := hCounter < U(hActive, hCounter.getWidth bits) &&
              vCounter < U(vActive, vCounter.getWidth bits)
  io.x := hCounter.resize(11)
  io.y := vCounter.resize(10)
}
