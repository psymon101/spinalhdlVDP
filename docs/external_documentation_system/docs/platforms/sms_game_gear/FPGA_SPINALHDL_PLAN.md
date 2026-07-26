# Sega Master System and Game Gear — FPGA / SpinalHDL Plan

## Dependencies

- TMS9918A
- shared tile/sprite substrate

## Ordered implementation tasks

1. Create shared Sega 8-bit adapter.
2. Decode native tilemap entries.
3. Enforce sprite-per-line limit/overflow.
4. Implement approved scroll locks.
5. Implement Game Gear viewport.
6. Reuse shared tile/sprite/compositor.

## Required SpinalHDL deliverables

- adapter component in the approved `adapters` package;
- typed configuration/register Bundle;
- explicit interface to shared fetch/compositor engines;
- documented clock domain and latency;
- pending/active commit behavior;
- status/error outputs;
- assertions;
- component simulation;
- `VdpTop` integration simulation;
- synthesis/resource delta;
- hardware diagnostic mode where needed.

## Integration review questions

1. Does the adapter duplicate a common engine?
2. Are all memory requests included in the bandwidth budget?
3. Are platform limits enforced in hardware or clearly delegated?
4. Are timing boundaries explicit?
5. Are reset and mode-switch states deterministic?
6. Are all crossings and FIFOs safe?
7. Does the output conform to the shared pixel-candidate contract?
