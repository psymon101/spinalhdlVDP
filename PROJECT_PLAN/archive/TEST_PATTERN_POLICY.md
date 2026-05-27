<!-- NON-CANONICAL: archived policy. Superseded by transport canary + OpenCV evidence standard. -->
**DEPRECATED — 2026-05-25**

This policy is preserved as historical reference only. The active evidence standard is defined in `AGENTS.md` §Evidence Standard (transport canary + bitstream sha1 + capture artifact + OpenCV-derived numeric). No new task should reference this document.

---

# Test Pattern Policy for spinalhdlVDP

**Updated:** 2026-04-13  
**Purpose:** Define the standard validation patterns and proof-scene rules for every hardware-affecting task. This policy exists because the R4 experience proved that busy decorative patterns create motion-blur ambiguity and waste debug time.

---

## The Rule

> **Every hardware-affecting task must specify a static proof scene and a motion proof scene using one of the standard patterns below.**

No more ad-hoc checkerboard mosaics or artistic tilemaps as the primary validation surface. Decorative patterns are fine for demos, but they are not acceptable as the sole task-completion evidence.

---

## Why This Matters

The R4 "bank-2 everywhere" bug was eventually diagnosed as a **motion-blur capture artifact**, not a hardware bug. The actual hardware was always correct, but the busy gradient pattern made a single-frame capture ambiguous. OpenCV frame-by-frame analysis was required to resolve the doubt.

Industry consensus (Xilinx TPG, Intel VIP, MiSTer 240p Test Suite, academic FPGA verification literature) agrees:

- **Simple, deterministic patterns** expose glitches immediately.
- **Solid color fields** make color-channel errors and bandwidth drops obvious.
- **Regular geometry grids** make aspect-ratio and timing errors obvious.
- **High-frequency stripes** make horizontal/vertical tearing obvious.

---

## Available Standard Patterns

The project now includes `TestPatternSource` in `hw/spinal/spinalhdlvdp/`. It is selectable at runtime via `layer0TestPatternSelect` (3 bits) when `layer0TestPatternEnable` is asserted.

| Select | Pattern | Best Used For |
|--------|---------|---------------|
| 0 | Color bars (8 vertical bars: black/white/red/green/blue/yellow/cyan/magenta) | Quick color-channel sanity check; classic reference |
| 1 | Solid red field | Pure R channel verification; detects dropped R or cross-channel bleed |
| 2 | Solid green field | Pure G channel verification |
| 3 | Solid blue field | Pure B channel verification |
| 4 | Solid gray field | Luma/brightness stability; good for detecting intensity drift |
| 5 | Checkerboard (16×16 black/white tiles) | Detects spatial discontinuity, seam errors, and wraparound bugs |
| 6 | Grid (white lines every 64 px on black) | Geometry, aspect ratio, and scaling verification |
| 7 | Vertical stripes (1 px black/white) | Highest horizontal frequency; detects timing jitter and tearing |

### How to Enable

In `TopTang20kHdmi.scala`, override the defaults:

```scala
video.io.layer0TestPatternEnable := True
video.io.layer0TestPatternSelect := U(1, 3 bits)  // solid red field
```

This bypasses the SDRAM fetch path entirely, so the pattern is available even if the fetch engine is under debug or not yet proven for the task.

---

## Task Proof Scene Requirements

Every new `TASK_*.md` must fill in these two fields in its **Hardware Proof** section:

### 1. Static Proof Scene

- **Pattern:** one of the standard patterns above
- **Scroll:** static (scroll offsets = 0 or frozen)
- **What correct looks like:** one sentence

Example:

> Static proof: solid red field (pattern 1). The entire 640×480 active area is uniform bright red with no bands, streaks, or color fringing.

### 2. Motion Proof Scene

- **Pattern:** one of the standard patterns above
- **Scroll:** slow uniform scroll in one axis (typically 1–4 px/frame)
- **What correct looks like:** one sentence

Example:

> Motion proof: color bars (pattern 0) scrolling horizontally at 2 px/frame. Bars remain crisp and color-pure; no tearing, smearing, or horizontal banding.

---

## Pattern Selection Guidelines by Task Type

| Task concern | Recommended static pattern | Recommended motion pattern |
|--------------|---------------------------|---------------------------|
| Color/palette path | Solid red, green, blue | Color bars |
| SDRAM/fetch/bandwidth | Solid gray or color bars | Checkerboard or color bars with scroll |
| Timing/line buffer/CDC | Vertical stripes | Grid with vertical scroll |
| Layer composition | Color bars or solid fields | Checkerboard with opposing scroll on each layer |
| Sprite overlay | Solid field (sprite visibility) | Solid field with slow scroll |
| Wraparound/seam | Checkerboard | Checkerboard with scroll crossing boundary |

---

## Anti-Patterns (Do Not Use as Primary Proof)

These are fine for demos, but do not satisfy the task proof-scene requirement on their own:

- Complex tilemaps with many different tiles
- Artistic sprites or logos
- Gradient ramps (unless the task is specifically about palette ramps)
- Scenes with many simultaneous objects of different colors
- Any pattern where a correct result cannot be verified from a single still capture

---

## Validation Checklist for Audit

Before signing off on a hardware-affecting task, CyanPeak must confirm:

1. The task doc names a specific standard pattern for static proof.
2. The task doc names a specific standard pattern for motion proof.
3. The captured evidence matches the stated "what correct looks like" sentence.
4. No busy decorative pattern is the sole evidence.

---

## References

- Xilinx PG103 — Video Test Pattern Generator (color bars, grayscale, solid field)
- Intel VIP — Test Pattern Generator II (SMPTE bars, black/white, pathological)
- MiSTer 240p Test Suite — Monoscope, color bars, solid-color screens
- P. Kopytov & A. Semyonov, *FPGA-Based SDI Stream Verification*, Springer (macroblock enumeration for deterministic error localization)
