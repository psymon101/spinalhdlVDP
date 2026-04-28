# SCENARIO_60.md — Beam Hardening Smoke Test

**Wave:** Hardening (Priority D)
**Validates:** Task — Beam-Driven Automation Hardening — Checkpoint C hardware proof
**Depends on:** Scenario 52
**Capture protocol:** 30 s, 1920×1080 @ 30 fps (RTSP letterbox)
**Owner:** BrightForge (implementation) / CyanPeak (audit)
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

This scenario provides a hardware smoke test for the combined Beam-Driven Automation stack. It verifies that the Copper FSM (with pixel-precise WAIT), HDMA engine, and Raster Triggers can coexist on real silicon without timing or resource violations.

---

## 2. Scene Description

The scene runs a single-frame copper program that changes the background rendering mode at a specific mid-screen pixel coordinate.
- **WAIT_PX:** Targets `x=320, y=200`.
- **HDMA:** (If active) provides concurrent register pressure on unrelated background layers.
- **Raster Triggers:** Programmed to fire at specific lines to verify interrupt/status functionality alongside Copper execution.

---

## 3. Copper Program (Pseudo-code)

```
WAIT y=0
WIN1 = full screen, COLOR_MATH = passthrough
WAIT_PX (x=320, y=200)        # BH-1 pixel-precise
COLOR_MATH = op=01 shadow     # Commit triggered at match
JUMP 0
```

---

## 4. Expected Pass Criteria

### Automated (OpenCV Intensity Scan)
- **Vertical Stability:** The shadow transition must remain stable over 30s.
- **Line Granularity:** While `WAIT_PX` matches at `x=320`, the `COLOR_MATH` register (using safe-boundary commit) is expected to transition on the subsequent line (`y≈203–204`).
- **Timing:** 0 glitches or freezes detected over 500+ frames.

### Visual
- The screen is divided horizontally into a top (normal) and bottom (shadow) section, with the split occurring near `y=200`.

---

## 5. Potential Failure Modes

- **FSM Hang:** Copper stops executing, resulting in a static display or no shadow transition.
- **Trigger Noise:** Raster triggers fire at incorrect coordinates due to logic noise.
- **HDMA Interference:** HDMA register writes corrupt Copper-driven state (or vice-versa), indicating a register-bus arbitration fault.

---

## 6. Out of scope

- Direct HW proof of sub-line (X) precision (validated via unit sim `CopperSim` Case 5).
- Complex HDMA indirect patterns (validated via unit sim `CopperHdmaSim` Case 7).
