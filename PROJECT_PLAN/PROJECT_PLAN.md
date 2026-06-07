# PROJECT_PLAN.md

**Updated:** 2026-06-04 (ACK/NAK Phase 2 DONE — hardware proof PASS. Lane-closeout complete. Awaiting owner direction for next lane. QSPI throughput investigation closed. CyanPeak doc audit PASS.)
**Purpose:** Entry point for the `PROJECT_PLAN/` documentation set.

## Reading Order

1. `PROJECT_PLAN.md` (this file)
2. `MODE0_PLANNING.md`
3. `PLATFORM_ADAPTERS.md`
4. `ASSESSMENT.md`
5. `TASK_TEMPLATE.md`
6. `TASKS.md`
7. `CONVENTIONS.md`
8. `PLATFORM.md`
9. `REPO_STRUCTURE.md`
10. `GLOSSARY.md`
11. `DESIGN_NOTE_CHUNKY_CORE_PLANAR_COMPAT.md`

If these documents disagree:

- `TASKS.md` wins for task ordering and task status
- `PLATFORM.md` wins for board facts and validated values
- `GLOSSARY.md` wins for terminology
- `REPO_STRUCTURE.md` wins for file placement

## Current Reality

This is a **full Mode0 rendering substrate** (SDRAM-backed tile, planar, bitmap, affine, sprites, Copper, HDMA, etc.) with 20+ hardware-proven scenarios on Tang Nano 20K.

The project has **one active critical-path lane**: **Planar Bitplane Source -> L0 Layer Render Proof** (PLANAR-L0-HW), opened per owner direction and reframed to 5-plane (32-color) depth-agnostic proof per BrightForge #11706. The most recently closed lane is **ACK/NAK Phase 2** (txnDropped detection), proven in hardware. Lane-closeout team check is complete; all CoralReef audit findings are closed.

Authoritative execution status: [`TASKS.md`](TASKS.md).
Summary of recent closeouts:
- **ACK/NAK Phase 1** — Fix B W1C clear path validated on silicon (2× 32-tile discriminator PASS with NAK recovery at tile[01] and tile[21]). Persistent bitstream `5282d33f` @ `3647f2e`. CyanPeak doc audit PASS #11638/#11639.
- **ACK/NAK Phase 2** — DONE. txnDropped detection implemented in `QspiDecoder.scala`; `QspiWriteStatusReproSim` CASE F proves overlap sets the sticky flag and W1C clears it. Original candidate `f18cd55` had thin margin (+0.036%); registered-path fix landed @ `b3880f2`, restoring `clk_pixel` Fmax to 25.584 MHz (~1.5% margin). Hardware proof PASS on bitstream sha1 `4097ac24...`: before=0x00, after overlap=0x15 (bit4=1), after W1C=0x04 (bit4=0).
- **QSPI Throughput Investigation** — Closed as answered research. BrightForge and BronzeGate reports converge: FPGA is the limiter, not the ESP32-S3 host. Read hard cap ~3 MHz, write safe ceiling ~6.3 MHz, sustained sink bounded by SDRAM byte controller. Proposed benchmark RTL held until after Phase 2 closes.
- **Mode2optimized Feature Strip** — Bitstream fits Tang Nano with 51% headroom.
- **Copper Double-Buffer (3b)** — Atomic bank-swap proven on silicon.
- **libvdp Mode0 Surface** — Full register-map helper coverage.

## Working Principles

- Do not start a task unless every item in its `depends_on` list is `DONE`.
- Do not build past a task's `scope_boundary`.
- A task is not `DONE` until its validation criteria have been met.
- Do not infer terminology from general knowledge when `GLOSSARY.md` defines it.
- Do not invent board values when `PLATFORM.md` defines them.
