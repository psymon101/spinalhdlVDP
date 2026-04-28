# TASK_R2_TWO_PASS_SPRITE_EVALUATOR.md

**Status:** CLOSED — Two-pass sprite evaluator implemented and integrated (R2 deliverable)

**Updated:** 2026-04-12  
**Purpose:** Second roadmap-derived execution task for `Mode0`. This task upgrades the current minimal two-sprite proof into a bounded two-pass sprite evaluation primitive that later sprite flags and platform adapters can reuse.

---

## Task Name

`Two-Pass Sprite Evaluator`

---

## Purpose

Replace the current inline, hard-coded two-sprite pixel test in `VdpTop` with a proper two-pass sprite pipeline:

1. evaluate which sprite descriptors are active on the upcoming scanline
2. render from a bounded secondary line-local sprite list during pixel fill

This task matters because the current sprite path is only a proof-of-existence. It does not scale to the platform pressures already identified in the roadmap:

- NES sprite-per-line behavior
- Commodore 64 sprite collisions and overflow pressure
- Genesis and SNES stronger overlap / visibility rules
- Amiga and Atari ST adapter groundwork for richer sprite-vs-background behavior

The goal is not to emulate any one platform’s full sprite subsystem yet. The goal is to give `Mode0` a real reusable sprite-evaluation primitive instead of keeping sprite logic baked into ad hoc per-sprite conditionals.

---

## Primitive Boundary

### In Scope

- replace the current direct `sprite0` / `sprite1` inline evaluator with a bounded two-pass evaluator
- introduce a small sprite descriptor table owned by `Mode0`
- pass 1: per-line range scan that selects a bounded set of active sprites for the next scanline
- pass 2: per-pixel render/evaluate from that bounded active-sprite list
- explicit sprite-per-scanline limit enforcement
- deterministic overlap / priority behavior inside the bounded sprite list
- one line-local secondary sprite buffer or equivalent bounded active-sprite staging structure
- simulation proof for the evaluator and no-regression proof for the current baseline
- one hardware-visible proof scene that demonstrates the evaluator is doing real bounded selection rather than the old two-sprite shortcut

### Explicitly Out of Scope

- sprite-0-hit, overflow, or collision status flags as architecturally committed adapter-facing semantics
- platform-exact OAM register maps
- sprite DMA / linked-list sprite systems
- sprite scaling / zoom / flipping / affine behavior
- palette banking, color math, or windowing
- fetch-slot scheduler refactor
- mid-line sprite register writes
- Copper / HDMA / beam-script behavior

Those hooks belong in later roadmap items once the evaluator itself is proven.

---

## Dependencies

- hardware-proven `Task 15` baseline
- hardware-proven `R1 Raster Trigger Unit`
- accepted `MODE0_ROADMAP.md`
- `TASK_TEMPLATE.md` as the planning format

Architectural prerequisites:

- preserve the interface-stability rule from the roadmap so later sprite hooks can attach without re-plumbing the evaluator
- preserve the GT-022 power-of-two `Mem` rule for any new initialized sprite tables or line-local buffers

---

## Interfaces

Minimum expected interface evolution:

- keep the current top-level demo usability for a small bounded sprite set
- introduce a descriptor-table-facing interface inside `VdpTop` rather than hard-coding one logic block per sprite
- expose a clean separation between:
  - sprite descriptor storage
  - per-line active-sprite selection
  - per-pixel sprite resolution

Expected internal interfaces:

- descriptor fields:
  - `x`
  - `y`
  - `enabled`
  - `patternIndex` or equivalent future-safe selector
  - `priority` if it stays cheap and stable
- active-line entry fields:
  - descriptor index or pattern reference
  - local row offset
  - x position
  - enabled / valid bit

Constraint:

- do not freeze a full future OAM bus in this task
- do choose naming and ownership that a later register bus can absorb

---

## Data Model

This primitive should own:

- a bounded sprite descriptor store
- a bounded active-sprite line buffer / staging structure
- line-local selection count / valid tracking
- simple deterministic priority order

Working expectation for the first bounded slice:

- total descriptor count should be greater than the current hard-coded `2`
- visible-per-line limit should be explicit and smaller than or equal to total descriptor count
- both limits should be easy to reason about in proof scenes

If new initialized `Mem` is introduced:

- pad to power-of-two depth immediately

If a purely register-based active list is sufficient for the first bounded slice, that is acceptable.

---

## Timing Model

This task is line-oriented.

Expected timing split:

- pass 1 happens at a deterministic line boundary or during the existing scanline-fill preparation phase
- pass 2 resolves sprite pixels during the existing per-pixel fill path

Working assumptions:

- evaluation remains in the pixel-domain timing context already used by `VdpTop`
- the active-sprite list for a line should remain stable for the full duration of that line’s fill
- no mid-line mutation of the active list
- no new clock-domain crossings are expected in this task

Hard rule:

- do not hide scheduling complexity by silently leaning on later fetch-slot work; this task should fit the current proven fill model

---

## Memory / Bandwidth Impact

Expected impact should remain small:

- no new SDRAM clients
- no arbitration changes
- no Task-15-path refactor in this task
- only bounded on-chip state for descriptor storage and per-line sprite staging

This task should strengthen sprite behavior without reopening the proven SDRAM fetch path.

---

## Platform Reuse

Primary beneficiaries:

- NES / Famicom
- Commodore 64
- Genesis / Mega Drive

Secondary beneficiaries:

- SNES
- Amiga

Foundational benefit:

- later sprite-0-hit / overflow / collision hooks become much cheaper once the evaluator is no longer ad hoc

---

## Failure Modes / Risks

Most likely risks:

- off-by-one line inclusion when selecting active sprites
- sprite-per-line limit enforced at the wrong boundary
- unstable overlap / priority ordering
- active-list content changing mid-line
- accidental regression of the current two-sprite demo behavior
- GT-022 violations if a new initialized descriptor memory uses a non-power-of-two depth

Secondary risks:

- scope creep into sprite flags and adapter semantics too early
- turning the line-local staging structure into an implicit future OAM contract

---

## Validation Plan

Required dedicated simulation proof:

- sprites on and off the current line are selected correctly
- bounded visible-per-line limit is enforced deterministically
- overlap priority between selected sprites is stable and repeatable
- active-sprite staging remains stable for the full rendered line
- sprites outside the bounded active set do not leak through

Required regression checks:

- `VdpTopSim` baseline still passes
- `SdramTileFetchSim` still passes
- `RasterTriggerUnitSim` still passes

Preferred task-specific diagnostic sim:

- one crowded line scene with more logical sprites than the visible-per-line limit
- one overlap scene that proves ordering is stable

---

## Hardware Proof

Minimum hardware proof should be a deliberately sprite-heavy diagnostic scene.

Preferred proof characteristics:

- more logical sprites than the old hard-coded two-sprite path could represent
- at least one line where the visible-per-line limit matters
- at least one overlap region where deterministic priority is visually obvious

Acceptance:

- the rendered scene must show bounded selection behavior consistent with the sim
- the old baseline scene must still render correctly after the task
- use a fresh capture-device frame or equivalent current hardware evidence

---

## Audit Questions

CyanPeak should explicitly verify:

- is the implementation still bounded to evaluator upgrade rather than broader sprite-feature creep
- is the active-sprite list stable across a line
- is the visible-per-line limit deterministic and clearly proven
- does the hardware proof demonstrate a real evaluator upgrade instead of the old two-sprite shortcut
- did the task accidentally imply a frozen OAM / adapter contract too early

---

## Constraints / Gotcha Check

This task must obey:

- GT-022 power-of-two `Mem` rule
- interface-stability rule from `MODE0_ROADMAP.md`
- no hardware claim before simulation proof
- no Task-15-path memory refactor
- no accidental bundling of sprite flags / collision semantics that belong in later tasks

---

## Exit Condition

This task is done when `Mode0` has a hardware-proven bounded two-pass sprite evaluator with explicit per-line selection and stable overlap behavior, replacing the current hard-coded two-sprite path without regressing the proven baseline.
