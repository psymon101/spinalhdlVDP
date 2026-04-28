# SCENARIO_16.md — Long Soak Baseline Scene

**Wave:** 3  
**Validates:** Task 22 (Long Soak Validation)  
**Depends on:** Scenario 15 (Task 21, closed)  
**Capture protocol:** Three 30 s snapshots at T=0, T=30 min, T=60 min; 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p  
**Owner:** BrightForge / CyanPeak  
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate that the closed Mode0 integrated baseline (mixed fetch modes, L0 scroll, and sprites) remains stable over a minimum 1-hour continuous run.

---

## 2. Bootstrap register sequence — `scenarioId=16`

The bootstrap is **identical** to `scenarioId=15`:

- `LAYER_ENABLE = 0x0005` (L0 + sprites enabled)
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

**Build/flash command:** `make all SCENARIO=16`

---

## 3. Expected visual output

The screen is divided into three horizontal bands:
- **Top 160 lines:** tile-mode background (packed 4bpp tiles)
- **Middle 160 lines:** planar-mode background (NES-style 2-plane 2bpp)
- **Bottom 160 lines:** shuffled-mode background (Amiga-style bitplanes)

L0 scrolls continuously across all bands. Two sprites move horizontally across the full height, crossing mode boundaries. This output must remain visually unchanged in character across the full 1-hour soak window.

---

## 4. OpenCV pass criteria

| Check | Condition | Reason |
|---|---|---|
| **C1a top-band presence** | Top 1/3 mean differs from bottom 2/3 mean by ≥ 15 units in at least one BGR channel for ≥ 95 % of sampled frames, **in every snapshot** | Proves packed mode remains active in the top band over time |
| **C1b mid/bottom coherence** | Mid 1/3 and bottom 1/3 means are within ±20 units in at least one BGR channel for ≥ 95 % of sampled frames, **in every snapshot** | Proves planar/shuffled bands remain coherent over time |
| **C2 L0 scroll motion** | Mean absolute frame-difference ≥ 5.0 in **every snapshot** | Proves scroll does not stall or drift |
| **C3 sprite presence** | Sprite detection fraction ≥ 95 % and x-range ≥ 100 px in **every snapshot** | Proves sprite paths remain stable over time |
| **C4 stability** | Combined C1a/C1b outlier rate ≤ 5 % in **every snapshot** | Proves no mid-frame corruption accumulates |
| **C5 zero corruption** | No lock-up, tearing, color corruption, or SDRAM drift observed during the 1-hour window | Proves long-run electrical/thermal stability |

Scenario passes when C1a, C1b, C2, C3, C4, and C5 PASS.

---

## 5. Physics / interpretation notes

- The copper fires at `y = 0`, `y = 160`, and `y = 320`. Because line-buffer fill occurs during the previous line’s hblank, the mode switch should take effect on the line immediately following the trigger. A ±1 line visual offset is acceptable.
- Any visible shift in band boundaries (>5 lines) between T=0 and T=60 min indicates drift or timing instability.
- The soak test is purely a duration extension of the already-proven Sc15 integration scene.

---

## 6. Out of scope

- New primitives or register maps
- Color math / window (already validated by Scenario 14)
- Affine background (already validated by Scenario 12)
- Per-line affine updates
- Stress-load testing (Task 23)
