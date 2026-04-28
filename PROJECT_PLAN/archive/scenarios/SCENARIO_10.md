# SCENARIO_10.md — Shuffled Bitmap Scene

**Wave:** 2
**Validates:** Task 17 (R4.1d shuffled/Amiga-style bitplane fetch path)
**Depends on:** Scenario 1
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate the shuffled (Amiga-style bitplane) tile fetch path on hardware. In shuffled mode (`VDP_TILE_MODE @ 0x0311 = 0x02`) the engine fetches plane 0 from `PlanarTileAssets.SdramBase = 0xA000` and plane 1 from `PlanarTileAssets.Plane1SdramBase = 0xB000` — two **separate** SDRAM bases — and reconstructs pixels as `{plane1[bit], plane0[bit]}`.

**Substrate-induced visual identity to Sc 9 (transparency note per CoralReef #7282):** see `SCENARIO_9.md` §1. Sc 9 and Sc 10 produce bit-identical visuals because the diagnostic tiles are uniform-pixel-value; the difference is solely whether plane 1 came from the same base as plane 0 (planar) or from a separate base (shuffled). The R4.1d Checkpoint C closeout already established the dual-base proof at `tile(0,1) intensity 186.2 ≈ exp 170 ✓`. Sc 10 reproduces that proof in the scenario matrix.

## 2. Bootstrap register sequence (`scenarioId=10`)

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0001` | LAYER_ENABLE = L0 only |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0311` | `0x0002` | VDP_TILE_MODE = shuffled (R4.1d) |
| 4 | `0x0312` | `0x0000` | VDP_ATTR_MODE = linear |
| 5 | `0x0334` | `0x0000` | color math passthrough |

Top-level Scala (TopTang20kHdmiScenario10):
- All sprites disabled
- L0 scroll = 0, L1 scroll = 0 (static scene)

## 3. Expected visual output

Identical to Sc 9: 2×2 grayscale checkerboard (BLACK / DARK_GRAY / LIGHT_GRAY / WHITE) repeating every 32 px across 640×480, but rendered through the **dual-base** shuffled fetch path.

## 4. OpenCV pass criteria

Reuses the R4.1d Checkpoint C analysis methodology.

| Check | Condition | Reason |
|---|---|---|
| **C1 stability** | 0 unique transitions over 30 s post-sync | Static scene |
| **C2 four bit-observable bands** | Whole-screen intensity histogram ≥ 90 % in four bands `[0±15, 85±15, 170±15, 255±15]`; per-band coverage 15-35 % | Confirms shuffled reconstruction produces all four sub-fields |
| **C3 dual-base proof** | At source (0,16) and (16,16) (mapped to capture) intensities match 170 and 255 within ±25 | tile(0,1) light-gray and tile(1,1) white can ONLY appear if plane 1 came from `0xB000` separately |

Scenario passes when C1, C2, C3 PASS.

## 5. Failure modes
- Screen darker overall: plane 1 base wrong → `tile(0,1)` reads 0 instead of 170, `tile(1,1)` reads 85 instead of 255
- Same as Sc 9: mode select didn't propagate, plane ROM not boot-copied

## 6. Out of scope
- Planar mode (Sc 9)
- Scroll over shuffled
