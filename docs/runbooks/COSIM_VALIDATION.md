# Runbook: Co-Simulation Validation

**Owner:** BrightForge  
**Reviewer:** CyanPeak (spec), CoralReef (reproducibility)  
**Scope:** SpinalSim co-simulation for VDP RTL, including the 2bpp backlog cosim used as the pass/fail gate for `2bpp-bank-completion-rtl`.

## Prerequisites

- JDK 17+ and `sbt` installed.
- Repository branch `brightforge/ham-decoder-171` checked out.
- Clean working tree or any local changes documented.

## Running a 2bpp co-simulation

```bash
cd /home/itadmin/github/spinalhdlVDP
sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"
```

## Expected results

### Nominal mode

- `bestDv == 3`
- `grantOverflow == 0`
- `displayUnderflow == 0`
- Wrong-row count ≤ startup allowance documented in the test plan.

### Forced-late mode

- With the **current** design (no `bankReady`/`bankRowTag` hardening), the run must show:
  - elevated `grantOverflow`
  - elevated wrong-row / incomplete-bank count
  - `displayUnderflow` may remain zero (the detector fires on stale row tags, not pixel starvation)
- After hardening, the same forced-late stimulus must return to clean counts.

## Capturing evidence

1. Save the console log as `cosim_log.txt`.
2. Compute SHA-256: `sha256sum cosim_log.txt > cosim_log.sha256`.
3. Copy both files into `PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`.

## Failure handling

- If nominal mode shows violations, stop and report to TopazCliff before any RTL change.
- If forced-late mode does **not** fire the detector on the unhardened design, the testbench or stimulus is suspect; do not proceed with RTL hardening.

## Tool versions to record

- SpinalHDL version from `build.sbt` or `hw/spinal/build.sbt`.
- sbt version.
- JDK version (`java -version`).
