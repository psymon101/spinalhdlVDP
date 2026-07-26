> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Documentation Guide

## Quick start

1. Read `STATUS.md` for active lanes and blockers.
2. Read the active task file in `PROJECT_PLAN/TASKS/`.
3. Read the governing specification in `docs/<area>/`.
4. Follow the linked runbook in `docs/runbooks/`.
5. Store actual evidence in `PROJECT_PLAN/proof_packets/<LANE>/`.

## Directory responsibilities

### `docs/architecture/`

System-wide contracts: source of truth, clock/reset/CDC, SDRAM arbitration,
video pipeline, capability model.

### `docs/fpga/`

SpinalHDL component specs: scope, interfaces, clock domains, memory behavior,
latency, commit timing, errors/status, assertions, SpinalSim coverage,
synthesis/resource limits, hardware proof, limitations.

### `docs/firmware/`

`libvdp` contracts: API boundary, semantics, errors/timeouts, blocking/async
behavior, transport limitations, compatibility, build/test targets, examples.

### `docs/runbooks/`

Exact commands for environment setup, simulation, Verilog generation,
synthesis, FPGA programming, firmware build, flash, hardware regression,
release build.

### `docs/testing/`

Test oracles, golden vectors, clean-room reproduction procedures.

### `docs/troubleshooting/`

Known issues, diagnostic steps, recovery procedures.

### `docs/reproducibility/`

Release manifests, tool version requirements, clean-room build instructions.

## Authority banner

Every stable technical document must begin with:

```markdown
> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.
```

## What not to put here

- Active lane status — use `STATUS.md`.
- Active task details — use `PROJECT_PLAN/TASKS/<TASK>.md`.
- Actual proof logs — use `PROJECT_PLAN/proof_packets/<LANE>/`.
- Release artifact hashes — use the release manifest.
