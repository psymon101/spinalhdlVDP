# Sega Mega Drive/Genesis — FPGA / SpinalHDL Plan

## Dependencies

- complex layer priority
- scroll-table fetch
- sprite chain

## Ordered implementation tasks

1. Create `GenesisAdapter.scala`.
2. Decode native name tables.
3. Implement plane/window selection.
4. Fetch scroll tables.
5. Implement approved priority resolver.
6. Map sprite chain/table.
7. Implement shadow/highlight post-compositor.

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
