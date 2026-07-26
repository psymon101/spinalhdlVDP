# Commodore 64 VIC-II — FPGA / SpinalHDL Plan

## Dependencies

- shared raster events
- shared sprite collisions

## Ordered implementation tasks

1. Create `C64VicIIAdapter.scala`.
2. Map character/screen/color/bitmap memory.
3. Implement multicolor decode.
4. Implement sprite expansion/multicolor/priority.
5. Map collisions/status.
6. Use Copper/HDMA for raster effects.
7. Model visual bad-line effects only when required.

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
