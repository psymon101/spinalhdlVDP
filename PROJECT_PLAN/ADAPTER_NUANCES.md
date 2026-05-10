# ADAPTER_NUANCES.md

**Updated:** 2026-05-10  
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

If this file and `MODE0_PLANNING.md` §3 (Strategic Roadmap) disagree on substrate readiness, `MODE0_PLANNING.md` §3 (Strategic Roadmap) wins.

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

## Platform Adapter Fidelity Standards

The following standards apply to all platform adapters (starting with Task 40). They are not optional enhancements; they are foundational to the definition of a "platform adapter" in this project.

### 0. General Capability Rule

Platform adapters must translate platform behavior onto the shared  substrate. They must not create separate platform-specific render engines when a general  primitive can serve multiple adapters honestly.

Required interpretation:

-  owns the reusable superset capability.
- adapters own platform-specific register semantics, limits, quirks, and presentation choices.
- an adapter may clamp or subset a richer  primitive to match its target platform.
- a more demanding adapter may use more of the same primitive's range without requiring a second engine.

Examples:

- a stronger Amiga-facing sprite model and a more limited C64-facing sprite model should still sit on the same generic  sprite machinery if the underlying primitive is shareable
- a blitter/DMA-style primitive belongs in  if multiple platforms can use it, even if they expose different platform-facing command semantics
- Copper/HDMA/H-int style control should reuse beam-driven automation primitives rather than creating per-platform timing engines

Do not:

- add a C64-only, Amiga-only, or SNES-only hardware engine inside  when the need is actually a shared primitive with different adapter limits
- weaken a shared primitive just to match the least demanding platform
- bypass a reusable  primitive from an adapter merely to preserve platform naming

### 0a. Transport Separation Rule

Each external host transport must be internally complete and self-consistent. If a lane uses , then framing, synchronization, payload rules, completion, status, and error handling must all be defined and validated inside the  transport contract itself.

Required interpretation:

-  is one complete transport contract.
- a future parallel address/data bus is a separate complete transport contract.
- both transports may target the same shared  host/control surface.
- transports must not borrow hidden timing, framing, or completion assumptions from each other.

Do:

- keep , header, payload-length, completion, and error semantics self-contained inside the  path
- define any future parallel-bus framing / handshake / completion rules independently, even if it targets the same registers or upload surface
- debug transport failures at the transport boundary first before blaming  primitives

Do not:

- mix  framing rules with address/data-bus assumptions
- rely on a second transport's handshake model to explain or complete a  transaction
- patch over a broken transport by leaning on side effects from another control path

### 1. Circuitry-Accurate Palettes

Palettes MUST NOT use generic RGB approximations. They must be derived from hardware-level circuitry analysis, specifically considering:

- **DAC resistor values** and output network characteristics of the target system.
- System-specific voltage levels and color-space mappings (e.g., YPbPr, S-Video nuances).
- Measurement-based references (e.g., for C64, using Pepto's palette or similar circuitry-aware models).

Adapter implementations must document the palette source and any assumptions in the artifact or code comments.

### 2. Native Platform Fonts

Every adapter must include the **default system font/ROM** (e.g., C64 character ROM) as its baseline text/tile asset to ensure authentic presentation. Fallback generated fonts are not acceptable for adapter proof.

Font ROMs should be stored as  initial content or Scala  constants in the adapter source file or a dedicated  directory.

### 3. Display Nuances

Implementations must consider and document platform-specific display nuances:

- Border/background relationships (e.g., C64 / interaction).
- Aspect ratio considerations if the adapter/composition level can influence them.
- Signal-level artifacts that are visually characteristic of the target platform.

### 4. Gap Analysis

Adapters must include an explicit "honest gap analysis" section listing:

- Features that are emulated.
- Features that are deliberately omitted and why.
- Features that are architecturally impossible on the current Mode0 substrate.

This prevents scope creep while maintaining honest claims about adapter fidelity.

---

## Platform Adapter Fidelity Standards

The following standards apply to all platform adapters (starting with Task 40). They are not optional enhancements; they are foundational to the definition of a "platform adapter" in this project.

### 0. General Capability Rule

Platform adapters must translate platform behavior onto the shared `Mode0` substrate. They must not create separate platform-specific render engines when a general `Mode0` primitive can serve multiple adapters honestly.

Required interpretation:

- `Mode0` owns the reusable superset capability.
- adapters own platform-specific register semantics, limits, quirks, and presentation choices.
- an adapter may clamp or subset a richer `Mode0` primitive to match its target platform.
- a more demanding adapter may use more of the same primitive's range without requiring a second engine.

Examples:

- a stronger Amiga-facing sprite model and a more limited C64-facing sprite model should still sit on the same generic `Mode0` sprite machinery if the underlying primitive is shareable
- a blitter/DMA-style primitive belongs in `Mode0` if multiple platforms can use it, even if they expose different platform-facing command semantics
- Copper/HDMA/H-int style control should reuse beam-driven automation primitives rather than creating per-platform timing engines

Do not:

- add a C64-only, Amiga-only, or SNES-only hardware engine inside `Mode0` when the need is actually a shared primitive with different adapter limits
- weaken a shared primitive just to match the least demanding platform
- bypass a reusable `Mode0` primitive from an adapter merely to preserve platform naming

### 0a. Transport Separation Rule

Each external host transport must be internally complete and self-consistent. If a lane uses `QSPI`, then framing, synchronization, payload rules, completion, status, and error handling must all be defined and validated inside the `QSPI` transport contract itself.

Required interpretation:

- `QSPI` is one complete transport contract.
- a future parallel address/data bus is a separate complete transport contract.
- both transports may target the same shared `Mode0` host/control surface.
- transports must not borrow hidden timing, framing, or completion assumptions from each other.

Do:

- keep `CS`, header, payload-length, completion, and error semantics self-contained inside the `QSPI` path
- define any future parallel-bus framing / handshake / completion rules independently, even if it targets the same registers or upload surface
- debug transport failures at the transport boundary first before blaming `Mode0` primitives

Do not:

- mix `QSPI` framing rules with address/data-bus assumptions
- rely on a second transport's handshake model to explain or complete a `QSPI` transaction
- patch over a broken transport by leaning on side effects from another control path

### 1. Circuitry-Accurate Palettes

Palettes MUST NOT use generic RGB approximations. They must be derived from hardware-level circuitry analysis, specifically considering:

- **DAC resistor values** and output network characteristics of the target system.
- System-specific voltage levels and color-space mappings (e.g., YPbPr, S-Video nuances).
- Measurement-based references (e.g., for C64, using Pepto's palette or similar circuitry-aware models).

Adapter implementations must document the palette source and any assumptions in the artifact or code comments.

### 2. Native Platform Fonts

Every adapter must include the **default system font/ROM** (e.g., C64 character ROM) as its baseline text/tile asset to ensure authentic presentation. Fallback generated fonts are not acceptable for adapter proof.

Font ROMs should be stored as `Mem` initial content or Scala `Seq[Bits]` constants in the adapter source file or a dedicated `assets/` directory.

### 3. Display Nuances

Implementations must consider and document platform-specific display nuances:

- Border/background relationships (e.g., C64 $D020/$D021 interaction).
- Aspect ratio considerations if the adapter/composition level can influence them.
- Signal-level artifacts that are visually characteristic of the target platform.

### 4. Gap Analysis

Adapters must include an explicit "honest gap analysis" section listing:

- Features that are emulated.
- Features that are deliberately omitted and why.
- Features that are architecturally impossible on the current Mode0 substrate.

This prevents scope creep while maintaining honest claims about adapter fidelity.

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

**Minimum readiness:** through `R3` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R7.2` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R4` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R4` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R4` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R5` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R5` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R6`, with scroll-table primitive complete, per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R6`, plus `R8` for Mode 7, per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R7`, with `R5` especially important, per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R7` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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

**Minimum readiness:** through `R7` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

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
