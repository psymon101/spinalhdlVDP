# Atari ST/STE — FPGA / SpinalHDL Plan

## Dependencies

- shared planar engine
- Copper X/Y writes
- atomic framebuffer commit

## Ordered implementation tasks

1. Create `AtariStAdapter.scala`.
2. Add interleaved 16-pixel word fetch.
3. Support 1/2/4 plane selection and significance.
4. Map border/display window.
5. Apply palette writes at approved X/Y.
6. Prove bandwidth for all three modes.

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
