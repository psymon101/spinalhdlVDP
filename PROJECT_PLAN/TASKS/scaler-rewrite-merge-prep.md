# scaler-rewrite-merge-prep

## Owner
TopazCliff / BrightForge

## Status
DONE — 2026-07-28; regression PASS; go recommendation recorded

## Background

The `topazcliff/scaler-rewrite` branch now contains the source-coordinate scaler, P3b bitmap/indexed fetch-side scaling, all external-review doc closeouts, and the recent cleanup commits. It is ahead of `main` (`f09159f`). Before merging to `main`, the branch must pass the same regression bar that `main` requires: compile clean, key co-sims green, PnR TNS=0, and a clean `git status`.

## Objective

Prepare `topazcliff/scaler-rewrite` for merge to `main` by running the standard regression suite and collecting a proof packet. Do **not** merge yet — this lane ends with a go/no-go recommendation and a signed-off proof packet.

## Scope

- Branch hygiene: verify current branch, clean working tree, list commits ahead of `main`.
- Compile: `sbt compile` must pass with zero errors.
- Elaboration: `sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"` must generate `hw/gen/top_tang20k.v` cleanly.
- Regression co-sims (production path):
  - `Indexed2bppFineCoSim` — fine-grained indexed 2bpp MATCH
  - `Indexed2bppCheckerCoSim` — checkerboard edge CLEAN
  - `Indexed2bppFrameCoSim` — LEFT-EDGE and ROW-CODED modes
  - `DirectColorFrameCoSim` — RGB565 X-ramp byte-exact at delay=0
- Synthesis/PnR: Gowin V1.9.12.01 `make pnr` (or equivalent) must produce TNS=0, no new BSRAM/DSP resource alarms, and a bitstream.
- Doc sanity: `PROJECT_PLAN.md` and `STATUS.md` reflect the branch state; `VOODOO_ADOPTION_PLAN.md` stale link noted.
- Produce a proof packet under `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/` with logs, hashes, and a `review.md` verdict.

## Acceptance criteria

- [x] Branch `topazcliff/scaler-rewrite` is clean; ahead of `main` (`f09159f`) by scaler-rewrite feature work + doc/cleanup closeouts.
- [x] `sbt compile` PASS.
- [x] `TopTang20kHdmiVerilog` elaboration PASS; generated `hw/gen/top_tang20k.v` SHA-256 `7ad5cee1…`.
- [x] All listed regression co-sims PASS (`Indexed2bppFineCoSim`, `Indexed2bppCheckerCoSim`, `Indexed2bppFrameCoSim`, `DirectColorFrameCoSim`).
- [x] Gowin PnR PASS: TNS=0, setup/hold violated endpoints=0; bitstream SHA-256 `8b241328…`.
- [x] Proof packet created at `PROJECT_PLAN/proof_packets/scaler-rewrite-merge-prep/` with `PASS.txt`, `review.md`, `manifest.yaml`, `hashes.sha256`, `simulation/regression.log`, `simulation/results.txt`, `synthesis/pnr.log`.
- [x] PM go recommendation: **GO for merge to main** pending PM sign-off; residual F1/F7 docs and stale `PROJECT_PLAN.md` link should be handled before/after merge.

## Blockers
None.

## Artifacts / References

- Branch: `topazcliff/scaler-rewrite`
- Baseline (`main`): `f09159f`
- Proof packet template: `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`
