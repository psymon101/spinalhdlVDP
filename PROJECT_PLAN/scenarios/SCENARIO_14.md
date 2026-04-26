# SCENARIO_14.md — Color Math / Window Scene

**Wave:** 2
**Validates:** Task 20 (R6 Color Math + Window post-palette stage)
**Depends on:** Scenario 1
**Capture protocol:** 30 s, 720×480 YUYV @ 50 fps lossless x264 qp=0 yuv444p
**Owner:** BrightForge / CyanPeak
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

Validate the post-palette color math + window stage on hardware in the scenario matrix. Re-validates the Task 20 closeout deliverable in the scenario-matrix framing.

## 2. Bootstrap register sequence — **REUSES `scenarioId=0`**

Per CoralReef #7282, this scenario reuses the **default `scenarioId=0`** bitstream which is the Task 20 + R4.1d Checkpoint C scene already programmed by the existing `TopTang20kHdmi.scala` bootstrap:
- LAYER_ENABLE = `0x0001` (L0 only)
- VDP_TILE_MODE = `0x0002` (shuffled bitplane)
- VDP_ATTR_MODE = `0x0000` (linear)
- VDP_CTRL = `0x0000` (copper disabled)
- VDP_WIN_X0..Y1 = `(160, 480, 120, 360)` — centred 320×240 window
- VDP_COLOR_MATH = `0x4000` (op=01 shadow, no invert, constant=0)
- All sprites disabled

**Build/flash command:** `make all` (no SCENARIO= parameter — selects the default `TopTang20kHdmiVerilog`).

## 3. Expected visual output

R4.1d bitplane-checkerboard background (4 grayscale shades in 2×2 layout) with a clearly darker rectangle in the centre — every shade inside the rectangle visibly halved by the shadow op.

## 4. OpenCV pass criteria

Reuses the Task 20 closeout analysis (commit `dd119ec` evidence), specifically:

| Check | Condition | Reason |
|---|---|---|
| **C1 shadow ratio** | Inside-window mean intensity / outside-window mean intensity ∈ `[0.40, 0.60]` (target 0.50 for `RGB >> 1`) | Gold-standard shadow proof |
| **C2 inside-window bands** | Inside-window pixels distribute ≥ 90 % across the four halved bands `[0±15, 42±15, 85±15, 127±15]` | Each background shade has a corresponding halved peak inside the window |
| **C3 outside-window bands** | Outside-window pixels distribute ≥ 90 % across the four original bands `[0±15, 85±15, 170±15, 255±15]` | Background outside the window is unaffected |

Scenario passes when C1, C2, C3 PASS.

## 5. Reference evidence (already captured by Task 20 closeout `dd119ec`)

```
inside  window mean intensity:  60.54
outside window mean intensity: 122.24
inside / outside ratio       :   0.495   ✓ PASS

outside histogram coverage in 4 bands: 98.90%   ✓ PASS
inside  histogram coverage in 4 bands: 100.00%  ✓ PASS
```

This scenario can be marked PASS by re-running the same capture against the current `scenarioId=0` bitstream and confirming the metrics still match the Task 20 reference within tolerance.

## 6. Out of scope
- Add-constant mode (Task 20 sim already covers it; not part of this HW proof)
- Window invert
- Per-frame window animation
