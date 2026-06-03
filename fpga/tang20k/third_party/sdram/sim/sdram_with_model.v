`timescale 1ns/1ps
// CP-A4 (Phase A #11444/#11446) — logic-side-only wrapper of the REAL sdram.v
// controller + the behavioral sdram_model chip, wired together INTERNALLY so the
// tristate SDRAM_DQ bus is fully hidden. Exposes only the controller's logic side
// (rd/wr/refresh/addr/din/dout/dout32/data_ready/busy) — no inout at the module
// boundary — so it can be instantiated as a clean SpinalHDL BlackBox and compiled
// by Verilator for the SpinalSim integration handshake proof. FREQ defaults small
// so the 200us init counter is short in sim (FREQ/1000*200/1000 cycles).
module sdram_with_model #(parameter FREQ = 1_000_000) (
  input             clk,
  input             clk_sdram,
  input             resetn,
  input             rd,
  input             wr,
  input             refresh,
  input  [22:0]     addr,
  input  [7:0]      din,
  output [7:0]      dout,
  output [31:0]     dout32,
  output            data_ready,
  output            busy
);
  // Internal SDRAM bus between the real controller and the behavioral chip.
  wire [31:0] SDRAM_DQ;
  wire [10:0] SDRAM_A;
  wire [1:0]  SDRAM_BA;
  wire        SDRAM_nCS, SDRAM_nWE, SDRAM_nRAS, SDRAM_nCAS, SDRAM_CLK, SDRAM_CKE;
  wire [3:0]  SDRAM_DQM;

  // REAL controller (same params as the production SdramController blackbox:
  // T_RP=2, T_RCD=2; CAS/T_WR/T_MRD/T_RC at sdram.v defaults that the cosim proved).
  sdram #(.FREQ(FREQ), .CAS(2), .T_WR(2), .T_MRD(2), .T_RP(2), .T_RCD(2), .T_RC(4)) ctrl (
    .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA), .SDRAM_nCS(SDRAM_nCS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_CLK(SDRAM_CLK), .SDRAM_CKE(SDRAM_CKE), .SDRAM_DQM(SDRAM_DQM),
    .clk(clk), .clk_sdram(clk_sdram), .resetn(resetn),
    .rd(rd), .wr(wr), .refresh(refresh), .addr(addr), .din(din),
    .dout(dout), .dout32(dout32), .data_ready(data_ready), .busy(busy));

  // Behavioral chip; samples on clk_sdram (180-deg), exactly as the controller intends.
  sdram_model #(.CAS(2)) chip (
    .clk(clk_sdram), .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA),
    .SDRAM_nCS(SDRAM_nCS), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_DQM(SDRAM_DQM));
endmodule
