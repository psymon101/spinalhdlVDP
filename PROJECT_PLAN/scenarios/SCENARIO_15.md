# SCENARIO_15.md — Mixed Fetch-Mode Integration Scene

**Wave:** 3
**Validates:** Task 21 (Mixed-Scene Integration)
**Depends on:** Scenarios 1, 9, 10, 11, 12, 14
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** ACTIVE — Fix A + Fix B applied, criteria audited by CyanPeak

---

## 1. Purpose

Validate that the closed Mode0 fetch primitives (tile, planar, shuffled) can coexist in one integrated scene with concurrent L0 scroll and sprite motion. This is the integration gate before soak/stress validation.

---

## 2. Bootstrap register sequence — `scenarioId=15`

The bootstrap configures:
- `LAYER_ENABLE = 0x0005` (L0 + sprites enabled) — L1 disabled so the three L0 mode bands are fully visible
- `VDP_TILE_MODE = 0x0000` (tile mode) as the initial state
- `VDP_ATTR_MODE = 0x0000` (linear attributes)
- `VDP_COPPER_CTRL = 0x0001` (copper enabled)
- Copper program (3 triggers):
  - `y =   0`: write `0x0311 = 0x0000` (reset L0 to packed mode at frame start)
  - `y = 160`: write `0x0311 = 0x0001` (switch L0 to planar mode)
  - `y = 320`: write `0x0311 = 0x0002` (switch L0 to shuffled mode)
- L0 scroll: 1 px/frame
- Sprite 0: bouncing horizontally at 2 px/frame, `y = 100`
- Sprite 1: bouncing horizontally at 2 px/frame (opposite phase), `y = 300`
- All other sprites disabled

**Build/flash command:** `make all SCENARIO=15`

---

## 3. Expected visual output

The screen is divided into three horizontal bands:
- **Top 160 lines:** tile-mode background (packed 4bpp tiles from the closed substrate)
- **Middle 160 lines:** planar-mode background (NES-style 2-plane 2bpp, 4 grayscale shades)
- **Bottom 160 lines:** shuffled-mode background (Amiga-style bitplanes)

L0 scrolls continuously across all bands. Two sprites move horizontally across the full height, crossing mode boundaries.

---

## 4. OpenCV pass criteria

| Check | Condition | Reason |
|---|---|---|
| **C1a top-band presence** | Top 1/3 mean differs from bottom 2/3 mean by ≥ 15 units in at least one BGR channel for ≥ 95 % of sampled frames | Proves packed mode is active in the top band |
| **C1b mid/bottom coherence** | Mid 1/3 and bottom 1/3 means are within ±20 units in at least one BGR channel for ≥ 95 % of sampled frames | Proves planar and shuffled bands render coherently as a bitplane block |
| **C2 L0 scroll motion** | Mean absolute frame-difference across the full 30 s ≥ 5.0 | Proves L0 scroll is active and coherent across mode boundaries |
| **C3 sprite presence** | Sprite detection fraction ≥ 95 % across 30 s; x-range ≥ 100 px | Proves sprite path remains stable under mode switching |
| **C4 stability** | Combined C1a/C1b outlier rate ≤ 5 % (frames where either the top-band distinctness or the mid/bottom coherence collapses) | Proves no corruption or glitching from copper-driven mode switches |

Scenario passes when C1a, C1b, C2, C3, and C4 PASS.

---

## 5. Physics / interpretation notes

- The copper fires at `y = 160` and `y = 320`. Because line-buffer fill occurs during the previous line’s hblank, the mode switch should take effect on the line immediately following the trigger. A ±1 line visual offset is acceptable.
- Tile, planar, and shuffled data are all boot-copied into SDRAM by `SdramTileAttributeFetch` at power-on, so the mode switch is purely a register change.

---

## 6. Out of scope

- Color math / window (already validated by Scenario 14)
- Affine background (already validated by Scenario 12)
- Per-line affine updates
- Copper programs longer than the two required mode switches
