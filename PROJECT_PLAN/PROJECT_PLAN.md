# PROJECT_PLAN.md

**Updated:** 2026-04-28
**Purpose:** Entry point for the `PROJECT_PLAN/` documentation set. Read this file first, then read the other files in the order listed below.

---

## Reading Order

1. `PROJECT_PLAN.md` — current architecture snapshot, document precedence, and working rules
2. `MODE0_ROADMAP.md` — strategic capability build order for the `Mode0` substrate
3. `ADAPTER_NUANCES.md` — platform-facing visual rules and adapter fidelity expectations
4. `MODE0_STOPLINES.md` — quantified Tang Nano 20K growth limits for `Mode0`
5. `MODE0_MAX_CAPABILITIES.md` — intended superset envelope for shared `Mode0` primitives
6. `MODE0_COVERAGE_MATRIX.md` — current coverage state of the intended `Mode0` envelope
7. `MODE0_HARDENING_BACKLOG.md` — prioritized shared-gap closure order before harder adapters
8. `TASK_TEMPLATE.md` — reusable planning template for turning roadmap items into bounded execution tasks
9. `TASKS.md` — authoritative execution order and task status
10. `CONVENTIONS.md` — coding, naming, clock/reset, and simulation rules
11. `PLATFORM.md` — board facts and validated hardware data
12. `REPO_STRUCTURE.md` — where code and board assets live today
13. `GLOSSARY.md` — project-specific term definitions

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
- Current visible hardware output: **SDRAM-backed Mode0 rendering** with tile, planar, shuffled, bitmap, affine, sprite, color-math, window, dual-window, palette RAM, Copper, HDMA, raster triggers, and QSPI host control — 20 hardware-proven scenarios
- Current validated output path: Tang Nano 20K HDMI output captured locally on this machine through `/dev/video2` and via RTSP stream

Do not treat this repository as if it were still at the "empty stub" stage. Tasks 1–43, R1–R6, and all substrate hardening lanes are **DONE**. The active lane is **Task 50 — ZX Spectrum Adapter — Implementation** (IN-PROGRESS, BrightForge).

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

The current hardware-proven path is a **full Mode0 rendering substrate**:

- `VdpTop.scala` owns raster timing, SDRAM fetch scheduler, tile/planar/shuffled/bitmap decoders, sprite evaluator, affine stepper, compositor, color math, dual window comparator, and palette RAM
- `TopTang20kHdmi.scala` owns board clocking, reset, LEDs, scenario bootstrap, sprite animators, and HDMI transport wiring
- `Tang20kHdmiTx.scala` is the SpinalHDL black-box boundary for the board-specific TMDS transport
- `fpga/tang20k/tang20k_hdmi_tx.sv` owns TMDS encode / serializer / LVDS output details
- `fpga/tang20k/Makefile` owns HDL generation, Gowin build, and board programming
- QSPI slave (`QspiSlave.scala`) enables runtime host control and asset upload
- Copper-lite coprocessor enables mid-frame register updates for raster effects

This is a full Mode0 implementation, not a bring-up slice. The strategic focus has shifted from **substrate construction** to **substrate hardening** and **platform adapter development**.

Architectural rule:

- `Mode0` is the superset rendering substrate for this repo, not one platform-specific mode among many
- platform-facing modes are semantic adapters over `Mode0`, not separate render engines
- platform-specific registers, quirks, and control models belong in the adapter layer
- generic rendering/timing/fetch/composition primitives belong in `Mode0`
- `Mode0` should expose the strongest general-purpose primitive that multiple platforms can share, even if some adapters use only a constrained subset of it
- adapters should translate platform intent into `Mode0` parameters, limits, and control choices instead of requiring separate per-platform engines
- if one platform needs only a bounded subset of a primitive and another needs the full range, both should consume the same `Mode0` primitive whenever the hardware model is honestly shareable
- do not weaken a shared `Mode0` primitive to match the least demanding platform; adapters may clamp a richer primitive downward, but they should not require a second weaker implementation
- if a capability is reusable across multiple target platforms, it belongs in `Mode0`; if it is mainly a platform-specific register map, policy rule, or presentation quirk, it belongs in the adapter
- the external host is a command/control owner, not a renderer
- host-side firmware may upload assets, write registers, poll status, and respond to interrupts/events
- per-pixel display processing, composition, fetch timing, and beam-synchronous behavior belong in the VDP-side video processor, not in the host firmware
- each external host transport must be internally complete and self-consistent: `QSPI` framing/sync/completion belong to the `QSPI` contract, and any future parallel bus must define its own transport contract independently
- multiple host transports may target the same `Mode0` control surface, but they must not depend on hidden assumptions from each other

Example:

- an Amiga-oriented adapter may implement Copper-style register semantics and raster-driven control updates
- but it should do so by consuming `Mode0` primitives such as current scanline timing, linestate commit, layer control, palette updates, and fetch/composition hooks
- an Amiga-oriented adapter may drive a richer sprite or blitter capability than a C64-oriented adapter, but both should sit on the same underlying `Mode0` sprite / transfer primitives if those primitives are general enough

---

## Validated Baseline

The following are already proven on this repository state:

- SBT generation from the repo root succeeds
- Gowin synthesis / place-and-route succeeds in a headless environment
- Board flash programming succeeds with explicit FTDI serial binding when required
- Direct local capture confirms the intended output is what the FPGA renders
- `VdpTopSim.scala` regression suite passes for all closed substrate paths
- 20 scenarios (Sc0–Sc17, Sc50, Sc51, Sc52, Sc60) have been hardware-proven on Tang Nano 20K
- Fetch Envelope Hardening is complete and audited
- Sprite Envelope Hardening is complete and audited
- Sprite Pattern Memory Foundation is complete and audited
- Sprite Phase 2 + 2-bis is complete and audited (Scenario 50, commit `39a7242`)
- Color/Window Hardening is complete and audited (Scenarios 51 + 52, commit `0f5dc65`)
- Beam-Driven Automation Hardening is complete and audited (Scenario 60, commit `6345fcc`)

---

## Next Practical Work

The current validated baseline is **past the entire mainline substrate construction phase**. Tasks 1–43, R1–R6, and the first three hardening lanes are complete.

**Active lane:**
- **Task 50 — ZX Spectrum Adapter — Implementation** (IN-PROGRESS, BrightForge)
  - First serious platform adapter after C64 smoke-test (Task 40)
  - ZX Spectrum bitmap + attribute display on Mode0 substrate
  - Thin translation layer; estimated +50–100 LUT/FF

**Previous lanes (all closed):**
- Beam-Driven Automation Hardening — DONE (`6345fcc`, audit PASS #8660)
- Color/Window Hardening — DONE (`0f5dc65`, audit PASS #8654)
- Sprite Phase 2 + 2-bis — DONE (`39a7242`, audit PASS #8638)
- Sprite Pattern Memory Foundation — DONE (`e86fe49`, audit PASS #8605)
- Mode0 Sprite Envelope Hardening — DONE (`d44a9c0`, audit PASS #8589)
- Mode0 Fetch Envelope Hardening — DONE

**Next expected work after Task 50 closeout:**
- Additional platform adapters (Amiga, Genesis/MD, SNES)
- Substrate hardening if adapter gaps emerge

For the strategic `Mode0` build order and adapter roadmap, use `MODE0_ROADMAP.md`. For the authoritative execution ledger, use `TASKS.md`.
