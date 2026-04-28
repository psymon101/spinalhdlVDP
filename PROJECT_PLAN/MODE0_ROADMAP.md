# MODE0_ROADMAP.md

**Updated:** 2026-04-28  
**Purpose:** Strategic capability roadmap for `Mode0` as the superset rendering substrate that future platform adapter modes will consume. This document is not the same as `TASKS.md`: it defines the long-range primitive build order needed to cover the target platform set.

---

## Why This Exists

`TASKS.md` is the authoritative execution/status ledger for the current repo state.

This roadmap answers a different question:

- what `Mode0` must eventually be able to do to support the target platforms
- which primitives should be built first because they unlock the most systems
- how to sequence those primitives so each task builds on earlier proven work

This document should drive future task creation and task refactoring, but it does not by itself mark any task `DONE`.

---

## Target Platform Set

The current intended platform set for roadmap purposes is:

- ZX Spectrum
- Commodore 64
- Amiga (OCS/ECS-class)
- Atari ST
- NES / Famicom
- SNES / Super Famicom
- Sega Genesis / Mega Drive

These are not all equally close. Some require only a narrow adapter over existing `Mode0`; others require substantial new primitives.

---

## Architectural Rule

`Mode0` is the generic machinery layer.

Platform adapters own:

- platform-specific registers
- command semantics
- IRQ/status semantics
- DMA script semantics
- compatibility quirks

`Mode0` owns:

- raster position and timing hooks
- layer fetch primitives
- sprite evaluation/fetch/composition primitives
- per-line and per-region state application
- palette, window, and color-composition primitives
- any generic beam-driven automation substrate

Example:

- an Amiga adapter may implement Copper-visible registers and Amiga-style wait/move semantics
- but it should do so by driving `Mode0` beam-compare, register-bus, layer-control, and fetch/compositor primitives

---

## Platform Pressure Matrix

| Platform | Strongest `Mode0` pressure |
|---|---|
| ZX Spectrum | shuffled fetch, bitmap+attribute pairing, indexed palette |
| Commodore 64 | raster IRQ, badline-style fetch timing, sprite collisions |
| Amiga | planar fetch, beam-synchronous display-list processor, stronger sprite priority |
| Atari ST | interleaved planar fetch, raster timing hooks |
| NES | 2-pass sprite evaluation, sprite-0 hit, tile+attribute fetch |
| SNES | 4-layer composition, windowing, color math, affine, HDMA-class beam automation |
| Genesis | per-line/per-column scroll tables, windowing, shadow/highlight, linked-list sprite behavior |

## Adapter-Readiness Closure Order

Before opening broad platform-adapter work, close the highest-leverage substrate
gaps in this order:

1. **Sprite flags / collision hooks** — needed for honest C64 / NES / Genesis claims
2. **Raw bitmap + attribute fetch** — needed for C64 bitmap use and ZX Spectrum-style adapters
3. **Sprite-capacity hardening** — needed once adapters outgrow the current bounded sprite counts
4. **V-scroll table primitive** — needed for Genesis-class scroll semantics

After those, the larger platform-enabling expansions remain:

- **DMA-style transfer primitive** for OAM/VRAM-class bulk movement
- **4-layer compositor expansion** for SNES-class adapter pressure
- **Blitter-class engine** for Amiga-class adapter pressure

---

## Proven Base

The current hardware-proven baseline covers the **entire R1–R6 substrate** plus R7 and R8:

- **R1** — Raster trigger unit (`beam compare` + raster IRQ/status)
- **R2** — Two-pass sprite evaluator with sprite-0 hit, overflow, collision hooks
- **R3** — Static fetch-slot scheduler + pre-announced arbiter grant
- **R4** — Tile+attribute fetch (linear, packed, planar, shuffled variants) + scroll-table primitive
- **R5** — Mode0 register bus + Copper-lite / HDMA automator
- **R6** — Window mask unit + color-math / shadow-highlight stage
- **R7** — Planar fetch engine + shuffled / bitmap+attribute fetch engine
- **R8** — Affine stepper (background + sprite paths)

Additional closed primitives:
- Raw bitmap fetch + SDRAM-backed bitmap (Tasks 44/44B)
- QSPI bidirectional host control (Tasks 38A–38C)
- Host driver library (Task 39)
- Sprite palette bank plumbing (Sprite Phase 2)

This means the roadmap **substrate construction phase is complete**. All substrate hardening is DONE:
- Fetch Envelope Hardening — DONE
- Sprite Envelope Hardening — DONE
- Sprite Pattern Memory Foundation — DONE
- Sprite Phase 2 + 2-bis — DONE
- Color/Window Hardening — DONE
- Beam-Driven Automation Hardening — DONE

The strategic focus has shifted to **platform adapter development**:
- Task 40 (C64 Adapter) — DONE
- Task 50 (ZX Spectrum Adapter) — IN-PROGRESS

---

## Cross-Cutting Constraints

These rules apply to every roadmap phase. They are not optional details.

### GT-022 Compliance

All new initialized `Mem` instances must use power-of-two depths. If the logical depth is not a power of two, pad it to the next power of two or split it into multiple power-of-two memories.

Reason:

- Gowin non-power-of-two inferred-memory failures are already reproduced in this repo
- future primitives such as secondary OAM, scroll tables, Copper/HDMA tables, palette banks, and small control memories are all exposed to the same risk

See:

- `kb/gowin/GOTCHAS.md`

### Interface Stability

The full `Mode0` register bus arrives later in the roadmap, but host-visible status/control semantics cannot remain ad hoc until then.

Working rule:

- any new primitive introduced in R1-R4 must expose control/status through a stable naming and ownership pattern that the later `Mode0` register bus can absorb without architectural breakage

### SDRAM-Latency Awareness

The current board substrate uses embedded SDR SDRAM, not fixed-latency on-chip VRAM.

Working rule:

- any new fetch primitive or scheduler must explicitly account for SDRAM latency, refresh, and arbitration overhead
- if a target platform’s original video cadence assumes fixed-latency VRAM, `Mode0` may need buffered prefetch, on-chip shadowing, or line-local staging instead of literal cycle-for-cycle fetch emulation

### Simulation Harness Growth

As primitives accumulate, isolated one-off sims stop scaling.

Working rule:

- every new roadmap phase should add to a shared line/frame validation harness where practical
- mixed-scene proofs must be planned as reusable regression assets, not one-off demonstrations

---

## Capability Build Order

The order below is chosen for cross-platform leverage, implementation risk, and reuse.

### Phase R1 — Raster Control Primitive ✅ CLOSED

*Status: Implemented, audited, and hardware-proven. Task doc closed.*

These are the cheapest missing hooks and unlock a large amount of platform behavior.

#### R1.1 Raster Trigger Unit

**Goal:** Generic compare against current beam position (`x`, `y`) with masked-match support, a clean action trigger, and direct host-visible raster status/IRQ plumbing.

**Needed by:**

- Commodore 64 raster IRQ
- Amiga Copper-style wait
- SNES HDMA timing
- Genesis H-int style events
- Atari ST border/raster tricks

**Delivers:**

- one or more programmable beam-compare comparators
- line compare and optional pixel-position compare
- edge-safe trigger pulses and sticky status
- host-visible raster match / IRQ surface

**Why first:** Almost every platform needs beam-synchronous control before it needs more exotic fetch formats.

**Build note:** This remains a cheap primitive and must not be gated on Copper/HDMA-style automation.

### Phase R2 — Sprite System Upgrade ✅ CLOSED

*Status: Implemented, audited, and hardware-proven. Task doc closed.*

The current sprite proof is not enough for most target systems.

#### R2.1 Two-Pass Sprite Evaluator

**Goal:** Replace the current minimal sprite path with a proper range-then-attribute evaluation pipeline using a small secondary line-local buffer.

**Needed by:**

- NES
- SNES
- Genesis
- Commodore 64
- Amiga

**Delivers:**

- per-line active-sprite scan
- bounded visible-sprite selection
- sprite-per-scanline limit enforcement
- secondary sprite buffer
- stronger overlap/priority behavior

#### R2.2 Sprite Flags and Hooks

**Goal:** Add the low-cost sprite-side hooks platforms depend on.

**Delivers:**

- sprite-0-hit style flag
- per-line sprite overflow/limit status
- sprite/background collision latches
- clearer priority-vs-layer hooks

**Why here:** These are direct by-products of the stronger evaluator and are cheaper to add once the evaluator is rebuilt.

### Phase R3 — Fetch Scheduling and Memory Discipline ✅ CLOSED

*Status: Implemented, audited, and hardware-proven. Task doc closed.*

The current memory path is proven, but the long-term substrate needs a more explicit fetch schedule.

#### R3.1 Static Fetch-Slot Scheduler

**Goal:** Move from a mostly reactive fetch model toward statically scheduled fetch windows within the scanline.

**Needed by:**

- Commodore 64 badline-style fetch behavior
- Amiga display data fetch windows
- NES fixed tile-fetch cadence
- future planar and multi-source fetchers

**Delivers:**

- explicit H-position fetch windows
- per-fetch-engine schedule slots
- stable line budget accounting
- explicit memory-bandwidth budget analysis for the scheduled scene classes

**Important:** This is not just a new primitive. It is a refactor of the currently proven Task 15 SDRAM path and must trigger re-proof of the current memory-backed tile-fetch baseline.

#### R3.2 Pre-Announced Arbiter Grant

**Goal:** Add BA-style lookahead so fetch clients can prepare before the exact memory-use slot.

**Needed by:**

- future bus-sharing
- latency-tolerant SDRAM arbitration
- more deterministic multi-fetch scenes

**Why now:** This is a substrate-quality improvement that makes later planar/shuffled/Copper work less fragile.

### Phase R4 — Tile/Attribute Generalization ✅ CLOSED

*Status: Implemented, audited, and hardware-proven. Task doc closed.*

The repo already has tile fetch. The next step is to make it flexible enough for more systems.

#### R4.1a Tile + Attribute Fetch Primitive

**Goal:** Support the common pattern where tile index and palette/attribute data are separate but synchronized.

**Needed by:**

- NES
- Commodore 64 text/bitmap hybrids
- SNES / Genesis tilemap layers
- ZX Spectrum as a degenerate attribute case

**Delivers:**

- tilemap fetch
- attribute fetch
- synchronized tile/palette/priority decode
- flexible 2/4/8bpp tile source handling
- multi-bank palette selection hooks
- per-pixel metadata flags carried into the compositor path where needed

#### R4.1b Packed-Attribute Variant

**Goal:** Extend the base tile+attribute fetch primitive to cover platforms whose attribute model is packed or region-encoded rather than one-entry-per-tile.

**Needed by:**

- NES
- ZX Spectrum-style degenerate attribute cases

**Delivers:**

- packed/region-based attribute decode support
- generalized attribute extraction rules layered on top of R4.1a

#### R4.2 Scroll Primitive Split

**Goal:** Stop treating all scroll behavior as one linestate field.

**Delivers:**

- per-line scroll via linestate
- separate small dual-port RAM/table primitive for per-column or per-band scroll
- explicit distinction between line state and scroll lookup state

**Needed by:**

- Genesis VSRAM/H-scroll patterns
- SNES offset-per-tile style pressure
- richer parallax systems

**Architectural rule:** Do not widen `LinestateStore` to fake per-column scroll.

### Phase R5 — Beam-Driven Automation ✅ CLOSED

*Status: Implemented, audited, and hardware-proven. Task doc closed.*

This is where Amiga Copper and SNES HDMA-class behavior enters the design.

#### R5.1 Mode0 Register Bus

**Goal:** Define a clean internal register/control bus over the Mode0 primitives.

**Why required:** A Copper/HDMA engine is only cheap if the targets it can drive are uniform. Right now the control surface is still relatively ad hoc.

**Planning note:** Bus semantics should be sketched before or during R1 so earlier primitives do not accumulate incompatible status/control surfaces.

#### R5.2 Copper-lite / HDMA Automator

**Goal:** A beam-synchronous micro-engine that can:

- wait for beam position
- write selected Mode0 registers
- optionally load values from a small table
- optionally drive palette-bank or palette-entry reload actions

**Needed by:**

- Amiga Copper-style effects
- SNES HDMA-style per-line updates
- Genesis H-int assisted register changes

**Constraint:** This should be introduced only after the register bus exists.

### Phase R6 — Post-Compositor Primitives ✅ CLOSED

*Status: Implemented, audited, and hardware-proven. Task doc closed.*

These are relatively small compared to fetch engines and unlock SNES/Genesis-style output behavior.

#### R6.1 Window Mask Unit

**Goal:** Per-layer or per-effect window enable/mask using rectangular regions and simple boolean combine.

**Needed by:**

- SNES
- Genesis

#### R6.2 Color Math / Shadow-Highlight

**Goal:** Post-compositor color operations that are orthogonal to fetch.

**Delivers:**

- add/sub blend options
- half/intensity-style modes
- shadow/highlight-compatible output muxing
- compositor consumption of per-pixel metadata flags such as math-enable or forced-priority

**Build note:** Treat this as a post-compositor stage, not a fetch concern.

### Phase R7 — Alternate Fetch Formats

These are narrower in platform coverage but essential for the computer-class targets.

#### R7.1 Planar Fetch Engine

**Goal:** Support 1-6 bitplane style fetch and shift.

**Needed by:**

- Amiga
- Atari ST

**Delivers:**

- per-plane pointer/modulo model
- bitplane shift/recombine path
- palette-index output into existing compositor

#### R7.2 Shuffled / Bitmap+Attribute Fetch

**Goal:** Support layouts where pixels and attributes live in separate, non-linear regions.

**Needed by:**

- ZX Spectrum
- some bitmap/text hybrid adapters

### Phase R8 — Affine and Advanced Transform

#### R8.1 Affine Stepper

**Goal:** Matrix-stepped screen-to-texture address generation.

**Needed by:**

- SNES Mode 7 class behavior
- any later rotated/scaled background adapter

**Why late:** Only one target platform requires it strongly, and it builds cleanly on the already-proven fetch/compositor substrate.

---

## Logical Progression Tasks

This is the condensed task progression that future task creation should follow.

1. ✅ Add raster trigger unit (`beam compare` + raster IRQ/status).
2. ✅ Rebuild sprite path into a 2-pass evaluator with sprite-0 / overflow / collision / per-line limit hooks.
3. ✅ Re-evaluate whether current reactive fetch remains acceptable under the stronger sprite/fetch load.
4. ✅ Add static fetch-slot scheduler.
5. ✅ Add pre-announced arbiter grant / lookahead.
6. ✅ Generalize tile fetch into tile+attribute fetch with 2/4/8bpp support and palette-bank / metadata hooks.
7. ✅ Add packed-attribute variant support.
8. ✅ Split scroll into per-line linestate scroll and separate scroll-table primitive.
9. ✅ Define a uniform internal Mode0 register bus.
10. ✅ Add Copper-lite / HDMA automator on top of the register bus.
11. ✅ Add window mask unit.
12. ✅ Add color-math / shadow-highlight stage.
13. ✅ Add planar fetch engine.
14. ✅ Add shuffled / bitmap+attribute fetch engine.
15. ✅ Add affine stepper.
16. ✅ Run mixed-scene proof that combines tile, sprite, raster, window, alternate fetch paths, and beam-driven state changes.
17. ✅ Run long soak and maximum-load validation.

### Dependency Notes

- R5.2 depends strongly on R5.1.
- R6.1 and R6.2 are separable once the compositor contract is stable.
- R3 and R4 are tightly coupled: fetch scheduling and tile/attribute fetch generalization should be specified together even if implemented as separate tasks.
- R7.1 and R7.2 are independent of each other once the scheduler/compositor substrate is stable.

---

## Adapter Readiness by Platform

These are not promises of cycle-accurate emulation. They are readiness checkpoints for beginning a serious adapter.

| Platform | Minimum roadmap milestone before adapter work is sensible |
|---|---|
| Commodore 64 | through R3 |
| NES / Famicom | through R4 |
| TMS9918-family (ColecoVision / SG-1000 / MSX1-class) | through R4 |
| Master System / Game Gear | through R4 |
| MSX2 | through R5 |
| PC Engine / TurboGrafx-16 | through R5 |
| Genesis / Mega Drive | through R6, with scroll-table primitive complete |
| SNES / Super Famicom | through R6, plus R8 for Mode 7 |
| Amiga | through R7, with R5 especially important |
| Atari ST | through R7 |
| Neo Geo | through R7 |
| ZX Spectrum | through R7.2 |

---

## What Not To Do

- Do not turn `Mode0` into an Amiga renderer, NES renderer, or Genesis renderer.
- Do not widen `LinestateStore` to absorb unrelated primitives like per-column scroll or Copper scripts.
- Do not gate a cheap primitive (for example raster IRQ) behind a later complex primitive (for example Copper-lite).
- Do not start adapter implementation before the required `Mode0` primitive is hardware-proven.
- Do not assume a feature is needed just because one platform has it. Build the highest-reuse primitives first.
- Do not add new initialized inferred memories without applying the GT-022 power-of-two rule.

---

## Immediate Planning Recommendation

Based on the current proven repo state, the next strategic targets are:

1. **Task 50 — ZX Spectrum Adapter** (IN-PROGRESS) — first serious platform adapter after C64 smoke-test
2. **Platform adapter development** — Amiga, Genesis/MD, SNES adapters over the proven Mode0 substrate

The R1–R6 substrate construction phase is complete. All substrate hardening (Fetch, Sprite, Color/Window, Beam) is DONE. Adapter lanes are now open.

---

## Mixed-Scene Proof Expectation

The final mixed-scene proof should not be an abstract “everything at once” test. It should be a concrete scene family that exercises:

- multi-layer tile composition
- stronger sprite evaluation and per-line sprite limits
- raster-triggered state changes
- window or color-math effects
- at least one alternate fetch format
- a beam-driven control update path

The exact proof scenario can evolve, but it must map to real target-platform behavior rather than synthetic stress alone.

---

## First Execution Target

The substrate construction phases (R1–R8) are complete. Current execution targets:

- **Active lane:** `PROJECT_PLAN/artifacts/TASK_50_ZX_SPECTRUM_ADAPTER.md` (IN-PROGRESS, BrightForge)
- **Next expected lanes:** Additional platform adapters (Amiga, Genesis/MD, SNES)
- **Adapter work:** Open now — all substrate hardening complete
