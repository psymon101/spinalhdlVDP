# SCENARIO_50.md — ZX Spectrum Adapter Proof

**Wave:** Adapter (Phase 10)
**Validates:** Task 50 — ZX Spectrum Adapter — Hardware proof
**Depends on:** Scenario 44 (Bitmap + Attribute Fetch), Beam Hardening
**Capture protocol:** 30 s, 1920×1080 @ 30 fps (RTSP letterbox)
**Owner:** BrightForge (implementation) / CyanPeak (audit)
**Status:** DONE — audited by CyanPeak

---

## 1. Purpose

This scenario demonstrates a recognizable ZX Spectrum display on the Tang Nano 20K using the Mode0 substrate's bitmap + attribute fetch path. It validates that:

- The `BitmapFetch` 1bpp Spectrum decode works on real silicon.
- Runtime palette RAM can hold the 15-color Spectrum palette.
- The dual-window logic can implement a visible border around a 256×192 active area.
- The adapter translation layer correctly maps ULA-style semantics to Mode0 registers.

---

## 2. Scene Description

A static 256×192 bitmap + attribute test pattern is uploaded to SDRAM and displayed centered in the HDMI output with a colored border.

**Bitmap content:** A checkerboard or simple text pattern in pre-shuffled linear row-major format.
**Attribute content:** A grid of 8×8 color cells demonstrating ink/paper/bright combinations.
**Border color:** Cyan (color 5) — a visibly non-black border.

---

## 3. Bootstrap Sequence

```
; 1. Load Spectrum palette into runtime palette RAM (0x0600/0x0601)
;    Entries 0..15 as defined in TASK_50 artifact §8.

; 2. Upload bitmap data (pre-shuffled, linear row-major) to SDRAM 0x6000
;    6144 bytes = 256×192 / 8

; 3. Upload attribute data (row-major) to SDRAM 0x5800
;    768 bytes = 32×24

; 4. Configure bitmap fetch registers
0x0350 = 0x0001    ; BITMAP_CTRL = enable | 1bpp
0x0351 = 0x6000    ; BITMAP_BASE_LO
0x0352 = 0x0000    ; BITMAP_BASE_HI
0x0353 = 0x5800    ; ATTR_BASE_LO
0x0354 = 0x0000    ; ATTR_BASE_HI
0x0355 = 0x0020    ; BITMAP_STRIDE = 32 bytes/row
0x0356 = 0x0020    ; ATTR_STRIDE = 32 bytes/row

; 5. Configure window for border effect
;    WIN0 covers active 256×192 area, inverted so outside = border
0x0330 = 192       ; WIN0_X0 (centered in 640×480)
0x0331 = 448       ; WIN0_X1 (192 + 256)
0x0332 = 144       ; WIN0_Y0
0x0333 = 336       ; WIN0_Y1 (144 + 192)
0x0334 = 0x0002    ; WIN0_CTRL = invert

; 6. Set border color via adapter
0x0E00 = 0x05      ; ZX_BORDER = cyan

; 7. Enable adapter
0x0E03 = 0x01      ; ZX_CTRL = enable
```

---

## 4. Expected Pass Criteria

### Automated (OpenCV)
- **Glitch/Freeze:** 0 glitches, 0 freezes over 500+ frames.
- **Border color:** Mean intensity of border region matches expected cyan (±10%).
- **Active area:** Non-zero pixel variance in central 256×192 region.
- **Stability:** No frame-to-frame changes (static scene).

### Visual
- A 256×192 bitmap is visible, centered in the HDMI frame.
- Color cells are visible as 8×8 blocks (attribute granularity).
- A cyan border surrounds the active area.
- The display is stable with no tearing or corruption.

---

## 5. Notes

- The bitmap data must be **pre-shuffled** by the host into linear row-major order. The on-FPGA fetch path does NOT implement Spectrum-style shuffled addressing.
- FLASH attribute is disabled in this scenario (static display).
- The 256×192 area is displayed at 1× scale within the 640×480 frame, surrounded by border. No integer scaling is applied in this first proof.
