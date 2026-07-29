> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# TP-2bpp-backlog-cosim — Continuous Scanout Bank-Completion Gate

**Owner:** `BrightForge`  
**Lane:** `2bpp-backlog-cosim` / `2bpp-bank-completion-rtl`  
**Environment:** SpinalSim, continuous pixel + SDRAM clocks, realistic latency/refresh  
**Source commit:** `5efe049` (cosim); current HEAD for hardening

## Procedure

```bash
sbt "runMain spinalhdlvdp.Indexed2bppBacklogCoSim"
```

## Nominal mode expected results

- `bestDv == 3`
- wrong-row events ≤ documented startup slack
- `grantOverflow == 0`
- `displayUnderflow == 0`
- `rowTagMismatch == 0` (gate idle in nominal — the fetch always keeps up)
- `malformed == 0`
- max fetch span within source-row budget

## Forced-late mode expected results (before hardening)

- Detector fires: `grantOverflow > 0` or wrong-row count increases sharply.
- Demonstrates incomplete-bank display hazard is reachable.

## Forced-late mode expected results (after hardening)

- `displayUnderflow == 0`
- `malformed == 0`
- No torn or stale display banks (gate holds on a non-consecutive tag rather than presenting a partial row).
- `rowTagMismatch` may be non-zero because it counts intentional gate-hold events when the next consecutive row is not yet complete; this is expected, not a failure.
- Wrong-row events within startup slack only.

## Pass/fail rule

- Nominal must be clean.
- Forced-late must fail before `bankReady`/row-tag hardening and pass afterward.

## Evidence path

`PROJECT_PLAN/proof_packets/2bpp-bank-completion-rtl/`
