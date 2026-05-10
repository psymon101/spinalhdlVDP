# PROJECT_PLAN.md

**Updated:** 2026-05-10 (Task 54 DONE; Task 56 Checkpoint A IN-PROGRESS)
**Purpose:** Entry point for the `PROJECT_PLAN/` documentation set. Read this file first, then read the other files in the order listed below.

---

## Reading Order

1. `PROJECT_PLAN.md`
2. `MODE0_PLANNING.md`
3. `ADAPTER_NUANCES.md`
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

---

## Current Reality

This repository is already past the initial blank-slate bring-up phase.

The currently validated slice is:

- SpinalHDL package prefix: `spinalhdlvdp`
- Generated HDL target directory: `hw/gen`
- Board flow: `fpga/tang20k/`
- Top-level board entry: `TopTang20kHdmi.scala`
- Current visible hardware output: **SDRAM-backed Mode0 rendering** with tile, planar, shuffled, bitmap, affine, sprite, color-math, window, dual-window, palette RAM, Copper, HDMA, raster triggers, and QSPI host control — 20+ hardware-proven scenarios
- Current validated output path: Tang Nano 20K HDMI output captured via RTSP stream (`rtsp://192.168.1.95:8554/live`) and local webcam fallback

Do not treat this repository as if it were still at the "empty stub" stage. Tasks 1–55 and all substrate hardening lanes are **DONE**. Task 57 is **DONE** (Path 5A PnR PASS #9617). The project is currently at **Task 56 — Multi-Layer SDRAM Fetch** (Checkpoint A landed per BrightForge #9685 / commit `93773d7`; awaiting CyanPeak audit). Task 54 is DONE per CyanPeak #9672.

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
- 20 scenarios (Sc0–Sc17, Sc50, Sc51, Sc52, Sc55, Sc60) have been hardware-proven on Tang Nano 20K
- Fetch Envelope Hardening is complete and audited
- Sprite Envelope Hardening is complete and audited
- Sprite Pattern Memory Foundation is complete and audited
- Sprite Phase 2 + 2-bis is complete and audited (Scenario 50, commit `39a7242`)
- Color/Window Hardening is complete and audited (Scenarios 51 + 52, commit `0f5dc65`)
- Beam-Driven Automation Hardening is complete and audited (Scenario 60, commit `6345fcc`)
- Sprite Pattern Address Width Expansion is complete and audited (Task 53, PASS #9433)
- Planar Fetch Hardening is complete and audited (Task 3, PASS #9406)
- Sprite Masking + Budget Counter is complete and audited (Task 55, PASS #9479)

---

## Next Practical Work

The current validated baseline is **past the entire mainline substrate construction and primary hardening phases**. Tasks 1–55 and the critical substrate lanes are complete.

**Project Status:**
- **Task 55 — Sprite Masking + Budget:** DONE — audit PASS #9479.
- **Task 53 — Sprite Pattern Width Expansion:** DONE — audit PASS #9433.
- **Task 3 — Planar Fetch Hardening:** DONE — audit PASS #9406.
- **Task 2b — Sprite Capacity Bump:** DONE — audit PASS #9298.
- **Task 2c — Sprite Evaluator Hardening:** DONE — audit PASS #9278.
- **Task 2a — Sprite Capacity Substrate Pre-Hardening:** DONE — audit PASS #9250.

**Current Checkpoint:**
- **Task 57 — Substrate DFF Optimization:** **DONE** — Path 5A PnR PASS per CyanPeak #9617. descCount=8 substrate restores GW2AR-LV18 hardware-readiness.
- **Task 54 — Sprite-Sprite Collision Detector:** **DONE** — CyanPeak audit PASS #9672. Commit `e556ff5`.
- **Task 56 — Multi-Layer SDRAM Fetch:** **IN-PROGRESS** — Checkpoint A (L0+L1 scheduler scaffold + arbiter wiring + compositor mux). BrightForge proof packet #9685 landed; awaiting CyanPeak audit.

**Previous lanes (all closed):**
- #9026 Zero-Footprint ROM Elimination — DONE (#9142)
- Task 52 Per-Sprite X/Y Flip — DONE (#9127)
- Beam-Driven Automation Hardening — DONE (`6345fcc`, audit PASS #8660)
- Color/Window Hardening — DONE (`0f5dc65`, audit PASS #8654)
- Sprite Phase 2 + 2-bis — DONE (`39a7242`, audit PASS #8638)
- Sprite Pattern Memory Foundation — DONE (`e86fe49`, audit PASS #8605)
- Mode0 Sprite Envelope Hardening — DONE (`d44a9c0`, audit PASS #8589)
- Mode0 Fetch Envelope Hardening — DONE

**Next expected work after Task 54:**
- **Task 56 — Multi-Layer SDRAM Fetch**
- **Task 58 — Substrate Redesign** (deferred — descCount recovery for 32+ sprites on larger device or redesigned substrate)
- **Task 56 — Multi-Layer SDRAM Fetch**
- Additional platform adapters (Atari ST, Amiga/Genesis/SNES)

For the strategic `Mode0` build order and adapter roadmap, use `MODE0_PLANNING.md` §3 (Strategic Roadmap). For the authoritative execution ledger, use `TASKS.md`.
