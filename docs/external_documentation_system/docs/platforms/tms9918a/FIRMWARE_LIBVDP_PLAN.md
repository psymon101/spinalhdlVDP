# TMS9918A Family — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create `vdp_tms9918_*`.
2. Reuse fixed-palette loader.
3. Add native table upload helpers.
4. Add mode/register configuration objects.

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
