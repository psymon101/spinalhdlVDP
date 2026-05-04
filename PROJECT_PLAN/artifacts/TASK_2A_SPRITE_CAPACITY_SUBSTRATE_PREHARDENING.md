# Task 2a — Sprite Capacity Substrate Pre-Hardening

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Artifact drafted — pending audit  
**Governing directive:** BronzeGate #9212 (PM ruling: split Task 2 into 2a + 2b)

---

## Task

Sprite Capacity Substrate Pre-Hardening — redesign the sprite render pipeline so that a future `visiblePerLine` 8→32 / `descCount` 32→64 bump becomes a small parameter change rather than a structural rewrite.

## Purpose

The direct parameter bump to 64/32 descriptors/visible slots reproduces a 51,191-logic synthesis failure on Tang Nano 20K (2.47× over the 20,736 LUT limit). This is the same failure mode documented in prior attempt #8577. The substrate architecture — specifically per-slot replicated AffineSteppers and parallel compositor merge logic — cannot scale via direct parameter expansion.

Task 2a makes the substrate scalable. Task 2b (deferred) will execute the actual bump once the substrate is proven.

## Scope

### In scope

1. **Pipelined compositor merge (Priority A)**
   - Replace the `for (s <- 0 until NUM_SLOTS)` parallel priority merge in `VdpTop.scala:1234-1336` with a 2–4 cycle pipelined priority encoder
   - Current logic replicates per-slot pixel selection, priority comparison, and palette lookup for all 8 slots in one cycle
   - Target: reduce merge-path LUT by ~4–8× through pipelining
   - Latency budget: 2–4 cycles within the existing line-buffer fill window

2. **Shared AffineStepper (Priority B)**
   - Current: each visible slot (0..7) instantiates a dedicated `AffineStepper` for matrix address generation
   - Target: time-multiplex one (or a small bank of 2–4) `AffineStepper`(s) across all visible slots within a scanline
   - Affine parameters for each slot are latched; the shared stepper processes them sequentially during H-blank or early active line
   - Savings estimate: ~15–18k LUT at V=32 (the dominant cost driver in the 51k failure)

3. **Pattern-memory topology review (Priority C, conditional)**
   - Evaluate whether adjacent slot pairs can share a dual-port pattern Mem
   - Only pursue if (1) and (2) together do not bring the projected V=32 cost within the 11.5k LUT stop-line
   - Halving the per-slot Mem count saves wrapper LUT but is a more invasive change

4. **Bit-identical regression proof**
   - All existing `visiblePerLine = 8` sims must produce identical outputs after substrate changes
   - `SpriteEvaluatorSim` 14 cases, `SpriteCapacitySim`, `SpriteFlipSim`, `AffineSpriteSim`, `ModeSelectSim`, `VdpTopSim`

5. **Resource projection for V=32**
   - After 2a substrate changes, project the LUT/FF cost of bumping to `visiblePerLine = 32`
   - Target: projected cost ~+1,000–2,000 LUT (well under 11.5k stop-line)

### Out of scope (deferred to Task 2b)

- The actual `visiblePerLine` 8→32 parameter bump
- The actual `descCount` 32→64 parameter bump
- New sim cases for 32/64 capacity (those belong in Task 2b)
- Hardware proof with 32 visible sprites (Task 2b)
- Any other sprite enhancements (masking, shrinking, linked lists, DMA)

### Out of scope (not planned)

- Changes to sprite descriptor format or word layout
- Changes to `legacyIoCount`
- Changes to palette banking
- Changes to the MODE_SELECT adapter contract

## Dependencies

- **R2 Sprite Evaluator** — ✅ DONE (`9e07804`)
- **Task 28 Two-Pass Sprite Evaluator** — ✅ DONE
- **Task 37 Affine Sprite Path** — ✅ DONE (provides the `AffineStepper` to be shared)
- **Task 52 Sprite Flip** — ✅ DONE
- **MODE_SELECT infrastructure** — ✅ DONE (`ef4f8ce`)
- **Task 2 artifact** — ✅ DONE (`b42fcb9`, audit PASS #9208)
- **Blocker #9210 analysis** — ✅ DONE (BrightForge root-cause analysis)

## Interfaces / State

### Changed interfaces

| Interface | Change | Files |
|---|---|---|
| Compositor sprite merge | Parallel → pipelined (2–4 cycles) | `VdpTop.scala` |
| AffineStepper instantiation | Per-slot → shared (1 or 2–4 bank) | `VdpTop.scala`, possibly new `SharedAffineStepper.scala` |
| Pattern Mem | Per-slot → potentially paired dual-port | `VdpTop.scala` (conditional) |
| Line buffer timing | Unchanged fill window; pipelined merge adds 2–4 cycle latency at start of line | `VdpTop.scala` |

### No-change interfaces

The following must remain functionally identical:
- `SpriteEvaluator.io` (all outputs: `activeValid`, `activeX/Y`, `activeRow`, `activePatternIdx`, etc.)
- `SpriteEvaluator.descCount` and `visiblePerLine` parameters
- Register bus decode for descriptor writes
- `io.sprite0/1X/Y/Enabled/PatternIdx` legacy inputs
- Overflow flag semantics

## Timing / Memory Notes

- **Compositor pipeline latency:** 2–4 cycles must fit within the existing line buffer fill window. Current fill starts at a fixed pixel offset; pipeline delay shifts valid pixel output by 2–4 cycles but does not affect the overall line timing if absorbed into the existing front-porch.
- **AffineStepper time-multiplexing:** If one shared stepper serves 8 slots, it needs 8 sequential operations. At 1080p pixel clock (~74 MHz, ~13.5 ns/cycle), 8 cycles = ~108 ns. H-blank duration is ~280 pixels × 13.5 ns = ~3.78 µs. Ample headroom.
- **Pattern Mem sharing:** Dual-port Mem allows two slots to read simultaneously. If pairing is used, 4 dual-port Mems serve 8 slots. No bandwidth change.
- **BSRAM impact:** None expected. Current pattern Mems are already inferred as BSRAM. Sharing may actually reduce total BSRAM count.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Pipeline latency breaks line timing** | Low | High | Verify fill window absorbs 2–4 cycle offset. Sim assertion on line-start pixel validity. |
| **Shared AffineStepper breaks affine sprite accuracy** | Low | High | Latch slot parameters; stepper state is per-invocation. Regression sim with affine sprites must PASS bit-identical. |
| **Pipelined merge breaks priority ordering** | Low | High | Priority encoder must preserve stable ordering. Test with overlapping sprites at multiple priorities. |
| **Resource savings insufficient** | Medium | High | Measure after each sub-slice. If (1)+(2) doesn't bring V=32 within 2k LUT projected cost, pursue (3) pattern Mem sharing. |
| **Regression in existing scenes** | Low | Medium | Full regression suite mandatory after each commit. |

## Validation

### Simulation

- **All existing sprite sims must PASS bit-identical:**
  - `SpriteEvaluatorSim` — 14 cases
  - `SpriteCapacitySim` — 4 cases
  - `SpriteFlipSim` — 12 cases
  - `AffineSpriteSim` — must PASS
  - `ModeSelectSim` — must PASS
  - `VdpTopSim` — must PASS

- **New `SpriteSubstrateSim` (NEW):**
  - Case A: 8 overlapping sprites with varying priorities — verify identical pixel output before/after substrate change
  - Case B: Affine sprites at slots 0, 2, 4, 6 — verify identical transform output
  - Case C: Line-timing assertion — first valid pixel within expected cycle window
  - Case D: Overflow flag behavior unchanged with mixed enabled/disabled descriptors

### Resource projection (no hardware required for 2a)

- Elaborate `TopTang20kHdmi(scenarioId=0)` with `visiblePerLine = 8` after substrate changes — verify no resource regression
- Elaborate with `visiblePerLine = 32` (parameter-only bump on hardened substrate) — record projected LUT/FF/BSRAM
- Target: V=32 projection ≤ ~11.5k LUT total (current ~10.2k + ~1.3k headroom)

## Hardware Proof (Task 2a — minimal)

Task 2a does not require a full 32-sprite hardware proof. The hardware proof is deferred to Task 2b.

Minimal hardware validation for 2a:
- Re-flash an existing scenario (e.g., sc20 or sc50) that uses sprites
- 30s capture, `analyze.py` reports `freeze=0`
- Confirms substrate changes did not break existing 8-sprite behavior on hardware

## Decomposition

BronzeGate #9212 requires a bounded substrate lane. Task 2a is already narrowly scoped. Recommended single lane with two checkpoint commits:

1. **Checkpoint 1 — Pipelined compositor merge:** Implement and verify. Run regression. Measure resource.
2. **Checkpoint 2 — Shared AffineStepper:** Implement and verify. Run regression. Measure resource. Final V=32 projection.

If Checkpoint 1 alone achieves sufficient savings, Checkpoint 2 may be optional (but likely needed for the full V=32 target).

## Audit Focus

- Does the pipelined merge preserve exact pixel output for all existing sim cases?
- Does the shared AffineStepper preserve exact transform output for affine sprites?
- Is the line-timing assertion satisfied?
- Does the V=32 resource projection fall within the 11.5k LUT stop-line?
- Are all existing simulations rerun and passing?
- Is the substrate change backward-compatible with MODE_SELECT adapters?

## Exit Condition

> This task is done when the sprite render substrate has been restructured (pipelined compositor merge + shared AffineStepper) such that a parameter-only bump to `visiblePerLine = 32` projects within the Tang Nano 20K LUT budget, all existing `visiblePerLine = 8` simulations pass bit-identically, and an existing sprite scenario re-proven on hardware shows `freeze = 0`.

---

## Appendix: Blocker Context

**BrightForge #9210** attempted the direct bump per original Task 2 artifact (`b42fcb9`). All 14 sims and regressions PASS, but Gowin synthesis fails with 51,191 logic units vs 20,736 limit. Root cause: per-slot AffineStepper replication + parallel compositor merge. Same failure mode as prior #8577 attempt.

**CyanPeak #9213** reviewed and confirmed the blocker is valid. Audit #9208 underestimated LUT cost by two orders of magnitude due to stale assumptions not accounting for Task 37 and Task 28 complexity.

**BronzeGate #9212** ruled to open Task 2a (substrate pre-hardening) and defer Task 2b (capacity bump).
