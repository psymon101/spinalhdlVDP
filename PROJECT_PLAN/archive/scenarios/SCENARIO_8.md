# SCENARIO_8.md — Multi-Layer Parallax Scroll

**Wave:** 2
**Validates:** Task 13 (parallax scroll — independent L0 and L1 scroll rates)
**Depends on:** Scenario 2 (single-axis scroll works)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate that L0 and L1 scroll independently at different rates without tearing, layer drift, or compositor artifacts. Parallax is the canonical 2D VDP multi-layer motion effect.

## 2. Bootstrap register sequence (`scenarioId=8`)

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0003` | LAYER_ENABLE = L0 + L1 (no sprites) |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0311` | `0x0000` | VDP_TILE_MODE = packed (L0 uses original `TileAttributeAssets` gradient/diagonal/rings/checker tiles — per CoralReef #7282) |
| 4 | `0x0312` | `0x0001` | VDP_ATTR_MODE = packed 2×2 (bank-checkerboard attribute decode for visual richness) |
| 5 | `0x0334` | `0x0000` | color math passthrough |

Top-level Scala (TopTang20kHdmiScenario8):
- `io.layer0ScrollX = +1 px/frame` (slow) — L0 background layer
- `io.layer1ScrollX = +3 px/frame` (3× faster) — L1 foreground layer  
- No sprites, no color math

## 3. Expected visual output

L0 (packed-mode R4.1c bank checkerboard — 4 palette banks with gradient/diagonal/rings/checker tiles) scrolls slowly. L1 (BasicPatternSource colorful tiles with arrows/circles) scrolls 3× faster. Both layers composited per-pixel; the relative motion between them produces the parallax depth illusion.

Parallax visibility: over 1 s, L0 moves 60 px left while L1 moves 180 px left → 120 px relative offset = unambiguously observable.

## 4. OpenCV pass criteria

| Check | Condition | Reason |
|---|---|---|
| **C1 motion present** | mean inter-frame diff ≥ 20.0 | Two scrolling layers produce more motion than single-layer Sc2 |
| **C2 motion bounded** | mean inter-frame diff ≤ 80.0; std ≤ 15 | No runaway; std scales with dual-layer velocity variance |
| **C3 no spikes / freezes** | 0 freezes; ≤ 1 % of unique transitions exceed 5σ (same tolerance as Sc3) | Two-layer motion amplifies YUYV chroma noise on high-contrast edges |
| **C4 parallax visible** | Phase-correlation per horizontal strip: the upper strip (rows 0..120, dominated by L1 colorful tiles) shows higher per-frame horizontal shift than the lower strip (rows 360..479, dominated by L0) | Direct proof that L0 and L1 move at different rates |

Scenario passes when C1, C2, C3 PASS. C4 quantifies the parallax claim; informational if strip content is mixed.

## 5. Failure modes
- Both layers at same rate: scroll register wiring bug; one rate applied to both
- One layer doesn't scroll: `layer0ScrollX` or `layer1ScrollX` not connected
- Tearing across horizontal seams: per-layer scroll commits at different hCounter positions
- Layer-priority inversion: wrong layer on top at the compositor stage

## 6. Out of scope
- Sprites
- Per-line scroll (Sc 11)
- Color math
