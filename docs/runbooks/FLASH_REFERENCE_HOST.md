> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Flash Reference Host

**Owner:** `BronzeGate`  
**Status:** template — requires validation for target host

## Working directory

`/home/itadmin/github/spinalhdlVDP/firmware/<PROJECT>`

## Prerequisites

- Reference firmware built.
- Host board connected.

## Command

```bash
idf.py flash
```

## Expected outputs

- ESP-IDF writes and verifies flash.

## Pass/fail criteria

- Exit code 0.
- Verify passes.

## Evidence to save

- Flash log.
- Firmware hashes.
