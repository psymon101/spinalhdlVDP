# Task 2a — Sprite Capacity Substrate Pre-Hardening

**Version:** 2.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-05  
**Status:** Artifact reshaped — Sequential Scanline Rasterizer direction  
**Governing directive:** BronzeGate #9235 (PM ruling: reshape to Sequential Scanline Rasterizer per convergent diagnosis #9233/#9234)

**Supersedes:** v1.0-draft (2026-05-04). The parallel per-slot substrate path (Checkpoint 2 shared AffineStepper) is **retired**; see Appendix: Retired Path for history.

---

## Task

Sprite Capacity Substrate Pre-Hardening — redesign the sprite render pipeline so that a future `visiblePerLine` 8→32 / `descCount` 32→64 bump becomes a small parameter change rather than a structural rewrite.

## Purpose

The direct parameter bump to 64/32 descriptors/visible slots reproduces a 51,191-logic synthesis failure on Tang Nano 20K (2.47× over the 20,736 LUT limit). This is the same failure mode documented in prior attempt #8577. The substrate architecture — specifically per-slot replicated AffineSteppers and parallel compositor merge logic — cannot scale via direct parameter expansion.

Task 2a makes the substrate scalable. Task 2b (deferred) will execute the actual bump once the substrate is proven.

## Reshape History

**2026-05-05 — Direction changed from parallel substrate to Sequential Scanline Rasterizer.**

BrightForge implemented Checkpoint 2 (shared AffineStepper + per-slot incremental state) per v1.0 artifact. V=8 regression passed bit-identically, but the V=32 projection was 22,086 logic / 20,736 limit (107%, +1,350 over). Worse, a V=16 synthesis experiment also **failed place-and-route** (#9231) with 17,167 logic (83%) but CLS 94% — 1,190 unplaced REGs. The per-slot state register density (uState/vState + pipeline regs) exhausts CLS sites before LUT logic does.

Paired diagnosis converged:
- CyanPeak #9230 independent pass → Sequential Scanline Rasterizer
- CoralReef #9228 independent pass → commit partial + reshape Task 2c
- BrightForge #9231 V=16 discriminator → invalidates V=16 fallback
- CyanPeak #9233 convergence packet → full convergence on Sequential Rasterizer
- CoralReef #9234 convergence packet → full convergence on Sequential Rasterizer

BronzeGate #9235 authorized the reshape. BrightForge #9236 delivered the design packet.

## Scope

### In scope

1. **Sequential Scanline Rasterizer (Priority A)**
   - Replace the parallel per-slot pixel-generation loop in `VdpTop.scala` (~lines 1239–1410) with a **single sequential drawing pipeline**
   - The drawer iterates over the active-sprite list (emitted by SpriteEvaluator) and paints pixels one-at-a-time into a dedicated **sprite line buffer**
   - One hitbox evaluator, one shared AffineStepper (or incremental adder), one pixel unpack path — **not replicated per slot**
   - Cycle budget: ~800 cycles/line at 25.2 MHz; 32 sprites × 16 px = 640 cycles (fits with 20% margin)
   - Overflow: cycle-budget exhaustion sets `spriteOverflow` (same flag as count overflow); tail sprites not painted

2. **Sprite line buffer (single-port BSRAM)**
   - New BSRAM-backed line buffer for sprite pixels only, double-buffered (ping-pong) matching existing `LineBuffer` semantics
   - Fill phase (line N): sequential drawer writes opaque sprite pixels
   - Drain phase (line N+1): compositor reads sprite pixel + priority + palette bank alongside background layers
   - Single-port (SDPB) sufficient because fill and drain are strictly separated by the swap boundary

3. **Bit-identical regression proof**
   - All existing `visiblePerLine = 8` sims must produce identical outputs after substrate changes
   - `SpriteEvaluatorSim` 14 cases, `SpriteCapacitySim`, `SpriteFlipSim`, `AffineSpriteSim`, `ModeSelectSim`, `VdpTopSim`

4. **Resource projection for V=32**
   - After substrate changes, project the LUT/FF/BSRAM cost of bumping to `visiblePerLine = 32`
   - Target: projected cost constant (~+500–1,000 LUT vs Checkpoint 1 V=8 baseline) regardless of `visiblePerLine`, because the drawer logic does not scale with slot count

5. **Overflow / `sprite_limit` semantic mapping**
   - Document how the existing `spriteOverflow` flag and `sprite_limit` field interact with the new cycle-budget overflow path
   - Adapter-index table: per-platform typical vs worst-case sprite mix and whether it fits the 800-cycle drawer budget

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
- Multi-port or dual-clock sprite line buffer (single-port SDPB is sufficient per design packet #9236)

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
| Sprite pixel generation | Parallel per-slot loop → sequential drawer FSM | `VdpTop.scala` |
| Sprite compositor merge | Parallel last-hit-wins mux → sprite line buffer readout (already sequential in drain phase) | `VdpTop.scala` |
| AffineStepper instantiation | Per-slot → one shared stepper used by drawer | `VdpTop.scala` |
| Pattern Mem | Per-slot replicated → one shared read port (broadcast-write contents unchanged) | `VdpTop.scala` |
| Line buffer timing | Unchanged fill/drain window; drawer fill happens during line N, drain during line N+1 | `VdpTop.scala`, possibly new `SpriteLineBuffer.scala` |

### No-change interfaces

The following must remain functionally identical:
- `SpriteEvaluator.io` (all outputs: `activeValid`, `activeX/Y`, `activeRow`, `activePatternIdx`, etc.)
- `SpriteEvaluator.descCount` and `visiblePerLine` parameters
- Register bus decode for descriptor writes
- `io.sprite0/1X/Y/Enabled/PatternIdx` legacy inputs
- Overflow flag semantics (`spriteOverflow` sticky bit, cleared via write-1-to-clear at 0x0320)
- Priority ordering: lowest descriptor index rendered first (back-to-front), highest index wins on overlap

## Timing / Memory Notes

- **Drawer cycle budget:** At 25.2 MHz pixel clock, one 640×480 line = 800 cycles (640 active + 160 blank). The sequential drawer must complete its fill pass within this window. Per-sprite cost ≈ 4 cycles overhead + width cycles. 32 sprites × 16 px = 640 cycles (fits). 32 sprites × 64 px = 2,176 cycles (does not fit; degrades via overflow flag).
- **AffineStepper usage:** The shared stepper initializes per-sprite affine state at line start (2 multiplies + add, ~1 cycle each after pipeline fill). During the draw loop, incremental adders update uState/vState per pixel (~1 cycle, pipelined with pattern Mem read).
- **Pattern Mem:** One read port shared across all slots sequentially. Broadcast writes remain unchanged. BSRAM count: 1 pattern Mem (unchanged depth) + 2 sprite line buffers (ping-pong) = 3 BSRAM vs current 8 pattern Mems + line buffer infrastructure.
- **BSRAM impact:** Net reduction expected. Current design uses 8 per-slot pattern Mems; sequential design uses 1 shared pattern Mem + 2 sprite line-buffer banks.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Drawer cycle budget exceeded on typical workloads** | Low | High | Cycle math shows 32×16 px fits with 20% margin. Typical retro sprite mixes are 8–16 px. Budget-exhaustion path degrades gracefully via `spriteOverflow`. |
| **Sequential drawer breaks affine sprite accuracy** | Low | High | Reuse Checkpoint 2's proven recurrence math (`u_(x+1) = u_x + matrixA`). Regression sim with affine sprites must PASS bit-identical. |
| **Priority ordering differs from parallel merge** | Low | High | Drawer iterates highest-index slot first (reverse of evaluator order), so last-write-wins matches existing back-to-front semantic exactly. |
| **Sprite line buffer clear / stale pixel bleed** | Low | High | Ping-pong swap at line boundary guarantees clean bank. Drain reads only from committed bank; fill writes only to preparing bank. |
| **Resource savings insufficient** | Low | High | Sequential architecture collapses per-slot replication entirely. Expected +500–1,000 LUT vs V=8 baseline, flat regardless of `visiblePerLine`. |
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
  - Case C: Line-timing assertion — drawer completes before line swap
  - Case D: Overflow flag behavior with mixed enabled/disabled descriptors
  - Case E: Cycle-budget exhaustion — deliberately over-subscribe drawer and verify `spriteOverflow` + truncation

### Resource projection (no hardware required for 2a)

- Elaborate `TopTang20kHdmi(scenarioId=0)` with `visiblePerLine = 8` after substrate changes — verify no resource regression
- Elaborate with `visiblePerLine = 32` (parameter-only bump on hardened substrate) — record projected LUT/FF/BSRAM
- Target: V=32 projection flat vs V=8 (~+0–1,000 LUT), because sequential drawer does not scale with slot count

## Hardware Proof (Task 2a — minimal)

Task 2a does not require a full 32-sprite hardware proof. The hardware proof is deferred to Task 2b.

Minimal hardware validation for 2a:
- Re-flash an existing scenario (e.g., sc20 or sc50) that uses sprites
- 30s capture, `analyze.py` reports `freeze=0`
- Confirms substrate changes did not break existing 8-sprite behavior on hardware

## Decomposition

BronzeGate #9235 authorized a reshaped bounded lane. Recommended checkpoints:

1. **Checkpoint A — Design packet:** Cycle budget, state-machine decomposition, overflow semantics, port choice (single-port), resource estimate. (BrightForge #9236 — DONE)
2. **Checkpoint B — Audit:** CyanPeak verifies cycle budget, state-machine soundness, and overflow mapping before coding deepens.
3. **Checkpoint C — Implementation + sim:** Sequential drawer FSM, sprite line buffer, integration with existing compositor. Regression suite PASS bit-identical.
4. **Checkpoint D — Resource projection:** Gowin synthesis at V=8 and V=32 to confirm flat scaling curve.
5. **Checkpoint E — Hardware proof:** Existing sprite scenario re-flashed, 30s capture, `freeze=0`.

## Audit Focus

- Does the sequential drawer preserve exact pixel output for all existing sim cases?
- Does the shared affine recurrence preserve exact transform output for affine sprites?
- Is the drawer cycle budget satisfied for typical workloads (32×16 px)?
- Does the V=32 resource projection show flat scaling (not linear in slot count)?
- Are all existing simulations rerun and passing?
- Is the substrate change backward-compatible with MODE_SELECT adapters?
- Is the overflow / `sprite_limit` semantic mapping sound and documented?

## Exit Condition

> This task is done when the sprite render substrate has been restructured as a **Sequential Scanline Rasterizer** such that a parameter-only bump to `visiblePerLine = 32` projects within the Tang Nano 20K LUT budget with flat scaling (drawer logic does not replicate per slot), all existing `visiblePerLine = 8` simulations pass bit-identically, and an existing sprite scenario re-proven on hardware shows `freeze = 0`.

---

## Appendix: Blocker Context

**BrightForge #9210** attempted the direct bump per original Task 2 artifact (`b42fcb9`). All 14 sims and regressions PASS, but Gowin synthesis fails with 51,191 logic units vs 20,736 limit. Root cause: per-slot AffineStepper replication + parallel compositor merge. Same failure mode as prior #8577 attempt.

**CyanPeak #9213** reviewed and confirmed the blocker is valid. Audit #9208 underestimated LUT cost by two orders of magnitude due to stale assumptions not accounting for Task 37 and Task 28 complexity.

**BronzeGate #9212** ruled to open Task 2a (substrate pre-hardening) and defer Task 2b (capacity bump).

## Appendix: Retired Path — Parallel Substrate (Checkpoint 2)

**Status:** DISCARDED — do not commit.

BrightForge implemented Checkpoint 2 (shared AffineStepper + per-slot incremental state) on top of Checkpoint 1. Results:
- V=8: bit-identical regression PASS; +1,551 logic vs Checkpoint 1
- V=32 projection: 22,086 logic / 20,736 limit (107% over)
- V=16 experiment: 17,167 logic (83%) but **CLS 94%** → P&R failed with 1,190 unplaced REGs

**Why it was retired:** The per-slot state register density (uState 32-bit + vState 32-bit + pipeline regs ≈ 74 FFs/slot) exhausts CLS sites faster than logic budget would suggest. Any architecture that replicates per-slot pixel-generation state is non-viable for V>8 on GW2A-18. The shared AffineStepper solved the multiplier wall but not the replication wall.

**Mineable assets:** The shared H-blank init FSM pattern and the pre-rolled affine recurrence math (`u_(x+1) = u_x + matrixA`) are correct and can be reused as the line-start coordinate initializer in the sequential rasterizer.

**Authority chain:** #9224 (plan) → #9226 (results) → #9228 (CoralReef diagnosis) → #9230 (CyanPeak diagnosis) → #9231 (V=16 discriminator) → #9233/#9234 (convergence) → #9235 (PM ruling) → #9236 (design packet).
