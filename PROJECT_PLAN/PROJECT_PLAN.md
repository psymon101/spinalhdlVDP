# PROJECT_PLAN.md

**Updated:** 2026-04-11
**Purpose:** Entry point for the `PROJECT_PLAN/` documentation set. Read this file first, then read the other files in the order listed below.

---

## Reading Order

1. `PROJECT_PLAN.md` — current architecture snapshot, document precedence, and working rules
2. `MODE0_ROADMAP.md` — strategic capability build order for the `Mode0` substrate
3. `TASK_TEMPLATE.md` — reusable planning template for turning roadmap items into bounded execution tasks
4. `TASKS.md` — authoritative execution order and task status
5. `CONVENTIONS.md` — coding, naming, clock/reset, and simulation rules
6. `PLATFORM.md` — board facts and validated hardware data
7. `REPO_STRUCTURE.md` — where code and board assets live today
8. `GLOSSARY.md` — project-specific term definitions

If these documents disagree:

- `TASKS.md` wins for task ordering and task status
- `PLATFORM.md` wins for board facts and validated values
- `GLOSSARY.md` wins for terminology
- `REPO_STRUCTURE.md` wins for file placement

---

## Current Reality

This repository is already past the initial blank-slate bring-up phase.

The currently validated slice is:

- SpinalHDL package prefix: `spinalhdlvdp`
- Generated HDL target directory: `hw/gen`
- Board flow: `fpga/tang20k/`
- Top-level board entry: `TopTang20kHdmi.scala`
- Current visible hardware output: deterministic memory-backed tiled pattern generated from on-chip tile and tilemap ROMs
- Current validated output path: Tang Nano 20K HDMI output captured locally on this machine through `/dev/video2`

Do not treat this repository as if it were still at the "empty stub" stage. The task list below is organized around the implementation that already exists.

---

## Working Principles

- Do not start a task unless every item in its `depends_on` list is `DONE`.
- Do not build past a task's `scope_boundary`.
- A task is not `DONE` until its validation criteria have been met.
- Do not infer terminology from general knowledge when `GLOSSARY.md` defines it.
- Do not invent board values when `PLATFORM.md` defines them.
- Do not refactor package names or directory layout unless a task explicitly calls for that work.

---

## Current Architecture Snapshot

The current hardware-proven path is intentionally small:

- `VdpTop.scala` owns visible raster timing and the direct test-pattern pixel generator
- `TopTang20kHdmi.scala` owns board clocking, reset, LEDs, and wiring into the HDMI transport
- `Tang20kHdmiTx.scala` is the SpinalHDL black-box boundary for the board-specific TMDS transport
- `fpga/tang20k/tang20k_hdmi_tx.sv` owns TMDS encode / serializer / LVDS output details
- `fpga/tang20k/Makefile` owns HDL generation, Gowin build, and board programming

This is a vertical slice for output bring-up, not yet a full Mode0 implementation.

Architectural rule:

- `Mode0` is the superset rendering substrate for this repo, not one platform-specific mode among many
- platform-facing modes are semantic adapters over `Mode0`, not separate render engines
- platform-specific registers, quirks, and control models belong in the adapter layer
- generic rendering/timing/fetch/composition primitives belong in `Mode0`
- the external host is a command/control owner, not a renderer
- host-side firmware may upload assets, write registers, poll status, and respond to interrupts/events
- per-pixel display processing, composition, fetch timing, and beam-synchronous behavior belong in the VDP-side video processor, not in the host firmware

Example:

- an Amiga-oriented adapter may implement Copper-style register semantics and raster-driven control updates
- but it should do so by consuming `Mode0` primitives such as current scanline timing, linestate commit, layer control, palette updates, and fetch/composition hooks

---

## Validated Baseline

The following are already proven on this repository state:

- SBT generation from the repo root succeeds
- Gowin synthesis / place-and-route succeeds in a headless environment
- Board flash programming succeeds with explicit FTDI serial binding when required
- Direct local capture confirms the intended current tiled pattern is what the FPGA renders
- `VdpTopSim.scala` checks the same on-chip pattern source in simulation

---

## Next Practical Work

The current validated baseline is now past the early on-chip pipeline tasks:

- Task 8: wraparound / seam correctness
- Task 9: line buffer path
- Task 10: palette lookup
- Task 11: single-sprite proof
- Task 12: sprite priority / transparency
- Task 13: two-layer background composition
- Task 14: per-line linestate prepare / commit

The next unfinished mainline task is:

- Task 15: memory-backed fetch path using the Tang Nano 20K embedded SDR SDRAM

Task 15 must stay tightly bounded:

- use the embedded 64 Mbit SDR SDRAM SiP, not PSRAM / HyperRAM
- use the corrected 32-bit SDRAM model from `PLATFORM.md`
- keep sprites on-chip
- keep planar and shuffled fetch out of scope
- preserve the current on-chip path as a comparison baseline until SDRAM fetch is proven

The current visible output path is known-good and must remain the comparison reference while the SDRAM-backed fetch path is brought up.

For the longer-range `Mode0` build order beyond the currently validated slice, use `MODE0_ROADMAP.md`. It defines the strategic primitive progression needed to support the target platform adapters without turning `Mode0` into a platform-specific renderer.
