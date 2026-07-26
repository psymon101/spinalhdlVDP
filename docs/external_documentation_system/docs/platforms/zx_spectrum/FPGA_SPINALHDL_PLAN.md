# ZX Spectrum — FPGA / SpinalHDL Plan

## Dependencies

- Generic Mode0

## Ordered implementation tasks

1. Re-run existing adapter simulation against reconciled baseline.
2. Verify production shuffled addressing.
3. Verify flash cadence/reset.
4. Define border commit boundary.
5. Add direct attribute-clash regression.
6. Integrate without duplicating shared palette/scaler/output.

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
