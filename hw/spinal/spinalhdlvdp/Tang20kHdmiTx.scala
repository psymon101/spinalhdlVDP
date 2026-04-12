package spinalhdlvdp

import spinal.core._

case class Tang20kHdmiTx() extends BlackBox {
  setDefinitionName("tang20k_hdmi_tx")
  noIoPrefix()

  val clk_pixel = in Bool()
  val clk_pixel_x5 = in Bool()
  val reset = in Bool()
  val hsync = in Bool()
  val vsync = in Bool()
  val de = in Bool()
  val red = in Bits(8 bits)
  val green = in Bits(8 bits)
  val blue = in Bits(8 bits)
  val tmds_clk_p = out Bool()
  val tmds_clk_n = out Bool()
  val tmds_data_p = out Bits(3 bits)
  val tmds_data_n = out Bits(3 bits)
}
