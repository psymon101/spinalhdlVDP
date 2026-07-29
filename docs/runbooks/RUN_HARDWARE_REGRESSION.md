> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Run Hardware Regression

**Owner:** `BrightForge` + `BronzeGate`  
**Status:** template — procedure varies by lane

## Working directory

`/home/itadmin/github/spinalhdlVDP`

## Prerequisites

- Matched bitstream and firmware committed.
- FPGA programmed or flashed.
- Host board flashed.

## Procedure

1. Record bitstream hash.
2. Record firmware ELF/BIN/partition hashes.
3. Power-cycle or reset host.
4. Run lane-specific test sequence.
5. Capture serial output / captures.
6. Verify health flags and PASS markers.

## Pass/fail criteria

- Lane-specific oracle passes.
- Health flags clear.

## Evidence to save

- Serial log.
- Capture hashes.
- Proof packet under `PROJECT_PLAN/proof_packets/<LANE>/`.
