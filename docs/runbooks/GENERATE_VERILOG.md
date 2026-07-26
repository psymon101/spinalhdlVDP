> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Generate Verilog

**Owner:** `BrightForge`  
**Status:** validated command from `fpga/tang20k/Makefile`

## Working directory

`/home/itadmin/github/spinalhdlVDP/fpga/tang20k`

## Command

```bash
make gen
```

Equivalently, from repo root:

```bash
sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"
```

## Expected outputs

- `hw/gen/top_tang20k.v` regenerated.
- Hash changes only reflect source changes.

## Pass/fail criteria

- `sbt` exits 0.
- No manual edits to `hw/gen/top_tang20k.v`.

## Evidence to save

- Source commit.
- `sha256sum hw/gen/top_tang20k.v`.
