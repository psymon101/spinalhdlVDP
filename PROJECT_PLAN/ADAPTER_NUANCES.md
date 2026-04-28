# ADAPTER_NUANCES.md

**Updated:** 2026-04-28  
**Purpose:** Platform-facing visual and behavioral nuance reference for adapter planning. This file captures what each adapter should preserve at the presentation layer so future adapter tasks stay honest about pixel shape, palette behavior, timing-visible artifacts, and display quirks.

---

## How To Use This File

This file is not a promise of cycle-accurate emulation.

It is a planning reference for:

- adapter artifact authors
- audit/review lanes checking fidelity claims
- PM reassessment when choosing the next adapter
- deciding whether a requested platform quirk belongs in the adapter or in `Mode0`

If this file and `TASKS.md` disagree on task ordering or status, `TASKS.md` wins.

If this file and `MODE0_ROADMAP.md` disagree on substrate readiness, `MODE0_ROADMAP.md` wins.

---

## Working Rule

Every serious adapter should document, at minimum:

- native logical resolution or tile/cell structure
- intended output scaling / aspect treatment on the fixed HDMI raster
- border / overscan / display-window behavior
- palette rules
- attribute / fetch-layout quirks
- timing-visible or artifact-like presentation quirks
- which `Mode0` primitives those behaviors depend on

An adapter should not flatten those into generic RGB output if doing so destroys the recognizable look of the target platform.

---

## Shared Categories

### Pixel Shape / Scaling

How the platform's native logical pixels or cells should be mapped onto the fixed HDMI output. This may be integer enlargement, non-square presentation, or centered display-window treatment.

### Border / Overscan

How much of the frame is considered active picture versus decorative or timing-significant border area.

### Palette / Color Rules

How colors are selected and constrained:

- fixed palette
- banked palette
- cell attributes
- intensity / bright bits
- shadow/highlight
- color math

### Fetch / Memory Layout

The platform-facing storage model the adapter should present:

- tiled
- planar
- bitmap + attribute
- shuffled / non-linear layout
- linked sprite lists

### Timing-Visible Quirks

Artifacts or behaviors that are visible to the user even if they are not "features" in the normal API:

- raster IRQ splits
- attribute clash
- sprite-per-line limits
- display-window timing character
- Copper / HDMA line effects

---

## Platform Matrix

### Commodore 64

**Minimum readiness:** through `R3` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:451)

**Pixel shape / scaling**

- native feel is not square-pixel modern RGB
- adapter should document how `320x200` or character-cell content is presented on the fixed HDMI raster
- integer enlargement is acceptable for proof if it preserves the expected C64 visual character

**Border / overscan**

- border/background relationship is part of the platform identity
- `$D020` / `$D021` interaction should be treated as a visual requirement, not a decorative afterthought

**Palette / color rules**

- use a circuitry-aware C64 palette reference, not generic RGB
- text/bitmap color restrictions and color-cell behavior matter

**Fetch / memory layout**

- text/tile semantics
- bitmap modes
- color RAM / per-cell color behavior

**Timing-visible quirks**

- raster IRQ splits
- badline-style fetch pressure
- sprite collision semantics
- open-border and other exact VIC-II timing tricks are separate hardening work, not baseline adapter assumptions

**Relevant `Mode0` pressure**

- raster IRQ
- sprite flags / collision hooks
- bitmap + attribute fetch

### ZX Spectrum

**Minimum readiness:** through `R7.2` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:462)

**Pixel shape / scaling**

- native logical frame is `256x192`
- adapter should preserve the feel of a compact bitmap centered within a bordered display area
- integer enlargement is preferred where practical

**Border / overscan**

- border is visually important and often used deliberately
- active picture should not simply fill the whole HDMI frame unless the adapter explicitly chooses a "cropped modern presentation" mode

**Palette / color rules**

- 8-color base palette with bright variants
- per-cell ink/paper constraints must remain visible

**Fetch / memory layout**

- bitmap + attribute pairing is core identity
- non-linear / shuffled screen memory is part of the platform model

**Timing-visible quirks**

- attribute clash / color clash when moving objects cross 8x8 color cells
- optional flash/blink semantics
- the adapter should preserve the visible color-cell limitations instead of silently upgrading to per-pixel color freedom

**Relevant `Mode0` pressure**

- shuffled fetch
- bitmap + attribute pairing
- indexed palette

### NES / Famicom

**Minimum readiness:** through `R4` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:452)

**Pixel shape / scaling**

- adapter should document intended presentation of the NES active area on fixed HDMI output
- modest overscan treatment may matter more than exact aspect in a first proof

**Border / overscan**

- overscan/cropping expectations matter
- active area may intentionally not use every output pixel

**Palette / color rules**

- palette behavior is not generic RGB; the adapter should use an NES-appropriate palette reference
- attribute-table color granularity is part of the look

**Fetch / memory layout**

- tile + attribute background model
- sprite evaluation with bounded visible-per-line behavior

**Timing-visible quirks**

- sprite-0 hit semantics
- sprite-per-line limits / overflow character
- scrolling splits / status-bar style raster tricks

**Relevant `Mode0` pressure**

- tile + attribute fetch
- two-pass sprite evaluation
- raster timing hooks

### TMS9918-family (ColecoVision / SG-1000 / MSX1-class)

**Minimum readiness:** through `R4` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:453)

**Pixel shape / scaling**

- preserve the compact low-resolution console/computer presentation

**Border / overscan**

- strong framed active area is typical

**Palette / color rules**

- limited fixed palettes and mode-dependent constraints

**Fetch / memory layout**

- tile-centric background organization
- mode-specific bitmap/tile differences should be adapter-visible

**Timing-visible quirks**

- sprite-per-line limits
- mode-specific color restrictions

**Relevant `Mode0` pressure**

- tile/attribute fetch
- bounded sprite evaluation

### Master System / Game Gear

**Minimum readiness:** through `R4` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:454)

**Pixel shape / scaling**

- Master System and Game Gear should be treated as related but not visually identical presentation targets
- Game Gear viewport/windowing should not be silently conflated with Master System full-frame output

**Border / overscan**

- Master System framing and Game Gear viewport differences should be documented

**Palette / color rules**

- palette format differs from earlier TMS-family expectations

**Fetch / memory layout**

- tilemap layers plus sprite system

**Timing-visible quirks**

- sprite limits / priority behavior
- scrolling-window presentation

**Relevant `Mode0` pressure**

- tile/attribute fetch
- sprite evaluation

### MSX2

**Minimum readiness:** through `R5` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:455)

**Pixel shape / scaling**

- mode-specific presentation should be explicit rather than flattened into one "MSX-like" view

**Border / overscan**

- visible window / border treatment varies by screen mode

**Palette / color rules**

- palette behavior is mode-sensitive

**Fetch / memory layout**

- multiple distinct screen modes with different tile/bitmap expectations

**Timing-visible quirks**

- line interrupts and mode changes can be visually important

**Relevant `Mode0` pressure**

- stronger control bus discipline
- beam-driven automation

### PC Engine / TurboGrafx-16

**Minimum readiness:** through `R5` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:456)

**Pixel shape / scaling**

- preserve the low-resolution arcade-console feel rather than stretching blindly to fill output

**Border / overscan**

- visible area treatment should be documented

**Palette / color rules**

- bank/palette usage is part of the look

**Fetch / memory layout**

- tile/sprite mix with strong sprite emphasis

**Timing-visible quirks**

- scroll and line-effect behavior where used

**Relevant `Mode0` pressure**

- control bus
- beam-driven automation

### Genesis / Mega Drive

**Minimum readiness:** through `R6`, with scroll-table primitive complete, per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:457)

**Pixel shape / scaling**

- adapter should preserve the wide-console presentation feel and document how non-square native pixels are treated

**Border / overscan**

- active-area framing and overscan treatment should be explicit

**Palette / color rules**

- palette banks and priority interactions matter
- shadow/highlight behavior is part of the platform character

**Fetch / memory layout**

- multi-layer tilemaps
- per-line / per-column scroll semantics
- linked-list sprite behavior

**Timing-visible quirks**

- line interrupts
- window-plane use
- shadow/highlight presentation
- sprite overflow / list behavior

**Relevant `Mode0` pressure**

- scroll tables
- windowing
- post-compositor color behavior

### SNES / Super Famicom

**Minimum readiness:** through `R6`, plus `R8` for Mode 7, per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:458)

**Pixel shape / scaling**

- adapter should document how different SNES mode presentations map onto the fixed output raster

**Border / overscan**

- overscan/interlace/active-area variants need explicit treatment if claimed

**Palette / color rules**

- richer palette handling
- windowing and color math are core presentation behaviors, not extras

**Fetch / memory layout**

- 4-layer composition pressure
- affine path for Mode 7-class behavior

**Timing-visible quirks**

- HDMA-style per-line updates
- window masks
- color math visibility
- Mode 7 transform behavior when supported

**Relevant `Mode0` pressure**

- 4-layer compositor
- windowing
- color math
- affine
- beam-driven automation

### Amiga (OCS/ECS-class)

**Minimum readiness:** through `R7`, with `R5` especially important, per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:459)

**Pixel shape / scaling**

- adapter should preserve the display-window feel instead of pretending to be a generic framebuffer
- non-square presentation and mode-specific width choices should be documented

**Border / overscan**

- display-window placement and border timing are part of the machine’s look

**Palette / color rules**

- bitplane-derived color indices and palette interpretation are central

**Fetch / memory layout**

- planar fetch
- beam-synchronous display-list control
- stronger sprite priority semantics

**Timing-visible quirks**

- Copper-style wait/move effects
- display-window timing character
- bitplane composition look

**Relevant `Mode0` pressure**

- planar fetch
- register bus discipline
- Copper-lite / beam-driven automation
- stronger sprite priority

### Atari ST

**Minimum readiness:** through `R7` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:460)

**Pixel shape / scaling**

- preserve the planar framebuffer look and resolution-dependent presentation

**Border / overscan**

- raster/border tricks may matter to the final character

**Palette / color rules**

- palette behavior is mode-sensitive and should not be generalized away

**Fetch / memory layout**

- interleaved planar fetch is the central adapter-facing storage model

**Timing-visible quirks**

- raster-timing tricks
- palette changes during display

**Relevant `Mode0` pressure**

- planar fetch
- raster timing hooks

### Neo Geo

**Minimum readiness:** through `R7` per [MODE0_ROADMAP.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/MODE0_ROADMAP.md:461)

**Pixel shape / scaling**

- preserve the arcade presentation rather than flattening it into a generic console-style tilemap output

**Border / overscan**

- visible framing should be documented

**Palette / color rules**

- rich sprite/palette use is part of the platform identity

**Fetch / memory layout**

- heavily sprite-centric composition
- large object and tile/sprite integration rules

**Timing-visible quirks**

- zoom/scaling semantics
- sprite ordering and priority feel

**Relevant `Mode0` pressure**

- stronger sprite path
- richer composition contracts

---

## Mixed-Adapter Rule

A future scene may deliberately mix platform-flavored regions on one HDMI frame, but that should be treated as a **composite demo mode**, not as evidence that two full historical machines are being rendered at once.

Example:

- top of frame uses a C64-flavored adapter region
- bottom of frame uses an Atari ST-flavored adapter region

This is architecturally possible in principle if:

- the scene drives different `Mode0` state by region or scanline
- the fetch/compositor substrate can feed the required formats
- the adapter semantics are expressed as beam-synchronous state changes, not two unrelated renderers fighting each other

What this is **not**:

- not two fully independent machines running side by side
- not proof that all platform timing quirks can coexist simultaneously
- not a replacement for a dedicated adapter proof

So the correct rule is:

- **mixed-platform presentation is possible as a deliberate demo technique**
- **but it should be treated as a higher-level scene composition problem, not the default adapter model**

---

## What Not To Do

- Do not erase platform-specific color restrictions just to make the output look "cleaner".
- Do not replace platform-visible pixel/cell quirks with a universal square-pixel assumption unless the adapter explicitly documents that compromise.
- Do not silently upgrade a platform from cell-attribute color rules to per-pixel free color.
- Do not claim cycle-accurate hardware behavior from a presentation-only proof.
