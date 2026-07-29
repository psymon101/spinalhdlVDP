# Amiga OCS/ECS — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create `vdp_amiga_*`.
2. Add plane/modulo/window/color/sprite helpers.
3. Add Copper list builder using existing opcode helpers.
4. Add native independent-bitplane converter.
5. Add EHB/HAM6 reference assets.

## API requirements

The final public API uses the `vdp_` prefix and platform-specific names. It must
document:

- required initialization order;
- capability checks;
- structures and valid ranges;
- memory ownership/lifetime;
- blocking versus asynchronous operations;
- commit/present behavior;
- error/timeout handling;
- interrupt/thread/reentrancy rules;
- reference application sequence.

## Build matrix

At least:

- authoritative host/transport;
- mock/unit-test backend;
- one secondary host when supported.

## Rule

Reference applications may not hand-frame protocol commands that belong in
`libvdp`.
