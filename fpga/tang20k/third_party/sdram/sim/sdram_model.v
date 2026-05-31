`timescale 1ns/1ps
// Behavioral EM638325-style SDR SDRAM model for #11162 real-RTL co-simulation.
// Pairs with the project's nand2mario sdram.v controller. Cycle-accurate
// FUNCTIONAL model (no analog setup/hold) — it samples on the 180-deg SDRAM
// clock (clk_sdram / SDRAM_CLK) exactly as the controller intends, so it
// isolates FUNCTIONAL correctness (command sequence, address decode, DQM byte
// masking, refresh, CAS read latency) from physical timing margin (Finding 3).
//
// Geometry matches sdram.v: BANK=addr[22:21], ROW(11)=addr[20:10],
// COL(8)=addr[9:2], byte-lane=addr[1:0]. 32-bit words.
module sdram_model #(
  parameter CAS = 2
) (
  input              clk,          // sample clock = controller clk_sdram (180-deg)
  inout  [31:0]      SDRAM_DQ,
  input  [10:0]      SDRAM_A,
  input  [1:0]       SDRAM_BA,
  input              SDRAM_nCS,
  input              SDRAM_nRAS,
  input              SDRAM_nCAS,
  input              SDRAM_nWE,
  input  [3:0]       SDRAM_DQM
);
  // Command encoding {nRAS,nCAS,nWE} (nCS=0 selected) — matches sdram.v.
  localparam [2:0] CMD_MRS=3'b000, CMD_REF=3'b001, CMD_PRE=3'b010,
                   CMD_ACT=3'b011, CMD_WR=3'b100, CMD_RD=3'b101, CMD_NOP=3'b111;
  wire [2:0] cmd = SDRAM_nCS ? CMD_NOP : {SDRAM_nRAS, SDRAM_nCAS, SDRAM_nWE};

  // Backing store: 2^21 = 2,097,152 32-bit words (8 MB host). Word address =
  // {bank[1:0], row[10:0], col[7:0]}.
  reg [31:0] mem [0:2097151];
  reg [10:0] act_row [0:3];        // active row per bank (latched at ACTIVATE)

  // Read-data pipeline: drive DQ exactly CAS cycles after the READ command.
  reg [31:0] rd_word;
  reg [3:0]  rd_timer;
  reg        rd_active;
  reg        refreshed;            // sticky: at least one AUTO_REFRESH seen

  function [20:0] word_addr;
    input [1:0] bank; input [10:0] row; input [7:0] col;
    word_addr = {bank, row, col};
  endfunction

  integer i;
  initial begin
    rd_active = 1'b0; rd_timer = 0; rd_word = 0; refreshed = 1'b0;
    for (i = 0; i < 4; i = i + 1) act_row[i] = 0;
    // EM638325 powers up undefined; init to a NON-zero, NON-0xFFFF sentinel so a
    // "stuck/uninitialised read" is visibly distinct from real written data.
    for (i = 0; i < 2097151; i = i + 1) mem[i] = 32'hDEAD_BEEF;
  end

  wire [7:0] col = SDRAM_A[7:0];   // column = A[7:0] (sdram.v puts addr[9:2] here)

  always @(posedge clk) begin
    case (cmd)
      CMD_ACT: act_row[SDRAM_BA] <= SDRAM_A[10:0];
      CMD_WR: begin
        // write data is presented coincident with the WRITE command (SDR).
        // DQM bit i HIGH = mask lane i (do not write). sdram.v drives ~be.
        if (!SDRAM_DQM[0]) mem[word_addr(SDRAM_BA, act_row[SDRAM_BA], col)][7:0]   <= SDRAM_DQ[7:0];
        if (!SDRAM_DQM[1]) mem[word_addr(SDRAM_BA, act_row[SDRAM_BA], col)][15:8]  <= SDRAM_DQ[15:8];
        if (!SDRAM_DQM[2]) mem[word_addr(SDRAM_BA, act_row[SDRAM_BA], col)][23:16] <= SDRAM_DQ[23:16];
        if (!SDRAM_DQM[3]) mem[word_addr(SDRAM_BA, act_row[SDRAM_BA], col)][31:24] <= SDRAM_DQ[31:24];
      end
      CMD_RD: begin
        rd_word   <= mem[word_addr(SDRAM_BA, act_row[SDRAM_BA], col)];
        rd_timer  <= CAS;          // present data CAS cycles later
        rd_active <= 1'b1;
      end
      CMD_REF: refreshed <= 1'b1;
      default: ;
    endcase
    if (rd_active) begin
      if (rd_timer > 1) rd_timer <= rd_timer - 1;
      else              rd_active <= 1'b0;
    end
  end

  // Drive DQ on the CAS cycle (and one extra) so the controller's sample edge
  // sees stable read data regardless of the exact capture cycle.
  wire drive_dq = rd_active && (rd_timer <= 2);
  assign SDRAM_DQ = drive_dq ? rd_word : 32'bz;
endmodule
