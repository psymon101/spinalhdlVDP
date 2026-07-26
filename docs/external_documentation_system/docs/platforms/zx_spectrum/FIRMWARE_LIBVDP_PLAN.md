# ZX Spectrum — Firmware / libvdp Plan

## Ordered implementation tasks

1. Add `vdp_zx_init`.
2. Add bitmap and attribute upload helpers.
3. Add border/flash/present helpers.
4. Add Spectrum memory-layout converter.
5. Build an intentional attribute-clash reference scene.

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
