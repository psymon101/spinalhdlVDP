# PROJECT_PLAN.md

**Updated:** 2026-05-17 (Mode2optimized Compile-Time Feature Strip DONE per BrightForge #10142 / CoralReef audit closeout. Task 10026 Barebones Simple Sprite DONE audit PASS #10117. No active lanes.)
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

If these documents disagree:

- `TASKS.md` wins for task ordering and task status
- `PLATFORM.md` wins for board facts and validated values
- `GLOSSARY.md` wins for terminology
- `REPO_STRUCTURE.md` wins for file placement

## Current Reality

This is a **full Mode0 rendering substrate** past initial bring-up. The hardware-proven path includes SDRAM-backed tile, planar, shuffled, bitmap, affine, sprite, color-math, window, dual-window, palette RAM, Copper, HDMA, raster triggers, and QSPI host control — 20+ hardware-proven scenarios on Tang Nano 20K.

The project has no active critical-path lanes. Recently completed:

- **Mode2optimized Compile-Time Feature Strip** — DONE (BrightForge #10142 / CoralReef audit closeout)
  - Rich-top default build now fits Tang Nano 20K with `project.fs` produced
  - Branch `mode2optimized-gate2-enableL2L3` @ `22afb90`
- **Task 10026 — Barebones Simple Sprite over Background** — DONE (audit PASS #10117)
  - Commits `eda89d7`, `6119360`, `40f1424`
- **Task 56 — Multi-Layer SDRAM Fetch** — DONE (audit PASS #9709)
- **Task 54 — Sprite-Sprite Collision Detector** — DONE (audit PASS #9672)

For the authoritative execution ledger, see `TASKS.md`.

## Working Principles

- Do not start a task unless every item in its `depends_on` list is `DONE`.
- Do not build past a task's `scope_boundary`.
- A task is not `DONE` until its validation criteria have been met.
- Do not infer terminology from general knowledge when `GLOSSARY.md` defines it.
- Do not invent board values when `PLATFORM.md` defines them.
