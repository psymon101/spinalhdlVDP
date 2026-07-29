> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# FPGA Documentation

SpinalHDL component specifications and verification expectations.

Each document covers one component or subsystem and includes:
- scope;
- SpinalHDL component names;
- interfaces;
- clock/reset domain;
- memory behavior;
- latency;
- commit timing;
- errors/status;
- assertions;
- SpinalSim coverage;
- synthesis/resource limits;
- hardware proof;
- limitations.

Editable source lives in `hw/spinal/spinalhdlvdp/`. Generated Verilog under
`hw/gen/` is a build artifact.
