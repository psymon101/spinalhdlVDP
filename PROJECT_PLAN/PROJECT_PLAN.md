# PROJECT_PLAN.md

**Updated:** 2026-04-11
**Purpose:** Entry point for the `PROJECT_PLAN/` documentation set. Read this file first, then read the other files in the order listed below.

---

## Reading Order

1. `PROJECT_PLAN.md` — current architecture snapshot, document precedence, and working rules
2. `TASKS.md` — authoritative execution order and task status
3. `CONVENTIONS.md` — coding, naming, clock/reset, and simulation rules
4. `PLATFORM.md` — board facts and validated hardware data
5. `REPO_STRUCTURE.md` — where code and board assets live today
6. `GLOSSARY.md` — project-specific term definitions

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

The next unfinished work begins after the current direct test-pattern slice:

- Task 7: add scroll offsets on top of the current on-chip pattern source
- Task 9: introduce a line buffer path while preserving the current visible output
- Task 10 and beyond: palette, composition, sprites, and more advanced Mode0 primitives

These tasks must be approached incrementally. The current output path is known-good and should remain the comparison baseline for future changes.
