# Generic Mode0 — FPGA / SpinalHDL Plan

## Dependencies

- Foundation 0
- Foundation 1
- Foundation 2

## Ordered implementation tasks

1. Reconcile bitmap format encoding and regenerate schema/bindings.
2. Finish and prove packed bitmap modes.
3. Finish shared one-to-six-plane engine.
4. Stabilize four layers and documented sprite ceilings.
5. Stabilize Copper, HDMA, LINESTATE, DMA, Blitter, compositor, scaler.
6. Add capability/ABI registers and late/underrun diagnostics.
7. Run full contention and reset regression.

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
