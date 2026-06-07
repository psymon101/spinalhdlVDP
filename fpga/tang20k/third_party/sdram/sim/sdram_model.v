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
  parameter CAS = 2,
  // --- timing-violation assertion layer (SDRAM-TIMING-ASSERT lane, P15) ---
  // Defaults match the nand2mario controller's T_xx @ FREQ=64.8MHz so a
  // LEGITIMATE controller never trips them; an illegal/too-fast command
  // sequence does. All in SDRAM clock cycles.
  parameter TIMING_CHECK = 0,  // 1 = enable the assertion layer (opt-in: the
                               // existing functional cosim refreshes in bursts,
                               // not on a realistic cadence, so the tREF watchdog
                               // is only meaningful for timing-focused TBs)
  parameter tRCD_CK = 1,       // ACT -> RD/WR        (controller T_RCD=1)
  parameter tRP_CK  = 1,       // PRE -> ACT          (controller T_RP=1)
  parameter tRAS_CK = 3,       // ACT -> PRE          (~42ns @64.8MHz)
  parameter tRC_CK  = 4,       // ACT -> ACT same bank (controller T_RC=4)
  parameter tRFC_CK = 4,       // AUTO_REFRESH busy window / REF-to-REF (~tRC)
  parameter tREFI_CK = 1011,   // distributed-mode watchdog: max cycles between any
                               // two AUTO_REFRESH (15.6us @64.8MHz). Set huge to
                               // disable for a BURST scheme (intentional big gaps).
  parameter REF_ROWS = 2048,   // rows the AUTO_REFRESH counter sweeps (sdram.v=2048)
  parameter tREF_CK  = 4147200 // burst-mode invariant: each row must be re-refreshed
                               // within this (64ms @64.8MHz). Row-coverage check.
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
        rd_timer  <= CAS + 3;      // hold the read word across a wide window so
        rd_active <= 1'b1;         // the controller's capture edge (registered
      end                          // dq_in_r after #11168 FIX A, +1 latency) sees it
      CMD_REF: refreshed <= 1'b1;
      default: ;
    endcase
    if (rd_active) begin
      if (rd_timer > 0) rd_timer <= rd_timer - 1;
      else              rd_active <= 1'b0;
    end
  end

  // Drive DQ for the whole read window (the real chip holds data through the
  // burst + tOH); reads are busy-spaced so this never collides with a write.
  wire drive_dq = rd_active;
  assign SDRAM_DQ = drive_dq ? rd_word : 32'bz;

  // ---------------------------------------------------------------------------
  // Timing-violation assertion layer (ZipCPU sdramsim rule-set, harvested into
  // our 32-bit model — TopazCliff #11950/#11954, lane SDRAM-TIMING-ASSERT P15).
  // Additive + non-intrusive: observes the command stream only; the functional
  // always block above is untouched. Counts violations into `timing_violations`
  // and prints a tagged line per event (iverilog-portable: no $error/$fatal).
  // Rules: tRCD, tRP, tRAS, tRC, tRFC/read-during-refresh, tREF, bank-conflict.
  // ---------------------------------------------------------------------------
  integer timing_violations;
  integer tck;                    // free-running SDRAM-clock cycle counter
  integer cyc_since_ref;          // tREF watchdog
  integer ref_busy_until;         // tck before which the array is refresh-busy
  integer last_act_tck [0:3];     // last ACTIVATE cycle per bank
  integer last_pre_tck [0:3];     // last PRECHARGE cycle per bank
  reg [3:0] bank_active;          // per-bank row-open state
  integer tb;
  integer ref_ring [0:REF_ROWS-1];// per-row last-refresh cycle (row-coverage tREF)
  integer ref_count;              // total AUTO_REFRESH issued (advances row counter)
  integer rr;

  `define TVIOL(MSG) begin \
      timing_violations = timing_violations + 1; \
      $display("[%0t] SDRAM-TIMING-VIOLATION tck=%0d: %s", $time, tck, MSG); \
    end

  initial begin
    timing_violations = 0; tck = 0; cyc_since_ref = 0; ref_busy_until = -1;
    bank_active = 4'b0000; ref_count = 0;
    for (tb = 0; tb < 4; tb = tb + 1) begin
      last_act_tck[tb] = -100000; last_pre_tck[tb] = -100000;
    end
    for (rr = 0; rr < REF_ROWS; rr = rr + 1) ref_ring[rr] = 0;
  end

  always @(posedge clk) if (TIMING_CHECK) begin
    // tREF: AUTO_REFRESH cadence watchdog.
    cyc_since_ref = cyc_since_ref + 1;
    if (cyc_since_ref > tREFI_CK) begin
      `TVIOL("tREF: refresh interval exceeded")
      cyc_since_ref = 0;          // re-arm (avoid one-per-cycle spam)
    end

    case (cmd)
      CMD_ACT: begin
        if (tck < ref_busy_until)
          `TVIOL("tRFC: ACTIVATE inside AUTO_REFRESH busy window")
        if (bank_active[SDRAM_BA] && (tck - last_act_tck[SDRAM_BA]) < tRC_CK)
          `TVIOL("tRC: ACT-to-ACT same bank too soon (or ACT to open bank)")
        if (!bank_active[SDRAM_BA] && (tck - last_pre_tck[SDRAM_BA]) < tRP_CK)
          `TVIOL("tRP: PRECHARGE-to-ACTIVATE too soon")
        bank_active[SDRAM_BA] = 1'b1;
        last_act_tck[SDRAM_BA] = tck;
      end
      CMD_RD, CMD_WR: begin
        if (!bank_active[SDRAM_BA])
          `TVIOL("BANK: READ/WRITE to a bank with no open row")
        else if ((tck - last_act_tck[SDRAM_BA]) < tRCD_CK)
          `TVIOL("tRCD: ACTIVATE-to-READ/WRITE too soon")
        if (tck < ref_busy_until)
          `TVIOL("tRFC: READ/WRITE inside AUTO_REFRESH busy window")
        // Auto-precharge: A10 high on READ/WRITE closes the bank after the
        // access (the nand2mario controller uses this instead of explicit
        // PRECHARGE), so the row is no longer open for the REF/tRC checks.
        if (SDRAM_A[10]) begin
          bank_active[SDRAM_BA] = 1'b0; last_pre_tck[SDRAM_BA] = tck;
        end
      end
      CMD_PRE: begin
        if (SDRAM_A[10]) begin    // A10 high = precharge-all-banks
          for (tb = 0; tb < 4; tb = tb + 1) begin
            if (bank_active[tb] && (tck - last_act_tck[tb]) < tRAS_CK)
              `TVIOL("tRAS: ACTIVATE-to-PRECHARGE too soon (precharge-all)")
            bank_active[tb] = 1'b0; last_pre_tck[tb] = tck;
          end
        end else begin
          if (bank_active[SDRAM_BA] && (tck - last_act_tck[SDRAM_BA]) < tRAS_CK)
            `TVIOL("tRAS: ACTIVATE-to-PRECHARGE too soon")
          bank_active[SDRAM_BA] = 1'b0; last_pre_tck[SDRAM_BA] = tck;
        end
      end
      CMD_REF: begin
        if (tck < ref_busy_until)
          `TVIOL("tRFC: AUTO_REFRESH too soon after AUTO_REFRESH")
        for (tb = 0; tb < 4; tb = tb + 1)
          if (bank_active[tb])
            `TVIOL("REF: AUTO_REFRESH issued with a bank still open")
        // Row-coverage tREF (burst-aware): AUTO_REFRESH refreshes row
        // (ref_count % REF_ROWS); if that row was last refreshed > tREF_CK ago
        // it lost data. Catches under-refresh (e.g. the bogus 136/frame burst)
        // and works for burst AND distributed schemes (unlike tREFI above).
        if (ref_count >= REF_ROWS &&
            (tck - ref_ring[ref_count % REF_ROWS]) > tREF_CK)
          `TVIOL("tREF: row exceeded refresh window (under-refresh / lost data)")
        ref_ring[ref_count % REF_ROWS] = tck;
        ref_count = ref_count + 1;
        cyc_since_ref = 0;
        ref_busy_until = tck + tRFC_CK;
      end
      default: ;
    endcase

    tck = tck + 1;
  end
endmodule
