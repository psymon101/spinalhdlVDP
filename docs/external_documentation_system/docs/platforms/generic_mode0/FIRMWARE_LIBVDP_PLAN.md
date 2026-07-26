# Generic Mode0 — Firmware / libvdp Plan

## Ordered implementation tasks

1. Complete full `libvdp` build matrix.
2. Add capability query and ABI guard.
3. Add structured Mode0 configuration and atomic commit helpers.
4. Add deterministic generic asset converters.
5. Remove transport-specific assumptions from generic helpers.

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
