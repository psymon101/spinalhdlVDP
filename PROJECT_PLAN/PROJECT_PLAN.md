# PROJECT_PLAN.md

**Updated:** 2026-05-10 (Task 56 DONE per CyanPeak #9709. No active lane converged yet.)
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

The project is currently at **Task 56 — Multi-Layer SDRAM Fetch** (Checkpoint B coding authorized per CyanPeak #9689). Task 54 is DONE per CyanPeak #9672. For the authoritative execution ledger, see `TASKS.md`.

## Working Principles

- Do not start a task unless every item in its `depends_on` list is `DONE`.
- Do not build past a task's `scope_boundary`.
- A task is not `DONE` until its validation criteria have been met.
- Do not infer terminology from general knowledge when `GLOSSARY.md` defines it.
- Do not invent board values when `PLATFORM.md` defines them.
