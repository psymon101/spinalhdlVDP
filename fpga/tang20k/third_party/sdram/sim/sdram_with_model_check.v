`timescale 1ns/1ps
// sdram_with_model_check — same logic-side wrapper as sdram_with_model.v (real
// sdram.v controller + behavioral sdram_model, tristate hidden) BUT with the
// model's timing-violation assertion layer ENABLED (TIMING_CHECK=1) and its
// burst-aware row-coverage tREF check parameterized, for the SDRAM-BURST-REFRESH
// data-survival cosim (P16, #12000). REF_ROWS/TREF_CK are scaled by the TB; the
// distributed-mode tREFI watchdog is disabled (burst intentionally has big gaps).
module sdram_with_model_check #(
  parameter FREQ     = 1_000_000,
  parameter REF_ROWS = 2048,
  parameter TREF_CK  = 4147200,
  parameter WARMUP_CK = 250
) (
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
  wire [31:0] SDRAM_DQ;
  wire [10:0] SDRAM_A;
  wire [1:0]  SDRAM_BA;
  wire        SDRAM_nCS, SDRAM_nWE, SDRAM_nRAS, SDRAM_nCAS, SDRAM_CLK, SDRAM_CKE;
  wire [3:0]  SDRAM_DQM;

  sdram #(.FREQ(FREQ), .CAS(2), .T_WR(2), .T_MRD(2), .T_RP(2), .T_RCD(2), .T_RC(4)) ctrl (
    .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA), .SDRAM_nCS(SDRAM_nCS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_CLK(SDRAM_CLK), .SDRAM_CKE(SDRAM_CKE), .SDRAM_DQM(SDRAM_DQM),
    .clk(clk), .clk_sdram(clk_sdram), .resetn(resetn),
    .rd(rd), .wr(wr), .refresh(refresh), .addr(addr), .din(din),
    .dout(dout), .dout32(dout32), .data_ready(data_ready), .busy(busy));

  // Behavioral chip with the timing-assertion layer ENABLED.
  sdram_model #(.CAS(2), .TIMING_CHECK(1), .REF_ROWS(REF_ROWS), .tREF_CK(TREF_CK),
                .tREFI_CK(100000000), .WARMUP_CK(WARMUP_CK)) chip (
    .clk(clk_sdram), .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA),
    .SDRAM_nCS(SDRAM_nCS), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_DQM(SDRAM_DQM));
endmodule
