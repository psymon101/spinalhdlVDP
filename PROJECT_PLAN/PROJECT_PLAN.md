# PROJECT_PLAN.md

**Updated:** 2026-07-25
**Purpose:** Entry point for the `PROJECT_PLAN/` documentation set.

## Reading Order

1. `PROJECT_PLAN/STATUS.md` — **authoritative live lane status**
2. `PROJECT_PLAN/PROJECT_PLAN.md` (this file)
3. `MODE0_PLANNING.md` (archived historical planning)
4. `PLATFORM_ADAPTERS.md`
5. `ASSESSMENT.md`
6. `TASK_TEMPLATE.md`
7. `VOODOO_ADOPTION_PLAN.md`
8. `CONVENTIONS.md`
9. `PLATFORM.md`
10. `REPO_STRUCTURE.md`
11. `GLOSSARY.md`
12. `DESIGN_NOTE_CHUNKY_CORE_PLANAR_COMPAT.md`

> **Note:** The legacy operational task ledger `TASKS.md` has been archived to
> `PROJECT_PLAN/archive/TASKS_stale_2026-06-19.md`. It is retained for history
> only. `STATUS.md` is now the single source of truth for active lanes.

If these documents disagree:

- `STATUS.md` wins for current task status and lane ordering
- `PLATFORM.md` wins for board facts and validated values
- `GLOSSARY.md` wins for terminology
- `REPO_STRUCTURE.md` wins for file placement

## Current Reality

This is a **full Mode0 rendering substrate** (SDRAM-backed tile, planar, bitmap, affine, sprites, Copper, HDMA, etc.) on Tang Nano 20K.

Recent closed lanes (see `STATUS.md` for full proof):
- **QSPI-CRC8-185** — functional CRC8 proof PASS; visual artifact closed as downstream scaler ring.
- **PIXELWITHINBYTE-ALIGN** — fixed latent `VdpTop:1602` intra-byte pixel-index skew; cherry-picked to `main` @ `f09159f`.
- **BITMAP-CDC-SHIMMER-FIX** — registered bitmap fetch line + stretched grant to fix CDC shimmer.
- **HAM6 removal + 2bpp indexed replacement** — 4 MHz canonical bulk-upload path proven; HAM6 shelved from critical path.
- **repo-cleanup** — archived test firmware, capture media, sdkconfig artifacts, and stale `TASKS.md` to `PROJECT_PLAN/archive/`.

The long-term strategic roadmap is in [`VOODOO_ADOPTION_PLAN.md`](VOODOO_ADOPTION_PLAN.md).

**Host interface:** the canonical Tang Nano 20K host path is **QSPI/ESP32-P4** (`QspiSlave`/`QspiDecoder`/`QspiSdramBridge`). The i80/ESP32-S3 path and the legacy SPI path are retired as primary development targets; historical sketches are archived in `PROJECT_PLAN/archive/firmware_tests/`.

Authoritative execution status: [`STATUS.md`](STATUS.md).

## Documentation, Decisions, and Proof Structure

- Stable technical docs live under `docs/`. See `docs/DOCUMENTATION_GUIDE.md`.
- Architecture decisions live under `PROJECT_PLAN/DECISIONS/`.
- Proof packets for every result-bearing lane live under
  `PROJECT_PLAN/proof_packets/<LANE>/`.
- Task details live under `PROJECT_PLAN/TASKS/<TASK>.md`.
- The external documentation system under `docs/external_documentation_system/`
  is a **reference snapshot**, not canonical live state.

## Working Principles

- Do not start a task unless every item in its `depends_on` list is `DONE`.
- Do not build past a task's `scope_boundary`.
- A task is not `DONE` until its validation criteria have been met.
- Do not infer terminology from general knowledge when `GLOSSARY.md` defines it.
- Do not invent board values when `PLATFORM.md` defines them.
