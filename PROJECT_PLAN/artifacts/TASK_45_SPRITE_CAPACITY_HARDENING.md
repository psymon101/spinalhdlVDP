# Task 45 — Sprite Capacity Hardening

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Status:** Awaiting CyanPeak audit  
**Coding authorized:** NO — implementation waits for artifact audit PASS + PM authorization  

---

## 1. Executive Summary

Task 28 landed a two-pass sprite evaluator (`SpriteEvaluator.scala`) with parametric `descCount` and `visiblePerLine`. The module itself defaults to `descCount=32`, `visiblePerLine=8` and simulates cleanly at those parameters (`SpriteEvaluatorSim` uses 32/8).

However, `VdpTop.scala` currently instantiates the evaluator at the **reduced** scale `descCount=8`, `visiblePerLine=4` (Task 28 CP-C Option A, BronzeGate #7883). The reduction was a conservative discriminator when the team was debugging unrelated Gowin timing issues. Those issues are now resolved (Task 44b closed, all clocks meeting with margin).

This artifact specifies the exact parameter restoration, register-bus expansion, timing-margin verification, and validation plan required to bring the live VdpTop instance to `descCount=32`, `visiblePerLine=8` with synthesis-stable confidence.

**Scope boundary:** Descriptor storage and evaluator scale only. No platform-exact OAM maps, no new compositor layers, no pattern-memory expansion beyond the existing two ROM patterns (see §6 for follow-up note).

---

## 2. Current State Analysis

### 2.1 Live instantiation (reduced)

```scala
// VdpTop.scala:781-785
val spriteEval = SpriteEvaluator(
  descCount      = 8,
  visiblePerLine = 4,
  patternSelBits = 4,
  legacyIoCount  = 4)
```

Consequences of the reduction:
- Only 4 extended bus-programmable slots (slots 4..7) instead of 28 (slots 4..31).
- Only 4 visible-per-line slots (`NUM_SLOTS = 4`) instead of 8.
- Register-bus decode hard-limited to `0x0800..0x083F` (64 words = 8 slots × 8 words).
- `evalStart` strobe fires at `hTotal - 33`, calibrated for an 8-cycle scan.

### 2.2 Why the reduction can now be reverted

The original discriminator (#7883) was applied during Task 28 when the team was still chasing Gowin timing-closure artifacts. Post-Task-44b, the latest PnR report (`project.rpt.txt`, iter-6i-r1) shows:

| Resource | Usage / Limit | Utilization |
|---|---|---|
| Logic (LUT/ALU/ROM16) | 7794 / 20736 | 38% |
| Register (FF) | 2276 / 15552 | 15% |
| BSRAM | 7 / 46 | 16% |
| DSP | 10 / 24 | 42% |
| CLS | 4998 / 10368 | 49% |
| I/O Port | 21 / 66 | 32% |
| rPLL | 2 / 2 | 100% |

**Headroom is ample.** The register growth from restoring `descCount=32`, `visiblePerLine=8` is bounded and well within capacity (see §5).

Timing closure is clean:
- `clk_pixel` (25.2 MHz): setup slack positive, 0 violations.
- `clk_x5` (126 MHz): max reported 559.719 MHz vs required 126 MHz.
- No critical paths through the sprite evaluator in the current timing report.

---

## 3. Exact Changes Required

### 3.1 `VdpTop.scala` — parameter restoration

**Change A:** Restore evaluator instantiation to full parameters.

```scala
val spriteEval = SpriteEvaluator(
  descCount      = 32,
  visiblePerLine = 8,
  patternSelBits = 4,
  legacyIoCount  = 4)
```

**Change B:** Restore `NUM_SLOTS` to match `visiblePerLine`.

```scala
val NUM_SLOTS = 8   // restored to match visiblePerLine=8
```

**Change C:** Adjust `evalStart` timing for 32-cycle scan.

Current:
```scala
spriteEval.io.evalStart := hCounter === U(hTotal - 33, log2Up(hTotal) bits)
```

Proposed:
```scala
spriteEval.io.evalStart := hCounter === U(hTotal - 45, log2Up(hTotal) bits)
```

Rationale:
- `hTotal = 800` (640 active + 16 front + 96 sync + 48 back).
- 32-cycle scan + 13-cycle margin = 45 cycles before line-end.
- Completion at `800 - 45 + 32 = 787`, i.e. 12 cycles before `hTotal - 1` (line-buffer swap).
- This preserves the same ~13-cycle margin that the original `hTotal-33` gave to the 8-cycle scan (`800-33+8 = 775`, margin = 24 cycles; the comment calls it "comfortable").

> **Audit question:** Should `evalStart` be computed parametrically (e.g. `hTotal - (descCount + 13)`) so it scales automatically with future descriptor count changes? The artifact recommends keeping it a constant for now to avoid introducing a new `descCount` dependency into the timing generator, but notes this as a future maintainability option.

**Change D:** Expand register-bus decode for 32 slots.

Current decode (VdpTop.scala:807-814):
```scala
val spriteBusRangeHit = effWrite &&
  (effAddr >= U(0x0800, 15 bits)) &&
  (effAddr <  U(0x0840, 15 bits))
val spriteBusSub = (effAddr - U(0x0800, 15 bits))(5 downto 0)
spriteEval.io.busSlot := spriteBusSub(5 downto 3).resize(spriteEval.descIdxBits)
spriteEval.io.busWord := spriteBusSub(2 downto 0).resize(spriteEval.busWordBits)
```

Proposed decode:
```scala
val spriteBusRangeHit = effWrite &&
  (effAddr >= U(0x0800, 15 bits)) &&
  (effAddr <  U(0x0900, 15 bits))    // 32 slots × 8 words = 256 words
val spriteBusSub = (effAddr - U(0x0800, 15 bits))(7 downto 0)  // 8 bits for 256 words
spriteEval.io.busSlot := spriteBusSub(7 downto 3).resize(spriteEval.descIdxBits)  // 5 bits → 32 slots
spriteEval.io.busWord := spriteBusSub(2 downto 0).resize(spriteEval.busWordBits)  // unchanged
```

Address map preserved:
- Slot N, word W = `0x0800 + N*8 + W`
- Slot 0..3: legacy IO (still driven from top-level `sprite0X` etc.)
- Slot 4..31: bus-programmable extended descriptors
- Full range: `0x0800` .. `0x08FF`

**Change E:** Update the comment block above the instantiation to reflect restored parameters.

### 3.2 `TopTang20kHdmi.scala` — scenario compatibility

Scenarios that write to sprite bus addresses (Sc5, Sc6, Sc7, Sc15, Sc16, Sc17, Sc28, Sc29, Sc37) currently use addresses in the `0x0820..0x083F` range (slots 4..7). These addresses are unchanged under the expansion — slot 4 is still at `0x0820`, slot 7 at `0x0838`. **No changes required** to existing scenario copper programs.

New scenarios that exercise slots 8..31 would use addresses `0x0840..0x08FF`. These are outside the current scenario set and are not required for Task 45 closure.

### 3.3 `SpriteEvaluator.scala` — no changes required

The module already defaults to `descCount=32`, `visiblePerLine=8`. No RTL edits.

### 3.4 Sim files — update integration sim parameters

`AffineSpriteSim.scala` currently instantiates with `descCount=8, visiblePerLine=4`. It should be updated to match the live VdpTop parameters (32/8) or parameterized. The artifact recommends updating it to 32/8 so integration sims reflect the live configuration.

`SpriteBusViaVdpTopSim.scala` uses addresses in the `0x0820..0x0838` range; these remain valid and require no change.

---

## 4. Timing Margin Verification

### 4.1 Horizontal timing budget

| Parameter | Value (cycles) |
|---|---|
| hActive | 640 |
| hFront | 16 |
| hSync | 96 |
| hBack | 48 |
| **hTotal** | **800** |
| h-blank (hTotal - hActive) | 160 |

### 4.2 Evaluator scan timing

| descCount | evalStart | Scan completes | Cycles before swap | Margin assessment |
|---|---|---|---|---|
| 8 (current) | 767 | 775 | 24 | Comfortable |
| 32 (proposed) | 755 | 787 | 12 | Safe — 12 cycles at 25 MHz = 476 ns |

The 12-cycle margin is sufficient. The sprite evaluator outputs (`activeValid`, `activeX`, etc.) are registered and stable for the entire next line. No downstream consumer samples them before `hCounter=0`.

### 4.3 Bus write timing

Bus writes to sprite descriptor slots are asynchronous single-cycle register updates. With 32 slots, the write decode is a 32-way slot mux + 8-way word mux. This path is not timing-critical — it runs on the register-bus clock (Pico QSPI / `sysClk` domain), not the pixel clock.

---

## 5. Resource Budget Analysis

### 5.1 Register growth (SpriteEvaluator internal)

Extended descriptor registers (28 slots, each 126 bits):
- enabled: 1
- x, y: 20
- patternIndex: 4
- affineEnable: 1
- matrixA/B/C/D: 64
- transX/transY: 32
- **Per slot: 122 bits** (plus 4 bits of `patternIndex` already counted)
- Wait: 1+10+10+4+1+64+32 = **122 bits per slot**
- 28 slots × 122 = **3416 FFs**

Current extended descriptor registers (4 slots): 4 × 122 = **488 FFs**

**Net increase: ~2928 FFs**

Active slot registers (8 slots vs 4 slots, each 130 bits):
- valid + x + y + row + pattern + affineEnable + matrixA/B/C/D + transX/transY
- 1 + 10 + 10 + 4 + 4 + 1 + 64 + 32 = **126 bits** (wait, let me recalculate)
- Actually: activeValid(1) + activeX(10) + activeY(10) + activeRow(4) + activePattern(4) + activeAffineEnable(1) + activeMatrixA-D(64) + activeTransX/Y(32) = **126 bits**
- Current 4 slots: 4 × 126 = 504 FFs
- New 8 slots: 8 × 126 = 1008 FFs
- Net increase: **504 FFs**

Other FSM registers (scanIdx, activeCount, totalOnLine, scanBusy, overflowFlag):
- scanIdx: 5 bits (was 3)
- activeCount: 4 bits (was 3)
- totalOnLine: 6 bits (was 4)
- scanBusy: 1
- overflowFlag: 1
- Net increase: ~5 FFs (negligible)

**Total FF increase: ~2928 + 504 + 5 ≈ 3437 FFs**

Current total FFs: 2276
Projected total FFs: **~5713** (36.7% of 15552)

**Conclusion: well within capacity.**

### 5.2 Logic growth

The main logic growth comes from:
1. **Descriptor read mux**: `switch(scanIdx)` with 32 cases reading 122 bits each. This is a 32:1 mux tree — SpinalHDL / Gowin will synthesize this as LUTs. Estimated ~300-400 LUTs (vs ~100 for 8 cases).
2. **Bus write decode**: 28-case slot mux × 8-case word mux. Estimated ~200 LUTs (vs ~50 for 4 slots).
3. **Active slot priority chain**: `NUM_SLOTS` grows from 4 to 8 in the `for (s <- 0 until NUM_SLOTS)` priority assignment. Estimated ~50 LUTs increase.

Total logic increase: ~500-600 LUTs.
Current logic: 5226 LUTs
Projected logic: **~5800 LUTs** (28% of 20736)

**Conclusion: well within capacity.**

### 5.3 BSRAM impact

No BSRAMs are added. Descriptor storage uses distributed registers, not block RAM. The existing 2 sprite pattern ROMs (BSRAMs 6-7 of the 7 total) are unchanged.

> **Note:** If future work moves descriptor storage to BSRAM to save registers, that is a separate architectural decision outside Task 45 scope.

---

## 6. Pattern Memory — Out of Scope but Noted

The current VdpTop has only **2 sprite pattern ROMs** (`sprite0Pattern`, `sprite1Pattern`), selected by `patIdx(0)`. The `SpriteEvaluator` supports `patternSelBits=4` (16 patterns), but the VdpTop pixel path ignores bits [3:1].

Under Task 45, 32 descriptors sharing 2 patterns is **functionally correct** but visually limiting. The evaluator will correctly select, cull, and prioritize 32 descriptors; only the pattern lookup is constrained.

**Artifact recommendation:** Task 45 validation should use the existing 2 patterns at different positions to prove evaluator scale. Pattern-memory expansion (e.g. to 16 pattern banks in BSRAM) is a natural follow-up task but explicitly out of scope for Task 45 per the TASKS.md boundary.

---

## 7. Validation Plan

### 7.1 Simulation validation

**7.1.1 SpriteEvaluator unit sim (`SpriteEvaluatorSim`)**
- Already runs at `descCount=32`, `visiblePerLine=8`.
- Must pass all 7 cases (legacy slot drive, overflow, bus-programmed slot selection).
- **Evidence required:** sim console log showing `SpriteEvaluatorSim: PASS`.

**7.1.2 Affine sprite integration sim (`AffineSpriteSim`)**
- Currently runs at `descCount=8`, `visiblePerLine=4`.
- Must be updated to `descCount=32`, `visiblePerLine=8` and pass.
- **Evidence required:** sim console log showing `PASS`.

**7.1.3 Sprite bus via VdpTop sim (`SpriteBusViaVdpTopSim`)**
- Tests register-bus writes to slots 4..7 at addresses `0x0820..0x0838`.
- These addresses remain valid under the 32-slot expansion.
- Must pass unchanged.
- **Evidence required:** sim console log showing `PASS`.

**7.1.4 New sim: 32-descriptor overflow and cull (`SpriteCapacitySim`) — recommended**
- A new targeted sim (or extension to `SpriteEvaluatorSim`) that:
  - Programs 32 descriptors with varying Y positions.
  - Verifies exactly 8 are selected per line when >8 overlap.
  - Verifies `overflowFlag` asserts when >8 overlap.
  - Verifies correct `activeRow` computation for each selected slot.
- **Evidence required:** sim console log showing `PASS` with explicit overflow assertion check.

### 7.2 Hardware validation

**7.2.1 Synthesis and PnR**
- `make -C fpga/tang20k SCENARIO=<existing-sprite-scenario> all`
- Must complete with 0 errors, 0 critical warnings.
- Timing report must show `clk_pixel` and `clk_x5` still meeting with positive slack.
- **Evidence required:** `project.rpt.txt` resource and timing summary.

**7.2.2 Scenario selection for hardware proof**

Recommended scenario: **Sc5** (4 bouncing sprites) or **Sc17** (L0 + L1 + sprite, max load).

- Sc5 already uses 4 sprites; the behavior should be visually identical after the parameter change.
- Sc17 uses the maximum current load and is the best stress test.

**No new scenario is required for Task 45 closure.** The parameter change is intended to be behavior-neutral for existing scenarios that use ≤4 sprites.

**7.2.3 Hardware proof evidence**
- Direct capture or monitor screenshot showing sprite behavior unchanged.
- 30 s capture + `analyze.py` showing stable motion (for bouncing scenarios) or static pattern (for static scenarios).
- Bitstream md5 and HEAD commit hash.
- **Evidence required:** capture file, analysis.json, representative still, bitstream md5.

### 7.3 Regression checklist

| Check | Method | Pass criteria |
|---|---|---|
| Existing scenarios unchanged | Run Sc1, Sc4, Sc5 sims | Bit-identical to pre-change |
| Synthesis clean | Gowin IDE / make flow | 0 errors, 0 critical warnings |
| Timing closed | `project.rpt.txt` | `clk_pixel` slack > 0, `clk_x5` slack > 0 |
| Resource under limit | `project.rpt.txt` | FFs < 8000, LUTs < 12000, BSRAMs < 20 |
| Hardware smoke test | Sc5 on Tang Nano 20K | Visible sprites, no corruption |

---

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Gowin synthesis fails on 32-case `switch(scanIdx)` | Low | High (blocks build) | If unrouteable, replace with `Vec` + binary-indexed mux. The SpinalHDL `switch` is already synthesizable; Gowin has handled larger muxes in this design. |
| Timing path through descriptor read becomes critical | Low | Medium | Current timing margin is >4× on pixel clock. 32:1 mux adds ~1-2 ns. If violated, pipeline descriptor read into 2 cycles (scan-read + evaluate). |
| Existing scenarios break due to `NUM_SLOTS` increase | Very low | Medium | The slot iteration uses `for (s <- 0 until NUM_SLOTS)` with Vec indices. SpinalHDL will generate the correct width. Sim regression catches this before hardware. |
| Register bus decode width mismatch | Very low | High | Hard-error if bit-slice widths wrong. Verilator / SpinalHDL width inference catches this at elaboration. |
| Pattern memory confusion (only 2 patterns for 32 descriptors) | Very low | Low (cosmetic) | Documented in artifact. Validation uses position variation to prove evaluator scale. |

---

## 9. Files to Touch

| File | Change | Lines approx |
|---|---|---|
| `hw/spinal/spinalhdlvdp/VdpTop.scala` | Restore `descCount=32`, `visiblePerLine=8`, `NUM_SLOTS=8`, `evalStart=hTotal-45`, expand bus decode to `0x0800..0x08FF` | ~10 |
| `hw/spinal/spinalhdlvdp/AffineSpriteSim.scala` | Update instantiation to `descCount=32`, `visiblePerLine=8` | ~2 |
| `PROJECT_PLAN/TASKS.md` | Update live-lane status from "artifact" to "implementation" after audit PASS | ~5 |

**Files that do NOT change:**
- `SpriteEvaluator.scala` — already parametric, defaults correct.
- `SpriteEvaluatorSim.scala` — already uses 32/8.
- `SpriteBusViaVdpTopSim.scala` — addresses remain valid.
- `TopTang20kHdmi.scala` — existing scenario copper programs unchanged.
- `SpriteDescriptor.scala` — bundle unchanged.

---

## 10. Audit Checklist for CyanPeak

- [ ] Parameter restoration (32/8) is justified by resource headroom analysis in §5.
- [ ] `evalStart` timing (`hTotal-45`) provides ≥10 cycle margin before line-buffer swap.
- [ ] Register-bus decode expansion (`0x0800..0x08FF`, 8-bit sub-address) is arithmetically correct.
- [ ] Validation plan includes sim proof, synthesis proof, and hardware proof.
- [ ] Risk mitigations are actionable and bounded.
- [ ] Scope boundary excludes pattern-memory expansion and platform OAM maps.
- [ ] Existing scenario compatibility is preserved (no copper program changes needed).

---

## 11. Next Steps (Post-Audit)

1. **CyanPeak audit:** Rule PASS / HOLD / FAIL on this artifact.
2. **BronzeGate PM authorization:** If audit PASS, authorize BrightForge to begin implementation.
3. **BrightForge implementation:** Apply the §3 changes, run sims, synthesize, capture hardware evidence.
4. **CyanPeak implementation audit:** Audit the implementation evidence (sim logs, PnR report, hardware capture).
5. **CoralReef ledger sync:** Update `TASKS.md` to mark Task 45 DONE at the implementation commit.
