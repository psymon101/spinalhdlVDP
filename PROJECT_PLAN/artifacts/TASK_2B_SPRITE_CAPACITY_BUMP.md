# Task 2b — Sprite Capacity Bump

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-05  
**Status:** Artifact open — awaiting CyanPeak audit  
**Governing directive:** BronzeGate #9279 (PM closeout: Task 2c CLOSED; activate Task 2b)

**Tied back to:** #9248 (V=32 projection), #9278 (CyanPeak audit PASS — Task 2c closure), #9279 (BronzeGate PM activation)

**Supersedes:** `TASK_2_SPRITE_CAPACITY_EXPANSION.md` v1.0-draft (2026-05-04). The original artifact assumed a wide parallel per-slot pipeline expansion; that architecture was retired in Task 2a/2c and replaced with the Sequential Scanline Rasterizer + RAM-based active list. This artifact documents the parameter-bump lane on the hardened substrate.

---

## Task

Execute the actual parameter bump to the hardened sprite substrate:
- `visiblePerLine` 8 → 32
- `descCount` 32 → 64

## Purpose

The substrate is now hardened (Task 2a renderer + Task 2c evaluator). Both V=32 synthesis experiments place with zero unplaced REGs and zero timing violations. The remaining work is to commit the parameter change, validate it, and prove it on hardware.

This unlocks honest Tier 2/3 adapter claims:

| Platform | Needs visible/line | Needs descCount | Status after 2b |
|---|---|---|---|
| NES | 8 | 64 | ✅ honest |
| PC Engine | 16 | 64 | ✅ honest |
| MSX2 | 8 | 32 | ✅ already honest; 64 gives headroom |
| Genesis | 20 | 80 | approximate (32/64 covers typical) |
| SNES | 32 | 128 | approximate (32 visible honest; 128 desc deferred) |
| Neo Geo | 96 | 380 | still gap; deferred |

## Scope

### In scope

1. **Parameter bump in `VdpTop.scala`**
   - `NUM_SLOTS` 8 → 32
   - `descCount` 32 → 64
   - `visiblePerLine` 8 → 32
   - `evalStart` timing: `hTotal - 45` → `hTotal - 77` (maintains 13-cycle completion margin: 64 descriptors + 13 = 77)
   - `lineRenderStart` at `hTotal - 12` — **no change needed** (evaluator still completes at `hTotal - 13` with the adjusted `evalStart`)

2. **Default parameter updates**
   - `SpriteEvaluator.scala` defaults: `descCount = 64`, `visiblePerLine = 32`
   - `SpriteRasterizer.scala` default: `visiblePerLine = 32`

3. **Test-bench parameter alignment**
   - `SpriteEvaluatorSim`: update hardcoded `descCount = 64`, `visiblePerLine = 32`
   - `SpriteCapacitySim`: update `D = 64`, `V = 32`; add cases E–F for 64/32 capacity
   - `SpriteRasterizerSim`: update `visiblePerLine = 32`
   - `SpriteSubstrateSim`: update `visiblePerLine = 32`
   - `SpriteFlipSim`: update params
   - `AffineSpriteSim`: update params
   - `VdpTopSim` and integration sims: verify with new defaults

4. **New capacity sim cases**
   - Case E: 64 descriptors, 32 on one line → 32 active, overflow flag set
   - Case F: 32 sprites × 64 px → cycle-budget overflow path triggers, tail sprites truncated

5. **Bit-identical regression proof at V=32 defaults**
   - All existing sprite sims must PASS with the new default parameters

6. **Synthesis / P&R proof**
   - Elaborate `TopTang20kHdmi(scenarioId=0)` with committed V=32 defaults
   - Must place and route with zero unplaced REGs, zero timing violations

7. **Hardware proof**
   - Flash an existing sprite scenario (e.g., sc20 or sc50) with V=32 defaults
   - 30s capture, `analyze.py` reports `freeze = 0`

### Out of scope

- Descriptor count beyond 64 (SNES 128, Neo Geo 380 remain honest gaps)
- Linked-list / DMA sprite loading
- Sprite masking, shrinking, scaling
- Compositor changes (sequential rasterizer + sprite line buffer already handles 32 slots without widening)
- Palette bank expansion
- Changes to `legacyIoCount` (stays 4)
- Any reopening of Task 2a/2c architecture

## Dependencies

- **Task 2a — Sprite Capacity Substrate Pre-Hardening** — ✅ DONE (`b558cee`)
- **Task 2c — Sprite Evaluator Hardening** — ✅ DONE (`b558cee`, CyanPeak #9278)
- **Sequential Scanline Rasterizer** — ✅ DONE
- **RAM-based active list** — ✅ DONE

## Interfaces / State

### Changed interfaces

| Interface | Before | After | Files |
|---|---|---|---|
| `VdpTop.NUM_SLOTS` | 8 | 32 | `VdpTop.scala` |
| `VdpTop.descCount` | 32 | 64 | `VdpTop.scala` |
| `VdpTop.visiblePerLine` | 8 | 32 | `VdpTop.scala` |
| `SpriteEvaluator` defaults | 32 / 8 | 64 / 32 | `SpriteEvaluator.scala` |
| `SpriteRasterizer` default | 8 | 32 | `SpriteRasterizer.scala` |
| `evalStart` timing | `hTotal - 45` | `hTotal - 77` | `VdpTop.scala` |

### No-change interfaces

- `SpriteRasterizer.cycleBudget` — stays 798 (32 sprites × 16 px = 512 cycles, well within budget)
- `SpriteRasterizer` FSM logic — no change needed (already parameterized by `visiblePerLine`)
- `SpriteEvaluator` Pass-1/Pass-2 algorithm — no change needed (already parameterized)
- Compositor / drain logic — no change needed (reads sprite line buffer, width-independent)
- Host bus protocol / descriptor word layout — unchanged
- Overflow flag semantics — unchanged
- Collision telemetry — unchanged

## Timing / Memory Notes

- **Pass-1 scan duration:** 64 cycles (one per descriptor). `evalStart` at `hTotal - 77` gives completion at `hTotal - 13`, same 13-cycle margin as the prior `hTotal - 45` with 32 descriptors.
- **Rasterizer cycle budget:** 798 cycles. 32 sprites × 16 px = 512 cycles + 32 search cycles = 544 cycles (fits with 31% margin). Worst case 32 × 64 px = 2,048 cycles → budget overflow path degrades gracefully.
- **Active-list RAM:** Grows from 8 × 128 bits → 32 × 128 bits = 4,096 bits. Same 9K BSRAM inference (depth 64 × 128). Zero FF cost.
- **Descriptor storage (`regX`, `regY`, etc.):** Grows from 28 → 60 extended slots. Each slot ~133 bits → 60 × 133 = 7,980 FFs. This is the primary FF scaling cost, but it's within the 15,552 FF budget (7,980 + rasterizer ~2,000 + other logic < 15,000).

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Desc storage FF scaling pushes over limit** | Low | High | V=32 synth already proven 7,726 FFs total at descCount=32. Desc storage at 64 = ~8,000 FFs. Total still ~11,000 FFs, well under 15,552. BrightForge #9274 already tested this. |
| **evalStart timing too late for 64-desc scan** | Low | High | Math: 64 cycles + 13 margin = 77. `hTotal - 77` is exact. No guesswork. |
| **Test-bench churn hides regression** | Medium | Medium | Update params mechanically. Keep all assertions and expected values identical. |
| **V=32 synthesis fails despite prior experiment** | Low | High | Prior experiment was on the exact same code path. Only parameter defaults differ. |

## Validation

### Simulation

- **All existing sprite sims must PASS with V=32/64 defaults:**
  - `SpriteEvaluatorSim` — 14 cases
  - `SpriteCapacitySim` — 4 legacy cases + 2 new cases (E, F)
  - `SpriteFlipSim` — Phase A + B
  - `AffineSpriteSim`
  - `ModeSelectSim`
  - `VdpTopSim`
  - `SpriteRasterizerSim` — 3 cases
  - `SpriteSubstrateSim` — Cases A-D

- **New `SpriteCapacitySim` cases:**
  - Case E: 64 descriptors on one line → 32 active, overflow set
  - Case F: 32 sprites × 64 px → budget overflow, tail truncated, overflow flag set

### Synthesis / P&R

- Elaborate `TopTang20kHdmi(scenarioId=0)` with committed V=32/64 defaults
- Must place and route with **zero unplaced REGs**
- Must have **zero timing violations**
- Record LUT/FF/BSRAM vs Task 2c V=8 baseline

### Hardware Proof

- Flash existing sprite scenario (sc20 or sc50) with V=32/64 defaults
- 30s capture, `analyze.py` reports `freeze = 0`

## Decomposition

1. **Checkpoint A — Parameter bump:** Change defaults in `VdpTop.scala`, `SpriteEvaluator.scala`, `SpriteRasterizer.scala`. Adjust `evalStart` timing.
2. **Checkpoint B — Test alignment:** Update all sim hardcoded params. Run regression.
3. **Checkpoint C — New capacity cases:** Add `SpriteCapacitySim` Cases E–F.
4. **Checkpoint D — Synthesis / P&R:** Gowin synthesis with committed V=32 defaults. Verify zero unplaced REGs.
5. **Checkpoint E — Hardware proof:** 30s capture, `freeze = 0`.

## Audit Focus

- Are the parameter changes minimal and mechanical?
- Does `evalStart` timing math preserve the completion margin?
- Do all existing sims PASS with new defaults without changing expected values?
- Do new capacity cases cover the 64/32 boundary?
- Does V=32 synthesis still place with zero unplaced REGs on committed defaults?
- Is hardware proof clean?

## Exit Condition

> This task is done when `visiblePerLine = 32` and `descCount = 64` are the committed defaults, all simulations PASS, V=32 synthesis places with zero unplaced REGs and zero timing violations, and a 30s hardware capture shows `freeze = 0`.

---

## Appendix: Prior Art

Original Task 2 artifact (`TASK_2_SPRITE_CAPACITY_EXPANSION.md`, 2026-05-04) assumed a parallel per-slot pipeline expansion and listed wide Vec output widening as in-scope. That architecture was retired in Task 2a after #9210 (51k-LUT failure). The Sequential Scanline Rasterizer + RAM-based active list (Task 2a/2c) replaces it entirely. Task 2b is now a bounded parameter-bump lane, not an architectural redesign.
