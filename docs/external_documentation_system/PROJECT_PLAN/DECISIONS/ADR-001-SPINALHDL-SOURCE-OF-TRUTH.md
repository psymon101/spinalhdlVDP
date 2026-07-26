# ADR-001 — SpinalHDL is the FPGA source of truth

## Status

Accepted.

## Decision

All editable FPGA behavior is authored in Scala using SpinalHDL. Generated
Verilog is a build artifact. SpinalSim is the primary behavioral regression
environment before Gowin synthesis and hardware proof.

## Consequences

- No permanent generated-Verilog edits.
- Every FPGA feature requires SpinalHDL tests.
- Generator versions and commands are release-controlled.
