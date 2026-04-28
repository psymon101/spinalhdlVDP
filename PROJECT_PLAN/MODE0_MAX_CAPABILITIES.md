# MODE0_MAX_CAPABILITIES.md

**Updated:** 2026-04-28  
**Purpose:** Define the intended **maximum useful capability envelope** for `Mode0` as a general 2D video substrate on the Tang Nano 20K target. This file exists to answer "how far should `Mode0` go?" without drifting into per-platform engines or impossible whole-system ambitions.

---

## Why This Exists

The project already has:

- `MODE0_ROADMAP.md` for strategic build order
- `ADAPTER_NUANCES.md` for platform-facing visual rules
- `MODE0_STOPLINES.md` for quantified budget limits

This file answers a different question:

- what is the **technical ceiling** we want `Mode0` to cover?
- how strong should each shared primitive become before adapters simply clamp it?
- what reference platforms define that ceiling?
- what is explicitly outside the intended `Mode0` envelope?

This is a **superset contract**, not a promise that every section is already implemented.

---

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
5. does `MODE0_STOPLINES.md` say the cost is still sane on Tang Nano 20K?

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
