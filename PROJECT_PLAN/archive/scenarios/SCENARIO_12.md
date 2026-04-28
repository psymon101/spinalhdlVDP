# SCENARIO_12.md — Affine Background with Sprite

**Wave:** 3
**Validates:** Task 19 (Affine Layer) — Checkpoint C hardware proof
**Depends on:** Scenario 4 (single sprite must work first) + Scenario 8 (L0 background must work first)
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge (coding) / CyanPeak (audit)
**Status:** DONE — audited by CyanPeak; hardware-proven at Task 19 close

---

## 1. Purpose

Validate that the affine background transform (Task 19 `AffineStepper`) produces a visibly rotated/scaled texture on hardware, with a non-affine sprite composited correctly on top. This is the hardware proof gate for the affine background path.

---

## 2. Bootstrap register sequence (`scenarioId=12`)

| # | Addr | Data | Effect |
|---|------|------|--------|
| 1 | `0x0300` | `0x0005` | LAYER_ENABLE = L0 + sprite (affine background under sprite) |
| 2 | `0x0310` | `0x0000` | copper disabled |
| 3 | `0x0311` | `0x0000` | VDP_TILE_MODE = packed (default) |
| 4 | `0x0312` | `0x0000` | VDP_ATTR_MODE = linear |
| 5 | `0x0334` | `0x0000` | color math passthrough |
| 6 | `0x0346` | `0x0001` | AFFINE_CTRL = enable affine background |

After bootstrap, a per-vsync animator (6 cycles/frame) rewrites the affine matrix registers `0x0340..0x0345` from a 180-entry precomputed LUT:

- **Rotation:** 2°/frame (full 360° cycle in 180 frames ≈ 3.6 s at 50 fps)
- **Scale:** 0.2× (zoom so one 128-texel tile spans the 640-px screen width — per BronzeGate #7340 / CyanPeak #7341)
- **Center:** Screen center (320, 240); texture center (64, 64)
- **Matrix format:** Q8.8 for A/B/C/D, Q10.6 for X/Y

Matrix registers written each frame (addresses `0x0340..0x0345`):
| Register | Address | Content |
|----------|---------|---------|
| AFFINE_A | `0x0340` | `scale × cos(θ)` |
| AFFINE_B | `0x0341` | `-scale × sin(θ)` |
| AFFINE_C | `0x0342` | `scale × sin(θ)` |
| AFFINE_D | `0x0343` | `scale × cos(θ)` |
| AFFINE_X | `0x0344` | `tcx - cx×A - cy×B` |
| AFFINE_Y | `0x0345` | `tcy - cx×C - cy×D` |

Top-level Scala (`TopTang20kHdmiScenario12`):
- L0 scroll = 0 (static; affine transform provides all motion)
- L1 disabled
- Sprite 0: enabled, pattern 0, bouncing horizontally at 2 px/frame, Y = 200
- Sprites 1–3: disabled

**Build/flash command:** `make all SCENARIO=12`

---

## 3. Expected visual output

- **Background:** The 128×128 diagnostic texture (vertical/horizontal grid lines + center dot) is rendered across the full 640×480 screen through the affine transform. The grid rotates continuously at 2°/frame and is zoomed to 0.2× so individual texels are clearly visible as large blocks.
- **Sprite:** One 16×16 sprite (pattern 0) moves horizontally across the screen at Y = 200, bouncing between x = 16 and x = 624 at 2 px/frame. The sprite renders correctly over the rotating background without tearing or priority glitches.

---

## 4. OpenCV pass criteria

Capture: 30 s, 720×480 YUYV 50 fps lossless.

| Check | Condition | Reason |
|---|---|---|
| **C1 rotation present** | Edge orientation histogram shows dominant peaks offset from 0°/90° by the current rotation angle (±10° tolerance), and the offset changes monotonically across the capture | Proves the transform is genuine rotation, not just scroll or static texture |
| **C2 scale present** | Measured texel block size in the background ≈ 5× the native tile pixel size (0.2× inverse scale) | Proves the zoom is active |
| **C3 sprite presence** | Sprite detected by color/blob segmentation in ≥ 95 % of frames; x-range ≥ 100 px | Proves sprite path is stable over affine background |
| **C4 no tearing** | 0 frames with visible horizontal/vertical tear lines at the sprite boundary or within the background | Proves affine + sprite compositing is race-free |
| **C5 stability** | 0 freezes; 0 isolated jumps > 5σ above mean inter-frame diff | No drop / overflow / glitch |

Scenario passes when C1, C2, C3, C4, and C5 all PASS.

---

## 5. Failure modes to watch for

- Background looks scrolled but not rotated: affine matrix math error or fixed-point interpretation bug
- Background is static: animator not running, or `AFFINE_CTRL` not enabled
- Sprite missing or flickering: compositor priority bug when `layerSource` indicates affine background
- Tearing at rotation boundary: matrix registers not committed atomically at frame edge
- Zoom incorrect: scale parameter mismatch between animator and `AffineStepper` contract

---

## 6. Out of scope

- Affine sprites (Task 37 — covered by Scenario 37)
- Deep-angle quality tuning (deferred per `MODE0_HARDENING_BACKLOG.md`)
- Per-sprite affine matrices (Task 37)
- Background scroll + affine simultaneously (L0 scroll is held at 0)
