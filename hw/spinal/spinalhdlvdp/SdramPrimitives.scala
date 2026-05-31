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
  // FREQ=64.8M fixes the 83us->200us init bug (#11034).
  addGeneric("FREQ", 64_800_000)
  // #11123 FIX 3 (CyanPeak #11122): at 64.8 MHz (15.43 ns/cycle), T_RCD=1/T_RP=1
  // give only 15.43 ns < EM638325 spec (tRCD/tRP = 18 ns). Raise both to 2
  // (30.86 ns >= 18 ns). T_RC stays at the sdram.v default 4 — CRITICAL: the
  // CONFIG sequence matches {CONFIG, T_RP+2*T_RC+T_MRD} on a 4-bit cycle counter
  // that saturates at 15, so raising T_RC to 6 (the old TEST-3 attempt) gave
  // 2+6+6+2=16 > 15 -> CONFIG never completes -> SDRAM dead. That was the TEST-3
  // breakage, NOT T_RP/T_RCD. With T_RC=4: CONFIG=12, WRITE=T_RCD+T_WR+T_RP=6,
  // both <=15. Sim-proven on the real sdram.v (#11134): CONFIG completes, WRITE
  // issues at cycle T_RCD=2, busy clears at 6; T_RC=6 reproduces the CONFIG hang.
  addGeneric("T_RP",  2)
  addGeneric("T_RCD", 2)

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
