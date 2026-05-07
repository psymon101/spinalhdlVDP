# MODE0_PLANNING.md

**Updated:** 2026-04-26
**Purpose:** Consolidated planning document for the `Mode0` rendering substrate. This replaces the previous split of `MODE0_MAX_CAPABILITIES.md`, `MODE0_STOPLINES.md`, `MODE0_ROADMAP.md`, `MODE0_COVERAGE_MATRIX.md`, and `MODE0_HARDENING_BACKLOG.md`.

---

## Table of Contents

1. [1. Capability Envelope](#1-capability-envelope) — What Mode0 should eventually cover — the intended maximum useful envelope for shared primitives.
2. [2. Resource Stop-Lines](#2-resource-stop-lines) — Quantified Tang Nano 20K budget limits and growth gating rules.
3. [3. Strategic Roadmap](#3-strategic-roadmap) — Long-range primitive build order needed to cover the target platform set.
4. [4. Current Coverage](#4-current-coverage) — Mapping of intended envelope against current implementation state.
5. [5. Prioritized Backlog](#5-prioritized-backlog) — Prioritized work order for closing the most important remaining shared gaps.

---

## 1. Capability Envelope

## Core Rule

`Mode0` should provide the strongest honest **shared primitive** that multiple target adapters can consume.

Adapters should then:

- translate platform-specific register semantics
- constrain the primitive to platform-specific limits
- choose platform-specific presentation rules
- document omitted quirks honestly

`Mode0` should **not**:

- grow a C64-only render engine
- grow an Amiga-only sprite path
- grow a SNES-only timing core
- chase 3D-console or framebuffer-GPU ambitions

---

## Platform Classes

### Real Target Platforms

These are the actual platform families the project is trying to support honestly:

- ZX Spectrum
- Commodore 64
- Amiga (OCS/ECS-class)
- Atari ST
- NES / Famicom
- SNES / Super Famicom
- Sega Genesis / Mega Drive
- TMS9918-family / MSX1-class
- Master System / Game Gear
- MSX2
- PC Engine / TurboGrafx-16
- Neo Geo

### Ceiling Reference Platforms

These are **not** promised adapters. They are pressure markers used to avoid under-designing a shared primitive.

Recommended ceiling references by category:

- sprite/composition pressure: **Neo Geo**
- beam-driven automation: **Amiga**, **SNES HDMA**, **Genesis H-int**
- planar fetch: **Amiga**, **Atari ST**
- bitmap+attribute / clash behavior: **ZX Spectrum**, **C64 bitmap**
- layered tile/compositor pressure: **SNES**, **Genesis**
- affine ceiling: **SNES Mode 7**
- transfer/blitter pressure: **Amiga**, **ST/console bulk update use-cases**

### Explicit Non-Targets

These are useful as "stop fantasy here" markers:

- Dreamcast-class graphics pipeline
- PlayStation / Saturn-class multi-processor graphics systems
- full-framebuffer-centric modern GPU design
- arbitrary 3D rasterization or shader-like graphics

Those may be interesting thought experiments, but they are outside the intended `Mode0` ceiling on the Tang Nano 20K.

---

## Capability Categories

Each category below defines:

- why it belongs in `Mode0`
- the maximum useful envelope
- the main target / ceiling references
- what should remain adapter-local

---

## 1. Control Bus / Register Surface

**Why this belongs in `Mode0`:**

Every adapter needs a stable way to drive common rendering machinery. A shared control bus is cheaper and cleaner than per-platform ad hoc wiring.

**Maximum useful envelope:**

- stable internal register/control bus for all shared primitives
- host-driven and beam-driven writes converge on the same control surface
- shared status/event reporting for raster, sprite, transfer, and buffer state
- enough address/control space to grow generic primitives without renumbering chaos

**Target / ceiling references:**

- MSX2 host control pressure
- Amiga Copper
- SNES HDMA
- Genesis H-int / line-driven updates

**Adapter-local only:**

- exact historical register names
- exact IRQ bit numbering and status register semantics
- legacy mirror/alias behavior

---

## 2. Beam-Driven Automation

**Why this belongs in `Mode0`:**

Many platforms need scanline- or region-synchronous state changes, but the hardware mechanism is shared even when the platform-visible language differs.

**Maximum useful envelope:**

- beam compare hooks
- programmable line/pixel trigger points
- bounded script/table-driven register updates
- support for wait/move style automation
- support for line-based table reload/update patterns

**Target / ceiling references:**

- C64 raster IRQ
- Amiga Copper
- SNES HDMA
- Genesis H-int assisted updates
- Atari ST border/raster tricks

**Adapter-local only:**

- exact Copper instruction format
- exact HDMA channel register map
- platform-specific trigger status naming

---

## 3. Sprite System

**Why this belongs in `Mode0`:**

Sprites are cross-platform pressure. The same general machinery can serve weak and strong adapters if the primitive is designed as a superset.

**Maximum useful envelope:**

- descriptor-based sprite engine
- enough descriptor capacity for serious multi-platform use
- bounded visible-per-line selection
- priority and transparency control
- metadata / collision hooks
- bank/palette selection hooks
- room for richer size/flag fields if justified

**Target / ceiling references:**

- C64 / NES for raster-visible sprite behavior
- Genesis / SNES for stronger overlap/priority pressure
- Amiga for richer sprite usage
- Neo Geo for sprite-centric composition pressure

**Adapter-local only:**

- exact per-platform sprite limits
- exact overflow rules
- exact sprite-0 or collision register formatting
- exact object attribute layouts

**Design principle:**

- weak adapters clamp a rich engine downward
- strong adapters use more of the same engine's range
- do not build multiple hardware sprite engines for different platforms

---

## 4. Fetch System

**Why this belongs in `Mode0`:**

Fetch layout is a substrate issue, not an adapter issue. Adapters should choose formats and policies; `Mode0` should own the engines.

**Maximum useful envelope:**

- tile + attribute fetch
- bitmap + attribute fetch
- shuffled / non-linear fetch
- planar fetch
- optional affine-addressed fetch path
- scheduler-aware staging and buffering

**Target / ceiling references:**

- ZX Spectrum for shuffled bitmap+attribute
- C64 for bitmap use and mixed tile/bitmap character
- NES / Genesis / SNES for tile+attribute layers
- Amiga / Atari ST for planar fetch
- SNES Mode 7 for affine pressure

**Adapter-local only:**

- exact memory map seen by the target platform
- exact register naming for base pointers and mode bits
- exact layout quirks that are not reusable elsewhere

---

## 5. Scheduler / Memory Arbitration

**Why this belongs in `Mode0`:**

Any serious multi-fetch video system on Tang Nano 20K lives or dies by SDRAM scheduling and staging discipline.

**Maximum useful envelope:**

- explicit slot/budget-based fetch scheduling
- bounded support for multiple concurrent clients
- predictable per-line service model
- line-local or small working-set staging instead of whole-frame buffering

**Target / ceiling references:**

- C64 badline-style pressure
- Genesis multi-layer fetch timing
- Amiga display-data windows
- SNES mixed-layer fetch pressure

**Adapter-local only:**

- exact historical bus-cycle stories when they are only compatibility theater
- cycle-exact external bus contention unless it truly unlocks broad value

---

## 6. Compositor / Layer System

**Why this belongs in `Mode0`:**

Layer combination is a shared output problem. Platform adapters differ in how they use layers, not in the need for a compositor.

**Maximum useful envelope:**

- multiple background/layer inputs
- sprite integration
- priority and transparency control
- metadata propagation for later stages
- room for windows and post-compositor effects

**Target / ceiling references:**

- SNES for 4-layer and post-compositor pressure
- Genesis for window/priority layering
- Neo Geo for sprite-heavy composition
- Amiga for bitplane/sprite priority interplay

**Adapter-local only:**

- exact PPU/VIC/Denise register semantics
- platform-specific mode names

---

## 7. Palette / Color Pipeline

**Why this belongs in `Mode0`:**

Palette storage, lookup, banking, and post-compositor color behavior are generic mechanisms even though each adapter uses different historical values and limits.

**Maximum useful envelope:**

- banked palette lookup
- per-layer or per-pixel palette selection hooks
- support for fixed and indexed palette models
- room for post-compositor color operations
- room for shadow/highlight or equivalent generic color staging

**Target / ceiling references:**

- ZX Spectrum / C64 for constrained indexed-color models
- Genesis for shadow/highlight pressure
- SNES for color math pressure
- Neo Geo for rich sprite palette use

**Adapter-local only:**

- exact measured palette values
- exact bright/intensity/flash semantics
- exact analog-look compromises or lookup tables

---

## 8. Window / Mask / Post-Compositor Effects

**Why this belongs in `Mode0`:**

These are shared effects that several higher-end adapters need, and they are cleaner when expressed once as generic pipeline stages.

**Maximum useful envelope:**

- rectangular and simple combined window masks
- per-layer enable/mask influence
- generic post-compositor blend / add / subtract / highlight hooks

**Target / ceiling references:**

- SNES
- Genesis

**Adapter-local only:**

- exact register maps and mode names
- platform-specific rules for when masks affect object/background classes

---

## 9. Transfer Engines

**Why this belongs in `Mode0`:**

Bulk movement/fill/blit operations help many platforms even when they present very different host-visible control semantics.

**Maximum useful envelope:**

- bounded fill/copy DMA-style operations
- rectangular blitter-style transfers
- useful local staging or source-store support
- status/completion/event signaling

**Target / ceiling references:**

- Amiga blitter pressure
- Atari ST and console bulk memory update pressure
- sprite/tilemap/OAM/VRAM movement for many adapters

**Adapter-local only:**

- exact command language
- exact DMA register maps
- exact CPU-visible script formats

---

## 10. Event / Status Model

**Why this belongs in `Mode0`:**

Sticky bits, busy flags, done events, and compare hits are generic hardware facts. Adapters can remap them.

**Maximum useful envelope:**

- raster event status
- sprite overflow / collision hooks
- transfer done/busy signals
- host-visible enable/mask/latch policy for generic events

**Target / ceiling references:**

- C64 / NES collision and raster pressure
- transfer-engine completion semantics
- beam automation coordination

**Adapter-local only:**

- exact status-bit meaning for a given historical machine
- exact acknowledgement semantics if not broadly reusable

---

## What This Means In Practice

When planning a new `Mode0` feature, ask:

1. which capability category does this belong to?
2. which real target platforms need it?
3. which ceiling references pressure its maximum useful range?
4. can weak adapters clamp it instead of forcing a second engine?
5. does `MODE0_PLANNING.md` §2 say the cost is still sane on Tang Nano 20K?

If the answer to 4 is "no," the feature may be adapter-local rather than substrate work.

If the answer to 5 is "no," the feature may be outside the board's practical envelope even if it is architecturally attractive.

---

## Explicit Non-Goals

`Mode0` is not trying to become:

- a framebuffer-centric GPU
- a 3D rasterizer
- a Dreamcast-class graphics subsystem
- a per-platform hardware zoo of duplicated engines
- a perfect cycle-accurate clone framework for every target machine

The goal is a **strong, general, honest 2D substrate** that can be specialized downward by adapters.

---

## 2. Resource Stop-Lines

## Authority

Use this file when evaluating any proposed new `Mode0` primitive, major expansion, or architectural widening.

If this file and `TASKS.md` disagree on execution order, `TASKS.md` wins.

If this file and `PLATFORM.md` disagree on board facts, `PLATFORM.md` wins.

If this file and actual post-P&R reports disagree on current fit, the **post-P&R reports win** and this file must be updated.

---

## Tang Nano 20K Board Budget

Reference board: **Sipeed Tang Nano 20K**

Nominal hardware budget:

- LUT4: `20,736`
- FF: `15,552`
- BSRAM blocks: `46`
- BSRAM bits: `828 Kbit`
- DSP: `48` nominal board spec; use the project tool's effective reported budget for actual stop-line enforcement
- SDRAM: `64 Mbit` on-board SDR SDRAM
- PLL: `2`

Primary source:

- [Tang Nano 20K board spec](https://wiki.sipeed.com/hardware/en/tang/tang-nano-20k/nano-20k.html)

---

## Current Local Baseline

The current project baseline must always be taken from the latest successful local build, not from memory.

At the time this file was written, the local `spinalhdlVDP` build (post-Beam Hardening, commit `6345fcc`) reported:

- LUT/ALU/ROM16: `9875` total (`9062 LUT`, `813 ALU`, `0 ROM16`)
- FF: `6274 / 15552` (`40%`)
- BSRAM: `17 / 46` (`37%`)
- DSP: `18 / 24` (`75%`) as reported by the current Gowin flow
- active clocks include `25.2 MHz` pixel and `64.8 MHz` memory-domain timing, both currently meeting timing with 0 setup/hold violations

These baseline numbers are a **moving reference point**. They must be refreshed when the project changes materially.

---

## Stop-Line Zones

### Green Zone

A proposed feature is in the green zone when, after integrating it, the design is expected to remain below:

- LUT: `65%`
- FF: `65%`
- BSRAM: `50%`
- DSP: `70%`

and:

- all required clocks still meet timing with non-trivial slack
- SDRAM arbitration remains easy to explain and verify
- no new fragile clock-domain crossings are introduced without strong reason

Green-zone features are generally acceptable if they provide real cross-platform value.

### Yellow Zone

A proposed feature is in the yellow zone when the integrated design is expected to land in any of:

- LUT: `65% .. 80%`
- FF: `65% .. 80%`
- BSRAM: `50% .. 70%`
- DSP: `70% .. 85%`

or:

- timing margin is clearly shrinking
- SDRAM arbitration is materially more complex
- buffering strategy becomes harder to reason about

Yellow-zone features require explicit justification:

- which platforms benefit
- why this belongs in `Mode0`
- why adapter-side policy cannot achieve the goal more cheaply
- what the escape plan is if timing or memory pressure worsens

Yellow-zone features must not be approved as "nice to have."

### Red Zone

A proposed feature is in the red zone when the integrated design is expected to hit any of:

- LUT: `> 80%`
- FF: `> 80%`
- BSRAM: `> 70%`
- DSP: `> 85%`

or:

- timing only barely passes
- timing closure becomes fragile or tool-sensitive
- SDRAM behavior becomes difficult to prove per-line
- the feature demands large buffering or broad architectural special cases

Red-zone features should be:

- rejected
- deferred
- split into smaller parts
- or re-expressed as adapter policy instead of substrate growth

unless they unlock major cross-platform value and no cheaper honest alternative exists.

---

## Video-System Hard Stop Rules

These rules are stricter than raw LUT percentages because they directly affect viability on this board.

### 1. Full Framebuffer Rule

Treat a new feature as **high-risk by default** if it requires:

- a full framebuffer in BRAM, or
- a full framebuffer in SDRAM under active HDMI scanout plus heavy concurrent fetch traffic

Reason:

- framebuffer-heavy designs consume memory rapidly
- scanout bandwidth and synchronization complexity become dominant
- comparable Tang Nano 20K projects have explicitly avoided full-frame buffering for this reason

### 2. SDRAM Client Rule

Treat a new feature as **not ready for approval** if it adds a new SDRAM client and the proposer cannot clearly state:

- when it requests service
- worst-case bandwidth demand per line/frame
- arbitration priority relative to existing clients
- failure behavior when bandwidth is insufficient

If the per-line budget cannot be explained, the feature is not ready.

### 3. Clock-Domain Rule

Treat a new feature as **yellow or red by default** if it introduces:

- a new clock domain
- complex CDC
- additional phase relationships that are not already standard in the repo

Such changes require strong cross-platform value.

### 4. Genericity Rule

If a proposed hardware feature mainly helps one platform and materially increases:

- memory pressure
- arbitration complexity
- timing pressure
- buffering complexity

then it should be assumed **adapter-local or out of scope** unless there is a strong argument that the primitive is broadly reusable.

---

## Required Evidence For Any New Mode0 Primitive

No substantial `Mode0` expansion should be approved without a short evidence block that includes:

- estimated LUT delta
- estimated FF delta
- estimated BSRAM delta
- estimated DSP delta
- estimated SDRAM bandwidth / client delta
- timing-domain impact
- line-buffer / cache impact
- platforms helped
- reason this belongs in `Mode0` instead of an adapter

If exact post-P&R deltas are not available yet, the proposal must still provide bounded estimates and state uncertainty honestly.

No numbers means no approval.

---

## Decision Rule

A proposed feature should normally be approved into `Mode0` only if **all** of the following are true:

1. it has real cross-platform leverage
2. it fits within the current stop-line zone policy
3. its memory and timing impact are stated explicitly
4. it is more honest as a shared primitive than as adapter-local policy
5. its failure mode is understood if the board budget proves tighter than expected

If those conditions are not met, default to:

- defer
- narrow scope
- move to adapter layer
- or reject

---

## Comparison Guidance

Use these practical heuristics during planning:

- prefer line buffers over framebuffers
- prefer one stronger shared primitive over multiple weak platform-specific engines
- prefer beam-synchronous control over duplicating timing engines per platform
- prefer adapter-side clamping of rich primitives over hardware duplication
- distrust any proposal whose main answer is "we can probably fit it"

---

## What Not To Do

- Do not approve a `Mode0` feature on the basis of platform desirability alone.
- Do not use whole-board theoretical limits without checking current project utilization.
- Do not assume SDRAM capacity implies SDRAM bandwidth safety.
- Do not let "just for fun" experiments silently redefine the substrate scope.
- Do not let a red-zone feature enter the mainline without explicit project-level re-approval.

---

## 3. Strategic Roadmap

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

**Completed substrate closures:**

1. ✅ **Raw bitmap + attribute fetch** — DONE (Tasks 44/44B)
2. ✅ **Sprite-capacity hardening** — DONE (Tasks 2a/2c/2b: V=32/D=64)
3. ✅ **Sprite pattern address width** — DONE (Task 53: PatIdxWidth 4→6, 64 unique tiles)

**Remaining shared substrate gaps (execution-ready):**

4. **Sprite masking + tile-fetch budget counter** (Task 55) — needed for Genesis/SNES pixel-perfect edge cases
5. **Sprite-sprite collision detector** (Task 54) — needed for honest C64 collision claims
6. **Multi-layer SDRAM fetch** (Task 56) — needed for Amiga dual-playfield, Genesis/SNES rich backgrounds
7. **V-scroll table primitive** — needed for Genesis-class per-column scroll semantics

**Larger platform-enabling expansions (roadmap):**

- **DMA-style transfer primitive** for OAM/VRAM-class bulk movement
- **4-layer compositor expansion** for SNES-class adapter pressure
- **Blitter-class engine** (Task 49) for Amiga-class adapter pressure
- **Option B pattern RAM** (256 tiles) for dense SNES/Neo Geo scenes

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
- Sprite Capacity Expansion (2a/2c/2b) — DONE
- Planar Fetch Hardening (Task 3) — DONE
- Sprite Pattern Address Width Expansion (Task 53) — DONE

The strategic focus has shifted to **substrate gap closure + platform adapter development**:
- Task 40 (C64 Adapter) — DONE
- Task 50 (ZX Spectrum Adapter) — DONE
- Task 51 (MODE_SELECT Runtime Adapter Selection) — DONE

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

- **Active lane:** `PROJECT_PLAN/archive/tasks/TASK_50_ZX_SPECTRUM_ADAPTER.md` (IN-PROGRESS, BrightForge)
- **Next expected lanes:** Additional platform adapters (Amiga, Genesis/MD, SNES)
- **Adapter work:** Open now — all substrate hardening complete

---

## 4. Current Coverage

## Reading Rule

Use this file during:

- PM reassessment
- future task creation
- adapter planning
- review of whether a proposed gap belongs in `Mode0` or in an adapter

If this file and `TASKS.md` disagree on whether a specific task is `DONE`, `TASKS.md` wins.

If this file and actual code behavior disagree, the code and proof artifacts win and this file must be corrected.

---

## Status Labels

- `Strong` — shared primitive exists in a form that is already broadly reusable by multiple adapters
- `Usable` — primitive exists and is real, but likely needs bounded hardening or expansion for higher-pressure adapters
- `Partial` — meaningful groundwork exists, but the capability envelope is clearly not broad enough yet
- `Missing` — no honest shared primitive exists yet for this category
- `Deferred` — intentionally outside the current mainline despite being recognized as useful

---

## Coverage Matrix

| Capability Category | Current Status | Repo Evidence | Main Platforms Helped Now | Remaining Shared Gaps |
|---|---|---|---|---|
| Control bus / register surface | `Usable` | R5 host interface + Copper path closed; Task 25 definition artifact passed | C64, NES, MSX2, Amiga/SNES/Genesis control modeling groundwork | future parallel-bus implementation still deferred; status/readback surface may still need hardening for richer adapters |
| Beam-driven automation | `Usable` | R1 raster trigger DONE; R5 host interface + Copper DONE; Task 33 closed | C64 raster splits, basic Amiga/SNES/Genesis line-driven behavior | stronger bounded table/channel model may still be needed for richer HDMA/Copper pressure |
| Sprite system | `Usable` | R2 two-pass sprite evaluator DONE; sprite flags/collision hooks DONE; sprite-capacity hardening DONE; C64 adapter proof DONE | C64, NES-class, Genesis/SNES groundwork, some Amiga/Neo Geo groundwork | richer sprite capability envelope still likely needed for top-end Amiga/Neo Geo pressure; exact stronger sizing/priority features not yet fully generalized |
| Fetch system: tile + attribute | `Strong` | R4 tile+attribute DONE; multi-slot scheduler coupling DONE; packed-attribute decode DONE | NES, Genesis, SNES, TMS-family, much of C64 text-like work | mostly mature; later adapter-specific layout semantics still belong in adapters |
| Fetch system: bitmap + attribute | `Strong` | Task 44 and 44b DONE | ZX Spectrum-style, C64 bitmap-style, other bitmap-first adapters | adapter-local semantics like clash rules / exact memory maps still need adapter work, not substrate rescue |
| Fetch system: planar | `Usable` | R4.1b planar fetch path marked delivered in baseline/task structure | Amiga, Atari ST groundwork | likely needs stronger hardening / scale-up before high-pressure Amiga/ST adapter claims |
| Fetch system: shuffled / non-linear | `Usable` | R4.1d shuffled fetch path delivered in baseline/task structure; Task 44 supports bitmap+attribute family pressure | ZX Spectrum-class, some attribute-layout variants | likely needs adapter-side exact layout/quirk modeling; may need hardening for stronger Spectrum/ST style proofs |
| Affine / transformed fetch | `Usable` | Task 19 and Scenario 37 DONE; affine sim/hardware proof exists | SNES Mode 7-class groundwork, general affine background support | deeper affine tuning intentionally deferred; not yet a claim of full high-end affine envelope |
| Scheduler / memory arbitration | `Usable` | R3 scheduler DONE; R4.1 coupling DONE; SDRAM-backed fetch paths proven; long soak/stress scenes done | all memory-backed adapters | still the main practical board-limit risk area; every new client/feature must clear stop-line and per-line budget scrutiny |
| Compositor / layer system | `Strong` | multi-layer composition closed; Task 48 four-layer compositor DONE; mixed-scene integration DONE | Genesis, SNES, C64 mixed-layer, broader adapter groundwork | window/math interactions remain a separate stage for higher-end behavior |
| Palette / color pipeline | `Usable` | palette path DONE; palette animation scenario DONE; color math/window task family marked DONE | C64/ZX constrained palette models, SNES/Genesis groundwork | high-end post-compositor richness still likely narrower than full SNES/Genesis pressure envelope |
| Window / mask / post-compositor effects | `Usable` | Phase/task structure records color math/window effects DONE | SNES/Genesis groundwork | may still need richer generalization or tightening before claiming broad adapter completeness |
| Transfer engines | `Strong` | Task 47 DMA DONE; Task 49 blitter DONE | Amiga, Genesis, SNES, Neo Geo, general asset/OAM/tilemap movement support | adapter-visible command semantics remain adapter-local; substrate primitive now exists |
| Event / status model | `Usable` | raster/status IRQ plumbing exists; sprite hooks exist; transfer done/busy exists | C64/NES/Genesis groundwork, general host visibility | exact historical status surfaces still adapter-local; broader shared event discipline may still need cleanup as adapters grow |
| Presentation nuance support | `Partial` | first C64 adapter proof exists; `ADAPTER_NUANCES.md` now documents per-platform expectations | C64 explicitly; planning reference for all targets | most future adapters still need their own proof lanes to show aspect/border/clash/window behavior on top of current substrate |

---

## Platform-Oriented Summary

### Platforms already supported by broadly reusable substrate

These now have enough shared `Mode0` machinery that future work should mostly be adapter semantics plus bounded hardening:

- Commodore 64
- NES / Famicom
- TMS9918-family / MSX1-class
- Master System / Game Gear
- ZX Spectrum

### Platforms with real substrate groundwork but still meaningful shared gaps

These should not need a brand-new engine, but they likely still need shared hardening or richer envelopes before a strong adapter claim:

- Genesis / Mega Drive
- SNES / Super Famicom
- Amiga (OCS/ECS-class)
- Atari ST
- Neo Geo
- MSX2
- PC Engine / TurboGrafx-16

---

## Highest-Leverage Remaining Shared Questions

The substrate construction phase is complete. The next important shared planning questions are:

1. Is the sprite engine behaviorally complete enough for Genesis/SNES pixel-perfect claims (masking, budget counter, collision)?
2. Can the scheduler/memory model absorb multi-layer SDRAM fetch without violating stop-lines?
3. Is the planar fetch strong enough for Amiga/Atari ST adapter work, or does it need 320-pixel confinement / clipping?
4. Does the current compositor need 4-layer expansion before SNES-class adapters become realistic?

These are gap-closure questions, not primitive-invention questions.

---

## Current PM Reading

Based on current repo state, the most important conclusion is:

- the project is no longer at "invent missing primitives from scratch" stage for most categories
- it is now at "measure the strength of the current shared primitives against higher-pressure adapters" stage

That means future work should prefer:

- shared hardening / envelope-expansion tasks
- coverage-driven planning
- adapter lanes only when the shared primitive really looks strong enough

rather than creating new platform-specific engines prematurely.

---

## What Not To Do

- Do not read `Usable` as "no more substrate work ever needed."
- Do not read `Partial` as license to build a platform-specific engine first.
- Do not open a hard adapter lane just because one primitive in its pressure set exists.
- Do not treat this matrix as static; update it when a new proof, hardening task, or failure changes the real envelope.

---

## 5. Prioritized Backlog

## Decision Rule

Use this backlog when the project is at PM reassessment with no active lane.

Default rule:

- if a future adapter would mainly be blocked by a shared `Mode0` weakness, prefer the relevant hardening item here before opening that adapter
- if the shared primitive is already `Strong` and the remaining work is mainly semantic/presentation, an adapter lane is reasonable

This file is planning guidance, not status authority. `TASKS.md` remains the live execution ledger.

---

## Priority Order

### Priority A — Fetch Envelope Hardening ✅ DONE

*Status: Implemented, audited, and closed. See `TASKS_HISTORY.md` for closed-lane detail.*

**Why first:**

- fetch strength is central to several high-value future adapters
- the coverage matrix marks planar and shuffled fetch as only `Usable`, not `Strong`
- if this envelope is weak, adapters will start demanding platform-specific paths

**Main pressure served:**

- Amiga
- Atari ST
- ZX Spectrum
- stronger bitmap/C64 cases

**Outcome:**

- Planar, shuffled, and bitmap+attribute fetch paths were strengthened and hardware-proven
- Tasks 44/44B (raw bitmap + SDRAM fetch) completed and closed
- Gap analysis confirmed substrate is adapter-ready for fetch-dependent platforms

---

### Priority B — Sprite Envelope Hardening ✅ DONE

*Status: Implemented, audited, and closed. See `TASKS.md` live-lane history. Followed by Sprite Phase 2 + 2-bis (pattern memory foundation + bppSel/priority hardening, also DONE).*

**Why second:**

- the sprite system is already real and useful, but the coverage matrix still marks it only `Usable`
- many future adapters pressure this category harder than the first C64 proof did

**Main pressure served:**

- Amiga
- Genesis
- Neo Geo
- stronger NES/C64/console edge cases

**Outcome:**

- Sprite descriptor envelope expanded (bppSel, priority levels, palette bank plumbing)
- Sprite pattern memory foundation rebuilt with BSRAM-backed storage
- Hardware proof delivered (Scenario 50, commit `39a7242`)
- Stop-line confirmed: envelope fits within Tang Nano 20K limits

---

### Priority C — Color / Window Envelope Hardening ✅ DONE

*Status: Implemented, audited, and closed. BrightForge owner. All six sub-features proven (CW-1 through CW-6). Commit `0f5dc65`.*

**Why third:**

- the current repo has color-math/window work closed enough for groundwork, but the coverage matrix still calls it `Usable`
- this matters most once higher-layer and richer-adapter work becomes serious

**Main pressure served:**

- SNES
- Genesis

**Outcome:**

- CW-1: Runtime-writable palette RAM ✅
- CW-2: Sprite palette bank consumer ✅
- CW-3: `mathEnable` metadata → ColorMath gate ✅
- CW-4: Highlight mode ✅
- CW-5: Dual window + combination logic ✅
- CW-6: Per-layer window masking ✅
- Hardware proof: Scenarios 51 and 52 with RTSP capture evidence
- CyanPeak audit PASS #8654

---

### Priority D — Beam-Driven Automation Hardening ✅ DONE

**Why fourth:**

- raster triggers and Copper/HDMA-class groundwork already exist
- the current question is less "do we have it?" and more "is the envelope broad enough for richer use?"

**Main pressure served:**

- Amiga
- SNES
- Genesis
- Atari ST

**Main question to answer:**

- does the current beam-driven automation model need bounded table/channel hardening before stronger adapters rely on it heavily?

**Why after A/B/C:**

- there is already usable beam-driven machinery
- fetch and sprite pressure are more likely to force bad architectural decisions sooner

---

### Priority E — Adapter Lane Selection

Only after the higher-leverage shared hardening questions above are answered should the project choose its next harder adapter lane.

Most likely candidates after shared hardening:

- ZX Spectrum first serious adapter
- Amiga readiness-adjacent adapter work
- Genesis or SNES-class bounded adapter lane

The choice should be based on which hardening result says the substrate is now strong enough.

---

## Non-Priorities Right Now

These are specifically **not** the recommended next move:

- opening a hard Amiga adapter immediately
- opening a hard SNES adapter immediately
- adding platform-specific engines to work around shared-primitive weakness
- expanding `Mode0` in red-zone ways before the relevant envelope is measured

---

## Stop-Line Reminder

Every hardening item above must still pass `MODE0_PLANNING.md` §2.

That means:

- no approval by optimism
- no SDRAM-heavy widening without a clear per-line budget
- no major growth without estimated LUT/FF/BSRAM/DSP impact
- no "we'll see if it fits later" planning

---

## Current PM Recommendation

The hardening backlog execution order has been:

1. **Mode0 Fetch Envelope Hardening** — DONE
2. **Mode0 Sprite Envelope Hardening** — DONE (including Phase 2 + 2-bis)
3. **Color / Window Envelope Hardening** — DONE (BrightForge, audit PASS #8654)
4. **Beam-Driven Automation Hardening** — DONE (BrightForge implementation `7c2a18b..6345fcc`; CyanPeak audit PASS #8660)
5. **Sprite Capacity Expansion** — DONE (Tasks 2a/2c/2b, audit PASS #9286/#9298)
6. **Planar Fetch Hardening** — DONE (Task 3, audit PASS #9406)
7. **Sprite Pattern Address Width Expansion** — DONE (Task 53, audit PASS #9433)

The project is now in the **substrate gap closure + adapter readiness** phase.

---

## 6. Execution-Ready Queue

The following 3–6 tasks are bounded, have clear proof boundaries, and can open as active lanes immediately upon PM authorization.

| Rank | Task | Why | Dependencies | Scope Boundary | Proof Shape | Risk |
|---|---|---|---|---|---|---|
| 1 | **Task 55 — Sprite Masking + Tile-Fetch Budget Counter** | Completes sprite engine Phase 2 honesty for Genesis/SNES. Lowest risk of all open gaps. | Task 53 DONE | No compositor rewrite; no BPP/palette change | Masked sprite suppresses lower slots; 35-tile scene triggers overflow; regression PASS | Low |
| 2 | **Task 54 — Sprite-Sprite Collision Detector** | Honest C64 collision claims. Enhances NES/Genesis secondary. | Task 53 DONE | Bounding-box first, pixel-precision for candidates only | Overlapping sprites set collision bits; non-overlapping do not; status register readback correct | Medium |
| 3 | **320-pixel planar clipping mask** | CyanPeak #9406 follow-on. Confines planar fetch to intended left-half window if required for adapter honesty. | Task 3 DONE | Clip only; no fetch rewrite | Planar bars confined to 0..319; no wrap visible; regression PASS | Low |
| 4 | **Task 56 — Multi-Layer SDRAM Fetch** | Unblocks Amiga dual-playfield + rich Genesis/SNES backgrounds. | Task 3 DONE, Task 55 recommended first | L0+L1 only initially; L2/L3 deferred | L0+L1 concurrent SDRAM fetch; no line-drop under max load; resource + bandwidth report | Large |
| 5 | **Atari ST Adapter Lane** | Lowest-risk Tier 1 adapter. Planar + raster only; no sprites needed. | Task 3 DONE | v1: 320×200 4-plane only; STE blitter deferred | Static test pattern renders correctly; palette swap via raster trigger; 30s capture freeze=0 | Low-Medium |
| 6 | **NES Adapter Lane** | Highest-leverage Tier 2 adapter. Tile+sprite path already strong. | Task 2b DONE, Task 53 DONE | v1: 2bpp tiles + 64 sprites; no mapper IRQs | `NESAdapterSim` PASS; 30s HW capture with Mario-like test scene; freeze=0 | Medium |

**Recommended next lane:** Task 55 (CyanPeak #9436 + CoralReef #9437 consensus).

---

## 7. Forward Roadmap

The following 10–20 likely lanes are ordered by dependency and risk. They are **not** execution-ready until preflight work is complete.

### Near-term (after execution-ready queue)

| Lane | Ordering Rationale | Major Dependencies | Likely Proof Boundary | Current-Design Preservation |
|---|---|---|---|---|
| **Genesis Adapter (v1)** | Tier 3; sprite engine now strong enough for 20/line + 32×32 unique tiles | Task 2b DONE, Task 53 DONE, Task 55 recommended | `GenesisAdapterSim` PASS; 3-plane scroll + sprite scene; 30s capture | Preserve existing tile+sprite substrate; adapter owns scroll-table semantics |
| **PC Engine Adapter (v1)** | Tier 2; 16 sprites/line now supported; variable sizes already exist | Task 2b DONE | `PCEAdapterSim` PASS; 64-sprite scene; 30s capture | Preserve existing tile fetch; adapter owns SATB DMA emulation |
| **MSX2 Adapter (v1)** | Tier 2; bitmap + tile modes both supported | Task 44 DONE, planar DONE | `MSX2AdapterSim` PASS; G4–G7 bitmap modes; 30s capture | Preserve existing fetch variants; adapter owns V9938 command engine proxy |
| **SNES Adapter (v1 bounded)** | Tier 4; start with modes 0–3 (2–4bpp tiles, no Mode 7) | Task 2b DONE, Task 53 DONE, Task 55 recommended, window hardening DONE | `SNESAdapterSim` PASS; 4-layer scene; 30s capture | Preserve existing compositor; adapter owns layer priority + window math |
| **Neo Geo Adapter (v1 bounded)** | Tier 3; start with Fix layer + limited sprites | Task 2b DONE, Task 53 DONE | Fix layer renders correctly; 32-sprite scene; 30s capture | Preserve existing tile+sprite substrate; adapter owns sprite-list sorting |
| **Amiga Adapter (v1 bounded)** | Tier 4; start with 3–5 plane OCS + 8 sprites + Copper-lite | Task 3 DONE, planar DONE, beam hardening DONE | `AmigaAdapterSim` PASS; 5-plane copper-bar scene; 30s capture | Preserve planar fetch; adapter owns Copper wait/move semantics |
| **V-Scroll Table Primitive** | Needed for Genesis per-column scroll, SNES offset-per-tile | Task 3 DONE | Per-column V-scroll values applied to tile fetch; sim PASS; HW proof | Extend scroll-table primitive; do not break existing `layer0ScrollX/Y` |
| **Sprite Compositing / Multi-Tile Rasterizer** | True 32×32/64×64 from unique 16×16 tiles (not repeated) | Task 53 DONE | 32×32 sprite stitches 4 unique tiles; sim PASS; HW proof | Extend rasterizer FSM; preserve existing `sizeSel` behavior |

### Mid-term (structural expansion)

| Lane | Ordering Rationale | Major Dependencies | Likely Proof Boundary | Current-Design Preservation |
|---|---|---|---|---|
| **Task 49 — Blitter Engine** | Amiga/MSX2 command engine proxy; DMA-class bulk movement | Scheduler stable, Task 55 closed | Blit fill/copy rect sim; no corruption of concurrent fetch; resource report | Add as scheduler client; do not starve existing fetch slots |
| **4-Layer Compositor Expansion** | SNES modes 0–7 need >4 layers in some modes | Task 55 closed, window hardening DONE | 4-layer priority + transparency sim; HW proof; resource report | Extend `FourLayerCompositor`; preserve existing 2-layer path as subset |
| **DMA-Style Transfer Primitive** | OAM/VRAM bulk movement for NES/SNES/PC Engine | Blitter engine foundation | DMA transfer sim; no frame-drop under load; bandwidth report | Use scheduler slots for DMA; preserve real-time fetch priority |
| **Option B Pattern RAM** (256 tiles) | Dense SNES/Neo Geo scenes | Task 53 DONE, BSRAM headroom confirmed | 256 unique patterns addressable; sim PASS; resource report | Depth expansion only; no address-format change |
| **Interlace Output Support** | SNES/Amiga interlace modes | Timing generator stable | Interlaced frame renders correctly; no jitter; analyzer PASS | Extend `VgaTiming` state machine; preserve progressive path |
| **Color Math Add/Subtract/Blend** | SNES color math honesty | Color/Window hardening DONE | Add/sub/blend math sim; HW proof; resource report | Extend `ColorMathUnit`; preserve existing highlight/shadow |
| **HDMA Full Implementation** | SNES-style per-line register updates beyond Copper-lite | Beam hardening DONE | HDMA channel sim; register updates at correct raster lines; HW proof | Extend `CopperLite`; preserve existing wait/move semantics |
| **Offset-Per-Tile Scroll** | SNES Mode 2/4/6 feature | V-scroll primitive DONE | Per-tile scroll values applied; sim PASS; HW proof | Extend scroll-table format; preserve existing linear scroll |

---

## 8. Strategic Themes (Beyond 20 Lanes)

These are not bounded tasks. They are thematic directions that become relevant only after the forward roadmap is substantially closed.

- **Audio Integration:** No current audio primitive exists. Tang Nano 20K has no dedicated audio DAC. Would require external I2S/PCM chip or PWM approximation. Out of scope until video substrate is fully proven.
- **Higher Output Resolutions:** 720p output shell exists (Active Side Lane, closed). Native higher-resolution rendering (e.g., 640×480 internal) would require SDRAM bandwidth re-analysis and likely pipelining. Deferred indefinitely.
- **Full Tier 3/4 Ecosystem:** Genesis, SNES, Amiga, Neo Geo honest adapters with all edge cases. Requires most of the forward roadmap to be closed first.
- **Multi-Platform Bitstream Coexistence:** MODE_SELECT already supports runtime switching. The question is which platform combinations fit simultaneously within the Tang Nano 20K LUT/BSRAM budget. This is a packing problem, not a substrate problem.
- **External Memory Expansion:** SPI flash or QSPI PSRAM for larger frame buffers or tile sets. Would require new controller + scheduler client. Only relevant if current SDRAM bandwidth becomes the blocker.

---

## Current PM Recommendation

The hardening backlog execution order has been:

1. **Mode0 Fetch Envelope Hardening** — DONE
2. **Mode0 Sprite Envelope Hardening** — DONE (including Phase 2 + 2-bis)
3. **Color / Window Envelope Hardening** — DONE (BrightForge, audit PASS #8654)
4. **Beam-Driven Automation Hardening** — DONE (BrightForge implementation `7c2a18b..6345fcc`; CyanPeak audit PASS #8660)
5. **Sprite Capacity Expansion** — DONE (Tasks 2a/2c/2b, audit PASS #9286/#9298)
6. **Planar Fetch Hardening** — DONE (Task 3, audit PASS #9406)
7. **Sprite Pattern Address Width Expansion** — DONE (Task 53, audit PASS #9433)

Only after Beam Hardening closes should adapter lanes open.

---

## What Not To Do

- Do not treat this backlog as permanent; update it if the coverage matrix changes materially.
- Do not convert every planning question into a giant refactor.
- Do not let a future adapter task silently absorb a shared hardening question just because the team is impatient.

---
