> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Build Release

**Owner:** `TopazCliff`  
**Status:** template — requires validation

## Working directory

`/home/itadmin/github/spinalhdlVDP`

## Procedure

1. Confirm all active lanes closed.
2. Record source commit.
3. Generate Verilog.
4. Synthesize bitstream.
5. Build firmware.
6. Run full hardware regression.
7. Populate release manifest.

## Command

```bash
# TBD — validate with BrightForge and BronzeGate
```

## Evidence to save

- Release manifest YAML.
- All artifact hashes.
- Proof packets for every lane.
