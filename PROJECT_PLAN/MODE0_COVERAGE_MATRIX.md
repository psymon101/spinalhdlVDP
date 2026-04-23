# MODE0_COVERAGE_MATRIX.md

**Updated:** 2026-04-23  
**Purpose:** Map the intended `Mode0` capability envelope against the current repo state so the team can see which shared primitives are already strong enough, which are only partial, and which gaps still block honest future adapters.

---

## Why This Exists

The project now has:

- `MODE0_ROADMAP.md` for strategic primitive order
- `MODE0_MAX_CAPABILITIES.md` for the intended maximum useful envelope
- `MODE0_STOPLINES.md` for board-budget gating
- `ADAPTER_NUANCES.md` for platform-facing visual rules

This file bridges those into one practical question:

- what parts of the intended `Mode0` envelope already exist in the repo?
- what remains partial?
- what is still missing before each adapter can stay mostly in the adapter layer instead of forcing new substrate work?

---

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

The matrix suggests the next important shared planning questions are no longer "do we have any primitive at all?" but rather:

1. Is the current sprite envelope strong enough for Amiga/Neo Geo/strong Genesis pressure without a second sprite engine?
2. Is the current planar/shuffled fetch implementation strong enough for serious Amiga/Atari ST/ZX Spectrum adapter work, or only for bounded proofs?
3. Is the current color-math/window envelope broad enough for honest SNES/Genesis-style adapter semantics?
4. Can the current scheduler/memory model absorb any richer adapter work without violating `MODE0_STOPLINES.md`?

Those are better next questions than reopening already-closed low-level primitives.

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
