# scaler-rewrite-merge-prep — Review Report

**Date:** 2026-07-28  
**Lane:** `scaler-rewrite-merge-prep`  
**Owner:** TopazCliff / BrightForge  
**Branch:** `topazcliff/scaler-rewrite`  
**Baseline (`main`):** `f09159f`  
**Source commit:** `f91f58a`

## Verdict

**PASS — branch is ready for merge to `main` pending PM sign-off.**

The scaler-rewrite branch passes the standard regression bar: compile clean, elaboration clean, production-path co-sims green, and Gowin PnR TNS=0 with no new resource alarms.

## What was checked

1. **Branch hygiene**
   - Current branch: `topazcliff/scaler-rewrite`
   - Working tree clean (`git status --short` empty).
   - Branch is ahead of `main` (`f09159f`) by the scaler-rewrite feature work plus recent doc/cleanup closeouts.

2. **Compile / elaboration**
   - `sbt compile` — PASS.
   - `sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"` — PASS; generated `hw/gen/top_tang20k.v` (37,451 lines, SHA-256 `7ad5cee1…`).

3. **Co-sim regression (production path)**
   - `Indexed2bppFineCoSim` — **PASS**: intra-byte decode MATCH; `pixelWithinByte` aligned.
   - `Indexed2bppCheckerCoSim` — **PASS**: CHECKER-EDGE CLEAN; 64 px square edges, no spurious short runs.
   - `Indexed2bppFrameCoSim` — **PASS**: LEFT-EDGE CLEAN; ROW-CODED canonical `bestDv=3` with 1/480 startup wrong-row events (within slack); vertical-bar shear span = 0 px.
   - `DirectColorFrameCoSim` — **PASS**: `bitmapWritePipelineDelay=0` aligned at `dh=0`, 0.9956 byte-exact (residual <1% = startup transient + edge).

4. **Synthesis / PnR**
   - Tool: Gowin V1.9.12.01, GW2AR-LV18QN88C8/I7.
   - `make all` in `fpga/tang20k` — PASS.
   - Bitstream SHA-256: `8b2413288dc2c47c8ebed6a1af8b88ada6566a99a16ac1da4c0948b80f429ed6`.
   - Timing:
     - Setup violated endpoints: **0**
     - Hold violated endpoints: **0**
     - Total Negative Slack: **0.000** on all clocks.
     - `clk_pixel` Fmax: **29.148 MHz** (constraint 25.200 MHz).
     - `clk_x5` Fmax: **640.163 MHz** (constraint 126.000 MHz).
     - `clk_sdram` Fmax: **56.031 MHz** (constraint 40.501 MHz).
     - `qspi_sck` Fmax: **157.485 MHz** (constraint 40.000 MHz).
   - Resources:
     - Logic: 11,498 / 20,736 (**56%**)
     - Register: 5,685 / 15,915 (**36%**)
     - CLS: 7,688 / 10,368 (**75%**)
     - BSRAM: **40 / 46** (87%)
     - DSP: **12 / 24** (50%)
     - rPLL: **2 / 2** (100%)

## Merge recommendation

**GO** — the branch meets the same technical bar as prior `main`-bound lanes (`2bpp-bank-completion-rtl`, `external-review-scaler-rewrite-p3b`). The only items that should be resolved before or alongside the merge are PM/doc decisions, not RTL:

- **F1** in `PROJECT_PLAN/external_review_doc_impact.md` — standalone diagnostic build procedure still pending.
- **F7** in `PROJECT_PLAN/external_review_doc_impact.md` — `BasicPatternSource` pipeline doc deferred as Tier C.
- `PROJECT_PLAN/PROJECT_PLAN.md` is dated 2026-07-25 and references a missing `VOODOO_ADOPTION_PLAN.md`; should be refreshed.

No hardware flash is required for the merge itself; the existing `a5a047a2` production authority remains valid for the 1x SDRAM Layer 0 path. A new bitstream (`8b241328…`) is available if a bench smoke-test of the merged code is desired.

## Artifacts

- `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/manifest.yaml`
- `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/simulation/regression.log`
- `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/synthesis/pnr.log`
- `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/hashes.sha256`
- `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/PASS.txt`
