# SCENARIO_2.md — Single-Axis Scroll

**Wave:** 1
**Validates:** Task 7 (single-axis scroll on the legacy L1 path)
**Depends on:** Scenario 1 (static background must be stable first)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge (coding) / CyanPeak (audit)
**Status:** DRAFT — awaiting CyanPeak audit of pass criteria

---

## 1. Purpose

Validate that the L1 horizontal-scroll path (`io.layer1ScrollX` driven from a per-frame counter in the top-level) produces visible, smooth scrolling motion across 30 s with no tearing, no visible step-skip, and no rendering corruption.

## 2. Bootstrap register sequence

Identical to Scenario 1:
| # | Addr | Data |
|---|------|------|
| 1 | `0x0300` | `0x0002` (LAYER_ENABLE = L1 only) |
| 2 | `0x0310` | `0x0000` (copper disabled) |
| 3 | `0x0334` | `0x0000` (color math passthrough) |

Top-level Scala (TopTang20kHdmiScenario2):
- `io.layer1ScrollX` = `Reg(UInt(10 bits)) init 0`, increments by 1 every frame (vsync edge), wraps at `MapPixelsX` (640).
- `io.layer1ScrollY` = 0
- `io.layer0ScrollX/Y` = 0 (L0 disabled)
- All sprites disabled

## 3. Expected visual output

The same static `BasicPatternSource` tile pattern as Scenario 1, but the entire frame is shifted left by `frameCount` pixels (modulo 640) every frame. Visually: the 8-tile pattern slides smoothly leftward at 60 px/sec (Tang outputs 60 Hz, scroll incr = 1 px/frame).

## 4. OpenCV pass criteria

Capture: 30 s, 720×480 YUYV 50 fps lossless. The motion target rate is 60 px/sec at the source; capture at 50 fps will see ~50 px of motion per second of capture wall time.

| Check | Condition | Reason |
|---|---|---|
| **C1 motion present** | mean inter-frame diff (unique transitions only) ≥ 5.0 | Confirms scroll is actually moving |
| **C2 motion bounded** | mean inter-frame diff ≤ 60.0; std ≤ 10 | Prevents pathological "every-frame totally different" (would suggest random pixels, not coherent scroll) |
| **C3 no spikes / freezes** | 0 isolated single-frame jumps > 5σ above mean; 0 freeze runs ≥ 4 consecutive identical frames | Smooth scroll has no skip events |
| **C4 horizontal motion direction** | Phase-correlation between consecutive sample frames yields predominantly horizontal shift (\|dy\| ≤ 1 px, \|dx\| ≥ 1 px) | Confirms it's a *single-axis* scroll, not 2-axis or random |
| **C5 background colors preserved** | Whole-30 s color histogram still shows the same dominant palette colors as Scenario 1 (per-channel mean within ±10 of Scenario 1's reference) | Scrolling shouldn't change the palette content |

Scenario passes when C1, C2, C3, C4 all PASS. C5 is a cross-scenario consistency check.

## 5. Failure modes to watch for

- No motion: scroll register never updates → reg-write path bug, or frame counter not running
- Tearing (motion happens mid-frame): scroll commit didn't land at hCounter===0 / vsync boundary
- Wrap glitch at scroll boundary: `ScrollWrap` primitive bug (validated separately by `ScrollWrapSim`, but visible regression here)
- Vertical motion present: wrong register driven

## 6. Out of scope

- Wrap stress (Scenario 3)
- Y-axis scroll (validated implicitly via the same primitive on the Y register; covered in long-soak Scenario 16)
- Sprites
