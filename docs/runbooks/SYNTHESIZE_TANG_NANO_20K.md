> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Synthesize Tang Nano 20K

**Owner:** `BrightForge`  
**Status:** validated command from `fpga/tang20k/Makefile`

## Working directory

`/home/itadmin/github/spinalhdlVDP/fpga/tang20k`

## Prerequisites

- Generated Verilog up to date (`make gen`).
- `GOWIN_HOME` set or `gw_sh` on PATH.

## Command

```bash
make
```

## Expected outputs

- `fpga/tang20k/impl/pnr/project.fs`
- Gowin timing/resource reports under `fpga/tang20k/impl/`.

## Pass/fail criteria

- `make` exits 0.
- TNS = 0 (or documented exception).

## Evidence to save

- Bitstream SHA-256.
- Timing/resource summary.
- Source commit.
