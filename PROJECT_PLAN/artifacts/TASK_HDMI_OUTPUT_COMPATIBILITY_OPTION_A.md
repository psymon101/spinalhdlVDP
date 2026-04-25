# Task
HDMI Output Compatibility — Option A Detailed Plan

## Purpose

Move `spinalhdlVDP` to a fixed **720p60 CEA-861 HDMI output** without turning the first step into a risky full-substrate resolution rewrite. The long-term goal is to let adapters choose lower native logical resolutions while the HDMI transport remains a stable, capture-card-friendly 1280×720 output mode.

This plan exists because the current code couples **internal render raster**, **timing generation**, and **HDMI output** too tightly to safely "just switch to 720p" in one patch.

## Current Verified Code Reality

### 1. `VdpTop.scala` owns the active raster today

Current `VdpTop.scala` is not only a renderer. It is also the timing owner:

- `hActive = 640`, `vActive = 480`, with 640×480 timing hard-coded near the top of the file
- `hsync`, `vsync`, and `de` are generated directly from those counters
- many downstream structures size themselves from `hActive` / `vActive`

Important consequences visible in the current source:

- `LinestateStore(lineCount = vActive)` depends directly on `vActive`
- `LineBuffer(..., lineWidth = hActive)` depends directly on `hActive`
- bitmap fetch scheduling uses `hActive` as the fetch-grant boundary
- many comments and register contracts assume 640-pixel active width
- scenario bootstrap logic and scroll-map widths are currently based on 640-pixel assumptions

So a direct 640×480 → 1280×720 substitution inside `VdpTop` would be a **functional substrate change**, not a top-level compatibility patch.

### 2. `TopTang20kHdmi.scala` currently treats pixel raster and HDMI transport as one thing

Current top-level behavior:

- pixel-side PLL path is tuned for ~25.2 MHz pixel / 126 MHz TMDS serial
- `VdpTop` runs in the pixel domain directly
- TMDS inputs are registered from `video.io.{hsync,vsync,de,red,green,blue}`
- there is no scaler or output-mapper stage between the internal renderer and HDMI serializer

That means the current architecture is:

`VdpTop raster` -> `TMDS input` -> `serializer`

There is no explicit boundary yet between:

- internal logical render space
- output timing space
- output placement / scaling policy

### 3. The old `VDP` repository proved a reusable 720p output shell

The old baseline includes two useful reference modules:

- `m0_video_timing.v`
  - 1280×720 @ 60 Hz CEA-861 VIC 4
  - 1650×750 totals
  - active-high sync
  - 74.25 MHz pixel clock
- `m0_scaler.v`
  - simple nearest-neighbor 4×3 mapper from 320×240 -> 1280×720

This proves three things:

- 720p60 is feasible on the Tang Nano 20K
- the PLL / TMDS clock target is realistic
- a cheap output-mapper/scaler stage is the correct architectural seam

## Decision

**Option A remains the right target**, but implementation must be split into two layers:

1. **fixed HDMI transport layer**
   - always outputs 1280×720 @ 60 Hz CEA-861
2. **internal render layer**
   - owns the logical adapter / Mode0 content resolution
   - may be 320×240, 640×480, or another bounded logical space later

Do **not** begin by shrinking the entire current substrate to 320×240 in one step.

## Architecture Rule For This Lane

The correct end state is:

- HDMI output mode is fixed and standards-compliant
- internal render resolution is a separate concern
- adapters choose their native logical presentation
- a shared output mapper scales / positions that content into the 720p frame

This keeps output compatibility and adapter-native presentation from fighting each other.

## In Scope

- define the least-invasive path from current 640×480-coupled output to fixed 720p transport
- separate **output timing** from **internal render geometry**
- plan the reusable output-mapper/scaler boundary
- define the first implementation phases and proof gates
- define research questions still needed before coding

## Explicitly Out of Scope

- changing adapter semantics
- rewriting Mode0 register semantics in this planning artifact
- choosing the final logical resolution for every future adapter
- implementing a general-purpose arbitrary scaler
- reopening the paused fun-demo QSPI investigation before HDMI compatibility planning closes

## Recommended Phased Plan

### Phase 0 — Planning and evidence closure

Goal:
- lock down the implementation order before code changes begin

Required outputs:
- this artifact
- audit of this artifact
- one explicit decision packet naming the first coding slice

Additional research still worth doing:
- exact PLL implementation path in current `spinalhdlVDP` for 74.25 / 371.25
- whether the existing TMDS serializer path has enough timing headroom at 371.25 MHz in this repo's present structure
- whether a 720p shell can be proven first with a synthetic source before touching `VdpTop`

### Phase 1 — Small hardening slice: HDMI clean-start mute

Goal:
- improve lock/re-lock behavior independent of the larger 720p migration

Implementation idea:
- on `pll.LOCK` rise, force a short HDMI mute / blank interval at the TMDS boundary before allowing normal output

Why first:
- tiny scope
- useful under both current 640×480 and future 720p
- reduces reflash / relock ambiguity in every later hardware test

Scope boundary:
- no change to internal raster
- no change to `VdpTop`
- top-level / TMDS-boundary only

### Phase 2 — Introduce explicit output timing abstraction

Goal:
- stop treating `VdpTop` as the sole owner of HDMI timing

Required code direction:
- create a timing block or timing abstraction for **720p CEA-861**
- make output timing an explicit top-level concern

Target result:
- top-level owns:
  - output-space counters
  - hsync/vsync/de for 720p
- render path no longer has to equal HDMI transport timing

Important constraint:
- this phase should avoid changing Mode0 rendering behavior yet

### Phase 3 — Introduce an output mapper / presentation stage

Goal:
- add the architectural seam that lets logical render space differ from HDMI output space

The new stage should own:
- placement inside the 1280×720 frame
- border / letterbox / pillarbox policy
- nearest-neighbor replication policy for integer-friendly modes
- render-space coordinate generation for the upstream content source

Minimum supported policies for first implementation:
- centered 320×240 -> 1280×720 via 4×3 nearest-neighbor
- centered 640×480 compatibility window policy, if a temporary bridge mode is needed during migration

Important rule:
- the mapper should be simple and deterministic; no interpolation, no large framebuffer, no "smart" scaling

### Phase 4 — Decouple `VdpTop` from direct HDMI timing ownership

Goal:
- make `VdpTop` consume a render-space coordinate/timing contract rather than define the final HDMI transport itself

This is the major architectural refactor.

Expected code impact:
- `VdpTop.scala`
- `TopTang20kHdmi.scala`
- line-buffer and raster-trigger assumptions tied to `hActive` / `vActive`
- bitmap-fetch grant timing tied to the visible-line boundary

What likely needs to change:
- raster counters and active-space ownership become explicit inputs or a clearer internal render-timing abstraction
- rendering logic remains tied to one logical active raster
- output timing and sync generation move upward into the output shell

Audit focus here:
- no accidental mid-line behavior change
- no stale 640×480 assumptions left in fetch scheduling or line-buffer draining

### Phase 5 — First fixed-720p proof mode

Goal:
- prove the new 720p output path on hardware before full adapter-facing generalization

Recommended first proof:
- 320×240 internal diagnostic source
- 4×3 nearest-neighbor map to 1280×720
- explicit border/canary pattern so capture-card lock and geometry are obvious

Why this proof first:
- matches the proven old `VDP` baseline
- cheapest way to prove transport/timing/scaler path
- avoids blaming `VdpTop` while the output shell is still being stabilized

Important:
- this is an **output-shell proof**, not yet the final full Mode0 migration

### Phase 6 — Reattach Mode0 render path under the new shell

Goal:
- make current `Mode0` content render through the fixed 720p output architecture

Two acceptable bridge strategies:

1. **Temporary compatibility bridge**
   - preserve current logical renderer behavior while mapping it into the 720p shell
   - may involve centered/bordered presentation while architecture settles

2. **Planned logical render shrink**
   - if the project deliberately chooses 320×240 as the preferred shared render space for the next stage, do that as a separate bounded task after the 720p shell is already proven

Do not combine both decisions in one unbounded patch.

### Phase 7 — Adapter-facing presentation profiles

Goal:
- make lower-resolution adapters first-class citizens once the shell exists

Long-term model:
- each adapter defines:
  - logical resolution
  - aspect policy
  - border policy
  - pixel replication policy
- shared output mapper converts that into 720p presentation

Examples:
- C64-like: bordered lower-resolution image in a 720p frame
- ZX Spectrum-like: attribute/border feel preserved, scaled cleanly
- Amiga/ST-like: their own logical spaces mapped by policy, not by changing HDMI mode

## Research Questions Still Worth Answering Before Coding

These are not blockers for the architectural decision, but they are worth resolving up front:

1. Can the current `spinalhdlVDP` TMDS/serializer path close timing cleanly at 371.25 MHz with only a PLL retune and counter changes?
2. Is there already enough abstraction in `TopTang20kHdmi.scala` to add a separate output-timing generator without immediately rewriting `VdpTop`?
3. What is the smallest temporary bridge mode that lets current Mode0 content remain visible while the new shell is proven?
4. Which existing scenarios are the best hardware proof scenes for:
   - lock/re-lock robustness
   - scaling correctness
   - border placement
   - raster-trigger/fetch timing regressions

## Recommended First Coding Slice

The first coding slice should **not** be "port the full old 720p baseline."

It should be:

**Slice A — HDMI clean-start hardening**

Then:

**Slice B — 720p output-shell proof with a synthetic 320×240 source**

Only after those pass should the team open:

**Slice C — `VdpTop` decoupling / render-to-output split**

This order minimizes the chance of mixing:

- transport compatibility bugs
- scaler bugs
- substrate render bugs

into one ambiguous failure.

## Validation Plan

### Simulation-first expectations

Before hardware:

- 720p timing generator sim:
  - exact 1650×750 frame envelope
  - active-high sync polarity
  - correct DE window
- output mapper sim:
  - exact coordinate mapping for 320×240 -> 1280×720
  - border region behavior
  - frame-wrap reset correctness
- clean-start hardening sim:
  - mute window after PLL lock
  - normal output resumes deterministically

### Hardware proof expectations

Required hardware proof sequence:

1. clean-start hardening on current output path
   - repeated reflashes do not require operator recovery
2. synthetic 720p shell proof
   - Guermok capture locks across reflashes/power cycles
   - consumer display also locks
   - geometry/canaries show correct 4×3 scaling
3. Mode0-under-720p proof
   - current content visible through the new shell
   - no regression in basic fetch/compositor output

## Risks

- treating "720p output" and "internal resolution change" as one task
- leaving hidden 640×480 assumptions inside `VdpTop`
- timing closure risk at 371.25 MHz TMDS serial
- accidentally forcing non-integer scaling too early
- introducing a framebuffer-heavy design that violates the stop-line policy

## Recommendation

Proceed with **Option A**, but implement it as:

1. HDMI clean-start hardening
2. fixed 720p output shell
3. explicit output mapper boundary
4. only then internal render-path migration

This preserves the right architecture for future lower-resolution adapters while avoiding a premature full-substrate rewrite.

## Exit Condition

This planning task is done when the team has an audited, phased implementation plan that reaches fixed 720p output without conflating HDMI transport compatibility with internal render-geometry changes.
