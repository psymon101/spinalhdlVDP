# Active Lane

## Lane

**FOUNDATION-0 — Baseline and Contract Reconciliation**

## State

`RESEARCH / BASELINE CAPTURE`

## Goal

Produce one authoritative, reproducible baseline for source, SpinalHDL
generation, SpinalSim, generated Verilog, Gowin synthesis, FPGA bitstream,
`libvdp`, reference firmware, host hardware, transport, wiring, and hardware
acceptance.

## Ordered work

1. `FOUNDATION-0-001` — source and artifact inventory.
2. `FOUNDATION-0-002` — lock SpinalHDL generator and Gowin project.
3. `FOUNDATION-0-003` — reconcile bitmap-format encoding.
4. `FOUNDATION-0-004` — reconcile planar plane-count/layout contract.
5. `FOUNDATION-0-005` — reconcile Copper timing and `VdpTop` integration.
6. `FOUNDATION-0-006` — select authoritative host and transport.
7. `FOUNDATION-0-007` — repair complete `libvdp` build matrix.
8. `FOUNDATION-0-008` — run and lock baseline SpinalSim regression.
9. `FOUNDATION-0-009` — synthesize and capture resource/timing baseline.
10. `FOUNDATION-0-010` — matched firmware/bitstream hardware proof.
11. `FOUNDATION-0-011` — independent documentation and source audit.
12. `FOUNDATION-0-012` — sign gate and open Foundation 1.

## Blocking defects already identified

- Bitmap mode names/encodings are not consistently represented.
- Public planar configuration does not yet express the intended full contract.
- Vblank/status behavior is not uniformly transport-neutral.
- The current Pico CMake target does not compile the full visible `libvdp`
  source surface.
- ESP32-P4 requires a dedicated pure ESP-IDF transport/backend path.
- Exact source, bitstream, firmware, and hardware baseline values remain to be
  locked.

## Exit gate

Foundation 0 closes only when `CURRENT_BASELINE.md` contains no unresolved
`TBD-FOUNDATION-0` fields required for the supported baseline and a clean-room
reviewer can repeat the baseline build and hardware proof.
