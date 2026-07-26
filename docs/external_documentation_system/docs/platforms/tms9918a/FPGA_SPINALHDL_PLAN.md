# TMS9918A Family — FPGA / SpinalHDL Plan

## Dependencies

- Generic Mode0
- ZX closure

## Ordered implementation tasks

1. Create `Tms9918Adapter.scala`.
2. Map name/pattern/color tables.
3. Implement native sprite limits and status semantics.
4. Reuse tile/sprite/palette engines.
5. Add table-base register mapping.

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
