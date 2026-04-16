# SCENARIO_7.md — Sprite Priority / Overlap

**Wave:** 2
**Validates:** Task 12 (sprite priority semantics — slot-1-wins-over-slot-0)
**Depends on:** Scenario 6
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DRAFT — awaiting CyanPeak audit of pass criteria

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
- `io.sprite0Enabled = True`, position (320, 240), pattern 0 (arrow/diamond)
- `io.sprite1Enabled = True`, position (328, 248) — **8 px diagonal offset so the two 16×16 sprites overlap in an 8×8 region**, pattern 1
- sprite 2/3 disabled

## 3. Expected visual output

Sc1 colorful L1 background, with two overlapping sprites at near-center. The 8×8 overlap region shows pattern 1's content (sprite 1 wins). The non-overlap parts show pattern 0 (left/up) and pattern 1 (right/down) cleanly.

## 4. OpenCV pass criteria

| Check | Condition | Reason |
|---|---|---|
| **C1 stability** | Sc1-style: 0 unique transitions over 30 s post-sync | Static scene, both sprites pinned |
| **C2 sprite 0 visible non-overlap** | Diff vs Sc1 baseline shows non-zero pixels in the upper-left region of the (320,240) bounding rect (≈8×8 px not covered by sprite 1) | Sprite 0 renders where it isn't occluded |
| **C3 sprite 1 visible everywhere it footprints** | Diff vs Sc1 baseline shows non-zero pixels in the entire 16×16 region around (328,248) | Sprite 1's full footprint renders, including the 8×8 overlap |
| **C4 priority — slot-1-wins** | At a sample pixel in the overlap region (e.g. screen x=336, y=256 — center of the 8×8 overlap), the captured BGR matches sprite 1's pattern at that local coord, NOT sprite 0's | Direct proof that slot 1 occludes slot 0 |

Scenario passes when C1, C4 PASS. C2 + C3 are footprint sanity checks.

## 5. Failure modes
- Sprite 0 visible in overlap region: priority semantics broken (would show wrong color in a small 8×8 patch)
- Both sprites invisible: enable bits not propagating
- Sprite 0 occludes sprite 1 (slot-0-wins): priority mux ordering bug or evaluator slot assignment swapped

## 6. Out of scope
- More than 2 overlapping sprites (R2 per-line limit drops to slots 0/1; descriptor 2/3 dropped from this Y band)
- Sprite vs background priority (validated separately)
