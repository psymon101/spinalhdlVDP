# SCENARIO_51.md — Color/Window XOR + Highlight Proof

**Wave:** Hardening (Priority C)
**Validates:** Task R6 / Color/Window Hardening — Checkpoint C hardware proof
**Depends on:** Scenario 50 (Sprite Phase 2)
**Capture protocol:** 30 s, 1920×1080 @ 30 fps (RTSP letterbox)
**Owner:** BrightForge (implementation) / CyanPeak (audit)
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

This scenario demonstrates the simultaneous operation of four Color/Window sub-features on real silicon:
- **CW-1 (Runtime Palette RAM):** Overwriting default palette entries via the register bus.
- **CW-4 (Highlight Mode):** ColorMath operation performing `channel << 1` (clamped to 0xFF).
- **CW-5 (Dual Window XOR):** Combining two rectangular windows using boolean XOR logic.
- **CW-6 (Per-layer masking):** Gating specific layers (sprites) in the window region.

---

## 2. Scene Description

The scene uses a static copper program to set up a 640×480 rendering region divided into four quadrants by two overlapping windows:
- **Window 1:** Top half of the screen (`y < 240`).
- **Window 2:** Left half of the screen (`x < 320`).
- **Combination:** XOR mode.

**Quadrant Map:**
- **TL (Top-Left):** Window 1 (T) XOR Window 2 (T) = **False** (Normal)
- **TR (Top-Right):** Window 1 (T) XOR Window 2 (F) = **True** (Effect)
- **BL (Bottom-Left):** Window 1 (F) XOR Window 2 (T) = **True** (Effect)
- **BR (Bottom-Right):** Window 1 (F) XOR Window 2 (F) = **False** (Normal)

---

## 3. Register Setup (Copper)

```
0x0601 = 0x00 (Palette Ptr bank 0, entry 0)
0x0600 = [RED, GREEN, BLUE] (Entries 1..3 overwritten)
0x0330 = 0 (Win1 X0)
0x0331 = 640 (Win1 X1)
0x0332 = 0 (Win1 Y0)
0x0333 = 240 (Win1 Y1)
0x0335 = 0 (Win2 X0)
0x0336 = 320 (Win2 X1)
0x0337 = 0 (Win2 Y0)
0x0338 = 480 (Win2 Y1)
0x033A = 3 (XOR mode)
0x0400 = 0x0182 (Enable ColorMath, Op=10 Highlight, Constant=0)
```

---

## 4. Expected Pass Criteria

### Automated (OpenCV Intensity Scan)
- **Diagonal Contrast:** Quadrants TR and BL must show a statistically significant increase in mean intensity (≈6–7 levels) compared to TL and BR.
- **Boundary Precision:** Boundaries must be sharp at `x=320` and `y=240`.

### Visual
- The screen should show a clear diagonal XOR pattern of normal and highlighted background tiles.

---

## 5. Potential Failure Modes

- **Inverted Logic:** XOR quadrants appear in TL/BR instead of TR/BL.
- **Palette Corruption:** Background colors appear as random garbage after rewrite.
- **Timing Glitches:** Highlights appear smeared or shifted due to display-stage combinational pressure.

---

## 6. Out of scope

- Sub-line precision (Scenario 60).
- Complex sprite-masking boundaries (Scenario 52).
