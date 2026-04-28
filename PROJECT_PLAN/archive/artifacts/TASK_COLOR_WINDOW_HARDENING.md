# Task — Color/Window Hardening

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-26  
**Scope:** Bounded substrate hardening — runtime palette RAM, dual-window + combinations, per-layer masking, color math enhancement

---

## 1. Why This Task Exists

Current Mode0 color/window primitives are rated `Usable` but have gaps that block honest SNES/Genesis adapter claims:
- No runtime-writable palette RAM (colors fixed at synthesis)
- Single rectangular window only (SNES/Genesis need 2+ windows)
- Window only gates ColorMath, cannot mask individual layers/sprites
- No highlight mode (Genesis shadow/highlight needs both)
- `mathEnable` metadata exists but not wired to ColorMath

From platform coverage audit (#8581) and Color/Window/Beam assessment (#8580).

---

## 2. Scope

### In Scope

1. **Runtime-writable palette RAM**
   - Replace ROM-initialized palette with BSRAM-backed `Mem(Bits(24), 128)`
   - Bus interface for host writes (existing palette block or new addresses)
   - Preserve Pepto-2001 C64 palette and color ramps as default init content
   - Backward compatible: existing scenes render identically if palette not overwritten

2. **Sprite palette bank plumbing (compositor)**
   - Wire `activePaletteBank` into compositor pixel fill path
   - This was deferred from Sprite Hardening Stage B (`d44a9c0` revert)
   - Now that palette RAM is writable, per-sprite palette banking becomes meaningful

3. **`mathEnable` metadata → ColorMath gate**
   - `PixelMetadata.mathEnable` exists in line buffer (Task 41/48)
   - Wire it to `ColorMath.io.enable` so individual pixels can opt into color math
   - Currently driven by window comparator only

4. **Highlight mode in ColorMath**
   - Current: shadow (channel >> 1) and add-constant
   - Add: highlight (channel << 1 clamp to 0xFF)
   - New `op` encoding: `00`=passthrough, `01`=shadow, `10`=highlight, `11`=add-constant

5. **Second window comparator + combination logic**
   - Add `WindowUnit2` alongside existing `WindowUnit`
   - Window combination modes: AND, OR, XOR, INV_AND, INV_OR
   - Control register for mode select

6. **Per-layer window masking**
   - Each BG layer (L0..L3) and sprite layer gets per-layer window enable/mask
   - Window can disable (clip) individual layers within its region

### Explicitly Out of Scope
- Inter-palette or inter-layer blending (SNES color math between main/sub screens)
- Post-compositor effects beyond ColorMath (fade, mosaic, scanline dimming)
- Window priority / layering (more than 2 windows)
- Beam-driven automation (Copper/HDMA hardening — separate lane)

---

## 3. Technical Approach

### 3.1 Palette RAM

Current: `Mem(Bits(24), initialContent = TileAttributeAssets.paletteInit)`

New: `Mem(Bits(24), 128 entries)` with:
- `initialContent` preserved for backward compatibility
- Bus write interface for runtime updates
- Address = `{bank[2:0], idx[3:0]}` (same as current)

Bus interface options:
- Reuse existing palette register block if it already has write paths
- Or: new dedicated block for bulk palette writes

**Recommendation:** Add write capability to existing palette interface. Single-word writes sufficient; no streaming needed (palette changes are typically small).

### 3.2 Sprite Palette Bank

Current compositor forces `fillBank := U(0)` for all sprites.

Fix: `fillBank := activePaletteBank(slot)` when sprite wins.
- This was attempted in Sprite Hardening Stage B and reverted due to timing
- Now that the palette RAM is writable, the feature is worth re-attempting
- Use the same pipelined approach that worked for Phase 2 paletteBank (P2-3a)

### 3.3 mathEnable Wiring

Current: `ColorMath.io.enable := WindowUnit.io.effect`

New: `ColorMath.io.enable := WindowUnit.io.effect || PixelMetadata.mathEnable`

But `mathEnable` is per-pixel from line buffer drain, while `WindowUnit.io.effect` is global per-line. Need to gate per-pixel:

```
mathEnablePixel := lineBufMathEnable || globalWindowEffect
ColorMath.io.enable := mathEnablePixel
```

Actually, looking at the existing code, `WindowUnit.io.effect` is already per-pixel in the sense that it follows the raster position. But `mathEnable` from metadata is per-pixel in the line buffer. Both should be OR'd.

### 3.4 Highlight Mode

Current ColorMath ops:
- `00`: passthrough
- `01`: shadow (each channel >> 1)
- `10`: add constant per channel with clamp
- `11`: reserved

New:
- `00`: passthrough
- `01`: shadow (channel >> 1)
- `10`: highlight (channel << 1, clamp 0xFF)
- `11`: add constant per channel with clamp

### 3.5 Dual Window + Combinations

New registers:
- `0x0335..0x0339`: Window 2 (x0, x1, y0, y1, invert)
- `0x033A`: Window combination mode (3 bits: AND/OR/XOR/INV_AND/INV_OR)

Combination logic:
- `inside1 = (hCounter in [x0_1, x1_1)) && (vCounter in [y0_1, y1_1)) ^ invert1`
- `inside2 = (hCounter in [x0_2, x1_2)) && (vCounter in [y0_2, y1_2)) ^ invert2`
- `effect = mode(inside1, inside2)`

### 3.6 Per-Layer Window Masking

New registers:
- `0x033B`: Layer window mask enable (8 bits: L0..L3, sprite, reserved)
- When enabled for a layer, window effect disables (clips) that layer within the window region

Implementation: In compositor, before selecting a layer's pixel, check if window masking is enabled for that layer AND the window effect is active. If both, treat the pixel as transparent.

---

## 4. Validation

### Sim Proof
- `PaletteRamSim`: prove runtime palette write → visible color change
- `WindowCombinationSim`: prove AND/OR/XOR modes work correctly
- `LayerMaskSim`: prove per-layer window masking clips correctly
- `ColorMathSim`: prove highlight mode + mathEnable wiring
- `VdpTopSim` regression

### Hardware Proof
- Scenario with runtime palette changes (e.g., palette cycling effect)
- Scenario with dual windows and layer masking
- Scenario with sprite using non-zero palette bank

### Resource Report
- LUT/FF/BSRAM before vs. after
- Timing closure check

---

## 5. Stop-Line

| Resource | Current (5a0b370) | Add | Ceiling | Zone After |
|---|---|---|---|---|
| LUT/ALU/ROM16 | 9,438 | +~700 | 13,478 | Green |
| Register | 5,988 | +~400 | 10,109 | Green |
| BSRAM | 15 | +1–2 | 23 | Green |
| DSP | 18 | +0 | 24 | Yellow (unchanged) |

Total estimated: +~700 LUT / +~400 FF / +1–2 BSRAM. Green zone.

---

## 6. Exit Condition

This task is successful when:
1. Palette RAM is runtime-writable and initializes with legacy content
2. Sprite palette bank is consumed in compositor
3. `mathEnable` metadata gates ColorMath per-pixel
4. Highlight mode works in ColorMath
5. Second window + combination logic works
6. Per-layer window masking clips correctly
7. Sim proof + hardware proof pass
8. Resource report confirms green zone
9. All existing regressions still pass

---

## 7. Next Owner

- **BrightForge** for implementation (if authorized)
- **CyanPeak** to audit artifact and implementation
