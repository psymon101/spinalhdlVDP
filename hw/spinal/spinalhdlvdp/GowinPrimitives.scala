package spinalhdlvdp

import spinal.core._

/** Gowin GW2AR-18 rPLL primitive.
  *
  * Defaults match the proven 126 MHz path used by `TopTang20kHdmi`
  * (27 MHz × (FBDIV+1)/(IDIV+1) = 27 × 14 / 3 = 126 MHz; ODIV=8 sets
  * the post-divider). The 720p HDMI compatibility lane (#8482) needs a
  * different config (IDIV=3, FBDIV=54, ODIV=2 → 27 × 55 / 4 = 371.25
  * MHz, VCO 742.5 MHz — proven on the same Tang Nano 20K hardware in
  * the older `/home/itadmin/github/VDP` baseline). Constructor
  * parameters allow each instance to pick its own divider set without
  * touching call sites that rely on the existing default.
  */
case class GowinRpll(idivSel: Int = 2, fbdivSel: Int = 13, odivSel: Int = 8)
    extends BlackBox {
  setDefinitionName("rPLL")
  noIoPrefix()

  addGeneric("FCLKIN", "27")
  addGeneric("DYN_IDIV_SEL", "false")
  addGeneric("IDIV_SEL", idivSel)
  addGeneric("DYN_FBDIV_SEL", "false")
  addGeneric("FBDIV_SEL", fbdivSel)
  addGeneric("DYN_ODIV_SEL", "false")
  addGeneric("ODIV_SEL", odivSel)
  addGeneric("PSDA_SEL", "0000")
  addGeneric("DYN_DA_EN", "true")
  addGeneric("DUTYDA_SEL", "1000")
  addGeneric("CLKOUT_FT_DIR", true)
  addGeneric("CLKOUTP_FT_DIR", true)
  addGeneric("CLKOUT_DLY_STEP", 0)
  addGeneric("CLKOUTP_DLY_STEP", 0)
  addGeneric("CLKFB_SEL", "internal")
  addGeneric("CLKOUT_BYPASS", "false")
  addGeneric("CLKOUTP_BYPASS", "false")
  addGeneric("CLKOUTD_BYPASS", "false")
  addGeneric("DYN_SDIV_SEL", 2)
  addGeneric("CLKOUTD_SRC", "CLKOUT")
  addGeneric("CLKOUTD3_SRC", "CLKOUT")
  addGeneric("DEVICE", "GW2AR-18C")

  val CLKOUT = out Bool()
  val CLKOUTP = out Bool()
  val CLKOUTD = out Bool()
  val CLKOUTD3 = out Bool()
  val LOCK = out Bool()
  val CLKIN = in Bool()
  val CLKFB = in Bool()
  val FBDSEL = in Bits(6 bits)
  val IDSEL = in Bits(6 bits)
  val ODSEL = in Bits(6 bits)
  val DUTYDA = in Bits(4 bits)
  val PSDA = in Bits(4 bits)
  val FDLY = in Bits(4 bits)
  val RESET = in Bool()
  val RESET_P = in Bool()
}

case class GowinClkdiv() extends BlackBox {
  setDefinitionName("CLKDIV")
  noIoPrefix()

  addGeneric("DIV_MODE", "5")
  addGeneric("GSREN", "false")

  val CLKOUT = out Bool()
  val CALIB = in Bool()
  val HCLKIN = in Bool()
  val RESETN = in Bool()
}

case class GowinOser10() extends BlackBox {
  setDefinitionName("OSER10")
  noIoPrefix()

  addGeneric("GSREN", "false")
  addGeneric("LSREN", "true")

  val Q = out Bool()
  val D0 = in Bool()
  val D1 = in Bool()
  val D2 = in Bool()
  val D3 = in Bool()
  val D4 = in Bool()
  val D5 = in Bool()
  val D6 = in Bool()
  val D7 = in Bool()
  val D8 = in Bool()
  val D9 = in Bool()
  val PCLK = in Bool()
  val FCLK = in Bool()
  val RESET = in Bool()
}

case class GowinTlvdsObuf() extends BlackBox {
  setDefinitionName("TLVDS_OBUF")
  noIoPrefix()

  val O = out Bool()
  val OB = out Bool()
  val I = in Bool()
}

// Gowin IOBUF — bidirectional I/O buffer used for tri-state pads.
//   I   : data driven onto IO when OEN=0
//   OEN : active-low output enable (OEN=0 -> drive, OEN=1 -> high-Z / input)
//   O   : data sensed from IO
//   IO  : physical bidirectional pad
case class GowinIobuf() extends BlackBox {
  setDefinitionName("IOBUF")
  noIoPrefix()

  val O   = out Bool()
  val IO  = inout(Analog(Bool()))
  val I   = in Bool()
  val OEN = in Bool()
}
