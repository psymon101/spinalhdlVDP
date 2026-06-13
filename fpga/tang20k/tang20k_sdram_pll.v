// PLL for SDRAM clock generation on Tang Nano 20K.
//
// Generates the 40.5 MHz SDRAM main clock + its 180-degree phase-shifted companion
// for sdram.v's clk / clk_sdram inputs. (Originally 64.8 MHz under Task 15; lowered to
// 40.5 MHz under #11197 Option A — see the defparam block below — to widen the analog
// address-capture window. The "64.8 MHz" Task-15 wording was stale.)
//
// Parameter reconciliation (tool-proven formula from Gowin EX0311, msg 6601):
//   VCO    = FCLKIN * (FBDIV_SEL+1) * ODIV_SEL / (IDIV_SEL+1)
//   CLKOUT = VCO / ODIV_SEL  =  FCLKIN * (FBDIV_SEL+1) / (IDIV_SEL+1)
//   VCO must sit in the GW2AR window (tool reports 500-1250 MHz).
//
// #11197 Option A (lower SDRAM clock to widen the analog address-capture window;
// closes the 0xA000<->0xB000 row aliasing at 64.8MHz). Target ~40 MHz: EXACT 40
// is irreducible 40/27 -> IDIV+1=27 -> PFD=1MHz (below the rPLL minimum, won't
// lock). 40.5 MHz = 27*3/2 is the clean achievable point with a healthy PFD.
// FBDIV_SEL = 2, IDIV_SEL = 1, ODIV_SEL = 16:
//   CLKOUT = 27 * 3 / 2 = 40.5 MHz   (180deg capture window 12.35ns vs 7.7ns)
//   VCO    = 40.5 * 16  = 648 MHz    (within 500-1250)
//   PFD    = 27 / 2     = 13.5 MHz   (vs the proven 5.4MHz; comfortably valid)
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

// #11197 Option A: 40.5 MHz SDRAM clock (was 64.8 MHz). VCO = 40.5 * 16 = 648 MHz.
defparam rpll_inst.FBDIV_SEL = 2;
defparam rpll_inst.IDIV_SEL = 1;
defparam rpll_inst.ODIV_SEL = 16;
defparam rpll_inst.FCLKIN = "27";
defparam rpll_inst.DYN_IDIV_SEL = "false";
defparam rpll_inst.DYN_FBDIV_SEL = "false";
defparam rpll_inst.DYN_ODIV_SEL = "false";
// PSDA_SEL="1000" = 180° phase for CLKOUTP (per UG286 Table 5-7).
// #11168 FIX B TRIED + REVERTED: 90° ("0100") was expected to center the read
// eye, but STA showed it made the WRITE output path WORSE (-8.635 vs -4.777 at
// 180°) — 90° gives the addr/cmd/write-DQ outputs only ~2.36ns budget. Kept at
// 180° pending CyanPeak STA reconciliation (the residual write-path violation
// magnitude looks like a clock-routing/insertion-delay or modeling artifact).
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
