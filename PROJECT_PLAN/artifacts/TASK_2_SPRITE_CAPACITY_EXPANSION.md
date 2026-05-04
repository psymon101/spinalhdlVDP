# Task 2 — Sprite Capacity Expansion

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Artifact drafted — pending audit  
**Governing directive:** BronzeGate #9204

---

## Task

Sprite Capacity Expansion — raise `visiblePerLine` from 8 → 32 and `descCount` from 32 → 64.

## Purpose

The current `SpriteEvaluator` supports 8 visible sprites per scanline and 32 total descriptors. This blocks honest Tier 2/3 adapter claims:

| Platform | Needs visible/line | Needs descCount | Current honest? |
|---|---|---|---|
| NES | 8 | 64 | desc gap |
| PC Engine | 16 | 64 | both gaps |
| MSX2 | 8 | 32 | desc gap (Mode 2) |
| Genesis | 20 | 80 | both gaps |
| SNES | 32 | 128 | both gaps |
| Neo Geo | 96 | 380 | both gaps (massive) |

Raising to 32 visible / 64 descriptors unlocks honest NES support and brings Genesis/SNES/PC Engine within "approximate but usable" range. It is the highest-leverage substrate task after MODE_SELECT per `MODE0_GAP_TASKLIST.md`.

## Scope

### In scope

1. **`visiblePerLine` 8 → 32** in `SpriteEvaluator` + `VdpTop` instantiation
2. **`descCount` 32 → 64** in `SpriteEvaluator` + `VdpTop` instantiation
3. **Pass-1 FSM restructuring** to scan 64 descriptors and select up to 32 per line
4. **Pass-2 output widening** — `activeValid`, `activeX/Y/Row/PatternIdx` vectors grow from 8 → 32 entries
5. **Compositor input widening** — `FourLayerCompositor` sprite inputs expanded to match
6. **Tile budget counter scaling** — current `TileBudget = 34` may need re-evaluation for 32 visible sprites
7. **Overflow flag accuracy** — `totalOnLine` and tile-budget overflow must remain correct at new limits
8. **Sim proof** — dedicated capacity sim + all existing sprite sim regressions
9. **Resource report** — post-synthesis LUT/FF/BSRAM delta vs baseline

### Out of scope (deferred)

- Descriptor count beyond 64 (SNES 128, Neo Geo 380 remain honest gaps)
- Linked-list / DMA sprite loading (Genesis, Neo Geo)
- Sprite masking (Genesis sprite-0 mask)
- Per-sprite color attribute tables (MSX2 Mode 2)
- Sprite shrinking / scaling (Neo Geo)
- SNES 34-tile fetch budget hardening (already present but may need adjustment)
- Compositor priority metadata changes beyond width
- Palette bank expansion
- Changes to `legacyIoCount` (stays 4)

## Dependencies

- **R2 Sprite Evaluator** — ✅ DONE (`9e07804`). The two-pass architecture must be stable.
- **Task 28 Two-Pass Sprite Evaluator** — ✅ DONE. Provides the Pass-1/Pass-2 foundation.
- **Task 29 Sprite Flags** — ✅ DONE. Overflow/collision flags must not regress.
- **Task 37 Affine Sprite Path** — ✅ DONE. Extended descriptor words must remain compatible.
- **Task 52 Sprite Flip** — ✅ DONE. Flip metadata must propagate through widened paths.
- **MODE_SELECT infrastructure** — ✅ DONE (`ef4f8ce`). Adapter coexistence must not break.

## Interfaces / State

### Changed interfaces

| Interface | Before | After | Files |
|---|---|---|---|
| `SpriteEvaluator.visiblePerLine` | 8 | 32 | `SpriteEvaluator.scala`, `VdpTop.scala` |
| `SpriteEvaluator.descCount` | 32 | 64 | `SpriteEvaluator.scala`, `VdpTop.scala` |
| `io.activeValid` | Vec(8, Bool) | Vec(32, Bool) | `SpriteEvaluator.scala` |
| `io.activeX` | Vec(8, UInt(10)) | Vec(32, UInt(10)) | `SpriteEvaluator.scala` |
| `io.activeY` | Vec(8, UInt(10)) | Vec(32, UInt(10)) | `SpriteEvaluator.scala` |
| `io.activeRow` | Vec(8, UInt(6)) | Vec(32, UInt(6)) | `SpriteEvaluator.scala` |
| `io.activePatternIdx` | Vec(8, UInt(4)) | Vec(32, UInt(4)) | `SpriteEvaluator.scala` |
| `io.activeFlipH/V` | Vec(8, Bool) | Vec(32, Bool) | `SpriteEvaluator.scala` |
| `io.activePaletteBank` | Vec(8, UInt(4)) | Vec(32, UInt(4)) | `SpriteEvaluator.scala` |
| `io.activePriority` | Vec(8, UInt(2)) | Vec(32, UInt(2)) | `SpriteEvaluator.scala` |
| `io.activeSizeSel` | Vec(8, UInt(2)) | Vec(32, UInt(2)) | `SpriteEvaluator.scala` |
| `io.activeAffineEnable` | Vec(8, Bool) | Vec(32, Bool) | `SpriteEvaluator.scala` |
| `io.activeMatrixA/B/C/D` | Vec(8, SInt(16)) | Vec(32, SInt(16)) | `SpriteEvaluator.scala` |
| `io.activeTransX/Y` | Vec(8, SInt(16)) | Vec(32, SInt(16)) | `SpriteEvaluator.scala` |
| `video.io.sprite*` inputs | 8 slots | 32 slots | `VdpTop.scala` |

### Derived width changes

- `descIdxBits`: `log2Up(64)` = 6 bits (was 5)
- `slotBits`: `log2Up(32)` = 5 bits (was 3)
- `extCount`: 64 − 4 = 60 bus-programmable slots (was 28)

## Timing / Memory Notes

- **Pass-1 scan duration:** Scanning 64 descriptors vs 32 doubles the FSM walk time. Current scan happens during H-blank. Must verify H-blank duration is sufficient for 64 range checks.
- **Pass-2 render bandwidth:** 32 sprites / line increases pixel-fill arbitration pressure. Current compositor sprite merge loop iterates 8 slots; expanding to 32 may affect critical path.
- **Descriptor storage:** 64 × 16-word Reg-Vec = 1,024 registers (was 512). No BSRAM change — still register-backed.
- **Bus address decoding:** Descriptor write address range expands. Register bus decode in `VdpTop` must map the extended range.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **LUT budget overrun** | Medium | High | Current ~10.2k LUT at sc70. +32 slots may add 200–400 LUT. Monitor in Gowin report; defer to non-default bitstream if needed. |
| **H-blank scan timing** | Medium | High | Verify 64-descriptor scan fits in H-blank. If not, pipeline or prefetch. |
| **Compositor critical path** | Medium | High | 32-slot merge loop may lengthen path. Consider priority encoder or tree reduction. |
| **Regression in existing sims** | Low | Medium | All 14 `SpriteEvaluatorSim` cases + `SpriteCapacitySim` + `SpriteFlipSim` + `AffineSpriteSim` must be rerun. |
| **MODE_SELECT adapter breakage** | Low | High | C64/ZX adapters wire 2 sprites. Ensure 32-slot wiring is backward-compatible (higher indices tied to disabled). |
| **Tile budget semantics** | Low | Low | Current `TileBudget = 34` may be too low for 32 sprites. Re-evaluate or make configurable. |

## Validation

### Simulation

- **`SpriteCapacityExpansionSim` (NEW):**
  - Case A: 64 descriptors, 32 on one line → 32 active + overflow flag
  - Case B: Exactly 32 enabled, no overflow
  - Case C: High-index slots 48..63 active
  - Case D: Mixed Y groups across 64 descriptors
  - Case E: Tile budget overflow with 32 large sprites
  - Case F: MODE_SELECT mode=1 (C64) with 2 sprites in 32-slot system

- **Regression suite:**
  - `SpriteEvaluatorSim` — 14 cases must all PASS
  - `SpriteCapacitySim` — 4 cases must all PASS
  - `SpriteFlipSim` — 12 cases must all PASS
  - `AffineSpriteSim` — must PASS
  - `ModeSelectSim` — must PASS
  - `VdpTopSim` — must PASS

### Hardware

- **Scenario:** Tang Nano 20K with 32 sprites visible on screen
- **Proof:** 30s RTSP capture, `analyze.py` reports `freeze=0`
- **Method:** Upload 64 descriptors via QSPI, verify 32 visible + correct overflow flag via READ_STATUS

## Audit Focus

- Does the widened `SpriteEvaluator` preserve exact behavior for the first 8 slots?
- Is the H-blank scan timing still safe at 64 descriptors?
- Does the compositor handle 32 sprite inputs without critical-path violations?
- Are all existing simulations rerun and passing?
- Is the resource delta documented and within budget?
- Are MODE_SELECT adapters (C64, ZX) compatible with the widened evaluator?

## Decomposition Recommendation

BronzeGate asked for recommended decomposition. Given the bounded nature, this can be one lane with two sub-slices:

1. **Slice A — visiblePerLine 8→32 + compositor widening:** Lower risk; primarily parameter + vector width changes. Sim + hardware proof.
2. **Slice B — descCount 32→64 + Pass-1 FSM restructure:** Higher risk; doubles descriptor scan and storage. Sim + hardware proof.

However, both slices touch the same file (`SpriteEvaluator.scala`) and are tightly coupled. A single bounded lane is likely more efficient unless LUT budget forces splitting.

**Recommended: Single lane**, with explicit stop-line at ~11.5k LUT (55% of Tang Nano 20K budget). If post-synthesis exceeds this, descCount expansion can be gated behind a compile-time parameter or deferred to a non-default bitstream.

## Exit Condition

- This task is done when `SpriteEvaluator` supports 32 visible sprites per line and 64 total descriptors, all existing simulations pass without regression, and a 30s hardware capture with 32 visible sprites shows `freeze=0`.
