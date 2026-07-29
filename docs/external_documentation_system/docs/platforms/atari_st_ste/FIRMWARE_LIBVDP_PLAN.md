# Atari ST/STE — Firmware / libvdp Plan

## Ordered implementation tasks

1. Create `vdp_atarist_*` and optional `vdp_atariste_*`.
2. Upload authentic 32 KB screen data.
3. Add packed-to-ST-planar converter.
4. Reuse ST/STE palette helpers.
5. Add dirty-region and atomic-present helpers.

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
