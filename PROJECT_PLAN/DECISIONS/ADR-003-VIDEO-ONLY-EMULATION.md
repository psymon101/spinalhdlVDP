> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# ADR-003 — FPGA emulates video hardware only

## Status

Accepted.

## Decision

The FPGA does not emulate complete CPUs or machines. Any host may supply
platform-native graphics memory, register state, and timed events.

## Consequences

Accuracy claims are visual-chipset claims, not complete-machine compatibility.
