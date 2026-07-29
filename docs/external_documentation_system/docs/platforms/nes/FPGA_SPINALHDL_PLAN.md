# NES/Famicom — FPGA / SpinalHDL Plan

## Dependencies

- shared planar tile decode
- shared sprite evaluator

## Ordered implementation tasks

1. Create `NesAdapter.scala`.
2. Decode native 2bpp tiles.
3. Decode attribute quadrants.
4. Map OAM to shared sprites.
5. Enforce 8 sprites per line.
6. Implement sprite-zero hit/overflow.
7. Map mirroring and clipping.

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
