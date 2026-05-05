# Task 2c — Sprite Evaluator Hardening

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-05  
**Status:** Artifact open — awaiting CyanPeak audit  
**Governing directive:** BronzeGate #9252 (PM closeout: Task 2a accepted CLOSED; activate Task 2c)

**Tied back to:** #9248 (V=32 projection), #9250 (CyanPeak audit PASS — Task 2a closure), #9251 (CoralReef closeout), #9252 (BronzeGate PM activation)

---

## Task

Remove the `SpriteEvaluator` `active*` Vec FF-density wall so that `visiblePerLine = 32` / `descCount = 64` can physically place and route on Tang Nano 20K.

## Purpose

Task 2a hardened the renderer substrate (sequential rasterizer + sprite line buffer). The V=32 projection now shows 15,930 logic (77%) and 11,729 LUT — the renderer is no longer the scaling bottleneck. The new bottleneck is the `SpriteEvaluator` per-slot `active*` Vec register duplication:

| Field | Bits | FFs @ V=32 |
|---|---|---|
| activeValid | 1 | 32 |
| activeX | 10 | 320 |
| activeY | 10 | 320 (dead — no consumers after Task 2a Step 2) |
| activeRow | 6 | 192 |
| activePatternIdx | 4 | 128 |
| activeAffineEnable | 1 | 32 |
| activeMatrixA/B/C/D | 64 | 2,048 |
| activeTransX/Y | 32 | 1,024 |
| activeFlipH/V | 2 | 64 |
| activePaletteBank | 3 | 96 |
| activePriority | 2 | 64 |
| activeSizeSel | 2 | 64 |
| activeBppSel | 2 | 64 |
| **Total** | **139** | **~4,384** |

At V=32, Gowin reports **4,209 unplaced REGs** (94% CLS utilization). The `active*` Vecs are a second copy of the selected descriptor subset. The descriptor storage itself (`regX`, `regY`, etc. for 28 extended slots) is ~3,724 FFs and is required; the active-list duplication is the eliminable cost.

## Scope

### In scope

1. **Active-list RAM inside SpriteEvaluator**
   - Replace the 16 parallel `active*Reg` Vecs with a single `Mem(Bits(slotPackedBits), visiblePerLine)`
   - During Pass 1, write selected descriptors sequentially into the RAM at indices 0, 1, 2, … — producing a **compacted list with no gaps**
   - Expose a read port: `activeReadAddr : in UInt(slotBits)` + `activeReadData : out Bits(slotPackedBits)`
   - Expose `activeCount : out UInt` (currently internal only)
   - Pack order must be documented so rasterizer unpack is unambiguous

2. **SpriteRasterizer interface narrowing**
   - Remove the wide `active*` Vec inputs (~4,448 wires)
   - Add `activeReadAddr` output + `activeReadData` input + `activeCount` input
   - Search FSM simplified: walks from `activeCount-1` down to 0; every read is valid by construction (no `activeValid` check needed)
   - Render FSM `ST_IDLE` affine init must use latched `slotMatrixBR` / `slotTransXR` (already latched in `SF_LOAD`) instead of combinational `io.activeMatrixB(sIdx)`

3. **Dead-code removal**
   - `activeY` output from `SpriteEvaluator` — no consumer since Task 2a Step 2 removed the parallel per-slot pipeline that used it
   - Internal `activeYReg` and `io.activeY` wiring

4. **V=8 bit-identical regression**
   - All existing sims must PASS unchanged
   - `VdpTopSim` 8/8 regression bit-identical

5. **V=32 synthesis + P&R proof**
   - Parameter bump to `visiblePerLine = 32` / `descCount = 64` must complete P&R with **zero unplaced REGs**
   - Target: place within Tang Nano 20K LUT/FF/BSRAM limits

### Out of scope

- Changes to descriptor format, word layout, or host bus protocol
- Changes to `descCount` / `legacyIoCount` semantics
- Changes to Pass 1 scan algorithm or tile-budget counter
- Changes to `SpriteRasterizer` draw algorithm, cycle budget, or pixel pipeline
- Changes to `VdpTop` compositor, palette, or collision telemetry
- New sim cases for 32/64 capacity (deferred to Task 2b)
- Hardware proof with 32 visible sprites (deferred to Task 2b)

## Dependencies

- **Task 2a — Sprite Capacity Substrate Pre-Hardening** — ✅ DONE (`ab687d1`, CyanPeak #9250)
- **Sequential Scanline Rasterizer** — ✅ DONE (`45369b0`–`983d1db`)
- **SpriteEvaluator** — baseline (unchanged by Task 2a)

## Interfaces / State

### Changed interfaces

| Interface | Change | Files |
|---|---|---|
| Evaluator → Rasterizer data path | Wide Vec (`active*`) × visiblePerLine → RAM read port (`addr` + `data`) + `activeCount` | `SpriteEvaluator.scala`, `SpriteRasterizer.scala` |
| Evaluator outputs | `activeY` removed; `activeCount` added; all `active*` Vec outputs removed | `SpriteEvaluator.scala` |
| Rasterizer inputs | `active*` Vecs removed; `activeReadAddr`, `activeReadData`, `activeCount` added | `SpriteRasterizer.scala` |
| VdpTop wiring | Evaluator-Rasterizer link changed from for-loop Vec wiring to RAM-port wiring | `VdpTop.scala` |

### No-change interfaces

- `SpriteEvaluator.io` descriptor input side (`descX`, `descY`, `descEnabled`, `descPatternIdx`)
- `SpriteEvaluator.io` bus-write port (`busSlot`, `busWord`, `busData`, `busWr`)
- `SpriteEvaluator.io` trigger side (`evalLine`, `evalStart`)
- `SpriteEvaluator.io` overflow flag semantics
- `SpriteRasterizer.io` pattern Mem, drain, trigger, and status sides
- Register bus decode for descriptor writes
- `io.sprite0/1X/Y/Enabled/PatternIdx` legacy inputs
- Priority ordering: lowest descriptor index rendered first (back-to-front)

## Timing / Memory Notes

- **RAM inference:** 32 × 128 bits = 4,096 bits. Gowin will infer this as:
  - **Preferred:** one 9K BSRAM block (SDP/SDPB, simple dual-port: 1 write port for Pass 1, 1 read port for rasterizer). Zero FFs, zero LUTs, ~1 BSRAM.
  - **Fallback:** distributed RAM (LUTRAM) at ~256 LUTs if BSRAM inference fails. Still zero FFs.
- **Read latency:** combinational (addr → data in same cycle). Rasterizer search FSM already has a 1-cycle `SF_LOAD` latch state; combinational read fits cleanly.
- **Write/read conflict:** Pass 1 writes during H-blank; rasterizer reads during active display. No overlap. Simple dual-port is sufficient.
- **ActiveCount path:** `activeCount` is a 6-bit register output from evaluator to rasterizer. Set at end of Pass 1, stable for entire line. No timing risk.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **BSRAM inference fails; LUTRAM cost pushes LUT over limit** | Low | High | 4K bits is well within BSRAM min config (9K block, 64×128). If inference fails, force BSRAM via Spinal `addAttribute` or explicit `Ram_1w_1r` primitive. |
| **Rasterizer search FSM logic error with compacted list** | Low | High | Search walks `activeCount-1` → `0` instead of `visiblePerLine-1` → `0`. Existing `slotZeroDone` logic handles termination. Unit sim covers all edge cases. |
| **Affine init reads wrong slot due to slotIdx decrement timing** | Low | High | Render FSM `ST_IDLE` must use latched `slotMatrixBR` etc. (already latched in `SF_LOAD`), not combinational `io.active*(sIdx)`. Verify in `AffineSpriteSim` and `SpriteSubstrateSim`. |
| **Test-bench churn hides a real regression** | Medium | Medium | Update tests mechanically (replace `dut.io.activeX(s)` with RAM read helper). Do NOT change test assertions or expected values. |
| **V=8 timing violation from RAM read path** | Low | Medium | Combinational BSRAM read is fast (<1 ns). Existing `SF_LOAD` latches into registers; no new long combinational path. |

## Validation

### Simulation

- **All existing sprite sims must PASS bit-identical:**
  - `SpriteEvaluatorSim` — 14 cases (updated for RAM interface)
  - `SpriteCapacitySim` — 4 cases (updated for RAM interface)
  - `SpriteFlipSim` — 12 cases (updated for RAM interface)
  - `AffineSpriteSim` — must PASS (critical for affine init latch correctness)
  - `ModeSelectSim` — must PASS
  - `VdpTopSim` — 8/8 regression bit-identical

- **`SpriteRasterizerSim` — must PASS (updated for RAM interface)**
  - Case 1: 1-sprite flat render
  - Case 2: empty active list (`activeCount = 0`)
  - Case 3: 2-sprite overlap priority

- **`SpriteSubstrateSim` — Cases A–D must PASS (updated for RAM interface)**

### Synthesis / P&R

- **V=8 (`visiblePerLine = 8`, `descCount = 32`):**
  - Elaborate `TopTang20kHdmi(scenarioId=0)` — record LUT/FF/BSRAM
  - Must place and route with zero timing violations
  - Target: LUT within ±100 of pre-Task-2c baseline (allow small LUTRAM penalty)

- **V=32 (`visiblePerLine = 32`, `descCount = 64`):**
  - Parameter-only bump on hardened evaluator
  - Must place and route with **zero unplaced REGs**
  - Must fit within Tang Nano 20K LUT/FF/BSRAM limits
  - Target: zero unplaced REGs, CLS < 90%

### Hardware Proof (Task 2c — minimal)

Task 2c does not require a full 32-sprite hardware proof. The hardware proof is deferred to Task 2b.

Minimal hardware validation:
- Re-flash an existing sprite scenario (e.g., sc20 or sc50) after evaluator changes
- 30s capture, `analyze.py` reports `freeze=0`
- Confirms V=8 behavior is preserved on hardware

## Decomposition

BronzeGate #9252 authorized a bounded lane. Recommended checkpoints:

1. **Checkpoint A — Design packet (this artifact):** RAM architecture, pack/unpack layout, interface change list, test-bench impact, FF savings estimate. *(CoralReef — this artifact)*
2. **Checkpoint B — Audit:** CyanPeak verifies RAM architecture soundness, pack order correctness, rasterizer FSM safety, and test-bench coverage before coding.
3. **Checkpoint C — Evaluator RAM + exposed read port:** `SpriteEvaluator.scala` refactored; `SpriteEvaluatorSim` updated; unit sim PASS.
4. **Checkpoint D — Rasterizer narrow interface:** `SpriteRasterizer.scala` updated to RAM read port; `SpriteRasterizerSim` updated; unit sim PASS.
5. **Checkpoint E — Integration + VdpTop wiring:** `VdpTop.scala` wiring updated; `SpriteSubstrateSim` + `SpriteCapacitySim` + `SpriteFlipSim` + `AffineSpriteSim` updated; all unit sims PASS.
6. **Checkpoint F — V=8 regression + V=32 P&R:** `VdpTopSim` 8/8 bit-identical; V=32 synthesis zero unplaced REGs.
7. **Checkpoint G — Hardware proof:** Existing sprite scenario re-flashed, 30s capture, `freeze=0`.

## Audit Focus

- Does the RAM pack/unpack layout preserve every field exactly?
- Is the rasterizer search FSM safe for `activeCount = 0` and `activeCount = visiblePerLine`?
- Does the render FSM affine init use latched registers (not stale combinational reads)?
- Are all existing simulations rerun and passing with identical expected values?
- Does V=32 P&R show zero unplaced REGs?
- Is V=8 LUT impact minimal (±100 LUT)?
- Is `activeY` truly dead — no hidden consumer in test benches or other modules?

## Exit Condition

> This task is done when the `SpriteEvaluator` `active*` Vec FF-density wall is eliminated (replaced by RAM-based active-list storage), `visiblePerLine = 32` / `descCount = 64` places and routes on Tang Nano 20K with **zero unplaced REGs**, all existing `visiblePerLine = 8` simulations pass bit-identically, and an existing sprite scenario re-proven on hardware shows `freeze = 0`.

---

## Appendix: Root-Cause Context

**BrightForge #9248** produced the V=32 projection after Task 2a substrate hardening:
- Renderer substrate: 15,930 logic (77%), 11,729 LUT ✅
- SpriteEvaluator `active*` Vec: 4,209 unplaced REGs ❌
- CLS utilization: 94% (wall is register density, not LUT count)

**CyanPeak #9250** ruled this bottleneck is outside Task 2a scope and recommends Task 2c.

**BronzeGate #9252** accepted Task 2a closure and activated Task 2c with bounded evaluator-side focus.

## Appendix: Pack Layout (draft — to be finalized in Checkpoint C)

Proposed 128-bit packed word per active slot (MSB → LSB):

```
[127:112] matrixA   (16)
[111: 96] matrixB   (16)
[ 95: 80] matrixC   (16)
[ 79: 64] matrixD   (16)
[ 63: 48] transX    (16)
[ 47: 32] transY    (16)
[ 31: 22] x         (10)
[ 21: 16] row       (6)
[ 15: 12] patIdx    (4)
[ 11:  9] paletteBank (3)
[  8:  7] priority  (2)
[  6:  5] sizeSel   (2)
[  4:  3] bppSel    (2)
[  2]     affineEnable (1)
[  1]     flipH     (1)
[  0]     flipV     (1)
```

Total: 128 bits. `activeY` is omitted (dead). `activeValid` is implicit (all RAM entries are valid).

This layout may be adjusted during implementation for routing convenience; the artifact only requires that every field is preserved exactly and the layout is documented.
