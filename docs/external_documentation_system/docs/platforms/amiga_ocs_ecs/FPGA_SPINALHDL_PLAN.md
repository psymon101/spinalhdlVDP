# Amiga OCS/ECS — FPGA / SpinalHDL Plan

## Dependencies

- independent 1–6 plane fetch
- Copper
- shared sprites
- Blitter

## Ordered implementation tasks

1. Create `AmigaOcsAdapter.scala`.
2. Add independent plane pointers.
3. Add odd/even modulo and 1–6 plane fetch.
4. Implement dual-playfield split/priority.
5. Implement OCS sprite restrictions/attachment.
6. Map DIW/DDF-like windows.
7. Map allowed Copper registers.
8. Implement EHB.
9. Implement stateful HAM6 with line reset.
10. Map approved Blitter subset.

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
