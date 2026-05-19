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

This is a **full Mode0 rendering substrate** (SDRAM-backed tile, planar, bitmap, affine, sprites, Copper, HDMA, etc.) with 20+ hardware-proven scenarios on Tang Nano 20K.

The project currently has **no active critical-path lanes**. 

Authoritative execution status: [`TASKS.md`](TASKS.md).
Summary of recent closeouts:
- **Mode2optimized Feature Strip** — Bitstream fits Tang Nano with 51% headroom.
- **Copper Double-Buffer (3b)** — Atomic bank-swap proven on silicon.
- **libvdp Mode0 Surface** — Full register-map helper coverage.

## Working Principles

- Do not start a task unless every item in its `depends_on` list is `DONE`.
- Do not build past a task's `scope_boundary`.
- A task is not `DONE` until its validation criteria have been met.
- Do not infer terminology from general knowledge when `GLOSSARY.md` defines it.
- Do not invent board values when `PLATFORM.md` defines them.
