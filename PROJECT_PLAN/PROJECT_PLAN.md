# PROJECT_PLAN.md

**Updated:** 2026-06-12 (RGB565-FULLFRAME-132 active; VOODOO-ADOPTION plan drafted; FORMAL-131 and REGINFRA-130 closed.)
**Purpose:** Entry point for the `PROJECT_PLAN/` documentation set.

## Reading Order

1. `PROJECT_PLAN.md` (this file)
2. `MODE0_PLANNING.md`
3. `PLATFORM_ADAPTERS.md`
4. `ASSESSMENT.md`
5. `TASK_TEMPLATE.md`
6. `TASKS.md`
7. `VOODOO_ADOPTION_PLAN.md`
8. `CONVENTIONS.md`
9. `PLATFORM.md`
10. `REPO_STRUCTURE.md`
11. `GLOSSARY.md`
12. `DESIGN_NOTE_CHUNKY_CORE_PLANAR_COMPAT.md`

If these documents disagree:

- `TASKS.md` wins for task ordering and task status
- `PLATFORM.md` wins for board facts and validated values
- `GLOSSARY.md` wins for terminology
- `REPO_STRUCTURE.md` wins for file placement

## Current Reality

This is a **full Mode0 rendering substrate** (SDRAM-backed tile, planar, bitmap, affine, sprites, Copper, HDMA, etc.) with 20+ hardware-proven scenarios on Tang Nano 20K.

The project has one recently closed critical-path RTL lane and one active docs/example lane:
- **RGB565-FULLFRAME-132** — closed. RGB565 full-frame direct-color display correctness proven on silicon (burst-read SDRAM controller @ 40.5 MHz, merged to `main` @ `c8129bd` / `d668e01`).
- **RGB565-FULLFRAME-DOCS-133** — done. Host-facing documentation and examples were updated so the RGB565 feature is usable without reading RTL; merged to `main` @ `c98ec03`.

The long-term strategic roadmap is in [`VOODOO_ADOPTION_PLAN.md`](VOODOO_ADOPTION_PLAN.md).

**Host interface:** the canonical Tang Nano 20K host path is **QSPI/ESP32-P4** (`QspiSlave`/`QspiDecoder`/`QspiSdramBridge`). The i80/ESP32-S3 path and the legacy SPI path remain in the tree as historical references but are retired as primary development targets.

Authoritative execution status: [`TASKS.md`](TASKS.md).
Summary of recent closeouts:
- **FORMAL-131** — SdramArbiter formal verification closed; all 5 properties proven at BMC depth 20 (`cyanpeak/formal-131` @ `e9ead7c`, merged to main @ `c7a1ca1`).
- **REGINFRA-130** — Canonical `firmware/libvdp/mode0_regs.json` with 49 registers, descriptions, normalized categories, and generators `gen_mode0_regs.py` / `gen_reg_docs.py`. Spec §3.1.3 regenerated and TBD-free.
- **BITMAP-PLUMB-129** — Register-driven bitmap/attribute base, stride, and height implemented, sim/STA/HW proven.
- **ACK/NAK Phase 2** — txnDropped detection implemented in `QspiDecoder.scala`; hardware proof PASS on bitstream sha1 `4097ac24...`.
- **QSPI Throughput Investigation** — Closed as answered research. FPGA is the read limiter (~3 MHz), ESP32-S3 not the bottleneck.
- **Copper Double-Buffer (3b)** — Atomic bank-swap proven on silicon.
- **libvdp Mode0 Surface** — Full register-map helper coverage.

## Working Principles

- Do not start a task unless every item in its `depends_on` list is `DONE`.
- Do not build past a task's `scope_boundary`.
- A task is not `DONE` until its validation criteria have been met.
- Do not infer terminology from general knowledge when `GLOSSARY.md` defines it.
- Do not invent board values when `PLATFORM.md` defines them.
