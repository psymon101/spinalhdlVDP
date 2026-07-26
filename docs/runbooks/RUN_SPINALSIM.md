> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Run SpinalSim

**Owner:** `BrightForge`  
**Status:** draft — commands need validation against current test main

## Working directory

`/home/itadmin/github/spinalhdlVDP`

## Command

```bash
sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"
```

Replace `Indexed2bppBacklogCoSim` with the target simulation main.

## Expected outputs

- Console PASS/FAIL marker.
- `simWorkspace/` generated artifacts.

## Pass/fail criteria

- Exit code 0.
- Expected counters/behavior match test oracle.

## Evidence to save

- Console log.
- Sim commit hash.
