`timescale 1ns/1ps
// sdram_timing_violation_tb — checkpoint for lane SDRAM-TIMING-ASSERT (P15).
//
// Drives the sdram_model timing-assertion layer with a deliberately ILLEGAL
// command sequence for each rule and proves the corresponding assertion fires
// (timing_violations increments). The legitimate nand2mario controller never
// produces these sequences, so this only exercises the checker, not the model's
// functional path.
//
// tRCD_CK / tRP_CK are tightened to 2 here purely so a 1-cycle-too-soon gap is
// illegal (at the controller's real minimum of 1 cycle, a 1-command-per-cycle
// master cannot express a sub-1-cycle gap). All other thresholds are defaults.
//
// Run:  iverilog -g2012 -o /tmp/sdram_tv sdram_model.v sdram_timing_violation_tb.v && vvp /tmp/sdram_tv
module sdram_timing_violation_tb;
  reg         clk = 0;
  always #5 clk = ~clk;            // 10 ns period (checks are cycle-based)

  wire [31:0] SDRAM_DQ;            // model drives on reads; TB leaves hi-Z
  reg  [10:0] SDRAM_A   = 0;
  reg  [1:0]  SDRAM_BA  = 0;
  reg         SDRAM_nCS = 1, SDRAM_nRAS = 1, SDRAM_nCAS = 1, SDRAM_nWE = 1;
  reg  [3:0]  SDRAM_DQM = 0;

  sdram_model #(
    .CAS(2), .TIMING_CHECK(1),
    .tRCD_CK(2), .tRP_CK(2),       // tightened so a 1-cycle gap is illegal
    .tRAS_CK(3), .tRC_CK(4), .tRFC_CK(4), .tREFI_CK(1011)
  ) dut (
    .clk(clk), .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA),
    .SDRAM_nCS(SDRAM_nCS), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_DQM(SDRAM_DQM)
  );

  // one command per SDRAM clock; {nRAS,nCAS,nWE}: ACT=011 RD=101 WR=100 PRE=010 REF=001 NOP=111
  task step(input cs, input [2:0] c, input [1:0] bk, input [10:0] a);
    begin
      SDRAM_nCS = cs; {SDRAM_nRAS, SDRAM_nCAS, SDRAM_nWE} = c;
      SDRAM_BA = bk; SDRAM_A = a; @(posedge clk);
    end
  endtask
  task t_nop;                                   step(1'b1, 3'b111, 2'd0, 11'd0);          endtask
  task t_act(input [1:0] bk, input [10:0] row); step(1'b0, 3'b011, bk, row);              endtask
  task t_rd (input [1:0] bk, input [7:0]  col); step(1'b0, 3'b101, bk, {3'b000, col});    endtask
  task t_pre(input [1:0] bk);                   step(1'b0, 3'b010, bk, 11'd0);            endtask
  task t_preall;                                step(1'b0, 3'b010, 2'd0, 11'h400);        endtask // A10=1
  task t_ref;                                   step(1'b0, 3'b001, 2'd0, 11'd0);          endtask

  integer k;
  // Return to a clean idle: all banks precharged, a fresh refresh, window clear.
  task reset_idle;
    begin
      for (k=0;k<4;k=k+1) t_nop;      // satisfy tRAS before precharge
      t_preall;
      for (k=0;k<4;k=k+1) t_nop;
      t_ref;                          // banks idle -> legal; re-arms tREF watchdog
      for (k=0;k<5;k=k+1) t_nop;      // clear the tRFC busy window
    end
  endtask

  integer passes = 0, v0;
  task check(input [255:0] nm, input ok);
    begin
      if (ok) begin passes = passes + 1; $display("  PASS  %0s -- assertion fired", nm); end
      else                              $display("  FAIL  %0s -- NO assertion fired", nm);
    end
  endtask

  initial begin
    repeat (4) t_nop;
    reset_idle;

    $display("=== SDRAM timing-violation checkpoint (each rule must fire) ===");

    // 1. bank-conflict: READ to a bank with no open row.
    v0 = dut.timing_violations; t_rd(2'd0, 8'h10);
    check("BANK (R/W to inactive bank)", dut.timing_violations > v0); reset_idle;

    // 2. tRCD: ACTIVATE then READ 1 cycle later (< tRCD_CK=2).
    v0 = dut.timing_violations; t_act(2'd2, 11'd5); t_rd(2'd2, 8'h08);
    check("tRCD (ACT->R/W too soon)", dut.timing_violations > v0); reset_idle;

    // 3. tRP: PRECHARGE then ACTIVATE 1 cycle later (< tRP_CK=2).
    v0 = dut.timing_violations; t_preall; t_act(2'd3, 11'd7);
    check("tRP (PRE->ACT too soon)", dut.timing_violations > v0); reset_idle;

    // 4. tRAS: ACTIVATE then PRECHARGE 1 cycle later (< tRAS_CK=3).
    v0 = dut.timing_violations; t_act(2'd0, 11'd9); t_pre(2'd0);
    check("tRAS (ACT->PRE too soon)", dut.timing_violations > v0); reset_idle;

    // 5. tRC: ACTIVATE then ACTIVATE same bank 1 cycle later (< tRC_CK=4).
    v0 = dut.timing_violations; t_act(2'd1, 11'd3); t_act(2'd1, 11'd4);
    check("tRC (ACT->ACT same bank too soon)", dut.timing_violations > v0); reset_idle;

    // 6. tRFC / read-during-refresh: ACTIVATE inside the AUTO_REFRESH busy window.
    v0 = dut.timing_violations; t_ref; t_act(2'd0, 11'd1);
    check("tRFC (cmd during refresh window)", dut.timing_violations > v0); reset_idle;

    // 7. tREF: no AUTO_REFRESH for > tREFI_CK cycles.
    t_ref; v0 = dut.timing_violations;
    for (k=0;k<1015;k=k+1) t_nop;
    check("tREF (refresh interval exceeded)", dut.timing_violations > v0); reset_idle;

    $display("=== %0d/7 rules fired; total violations counted = %0d ===", passes, dut.timing_violations);
    if (passes == 7) $display("sdram_timing_violation_tb: PASS");
    else             $display("sdram_timing_violation_tb: FAIL (%0d/7)", passes);
    $finish;
  end

  // safety timeout
  initial begin #200000; $display("sdram_timing_violation_tb: TIMEOUT"); $finish; end
endmodule
