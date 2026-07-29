# Atari 2600 TIA — FPGA / SpinalHDL Plan

## Dependencies

- beam-timed event engine
- common compositor/output

## Ordered implementation tasks

1. Create `Atari2600TiaAdapter.scala`.
2. Implement procedural scanline state.
3. Generate playfield/player/missile/ball pixels.
4. Implement motion/copy/size.
5. Implement priority and collision latches.
6. Integrate timed-write command list.

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
