# SCENARIO_52.md — Sprite Window Masking Proof

**Wave:** Hardening (Priority C)
**Validates:** Task CW-6 / Per-layer window masking — Checkpoint C hardware proof
**Depends on:** Scenario 51
**Capture protocol:** 5 s, 1920×1080 @ 30 fps (RTSP letterbox)
**Owner:** BrightForge (implementation) / CyanPeak (audit)
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

This scenario provides definitive hardware proof of per-layer window masking (CW-6). It demonstrates that a specific layer (the sprite layer) can be gated by the window effect while other layers (background) remain visible.

---

## 2. Scene Description

The scene builds on SCENARIO_51 but adds a large 64×64 sprite centered on the vertical boundary between the Top-Left (TL) and Top-Right (TR) quadrants.
- **Window:** XOR(top half, left half) → Effect=True in TR quadrant.
- **Sprite:** Positioned at `x=320` (straddling the mask boundary).
- **Masking:** `LAYER_MASK` bit[4] set to 1 (mask sprite layer).

---

## 3. Register Setup (Copper)

```
0x0330 = 0 (Win1 X0)
0x0331 = 640 (Win1 X1)
0x0332 = 0 (Win1 Y0)
0x0333 = 240 (Win1 Y1)
0x0335 = 0 (Win2 X0)
0x0336 = 320 (Win2 X1)
0x0337 = 0 (Win2 Y0)
0x0338 = 480 (Win2 Y1)
0x033A = 3 (XOR mode)
0x033B = 0x0010 (Mask Sprite layer, unmask BG0..3)
```

---

## 4. Expected Pass Criteria

### Automated (OpenCV Column Scan)
- **Sharp Mask Boundary:** The column at `x=320` must show an absolute transition.
- **Sprite Visibility:** Sprite pixels must be visible at `x=288..319`.
- **Sprite Gating:** Sprite pixels must be uniformly black (masked) at `x=320..352`.

### Visual
- A diamond-shaped sprite is visible on its left half and perfectly "cut off" at the screen's horizontal midpoint.

---

## 5. Potential Failure Modes

- **Logic Leak:** Faint sprite pixels appearing in the masked region.
- **Misalignment:** Mask boundary shifted by 1 or more pixels relative to background highlight boundary (indicating a pipeline delay mismatch).
- **Global Gating:** Sprite masked everywhere (regardless of window state).

---

## 6. Out of scope

- Multiple sprites (Scenario 50).
- Sub-line precision (Scenario 60).
