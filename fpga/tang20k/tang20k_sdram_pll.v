// PLL for SDRAM clock generation on Tang Nano 20K.
//
// Task 15: generates 64.8 MHz main clock + 64.8 MHz 180-degree phase-shifted
// clock for sdram.v's clk / clk_sdram inputs.
//
// Parameter reconciliation (tool-proven formula from Gowin EX0311, msg 6601):
//   VCO    = FCLKIN * (FBDIV_SEL+1) * ODIV_SEL / (IDIV_SEL+1)
//   CLKOUT = VCO / ODIV_SEL  =  FCLKIN * (FBDIV_SEL+1) / (IDIV_SEL+1)
//   VCO must sit in the GW2AR window (tool reports 500-1250 MHz).
//
// FBDIV_SEL = 11, IDIV_SEL = 4, ODIV_SEL = 8:
//   CLKOUT = 27 * 12 / 5 = 64.8 MHz
//   VCO    = 64.8 * 8   = 518.4 MHz  (within 500-1250)
//
// PSDA_SEL = "1000" = 180° phase for CLKOUTP (per UG286 Table 5-7).
// DUTYDA_SEL = "1000" is the tool default; Gowin rejects "0000" and silently
// substitutes "1000", so pinning that value here matches what the tool emits.
// DEVICE = "GW2AR-18C" matches the family set in build.tcl.

module tang20k_sdram_pll (clkout, clkoutp, lock, clkin);

output clkout;
output clkoutp;
output lock;
input clkin;

wire clkoutd_o;
wire clkoutd3_o;
wire gw_gnd;

assign gw_gnd = 1'b0;

rPLL rpll_inst (
    .CLKOUT(clkout),
    .LOCK(lock),
    .CLKOUTP(clkoutp),
    .CLKOUTD(clkoutd_o),
    .CLKOUTD3(clkoutd3_o),
    .RESET(gw_gnd),
    .RESET_P(gw_gnd),
    .CLKIN(clkin),
    .CLKFB(gw_gnd),
    .FBDSEL({gw_gnd,gw_gnd,gw_gnd,gw_gnd,gw_gnd,gw_gnd}),
    .IDSEL({gw_gnd,gw_gnd,gw_gnd,gw_gnd,gw_gnd,gw_gnd}),
    .ODSEL({gw_gnd,gw_gnd,gw_gnd,gw_gnd,gw_gnd,gw_gnd}),
    .PSDA({gw_gnd,gw_gnd,gw_gnd,gw_gnd}),
    .DUTYDA({gw_gnd,gw_gnd,gw_gnd,gw_gnd}),
    .FDLY({gw_gnd,gw_gnd,gw_gnd,gw_gnd})
);

// 64.8 MHz SDRAM clock. VCO = 64.8 * 8 = 518.4 MHz.
defparam rpll_inst.FBDIV_SEL = 11;
defparam rpll_inst.IDIV_SEL = 4;
defparam rpll_inst.ODIV_SEL = 8;
defparam rpll_inst.FCLKIN = "27";
defparam rpll_inst.DYN_IDIV_SEL = "false";
defparam rpll_inst.DYN_FBDIV_SEL = "false";
defparam rpll_inst.DYN_ODIV_SEL = "false";
// PSDA_SEL="1000" = 180° (per UG286 Table 5-7).
// DUTYDA_SEL="1000" = tool default; "0000" is rejected by Gowin (see EX0205).
defparam rpll_inst.PSDA_SEL = "1000";
defparam rpll_inst.DYN_DA_EN = "false";
defparam rpll_inst.DUTYDA_SEL = "1000";
defparam rpll_inst.CLKOUT_FT_DIR = 1'b1;
defparam rpll_inst.CLKOUTP_FT_DIR = 1'b1;
defparam rpll_inst.CLKOUT_DLY_STEP = 0;
defparam rpll_inst.CLKOUTP_DLY_STEP = 0;
defparam rpll_inst.CLKFB_SEL = "internal";
defparam rpll_inst.CLKOUT_BYPASS = "false";
defparam rpll_inst.CLKOUTP_BYPASS = "false";
defparam rpll_inst.CLKOUTD_BYPASS = "false";
defparam rpll_inst.DYN_SDIV_SEL = 2;
defparam rpll_inst.CLKOUTD_SRC = "CLKOUT";
defparam rpll_inst.CLKOUTD3_SRC = "CLKOUT";
defparam rpll_inst.DEVICE = "GW2AR-18C";

endmodule
