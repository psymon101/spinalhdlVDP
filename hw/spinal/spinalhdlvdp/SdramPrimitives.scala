package spinalhdlvdp

import spinal.core._

/** BlackBox wrapper for the SDRAM PLL (tang20k_sdram_pll.v). */
case class Tang20kSdramPll() extends BlackBox {
  setDefinitionName("tang20k_sdram_pll")
  val clkin  = in Bool()
  val clkout = out Bool()
  val clkoutp = out Bool()
  val lock   = out Bool()
  noIoPrefix()
}

/** BlackBox wrapper for the nand2mario SDRAM controller (sdram.v).
  *
  * Directly maps to the sdram module interface. User logic interacts through
  * the logic-side signals (rd, wr, refresh, addr, din, dout, busy, data_ready).
  */
case class SdramController() extends BlackBox {
  setDefinitionName("sdram")
  addGeneric("FREQ", 27_000_000)

  val io = new Bundle {
    // SDRAM side (directly connected to Gowin's magic port names in the top wrapper)
    val SDRAM_DQ   = inout(Analog(Bits(32 bits)))
    val SDRAM_A    = out Bits(11 bits)
    val SDRAM_BA   = out Bits(2 bits)
    val SDRAM_nCS  = out Bool()
    val SDRAM_nWE  = out Bool()
    val SDRAM_nRAS = out Bool()
    val SDRAM_nCAS = out Bool()
    val SDRAM_CLK  = out Bool()
    val SDRAM_CKE  = out Bool()
    val SDRAM_DQM  = out Bits(4 bits)

    // Logic side
    val clk        = in Bool()
    val clk_sdram  = in Bool()
    val resetn     = in Bool()
    val rd         = in Bool()
    val wr         = in Bool()
    val refresh    = in Bool()
    val addr       = in UInt(23 bits)
    val din        = in Bits(8 bits)
    val dout       = out Bits(8 bits)
    val dout32     = out Bits(32 bits)
    val data_ready = out Bool()
    val busy       = out Bool()
  }
  noIoPrefix()
  mapCurrentClockDomain(io.clk)
}
