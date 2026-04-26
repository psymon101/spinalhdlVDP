# SCENARIO_6.md — Sprites Over Scrolling Background

**Wave:** 2
**Validates:** Tasks 12, 13, 27, 30 (sprite renderer + multi-layer composite over scrolling L1)
**Depends on:** Scenarios 5 (4 bouncing sprites work) + 27 (single-axis scroll works)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate the combination of L1 scrolling background + bouncing sprites. Confirms the sprite renderer composes correctly over a moving background without tearing or position drift, with each subsystem already validated in isolation by Scenarios 2 and 5.

## 2. Bootstrap register sequence (`scenarioId=6`)

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0006` | LAYER_ENABLE = sprite + L1 |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0334` | `0x0000` | color math passthrough |

Top-level Scala (TopTang20kHdmiScenario6):
- `io.layer1ScrollX` = `Reg(UInt(10 bits)) init 0`, `+1 px/frame` (Sc2 rate)
- 4 sprites enabled with `bouncer{}` motion (Sc5 wiring)
- L0 disabled

## 3. Expected visual output

Sc1 colorful L1 pattern slowly scrolling left (1 px/frame), with 4 sprites bouncing diagonally over it. Sprite footprints partially occlude scrolling tile content beneath them; background continues to scroll under each sprite without artifacts.

## 4. OpenCV pass criteria

| Check | Condition | Reason |
|---|---|---|
| **C1 motion present** | mean inter-frame diff ≥ 5.0 | Sc2-style scroll motion dominates the diff |
| **C2 motion bounded** | mean inter-frame diff ≤ 60.0; std ≤ 10 | No runaway / no tearing |
| **C3 no spikes / freezes** | 0 isolated > 5σ outliers; 0 freeze runs ≥ 4 (after capture-sync skip 100) | No FPGA glitches |
| **C4 sprites visible** | Diff vs Sc2 baseline frame at matched scroll position shows ≥ 4 connected components, each 64..400 px | Confirms 4 sprite footprints are on-screen above the scroll bg |

Scenario passes when C1, C2, C3 PASS. C4 informational; Sc2 baseline alignment is approximate at the captured scroll instant.

## 5. Failure modes
- Sprites disappear when scrolling: compositor priority bug at sprite-vs-bg seam
- Tearing line: scroll committed mid-frame
- Sprite trail / smearing: ping-pong race
- Scroll stops while sprites move: scroll register update gated incorrectly

## 6. Out of scope
- L0 (validated by Sc 9/10)
- Sprite priority/overlap (Sc 7)
- Color math (Sc 14)
