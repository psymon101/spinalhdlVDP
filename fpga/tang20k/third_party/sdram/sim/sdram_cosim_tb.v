`timescale 1ns/1ps
// #11162 real-RTL co-simulation: actual sdram.v controller (branch config:
// T_RP=2,T_RCD=2,T_RC=4) + behavioral sdram_model. Drives the controller's
// logic side BYTE-BY-BYTE exactly like the real host upload (the bridge issues
// one byte write per address), so the byte-write DQM lane logic and the 32-bit
// read assembly are exercised end to end. Verifies the exact sentinel/tile/
// cross-contamination sequence from the PM directive.
module sdram_cosim_tb;
  // FREQ tiny so the 200us init counter is short in sim (FREQ/1000*200/1000 cyc).
  localparam FREQ = 1_000_000;   // -> 200 init cycles

  reg clk = 0, clk_sdram = 0;
  reg resetn = 0, rd = 0, wr = 0, refresh = 0;
  reg [22:0] addr = 0; reg [7:0] din = 0;
  wire [7:0]  dout; wire [31:0] dout32; wire data_ready, busy;

  wire [31:0] SDRAM_DQ; wire [10:0] SDRAM_A; wire [1:0] SDRAM_BA;
  wire SDRAM_nCS, SDRAM_nWE, SDRAM_nRAS, SDRAM_nCAS, SDRAM_CLK, SDRAM_CKE;
  wire [3:0]  SDRAM_DQM;

  sdram #(.FREQ(FREQ), .CAS(2), .T_WR(2), .T_MRD(2), .T_RP(2), .T_RCD(2), .T_RC(4)) dut (
    .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA), .SDRAM_nCS(SDRAM_nCS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_CLK(SDRAM_CLK), .SDRAM_CKE(SDRAM_CKE), .SDRAM_DQM(SDRAM_DQM),
    .clk(clk), .clk_sdram(clk_sdram), .resetn(resetn), .rd(rd), .wr(wr), .refresh(refresh),
    .addr(addr), .din(din), .dout(dout), .dout32(dout32), .data_ready(data_ready), .busy(busy));

  // chip samples on the 180-deg SDRAM clock, exactly as the controller intends.
  sdram_model #(.CAS(2)) chip (
    .clk(clk_sdram), .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA),
    .SDRAM_nCS(SDRAM_nCS), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_DQM(SDRAM_DQM));

  // clk period 20; clk_sdram 180-deg (offset 10).
  always #10 clk = ~clk;
  initial begin #5 forever #10 clk_sdram = ~clk_sdram; end  // 180-deg phase

  integer errors = 0;

  task wr_byte(input [22:0] a, input [7:0] d);
    begin
      @(negedge clk); while (busy) @(negedge clk);
      addr = a; din = d; wr = 1;
      @(negedge clk); wr = 0;
      @(negedge clk); @(negedge clk); while (busy) @(negedge clk);  // write completes
    end
  endtask

  reg [31:0] rdata;
  task rd_word(input [22:0] a);
    begin
      @(negedge clk); while (busy) @(negedge clk);
      addr = a; rd = 1;
      @(negedge clk); rd = 0;
      @(posedge data_ready);
      #1 rdata = dout32;
      @(negedge clk); while (busy) @(negedge clk);
    end
  endtask

  task fire_refresh;
    begin
      @(negedge clk); while (busy) @(negedge clk);
      refresh = 1; @(negedge clk); refresh = 0;
      @(negedge clk); @(negedge clk); while (busy) @(negedge clk);
    end
  endtask

  // write a 32-bit word as 4 sequential byte writes (LE), like the host upload.
  task wr_word32(input [22:0] a, input [31:0] w);
    begin
      wr_byte(a+0, w[7:0]); wr_byte(a+1, w[15:8]);
      wr_byte(a+2, w[23:16]); wr_byte(a+3, w[31:24]);
    end
  endtask

  task chk(input [22:0] a, input [31:0] exp);
    begin
      rd_word(a);
      if (rdata !== exp) begin
        errors = errors + 1;
        $display("  MISMATCH @0x%05X: got 0x%08X exp 0x%08X", a, rdata, exp);
      end
    end
  endtask

  integer i;
  initial begin
    resetn = 0; repeat (4) @(negedge clk); resetn = 1;
    // wait init+config done (busy clears)
    i = 0; while (busy && i < 1000) begin @(negedge clk); i = i + 1; end
    if (busy) begin $display("FAIL: controller never left init (busy stuck)"); $finish; end
    $display("[cosim] init/config done after %0d cycles", i);

    // 1. sentinel {0x1111,0x2222} -> dword 0x22221111 at 0xB000
    wr_word32(23'h00B000, 32'h22221111);
    // 2. white tile: 32 dwords of 0x0000FFFF at 0xA000
    for (i = 0; i < 32; i = i + 1) wr_word32(23'h00A000 + i*4, 32'h0000FFFF);
    // 6. distinct patterns at 0xC000 / 0xD000 (cross-contamination)
    wr_word32(23'h00C000, 32'hCAFEBABE);
    wr_word32(23'h00D000, 32'hDEADC0DE);

    // 3/4/7. verify
    $display("[cosim] verifying...");
    chk(23'h00B000, 32'h22221111);
    for (i = 0; i < 32; i = i + 1) chk(23'h00A000 + i*4, 32'h0000FFFF);
    chk(23'h00C000, 32'hCAFEBABE);
    chk(23'h00D000, 32'hDEADC0DE);

    // 5. refresh interference: write, hammer AUTO_REFRESH, verify data survives.
    wr_word32(23'h00E000, 32'h12345678);
    for (i = 0; i < 12; i = i + 1) fire_refresh();
    chk(23'h00E000, 32'h12345678);
    // and interleave a refresh BETWEEN tile writes, then re-verify a couple tiles
    wr_word32(23'h00A100, 32'h0000FFFF); fire_refresh();
    wr_word32(23'h00A104, 32'h0000FFFF); fire_refresh();
    chk(23'h00A100, 32'h0000FFFF);
    chk(23'h00A104, 32'h0000FFFF);

    if (errors == 0) $display("[cosim] PASS — all readbacks exact, no cross-contamination, data survives refresh");
    else             $display("[cosim] FAIL — %0d mismatches", errors);
    $finish;
  end

  initial begin #5_000_000 $display("FAIL: timeout"); $finish; end
endmodule
