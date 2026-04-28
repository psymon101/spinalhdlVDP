# SCENARIO_7.md — Sprite Priority / Overlap

**Wave:** 2
**Validates:** Task 12 (sprite priority semantics — slot-1-wins-over-slot-0)
**Depends on:** Scenario 6
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate the sprite renderer's deterministic priority rule when two sprites overlap on the same scanlines. Per `VdpTop.scala:547-549`, when both slot-0 and slot-1 are visible at the same fillX, **slot 1 wins** (`slotVisible(1)` clause precedes `slotVisible(0)` in the priority mux). The 2-per-line evaluator picks slot 0 = lowest-index on-line descriptor and slot 1 = second-lowest.

For two sprites enabled at descriptor 0 and descriptor 1 both on the same Y range, sprite 1 fills slot 1, sprite 0 fills slot 0 → sprite 1 visually occludes sprite 0 in the overlap region.

## 2. Bootstrap register sequence (`scenarioId=7`)

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0006` | LAYER_ENABLE = sprite + L1 |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0334` | `0x0000` | color math passthrough |

Top-level Scala (TopTang20kHdmiScenario7):
- L1 background as Sc1 (no scroll, static)
- `io.sprite0Enabled = True`, position **(320, 240)**, pattern 0 (arrow/diamond)
- `io.sprite1Enabled = True`, position **(320, 240)** — **identical** to sprite 0 (perfect overlap, every pixel of slot 0's footprint is also slot 1's footprint)
- sprite 2/3 disabled

**Test design rationale:** With identical positions, sprite 1 (slot 1) must occlude sprite 0 (slot 0) at every visible pixel of the footprint. Per `VdpTop.scala` `spritePatternData` (pattern 0) uses palette indices `{0, 1=white, 2=red, 5=yellow}` (arrow/diamond) while `sprite1PatternData` (pattern 1) uses indices `{0, 6=cyan, 7=magenta}` (cross shape). Pattern 0's opaque colors are red/yellow; pattern 1's opaque colors are cyan/magenta — completely disjoint palettes. Therefore the dominant non-transparent colors in the footprint unambiguously identify which slot won.

The original 8 px diagonal offset was abandoned during execution because pattern 0 is sparsely opaque, making per-pixel "which sprite is here" hard to tell when only partially overlapping.

## 3. Expected visual output

Sc1 colorful L1 background, with one visible sprite at center showing the cyan-and-magenta cross pattern (pattern 1, sprite 1) — NOT the red/yellow arrow/diamond of pattern 0.

## 4. OpenCV pass criteria

Pattern color reference per `VdpTop.scala`:
- Pattern 0 opaque indices: `{1, 2, 5}` → palette = `{white, red, yellow}` → BGR `(255,255,255)`, `(0,0,255)`, `(0,255,255)`
- Pattern 1 opaque indices: `{6, 7}` → palette = `{cyan, magenta}` → BGR `(255,255,0)`, `(255,0,255)`

| Check | Condition | Reason |
|---|---|---|
| **C1 stability** | Sc1-style: 0 unique transitions over 30 s post-sync | Static scene, both sprites pinned |
| **C2 sprite footprint visible** | Diff vs Sc1 baseline shows ≥ 50 px differing by > 30 in the 18×16 region around source (320, 240) → capture (~360, 240) | At least one sprite is rendering |
| **C3 dominant color is pattern-1 cyan + magenta, NOT pattern-0 red + yellow** | In the differing pixels, count of cyan `BGR≈(255,255,0)` + magenta `BGR≈(255,0,255)` pixels exceeds count of red `BGR≈(0,0,255)` + yellow `BGR≈(0,255,255)` pixels by ≥ 4× margin | Direct proof slot 1 occludes slot 0 |

Scenario passes when C1, C2, C3 PASS.

## 5. Failure modes
- Sprite 0 visible in overlap region: priority semantics broken (would show wrong color in a small 8×8 patch)
- Both sprites invisible: enable bits not propagating
- Sprite 0 occludes sprite 1 (slot-0-wins): priority mux ordering bug or evaluator slot assignment swapped

## 6. Out of scope
- More than 2 overlapping sprites (R2 per-line limit drops to slots 0/1; descriptor 2/3 dropped from this Y band)
- Sprite vs background priority (validated separately)
