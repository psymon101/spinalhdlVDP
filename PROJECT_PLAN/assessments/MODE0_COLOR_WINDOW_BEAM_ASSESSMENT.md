# Mode0 Color / Window / Beam-Driven Assessment Report

**Assessment version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-25  
**Commit:** TBD  
**Scope:** Assessment / analysis only; no substrate implementation changes authorized

---

## Executive Summary

This assessment evaluates three shared `Mode0` primitives against their intended maximum envelopes and platform pressure:

| Primitive | Current Status | Assessment | Disposition |
|---|---|---|---|
| **Palette / Color Pipeline** | `Usable` | Runtime-writable palette RAM missing; sprite palette banking wired but unused; ColorMath global-only; no highlight mode | **Harden** |
| **Window / Mask / Post-Compositor** | `Usable` | Single rectangle only; cannot mask layers/sprites individually; no multiple windows | **Harden** |
| **Beam-Driven Automation** | `Usable` | Copper WAIT line-only; no conditional branches; HDMA 8-bit line wrap; single raster trigger | **Harden** |

**Top-line recommendation:** Open bounded **Color/Window Hardening** before Beam-Driven Hardening. Color/Window gaps block SNES/Genesis adapter claims more directly than Beam-Driven gaps.

---

## 1. Palette / Color Pipeline — Deep Audit

### 1.1 What Exists

| Component | Evidence | Status |
|---|---|---|
| 128-entry × 24-bit palette ROM | `VdpTop.scala:1120`, `TileAttributeAssets.paletteInit` | DONE |
| 8 banks × 16 colors | Address `{bank[2:0], idx[3:0]}` | DONE |
| ColorMath stage (shadow, add-constant) | `ColorMath.scala` (R6 Task 20) | DONE |
| Window-gated ColorMath enable | `WindowUnit.io.effect → ColorMath.io.enable` | DONE |
| Per-pixel metadata (`mathEnable`, `forcedPriority`, `layerSource`) | `PixelMetadata.scala` (Task 41/48) | Structural only — not wired to consumers |

### 1.2 Confirmed Substrate Gaps

| Gap | Impact | Platform Pressure |
|---|---|---|
| **No runtime-writable palette RAM** | Colors fixed at synthesis | All platforms need dynamic palette changes |
| **Sprite palette bank wired but unused** | All sprites forced to bank 0 | Genesis (4 banks), SNES (8 banks), Neo Geo (16 banks) |
| **`mathEnable` metadata not wired to ColorMath** | Cannot opt individual pixels into color math | SNES per-pixel color math, Genesis shadow/highlight |
| **No highlight mode** | Only shadow and add-constant exist | Genesis shadow/highlight requires both |
| **No inter-palette or inter-layer blending** | Operations restricted to single RGB stream | SNES color math between layers |

### 1.3 Adapter-Local Quirks

| Quirk | Platform | Why Adapter-Local |
|---|---|---|
| Exact measured palette values | All | Mode0 provides palette RAM; adapter loads historical values |
| Bright/intensity/flash semantics | C64, Spectrum | Platform-specific color attribute rules |
| Analog-look compromises | All | Display/presentation layer, not substrate |

### 1.4 Resource Budget Estimate

| Change | LUT | FF | BSRAM | DSP |
|---|---|---|---|---|
| Runtime-writable palette RAM (128×24) | +50 | +100 | +1 (or use existing BSRAM) | 0 |
| Sprite palette bank plumbing | +100 | +50 | 0 | 0 |
| `mathEnable` → ColorMath gate | +50 | +20 | 0 | 0 |
| Highlight mode (channel << 1 clamp) | +50 | 0 | 0 | 0 |
| **Total** | **+~250** | **+~170** | **+0–1** | **0** |

**Zone after:** Still green. Minimal impact.

### 1.5 Ruling

**Palette/Color Pipeline needs bounded hardening.** The gaps are small and well-bounded. Runtime-writable palette RAM + sprite palette plumbing + mathEnable wiring + highlight mode closes honest SNES/Genesis color claims.

---

## 2. Window / Mask / Post-Compositor — Deep Audit

### 2.1 What Exists

| Component | Evidence | Status |
|---|---|---|
| Single rectangular window | `WindowUnit.scala` (R6 Task 20) | DONE |
| Window registers (x0, x1, y0, y1, invert) | `0x0330..0x0334` in `VdpTop.scala` | DONE |
| Window gates ColorMath | `WindowUnit.io.effect → ColorMath.io.enable` | DONE |
| Safe-boundary commit | `hCounter === 0` shadow register pattern | DONE |

### 2.2 Confirmed Substrate Gaps

| Gap | Impact | Platform Pressure |
|---|---|---|
| **Only one window** | No complex masking | SNES (2 windows + combinations), Genesis (2 windows) |
| **Window only gates ColorMath** | Cannot mask individual layers/sprites | SNES window-per-layer masking, Genesis sprite window |
| **No window priority / layering** | Cannot combine windows with AND/OR/XOR | SNES window logic combinations |
| **No post-compositor blending** | Only binary opaque/transparent | SNES color math between layers/subscreens |

### 2.3 Adapter-Local Quirks

| Quirk | Platform | Why Adapter-Local |
|---|---|---|
| Exact register maps and mode names | SNES, Genesis | Platform-specific control semantics |
| Window-to-sprite-class rules | Genesis | Platform-specific object/background classification |

### 2.4 Resource Budget Estimate

| Change | LUT | FF | BSRAM | DSP |
|---|---|---|---|---|
| Second window comparator | +100 | +80 | 0 | 0 |
| Window combination logic (AND/OR/XOR) | +150 | +50 | 0 | 0 |
| Per-layer window masking | +200 | +100 | 0 | 0 |
| **Total** | **+~450** | **+~230** | **0** | **0** |

**Zone after:** Still green.

### 2.5 Ruling

**Window/Post-Compositor needs bounded hardening.** Single-window → dual-window + combination logic + per-layer masking is the minimum for honest SNES/Genesis claims. This is higher-complexity than palette hardening but still well-bounded.

---

## 3. Beam-Driven Automation — Deep Audit

### 3.1 What Exists

| Component | Evidence | Status |
|---|---|---|
| Copper coprocessor (512×16 RAM, WAIT/WRITE/WRITE_SEQ/JUMP) | `Copper.scala` (R5) | DONE |
| HDMA engine (4 channels × 8 entries) | `Copper.scala` (Task 33) | DONE |
| Raster trigger unit (single trigger) | `RasterTriggerUnit.scala` (R1) | DONE |
| IRQ/status bank (raster, sprite overflow, DMA, blit) | `VdpTop.scala:1165–1218` (Task 35) | DONE |
| Safe-boundary copper drain | `copperFifo` drained at `hCounter === 0` | DONE |

### 3.2 Confirmed Substrate Gaps

| Gap | Impact | Platform Pressure |
|---|---|---|
| **Copper WAIT is line-only** | No sub-line precision | Amiga Copper (pixel-precision WAIT), Atari ST raster bars |
| **No conditional branches** | Limited program flow | Amiga Copper SKIP on conditions |
| **HDMA 8-bit line compare** | Wraps at 256 lines | SNES HDMA (needs 9-bit for 240-line modes) |
| **No HDMA indirect mode** | Cannot point to data block | SNES HDMA indirect table |
| **Single raster trigger** | Only one IRQ line | Genesis H-int (per-line), SNES (multiple IRQ types) |
| **No beam-driven DMA trigger** | DMA/blitter not raster-synchronized | Amiga blitter-Copper synchronization |

### 3.3 Adapter-Local Quirks

| Quirk | Platform | Why Adapter-Local |
|---|---|---|
| Exact Copper instruction format | Amiga | Platform-specific opcode encoding |
| Exact HDMA channel register map | SNES | Platform-specific channel control |
| Platform-specific trigger status naming | All | Register semantics, not substrate |

### 3.4 Resource Budget Estimate

| Change | LUT | FF | BSRAM | DSP |
|---|---|---|---|---|
| Copper WAIT X,Y (pixel precision) | +150 | +100 | 0 | 0 |
| Copper conditional SKIP | +100 | +50 | 0 | 0 |
| HDMA 9-bit line compare | +50 | +20 | 0 | 0 |
| HDMA indirect mode | +100 | +50 | 0 | 0 |
| Multiple raster triggers (4×) | +100 | +80 | 0 | 0 |
| **Total** | **+~500** | **+~300** | **0** | **0** |

**Zone after:** Still green.

### 3.5 Ruling

**Beam-Driven Automation needs bounded hardening, but less urgently than Color/Window.** The existing machinery (Copper, HDMA, RasterTrigger) is already usable for C64/Amiga basic cases. The gaps (pixel-precision WAIT, conditional SKIP, 9-bit HDMA) matter most for advanced Amiga/SNES effects, not for honest adapter foundation.

---

## 4. Cross-Primitive Platform Pressure

| Platform | Palette Pressure | Window Pressure | Beam-Driven Pressure |
|---|---|---|---|
| C64 | Constrained 16-color; no runtime RAM needed for basic | None | Raster splits (basic; current RasterTrigger sufficient) |
| NES | 4 palettes × 8 sprites; needs runtime RAM | None | None |
| Genesis | Shadow/highlight; 4 sprite palettes; runtime RAM | 2 windows + sprite window | H-int per-line updates (current HDMA sufficient) |
| SNES | Color math between layers; 8 sprite palettes; runtime RAM | 2 windows + combinations + per-layer masking | HDMA indirect; multiple IRQ types |
| Amiga | 32-color (OCS); Copper palette cycling | None | Pixel-precision Copper WAIT; Copper blitter sync |
| Atari ST | 16-color (low-res); palette at vsync | None | Raster bars (line-only WAIT sufficient) |
| ZX Spectrum | Constrained bright/flash attributes | None | None |

---

## 5. Prioritized Recommendation

### 5.1 First: Color/Window Hardening (combined lane)

**Why first:**
- Color/Window gaps block honest SNES/Genesis claims more directly than Beam-Driven gaps
- Beam-Driven already works for basic C64/Amiga/Genesis cases
- Color/Window changes are more visually fundamental (users see wrong colors/masking immediately)

**Bounded scope:**
1. Runtime-writable palette RAM (128×24)
2. Sprite palette bank plumbing in compositor
3. `mathEnable` metadata → ColorMath gate
4. Highlight mode in ColorMath
5. Second window comparator + combination logic (AND/OR/XOR)
6. Per-layer window masking enable
7. Sim proof for all new features
8. Resource report

### 5.2 Second: Beam-Driven Hardening

**Bounded scope:**
1. Copper WAIT X,Y (pixel-precision)
2. Copper conditional SKIP
3. HDMA 9-bit line compare
4. HDMA indirect mode
5. Multiple raster triggers (4×)
6. Sim proof
7. Resource report

### 5.3 Resource Summary (Combined)

| Lane | LUT | FF | BSRAM | DSP | Zone |
|---|---|---|---|---|---|
| Color/Window Hardening | +~700 | +~400 | +0–1 | 0 | Green |
| Beam-Driven Hardening | +~500 | +~300 | 0 | 0 | Green |
| **Both combined** | **+~1,200** | **+~700** | **+0–1** | **0** | **Green** |

---

## 6. Exit Condition

This assessment is successful because it answers:
1. What color-math/window/beam-driven capabilities are already generic enough? → Palette banking, basic ColorMath, single window, Copper/HDMA foundation
2. Which missing shared hooks are highest-value? → Runtime palette RAM, sprite palette plumbing, dual-window + combinations, pixel-precision Copper WAIT
3. What is the stop-line-aware cost? → +~1,200 LUT / +~700 FF total for both lanes; stays green
4. Which should come first? → Color/Window before Beam-Driven

If the result is accepted, the next step is to open a bounded **Color/Window Hardening** task with explicit scope and stop-line expectations.
