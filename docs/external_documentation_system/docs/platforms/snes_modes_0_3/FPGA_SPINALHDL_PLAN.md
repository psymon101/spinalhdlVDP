# SNES Modes 0–3-lite — FPGA / SpinalHDL Plan

## Dependencies

- four layers
- windows/color math
- HDMA
- large sprite descriptors

## Ordered implementation tasks

1. Create `SnesAdapter.scala`.
2. Map modes and BG BPP.
3. Decode native tilemaps/tiles.
4. Implement mode priority.
5. Map OAM to shared sprites.
6. Map windows/masks/color math.
7. Map HDMA tables.
8. Defer Mode 7 until modes 0–3 close.

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
