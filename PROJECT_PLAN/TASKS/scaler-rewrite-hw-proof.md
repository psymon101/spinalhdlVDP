# scaler-rewrite-hw-proof

**Owner:** BrightForge  
**PM:** TopazCliff  
**Status:** DONE  
**Opened:** 2026-07-27  
**Closed:** 2026-07-28  
**Closeout Commit:** `60b01ab`  
**Proof Packet:** `PROJECT_PLAN/proof_packets/scaler-rewrite-hw-proof/`  
**Source lane:** `external-review-scaler-rewrite`  
**Source branch:** `topazcliff/scaler-rewrite`  
**Source commit:** `9314aa0`  
  

---

## Purpose

The `external-review-scaler-rewrite` lane delivered a source-coordinate `ScaleCoordGen` and retired the sink-side `PixelRepeatScaler`. Sim + PnR proof is complete, but the lane was intentionally scoped as **sim+PnR only**. Project history shows multiple cases where sim passed and hardware exposed timing/CDC/SI issues that co-sim did not catch. This lane closes that gap with an unambiguous hardware proof of the scaler change.

## Scope

**In scope:**
- Build a bitstream from the `topazcliff/scaler-rewrite` branch (`9314aa0` or later if minor fixes are required).
- Flash the bitstream to the Tang Nano 20K bench board.
- Capture visual evidence for **1× mode** (production path) and verify byte-equivalence to the existing HW-proven `a5a047a2` baseline.
- Capture visual evidence for **>1× scaled modes** (2×/3× procedural/testpattern and/or bitmap where host support exists).
- Document capture procedure, exact register/config values, and golden comparisons.
- File a complete proof packet under `PROJECT_PLAN/proof_packets/scaler-rewrite-hw-proof/`.

**Out of scope:**
- New RTL features or host ABI changes. If scaled-mode host firmware does not yet exist, use the minimum existing register writes needed; do not design new host commands here.
- Bitmap/indexed fetch-side scaling (P3b) — that remains a separate PM-sequenced lane with a BronzeGate interface checkpoint.
- Fixing hardware-divergence bugs, if any. This lane first *measures* and reports; fixes become a new lane unless trivial and owner-authorized.

## Dependencies

- `external-review-scaler-rewrite` DONE (`9314aa0`, CyanPeak PASS).
- HW-proven baseline bitstream `a5a047a2…` available for regression comparison.
- Board free for flash/reconfigure and host upload path functional.

## Interfaces / State

- Reuses existing `SCALE_CTRL` register fields and `scaleCtrl` wiring from `external-review-scaler-rewrite`.
- Host sets `scaleX`, `scaleY`, `autoCenter`, `logicWidth`, `logicHeight` via existing register map.
- No new FPGA pins or host registers.

## Risks

- **Sim-vs-hardware divergence on scaled modes:** new combinational/reciprocal-multiply paths and registered coordinate latencies could interact with real SDRAM refresh, CDC, or sync timing differently than in sim.
- **1× regression risk:** even though 1× is mux-bypassed in `VdpTop`, synthesis variations or surrounding integration changes could still alter real output.
- **Capture-chain ambiguity:** downstream scaler/overscan artifacts (as seen in QSPI-CRC8-185) can be mistaken for VDP defects. Use deterministic patterns and direct `/dev/video0` captures where possible; document monitor/capture settings.
- **SDRAM content cleared by reconfigure:** the P4 host must re-upload after each flash. Factor this into the procedure.

## Validation

- **Sim (already done):** `ScaleCoordGenSim` 8/8, `ScaleUpFrameCoSim` >1× PASS, 1× regression byte-identical.
- **Hardware (this lane):**
  - Build `top_tang20k.v` and Gowin bitstream from `topazcliff/scaler-rewrite`.
  - Flash and verify.
  - Run 1× checkerboard or equivalent deterministic pattern; compare capture to `a5a047a2` baseline.
  - Run 2×/3× procedural/testpattern captures; verify stripe run-lengths and checkerboard spacing match expected `scaleX`/`scaleY` behavior.
  - Record health/status registers before/after enable.

## Audit Focus

- CyanPeak to review the hardware proof procedure and classification of results (exact / visually equivalent / divergent).
- Confirm that 1× captures are compared against the HW-proven baseline, not just sim golden vectors.

## Exit Condition

This task is done when the scaler-rewrite bitstream is flashed, 1× and >1× modes are captured on real hardware, the captures are compared against the `a5a047a2` baseline and expected scaled behavior, and a complete proof packet with artifact hashes is committed.
