> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Build Reference Firmware

**Owner:** `BronzeGate`  
**Status:** template — requires validation per project

## Working directory

`/home/itadmin/github/spinalhdlVDP/firmware/<PROJECT>`

## Prerequisites

- ESP-IDF v6.0.2 sourced.

## Command

```bash
idf.py build
```

## Expected outputs

- `build/<project>.elf`
- `build/<project>.bin`
- `build/partition_table/partition-table.bin`

## Pass/fail criteria

- Exit code 0.

## Evidence to save

- ELF SHA-256.
- BIN SHA-256.
- Partition-table SHA-256.
- Source commit.
