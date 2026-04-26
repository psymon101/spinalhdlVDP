# SCENARIO_9.md — Planar Bitmap Scene

**Wave:** 2
**Validates:** Task 16 (R4.1b planar 2bpp tile fetch path)
**Depends on:** Scenario 1
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate the planar (NES-style 2-plane 2bpp) tile fetch path on hardware. In planar mode (`VDP_TILE_MODE @ 0x0311 = 0x01`) the engine reads each tile row as two 32-bit words from the SAME SDRAM base (`PlanarTileAssets.SdramBase = 0xA000`): word 0 holds plane 0 in low 16 bits, word 1 holds plane 1 in low 16 bits. Pixel reconstruction is `{plane1[bit], plane0[bit]}` — identical reconstruction logic as shuffled mode but with single-base addressing.

**Substrate-induced visual identity to Sc 10 (transparency note per CoralReef #7282):** R4.1d Checkpoint C replaced the original `PlanarTileAssets.tilePatterns` (vstripe4/hstripe4/diag/solid) with **uniform-pixel-value diagnostic tiles** (tile N renders as uniform pixel value N). Combined with the 2×2 repeating tile map, both planar and shuffled modes now produce a bit-identical 2×2 grayscale checkerboard. This scenario validates that planar mode reaches that diagnostic correctly using single-base reads — Sc 10 validates the same diagnostic via dual-base reads. The visual identity is intentional substrate behavior, not a validation gap.

## 2. Bootstrap register sequence (`scenarioId=9`)

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0001` | LAYER_ENABLE = L0 only |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0311` | `0x0001` | VDP_TILE_MODE = planar (R4.1b) |
| 4 | `0x0312` | `0x0000` | VDP_ATTR_MODE = linear |
| 5 | `0x0334` | `0x0000` | color math passthrough |

Top-level Scala (TopTang20kHdmiScenario9):
- All sprites disabled
- L0 scroll = 0, L1 scroll = 0 (static scene)

## 3. Expected visual output

2×2 grayscale checkerboard from the L0 diagnostic asset (same visual as R4.1d Checkpoint C but rendered through the **single-base planar fetch path** instead of the dual-base shuffled path):
- Tile (0,0) → BLACK (0)
- Tile (1,0) → DARK_GRAY (~85)
- Tile (0,1) → LIGHT_GRAY (~170)
- Tile (1,1) → WHITE (~255)
The pattern repeats every 32 px horizontally and vertically across the full 640×480 active area.

## 4. OpenCV pass criteria

Reuses the R4.1d Checkpoint C analysis methodology.

| Check | Condition | Reason |
|---|---|---|
| **C1 stability** | 0 unique transitions over 30 s post-sync | Static scene |
| **C2 four bit-observable bands** | Whole-screen intensity histogram has ≥ 90 % of pixels in the four bands `[0±15, 85±15, 170±15, 255±15]`; per-band coverage 15-35 % each | Confirms planar reconstruction produces all four `{plane1, plane0}` sub-fields |
| **C3 sample-tile intensities** | At source (0,0)/(16,0)/(0,16)/(16,16) (mapped to capture coords) intensity matches expected 0/85/170/255 within ±25 | Bit-observable per-tile validation |

Scenario passes when C1, C2, C3 PASS.

## 5. Failure modes
- All-black / one-color screen: planar mode select didn't propagate, or planar ROM not boot-copied
- Wrong intensities (e.g. 0/170 only): plane 1 not reconstructed (would mean `unpackRow(47:32)` slice is wrong)
- Mid-frame band shift: safe-boundary commit miss

## 6. Out of scope
- Shuffled mode (Sc 10)
- Scroll over planar (would be Sc 6 territory)
