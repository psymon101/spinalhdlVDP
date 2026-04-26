# SCENARIO_17.md — Stress-Scene Validation

**Wave:** 3  
**Validates:** Task 23 (Stress-Scene Validation)  
**Depends on:** Scenarios 6, 8, 15, 16  
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p  
**Owner:** BrightForge / CyanPeak  
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Exercise the Mode0 substrate at its documented load ceiling: all layers, all sprites, copper mode-switching, and dual-layer scroll simultaneously. This is the worst-case scene the hardware is expected to handle stably.

---

## 2. Bootstrap register sequence — `scenarioId=17`

The bootstrap configures **maximum concurrent load**:

- `LAYER_ENABLE = 0x0007` (L0 + L1 + sprites all enabled)
- `VDP_TILE_MODE = 0x0000` (tile mode) as the initial state
- `VDP_ATTR_MODE = 0x0000` (linear attributes)
- `VDP_COPPER_CTRL = 0x0001` (copper enabled)
- Copper program (3 triggers):
  - `y =   0`: write `0x0311 = 0x0000` (reset L0 to packed mode at frame start)
  - `y = 160`: write `0x0311 = 0x0001` (switch L0 to planar mode)
  - `y = 320`: write `0x0311 = 0x0002` (switch L0 to shuffled mode)
- L0 scroll: 2 px/frame
- L1 scroll: 4 px/frame (parallax, 2× L0 speed)
- Sprite 0: bouncing horizontally at 4 px/frame, `y = 80`
- Sprite 1: bouncing horizontally at 4 px/frame (opposite phase), `y = 200`
- Sprite 2: bouncing horizontally at 4 px/frame, `y = 320`
- Sprite 3: bouncing horizontally at 4 px/frame (opposite phase), `y = 400`
- All other sprites disabled (hardware limit: 4 descriptors)

**Build/flash command:** `make all SCENARIO=17`

---

## 3. Expected visual output

The screen shows a full three-layer composition:
- **L0** (background): three horizontal bands (packed / planar / shuffled) visible where L1 is transparent
- **L1** (midground): fast-scrolling packed tiles, partially occluding L0
- **Sprites** (foreground): four sprites bouncing horizontally across the full height

The compositor must blend all three layers every pixel. The fetch engine must service:
- L0 mixed-mode line buffer (mode-switched mid-frame by copper)
- L1 packed tile line buffer
- Up to 2 sprites visible per line (hardware limit)

---

## 4. OpenCV pass criteria

| Check | Condition | Reason |
|---|---|---|
| **C1 visual integrity** | No tearing, speckles, color glitches, or band collapse for ≥ 95 % of sampled frames | Proves the compositor and fetch engine remain coherent under maximum load |
| **C2 sprite presence** | All 4 sprites detected ≥ 95 % of frames; each sprite x-range ≥ 100 px | Proves the sprite evaluator handles max descriptor count without dropping |
| **C3 dual-layer scroll** | Mean absolute frame-difference ≥ 5.0 across the full 30 s | Proves both L0 and L1 scroll paths remain active and coherent |
| **C4 stability** | No lock-up, drift, or SDRAM artifacts observed during the 30 s window | Proves the integrated pipeline is electrically and thermally stable under stress |

Scenario passes when C1, C2, C3, and C4 PASS.

---

## 5. Physics / interpretation notes

- The compositor priority is L1 over L0, sprites over L1. L0 bands will be partially hidden by L1 tiles.
- The sprite evaluator is configured for `descCount=4, visiblePerLine=2`. Having all 4 sprites bouncing ensures the evaluator's per-line scan and priority sort are exercised every frame.
- Fast scroll speeds (2 px/frame L0, 4 px/frame L1) maximize SDRAM fetch turnover.
- The copper fires three times per frame (y=0, 160, 320), maximizing register-commit traffic.

---

## 6. Out of scope

- New primitives or register maps
- Increasing hardware sprite limits
- Soak testing (already validated by Scenario 16 / Task 22)
