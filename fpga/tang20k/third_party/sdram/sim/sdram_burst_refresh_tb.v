`timescale 1ns/1ps
// sdram_burst_refresh_tb — checkpoint for lane SDRAM-BURST-REFRESH (P16).
//
// Validates the all-rows-per-frame burst-refresh policy approved in #11962:
// burst REF_ROWS AUTO_REFRESH at each vblank, ZERO during active video. Uses the
// sdram_model row-coverage tREF guardrail to prove, as a positive+negative
// discriminator:
//   (1) the CORRECT all-rows burst -> 0 violations (every row within tREF), and
//   (2) an UNDER-refresh burst (the bogus 136/frame-class error) -> the guardrail
//       FIRES (row-coverage tREF), i.e. the bad scheme is rejected in sim.
//
// Scaled (REF_ROWS=64) for a fast run; the policy and the invariant are identical
// to the real 2048-row part. The full-scale 4x margin (16.67ms frame vs 64ms tREF)
// is the arithmetic proof in mail #11960. tREFI (distributed watchdog) is disabled
// here because a burst scheme intentionally has large inter-refresh gaps.
//
// Run: iverilog -g2012 -o /tmp/x sdram_model.v sdram_burst_refresh_tb.v && vvp /tmp/x
module sdram_burst_refresh_tb;
  localparam ROWS       = 64;     // scaled row count
  localparam ACTIVE_CYC = 200;    // active-video cycles (NO refresh)
  localparam TREF       = 2000;   // ~4 frames -> mirrors 64ms/16.67ms ~= 4x margin

  reg clk = 0; always #5 clk = ~clk;
  wire [31:0] SDRAM_DQ;
  reg [10:0] SDRAM_A = 0; reg [1:0] SDRAM_BA = 0;
  reg SDRAM_nCS = 1, SDRAM_nRAS = 1, SDRAM_nCAS = 1, SDRAM_nWE = 1; reg [3:0] SDRAM_DQM = 0;

  sdram_model #(
    .CAS(2), .TIMING_CHECK(1),
    .REF_ROWS(ROWS), .tREF_CK(TREF),
    .tREFI_CK(100000000)          // disable distributed watchdog (burst = big gaps)
  ) dut (
    .clk(clk), .SDRAM_DQ(SDRAM_DQ), .SDRAM_A(SDRAM_A), .SDRAM_BA(SDRAM_BA),
    .SDRAM_nCS(SDRAM_nCS), .SDRAM_nRAS(SDRAM_nRAS), .SDRAM_nCAS(SDRAM_nCAS),
    .SDRAM_nWE(SDRAM_nWE), .SDRAM_DQM(SDRAM_DQM));

  task step(input cs, input [2:0] c);
    begin SDRAM_nCS = cs; {SDRAM_nRAS, SDRAM_nCAS, SDRAM_nWE} = c; SDRAM_BA = 0; SDRAM_A = 0; @(posedge clk); end
  endtask
  task t_nop; step(1'b1, 3'b111); endtask
  task t_ref; step(1'b0, 3'b001); endtask

  integer f, r;
  // One frame: active video (no refresh), then a vblank burst of `rpf` AUTO_REFRESH
  // each spaced tRFC=4 cycles (REF + 3 NOP), then a short vblank tail.
  task run_frame(input integer rpf);
    begin
      for (r = 0; r < ACTIVE_CYC; r = r + 1) t_nop;
      for (r = 0; r < rpf; r = r + 1) begin t_ref; t_nop; t_nop; t_nop; end
      for (r = 0; r < 20; r = r + 1) t_nop;
    end
  endtask

  integer v_start, v_p1, v_p2, ok1, ok2;
  initial begin
    repeat (4) t_nop;
    $display("=== SDRAM burst-refresh policy checkpoint (scaled ROWS=%0d, tREF=%0d) ===", ROWS, TREF);

    // Phase 1: CORRECT — burst ALL rows every frame.
    v_start = dut.timing_violations;
    for (f = 0; f < 6; f = f + 1) run_frame(ROWS);
    v_p1 = dut.timing_violations;
    ok1 = (v_p1 - v_start == 0);
    $display("  Phase 1  all-%0d-rows/frame x6 : new violations=%0d  -> %0s",
             ROWS, v_p1 - v_start, ok1 ? "PASS (every row within tREF)" : "FAIL");

    // Phase 2: WRONG — under-refresh (ROWS/16 per frame; the 136/frame-class error).
    for (f = 0; f < 24; f = f + 1) run_frame(ROWS/16);
    v_p2 = dut.timing_violations;
    ok2 = (v_p2 - v_p1 > 0);
    $display("  Phase 2  under-refresh %0d/frame x24 : new violations=%0d  -> %0s",
             ROWS/16, v_p2 - v_p1, ok2 ? "PASS (under-refresh CAUGHT by guardrail)" : "FAIL (not caught)");

    if (ok1 && ok2) $display("sdram_burst_refresh_tb: PASS — all-rows burst safe; under-refresh rejected");
    else            $display("sdram_burst_refresh_tb: FAIL");
    $finish;
  end
  initial begin #5000000; $display("sdram_burst_refresh_tb: TIMEOUT"); $finish; end
endmodule
