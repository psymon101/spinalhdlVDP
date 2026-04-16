# SCENARIO_11.md — Per-Line Raster Effects

**Wave:** 2
**Validates:** Task 18 (linestate per-line raster overrides)
**Depends on:** Scenario 2
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DRAFT — awaiting CyanPeak audit of pass criteria

---

## 1. Purpose

Validate the linestate per-line raster override path. The `linestate` Mem (addresses `0x0000..0x01DF`) holds a 12-bit packed record per visible line (`{l0en, l1en, l0scrollX[9:0]}`). At end-of-line the engine commits the prepared linestate so the NEXT line uses the override. This scenario programs alternating layer enables to produce visible horizontal bands attributable to per-line raster control.

## 2. Bootstrap register sequence (`scenarioId=11`)

Per CoralReef #7282: compressed writes (every 8th line, ~60 writes) — fits within current bootstrap counter.

Static register writes (run before the linestate writes):
| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0003` | LAYER_ENABLE default = L0 + L1 (per-line will override) |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0311` | `0x0000` | packed mode for L0 visual richness |
| 4 | `0x0312` | `0x0001` | packed 2×2 attr |
| 5 | `0x0334` | `0x0000` | color math passthrough |

Linestate writes (compressed, every 8 lines):
- For `line ∈ {0, 8, 16, ..., 472}`:
  - even-block lines (`line/8` even) → write `0x000_+line`, data = `0x002` (l1en=1, l0en=0, scrollX=0) → L1 only
  - odd-block lines (`line/8` odd) → write `0x000+line`, data = `0x001` (l1en=0, l0en=1, scrollX=0) → L0 only

This produces visible horizontal banding: 8-line bands alternating L0/L1, repeating 30 times down the screen (60 writes total).

Top-level Scala (TopTang20kHdmiScenario11):
- All sprites disabled
- L0 scroll = 0, L1 scroll = 0 (static scene; per-line scroll-X also held at 0 in the linestate writes)

## 3. Expected visual output

Horizontal banding 8 px tall:
- Band rows 0..7: L1 only (BasicPatternSource colorful tiles)
- Band rows 8..15: L0 only (R4.1c packed bank checkerboard)
- Band rows 16..23: L1 only
- ... repeating every 16 rows down the screen
30 alternating 8-px-tall band pairs across 480 lines.

## 4. OpenCV pass criteria

| Check | Condition | Reason |
|---|---|---|
| **C1 stability** | Static scene — 0 unique transitions over 30 s post-sync | No motion expected |
| **C2 banding visible** | Compute mean BGR per scanline; the per-row signal must show a periodic alternation matching the 8-line band period — autocorrelation of row-mean intensity has a clear peak near lag 16 (one full L0+L1 band cycle) | Confirms per-line layer override actually fired per programmed line |
| **C3 band content distinct** | Mean BGR of an L0 band (e.g. rows 8..15) significantly differs from the L1 band immediately above (rows 0..7), per-channel ≥ 30 difference | Confirms the per-line writes wrote different layer enables, not the same |

Scenario passes when C1, C2, C3 PASS.

## 5. Failure modes
- Uniform colorful screen with no banding: linestate write failed or commit not firing per-line (entire screen falls back to default LAYER_ENABLE)
- Banding present but at wrong period: linestate write addresses wrong, or commit timing off
- Tearing within a band: per-line commit happens mid-line instead of at end-of-line

## 6. Out of scope
- Per-line scroll-X variation (would be a Wave 3 raster scroll scenario)
- Per-line color math (Task 20 doesn't have per-line registers; window is global)
