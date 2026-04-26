# SCENARIO_1.md — Static Background Fill

**Wave:** 1
**Validates:** Tasks 5, 6, 10 (legacy L1 `BasicPatternSource` + palette + L1 path)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge (coding) / CyanPeak (audit)
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate that the legacy L1 `BasicPatternSource` (3 bpp, pre-R4 substrate) renders a stable static colored test pattern on Tang Nano 20K HDMI, with the closed `c709176`+`dd119ec` baseline doing nothing post-palette (no shadow window, no sprite overlay, no scroll motion, no copper effects).

This is the foundation scenario — every other Wave 1 scenario assumes Scenario 1 passes.

## 2. Bootstrap register sequence

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0002` | LAYER_ENABLE = L1 only (disable L0 SDRAM, disable sprite layer enable bit) |
| 2 | `0x0310` | `0x0000` | VDP_CTRL = copper disabled (no copper-driven changes) |
| 3 | `0x0311` | `0x0000` | VDP_TILE_MODE = packed (default; not used since L0 disabled) |
| 4 | `0x0312` | `0x0000` | VDP_ATTR_MODE = linear (default) |
| 5 | `0x0334` | `0x0000` | VDP_COLOR_MATH = passthrough op=00, no invert, no constant |

Window registers `0x0330..0x0333` left at power-on default (all zero → window is `[0,0)×[0,0)` empty → effect always false → ColorMath enable false → passthrough).

Top-level Scala (TopTang20kHdmiScenario1):
- `io.layer0ScrollX` = 0, `io.layer0ScrollY` = 0
- `io.layer1ScrollX` = 0, `io.layer1ScrollY` = 0
- `io.spriteN_Enabled` = False for all 4 slots
- All other IO defaults

## 3. Expected visual output

Static colored test pattern from `BasicPatternSource`:
- 8 different tile patterns repeated across the 40×30 tile grid
- Tiles use bank-0 legacy palette: black, white, red, green, blue, yellow, cyan, magenta
- Whole frame is identical every frame (no motion, no animation)

## 4. OpenCV pass criteria

Capture: 30 s, 720×480 YUYV 50 fps lossless. Compute on the lossless capture file.

| Check | Condition | Reason |
|---|---|---|
| **C1 stability** | < 5 dup frames out of 1500; 0 freezes (≥ 4 consecutive identical); 0 isolated jumps (single-frame > 10× median diff) | Static scene must be temporally stable |
| **C2 intensity** | mean frame intensity > 30 over 30 s window; std (mean per frame) < 2.0 | Output is non-black and non-flickering |
| **C3 color presence** | ≥ 4 distinct colors detected (k-means on whole-screen pixel distribution, k=8, top-4 clusters each > 5 % of pixels) | Validates palette + L1 tile content rendering |
| **C4 bg layer correct** | Whole-screen histogram has peaks at expected bank-0 palette intensities (R/G/B primaries: peaks near R/G/B channel ratios `(0xFF,0,0)` etc., per-band coverage > 60 % combined) | Confirms `BasicPatternSource` colors actually reach the screen |

Scenario passes when C1, C2, C3 all PASS. C4 is informational (color-mix correctness depends on the chosen tile mix — flagged for CyanPeak review).

## 5. Failure modes to watch for

- All-black output → LAYER_ENABLE wrong, or palette init broken, or color math active on wrong default
- Animation visible → copper accidentally still enabled, or scroll register non-zero
- Sprites visible → sprite enables not driven False
- Single-color screen → BasicPatternSource not connected, or palette bank mis-routed

## 6. Out of scope

- No scroll (Scenario 2)
- No sprites (Scenario 4)
- No SDRAM tile fetch (R4 closed, validated by other scenarios)
- No color-math effects (Task 20 closed, validated separately)
