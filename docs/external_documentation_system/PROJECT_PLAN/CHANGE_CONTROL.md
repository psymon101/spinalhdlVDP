# Change Control

## Changes requiring an ADR

- host protocol framing;
- register ABI;
- clock-domain structure;
- SDRAM arbitration;
- public `libvdp` API compatibility;
- platform scope or accuracy claim;
- authoritative host/transport;
- source-of-truth order;
- resource-budget changes;
- release reproducibility policy.

## Required PR contents

Every implementation PR includes:

- task ID and lane state;
- approved specification links;
- SpinalHDL files changed;
- firmware files changed;
- register/schema changes;
- simulations added and commands;
- synthesis/resource impact;
- hardware proof requirements;
- documentation changes;
- rollback plan.

## Generated-file policy

Generated Verilog and generated bindings may be committed when required for
tooling, but CI must prove they were regenerated from the checked-in sources.
Permanent hand edits are forbidden.
