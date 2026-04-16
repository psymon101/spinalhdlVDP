# SCENARIO_13.md — Palette Animation During Motion

**Wave:** 3 (standalone validation lane)
**Validates:** Tasks 10 (palette) + Task 14 (raster/copper control) + Task 27 (single-axis scroll) — combined dynamic color appearance during motion
**Depends on:** Scenario 2 (scroll works) + Scenario 8 (packed-mode L0 works) + R5 copper (closed)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DRAFT — awaiting CyanPeak audit of pass criteria

---

## 1. Purpose

Validate dynamic color-appearance change during motion by combining:
1. **Copper-driven `VDP_ATTR_MODE` toggle** at specific scanlines — switches the L0 background between linear attribute decode (`0x0312=0`, one palette bank per quadrant) and packed 2×2 attribute decode (`0x0312=1`, four banks per 2×2 tile block)
2. **L1 single-axis scroll** at 1 px/frame — provides motion

Each `VDP_ATTR_MODE` state produces a distinct color palette mapping on L0; toggling per scanline creates visible horizontal bands, and scroll makes those bands appear to cycle/shift across time.

### Substrate transparency note (per CoralReef #7299)

**The current palette is implemented as ROM (`Mem` with `initialContent` only, `VdpTop.scala:599-601`) and has no runtime write port.** Therefore this scenario achieves palette-like color cycling via copper-driven `VDP_ATTR_MODE` switching rather than literal palette-entry rewrites. A future lane that adds a palette-write HDL primitive (new register-bus range + `palette.write(…)` port) would enable literal palette-cycle animation; that is explicitly out of Sc 13 scope.

## 2. Bootstrap register sequence (`scenarioId=13`)

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0003` | LAYER_ENABLE = L0 + L1 |
| 2 | `0x0311` | `0x0000` | VDP_TILE_MODE = packed (L0 uses `TileAttributeAssets` gradient/diagonal/rings/checker tiles) |
| 3 | `0x0312` | `0x0000` | VDP_ATTR_MODE = linear (initial; copper will animate this) |
| 4 | `0x0310` | `0x0001` | VDP_CTRL = copper **enabled** (overrides the Wave 1/2 default of disabled) |
| 5 | `0x0334` | `0x0000` | color math passthrough |

Copper program (uploaded to `0x0400..`):
```
PC  0: WAIT  y=60    (0 << 14) | 60
PC  1: WRITE 0x0312  (1 << 14) | 0x0312
PC  2: data  0x0001  (packed)
PC  3: WAIT  y=120
PC  4: WRITE 0x0312
PC  5: data  0x0000  (linear)
PC  6: WAIT  y=180
PC  7: WRITE 0x0312
PC  8: data  0x0001  (packed)
PC  9: WAIT  y=240
PC 10: WRITE 0x0312
PC 11: data  0x0000
PC 12: WAIT  y=300
PC 13: WRITE 0x0312
PC 14: data  0x0001
PC 15: WAIT  y=360
PC 16: WRITE 0x0312
PC 17: data  0x0000
PC 18: WAIT  y=420
PC 19: WRITE 0x0312
PC 20: data  0x0001
PC 21: JUMP  0
```

Produces 7 horizontal bands of alternating linear/packed attribute decode. The safe-boundary commit pattern ensures each toggle lands at `hCounter===0`, so the band boundaries are clean.

Top-level Scala (`TopTang20kHdmiScenario13`):
- `io.layer1ScrollX = +1 px/frame` (Sc 2 rate)
- All sprites disabled
- No color math window

## 3. Expected visual output

- L0 rendered in packed-mode rich tiles (gradient/diagonal/rings/checker)
- 7 horizontal bands alternating between linear-attr (single bank) and packed-attr (four banks per 2×2) decode
- L1 colorful background scrolling slowly left at 1 px/frame underneath
- The composite result: bands of different color "palettes" that shift horizontally as L1 scrolls, giving a palette-animation-during-motion impression

## 4. OpenCV pass criteria

Capture: 30 s, 720×480 YUYV 50 fps lossless. Skip first 100 frames (capture-sync warmup).

| Check | Condition | Reason |
|---|---|---|
| **C1 motion present** | Mean inter-frame diff on unique transitions ≥ 5.0 | Confirms scroll + L0 render is visibly changing frame-to-frame |
| **C2 motion stable** | 0 freeze runs ≥ 4 consecutive identical; ≤ 1 % of transitions > 5σ above median | No FPGA-side corruption |
| **C3 periodic horizontal banding** | Compute per-row mean intensity; autocorrelation has a clear peak near lag 60 (scanline period between attr toggles). Peak value > 0.5 | Confirms the copper-driven attr-mode toggle actually fired at the 60-line cadence |
| **C4 color-band phase shift over time** | Sample a fixed screen column's color signature (e.g. mean BGR of pixels in column x=100) at t=1s, t=5s, t=15s, t=25s. Compute k-means on the concatenated time-series with k=4. Expect ≥ 2 distinct centroid clusters (color samples vary over time as the scroll phase moves different bands into that column) | Dynamic color appearance during motion is the core scenario claim |

Scenario passes when C1, C2, C3 all PASS. C4 is the headline "palette-animation-like" check and is part of the PASS requirement.

## 5. Failure modes to watch for

- No banding (C3 fails): copper program not running (0x0310=1 didn't commit), or copper WAIT opcodes mis-encoded, or ATTR_MODE safe-boundary commit not firing
- Banding but static (C4 fails): scroll register not updating, or scroll stride = 0
- Freezes: copper infinite loop (bad JUMP target), or copper + bootstrap write race at boot
- Tearing at band boundary: ATTR_MODE commit hit mid-line instead of at `hCounter===0`

## 6. Out of scope

- Literal palette-entry rewriting (requires new HDL; see §1 substrate note)
- Per-pixel color cycling
- Palette bank selection via attributes (already validated by Sc 8)
