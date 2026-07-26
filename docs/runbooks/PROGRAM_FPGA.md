> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Program FPGA (SRAM)

**Owner:** `BrightForge`  
**Status:** validated command from `fpga/tang20k/Makefile`

## Working directory

`/home/itadmin/github/spinalhdlVDP/fpga/tang20k`

## Prerequisites

- Synthesized bitstream available.
- Tang Nano 20K connected.

## Command

```bash
make prog
```

## Expected outputs

- `openFPGALoader` reports success.
- FPGA reconfigured.

## Pass/fail criteria

- Exit code 0.
- Verify step passes if `--verify` used.

## Evidence to save

- Bitstream hash.
- Loader output.
- Board/wiring revision.

## Note

SRAM load is volatile; persistent flash uses `make flash`.
