`timescale 1ns/1ps
// #11123 FIX 3 sim gate: confirm sdram.v honors T_RP=2/T_RCD=2 (T_RC unchanged)
// and that T_RC=6 reproduces the TEST-3 CONFIG cycle-counter overflow.
module tb;
`ifdef TEST3_OVERFLOW
  localparam TRC = 6;   // TEST 3: 2+6+6+2 = 16 > 15 -> CONFIG cycle overflow
`else
  localparam TRC = 4;   // FIX 3: T_RC unchanged -> 2+4+4+2 = 12 <= 15
`endif
  reg clk=0, resetn=0, rd=0, wr=0, refresh=0;
  reg [22:0] addr=0; reg [7:0] din=0;
  wire [7:0]  dout;  wire [31:0] dout32; wire data_ready, busy;
  wire [31:0] SDRAM_DQ; wire [10:0] SDRAM_A; wire [1:0] SDRAM_BA;
  wire SDRAM_nCS, SDRAM_nWE, SDRAM_nRAS, SDRAM_nCAS, SDRAM_CLK, SDRAM_CKE;
  wire [3:0]  SDRAM_DQM;

  sdram #(.FREQ(100000), .T_RP(2), .T_RCD(2), .T_RC(TRC)) dut (
    .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA), .SDRAM_nCS(SDRAM_nCS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_CLK(SDRAM_CLK), .SDRAM_CKE(SDRAM_CKE), .SDRAM_DQM(SDRAM_DQM),
    .clk(clk), .clk_sdram(clk), .resetn(resetn), .rd(rd), .wr(wr), .refresh(refresh),
    .burstLen(4'd1), .addr(addr), .din(din), .dout(dout), .dout32(dout32),
    .data_ready(data_ready), .busy(busy));

  always #5 clk = ~clk;

  wire [2:0] cmd = {SDRAM_nRAS, SDRAM_nCAS, SDRAM_nWE};
  localparam [2:0] CMD_ACT = 3'b011, CMD_WR = 3'b100;

  integer cfgc, t, actc, wrc, wbusy;
  initial begin
    resetn=0; repeat(4) @(posedge clk); resetn=1;
    cfgc=0;
    while (busy===1'b1 && cfgc<300) begin @(posedge clk); cfgc=cfgc+1; end
    if (busy!==1'b0) begin
      $display("RESULT(T_RC=%0d): CONFIG-HANG busy stuck after %0d cycles -> cycle-counter overflow", TRC, cfgc);
      $finish;
    end
    $display("RESULT(T_RC=%0d): CONFIG-OK busy cleared after %0d cycles", TRC, cfgc);

    // drive the write pulse on negedge so wr is stable across the posedge the
    // DUT samples it (avoids the drive-at-posedge race)
    @(negedge clk); addr=23'h000B00; din=8'h5A; wr=1; @(negedge clk); wr=0;
    t=0; actc=-1; wrc=-1; wbusy=-1;
    while (t<25) begin
      @(negedge clk); t=t+1;   // sample mid-cycle: registered outputs are settled
      if (cmd===CMD_ACT && actc<0) actc=t;
      if (cmd===CMD_WR  && wrc<0)  wrc=t;
      if (busy===1'b0 && wrc>=0 && wbusy<0) wbusy=t;
    end
    if (wrc<0) $display("RESULT(T_RC=%0d): WRITE-FAIL no WRITE command issued", TRC);
    else $display("RESULT(T_RC=%0d): WRITE-OK act@%0d wr@%0d (wr-act=%0d, expect ~T_RCD=2) busy_clear@%0d", TRC, actc, wrc, (wrc-actc), wbusy);
    $finish;
  end
endmodule
