# SCENARIO_3.md — Scroll Wraparound / Seam Test

**Wave:** 1
**Validates:** Task 8 (`ScrollWrap` primitive boundary correctness on the L1 path)
**Depends on:** Scenario 2 (single-axis scroll must work first)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge (coding) / CyanPeak (audit)
**Status:** DRAFT — awaiting CyanPeak audit of pass criteria

---

## 1. Purpose

Validate that the L1 scroll path produces zero pixel-discontinuity at the wrap boundary — when the scroll register transitions from 639 → 0 (or any wrap), the visible output shows a clean seam with the same tile content on both sides of the boundary.

## 2. Bootstrap register sequence

Same registers as Scenario 2.

Top-level Scala (TopTang20kHdmiScenario3):
- `io.layer1ScrollX` increments by **a larger step** (e.g. 8 px/frame) so the wrap is exercised more frequently — a full 640-px wrap every ~80 frames (~1.3 s at 60 Hz). Over 30 s, ~22 wrap events.
- All other config identical to Scenario 2.

## 3. Expected visual output

Same scrolling motion as Scenario 2 but at higher horizontal speed. As the scroll register crosses 639→0 the tile pattern wraps smoothly: the rightmost column of the previous frame is adjacent to the leftmost column of the next frame with no visible seam.

## 4. OpenCV pass criteria

Capture: 30 s, 720×480 YUYV 50 fps lossless.

| Check | Condition | Reason |
|---|---|---|
| **C1 motion present (faster)** | mean inter-frame diff ≥ 30.0 (vs Sc2's ≥ 5) | Confirms higher scroll speed actually engaged |
| **C2 stability** | 0 freezes; 0 isolated jumps > 5σ above mean over 30 s | No glitch frames at any wrap boundary |
| **C3 no wrap-event outlier** | At expected wrap-boundary frames (every ~80 frames at the chosen step), inter-frame diff is within ±20 % of the steady-state mean diff | A torn or mis-wrapped seam would show as a single-frame outlier exactly at the wrap event |
| **C4 horizontal motion direction** | Phase-correlation of consecutive frames remains horizontal across the entire capture | Wrap should not introduce vertical shift artifact |
| **C5 long-tail variance bounded** | Across the full 30 s, max single-frame inter-frame diff < 2 × mean diff | Catches a single bad seam event in the entire capture |

Scenario passes when C1-C5 all PASS.

## 5. Failure modes to watch for

- Single-frame full-screen flash at wrap event: combinational glitch in `ScrollWrap` resize / wrap math
- Visible vertical seam line at wrap boundary: tile-map readback wrong at boundary tile index
- Half-frame torn (top half wrapped, bottom not): scroll register committed mid-frame instead of at hCounter===0/vsync

## 6. Out of scope

- Y-wrap (covered by long soak Scenario 16)
- L0 SDRAM-fetched scroll (validated by R5.4 `ScrollWrapSim` and the R4.2-redo proof)
