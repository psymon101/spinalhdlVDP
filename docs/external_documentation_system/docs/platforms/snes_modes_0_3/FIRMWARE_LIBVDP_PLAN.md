# SNES Modes 0–3-lite — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create mode/BG/tilemap/palette/OAM/window/color/HDMA helpers.
2. Add native planar tile converter.
3. Build one reference scene per mode.

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
