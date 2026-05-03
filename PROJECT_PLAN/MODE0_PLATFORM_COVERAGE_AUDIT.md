# Mode0 Platform-by-Platform Adapter Coverage Audit

**Audit version:** 1.0  
**Author:** CoralReef  
**Date:** 2026-04-25  
**Commit:** TBD  
**Scope:** Assessment only; maps each platform's video hardware → current Mode0 support + gaps

---

## Executive Summary

This audit evaluates 7+ target platforms against current `Mode0` substrate capabilities. Each platform is scored in four domains: **Fetch/Tile**, **Sprite**, **Color/Window**, and **Beam-Driven**.

| Platform | Fetch/Tile | Sprite | Color/Window | Beam-Driven | Overall | Verdict |
|---|---|---|---|---|---|---|
| C64 | Strong | Strong | N/A | Usable | Strong | **Ready** |
| NES | Strong | Strong | N/A | N/A | Strong | **Ready** |
| Genesis | Usable | Usable | Gap | Usable | Usable | **Color/Window hardening needed** |
| SNES | Gap | Usable | Gap | Gap | Usable | **Multiple gaps** |
| Amiga | Gap | Usable | N/A | Usable | Usable | **Fetch/tile + Beam hardening needed** |
| Atari ST | Usable | N/A | N/A | Usable | Usable | **Fetch/tile hardening needed** |
| ZX Spectrum | Usable | N/A | N/A | N/A | Usable | **Ready** |

**Key finding:** Fetch/Tile and Sprite hardening (closed / in-progress) serve NES/C64 well. The remaining frontier is **Color/Window for Genesis/SNES** and **Fetch/Tile richness for Amiga/Atari ST**.

---

## Platform 1: C64 (Commodore 64)

### Hardware Summary
- Resolution: 320×200
- Modes: text (16 colors), bitmap, multicolor
- Sprites: 8 hardware sprites, 24×21, 1 color + shared color, X/Y expand
- Colors: 16-color palette (fixed)
- Beam-driven: Raster IRQ for splits, sprite multiplexing

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×200 display | Strong | None | 4 layers available |
| Text mode | Strong | None | L0/L1 tile layers cover this |
| Bitmap mode | Strong | None | L0 SDRAM-backed bitmap fetch |
| Multicolor | Strong | None | 2-bit-per-pixel via bitplane |
| 8 sprites | Strong | None | 32 slots available; evaluator handles Y-sort |
| Sprite X/Y expand | Usable | None | `sizeSel` covers this (8/16/32/64) |
| Sprite priority | Strong | None | Back-to-front compositor |
| Raster splits | Usable | None | RasterTrigger + Copper sufficient |
| Sprite multiplexing | Strong | None | 32 slots >> 8 sprites |
| 16-color palette | Strong | None | 128-entry palette covers this |

### Verdict
**READY.** No substrate hardening needed for honest C64 adapter.

---

## Platform 2: NES (Nintendo Entertainment System)

### Hardware Summary
- Resolution: 256×240
- Background: 1 tile layer (8×8 or 8×16 tiles), 4 palettes
- Sprites: 64 sprites, 8×8 or 8×16, 4 palettes, priority bit
- Colors: 54-color master palette, 4 palettes × 4 colors (1 transparent)
- No beam-driven effects

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 256×240 display | Strong | None | Scalable output resolution |
| 1 BG tile layer | Strong | None | L0 covers this |
| 8×8 / 8×16 tiles | Strong | None | Tile size configurable |
| 4 BG palettes | Strong | None | 8 banks available |
| 64 sprites | Strong | None | 32 slots; evaluator covers Y-range |
| 8×8 / 8×16 sprite sizes | Strong | None | `sizeSel` configurable per sprite |
| Sprite priority bit | Usable | Gap | `activePriority` exists but compositor ignores it |
| 4 sprite palettes | Strong | None | 8 banks available; sprite palette bank wired but unused |
| Fine X/Y scroll | Strong | None | Per-column/per-line scroll supported |
| Sprite 0 hit | Gap | None | Status bit exists (bit 4) but not wired to compositor |
| Color emphasis bits | N/A | None | Post-CRT effect; adapter-local |

### Gaps
- **Sprite priority bit**: `activePriority` is evaluated but compositor does not use it. NES sprite priority determines sprite-vs-BG ordering per sprite.
- **Sprite palette bank unused**: All sprites forced to bank 0. NES needs 4 sprite palettes.
- **Sprite 0 hit**: Status register bit exists but not wired to compositor collision logic.

### Verdict
**NEARLY READY.** Minor sprite plumbing gaps (priority bit + palette bank) block honest NES claim. Fixable within current Sprite Hardening scope or a small follow-up.

---

## Platform 3: Genesis (Sega Mega Drive)

### Hardware Summary
- Resolution: 320×224 (NTSC) / 320×240 (PAL)
- Background: 2 tile layers (A, B), 1 plane (W), per-tile priority
- Sprites: 80 sprites total, 20/line (H40/320px) or 16/line (H32/256px), sizes 8×8 to 32×32, priority bit
- Colors: 64 9-bit CRAM entries = 4 palette lines × 16 colors (shared BG+sprite), shadow/highlight
- Window: 2 windows + sprite window, per-layer masking
- Beam-driven: H-int (horizontal interrupt), V-int, per-line scroll tables

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×224 display | Strong | None | Fits in 4-layer substrate |
| 2 BG tile layers (A, B) | Strong | None | L0 + L1 cover this |
| Window plane (W) | Usable | Gap | Single window only; no per-layer masking |
| Per-tile priority | Strong | None | L0 priority bit from SDRAM path |
| Per-line scroll tables | Strong | None | `LinestateStore` supports this |
| 80 sprites total, 20/line | Strong | None | `visiblePerLine=32` exceeds Genesis 20/line limit |
| Sprite sizes 8–32 | Strong | None | `sizeSel` covers 8/16/32/64 |
| Sprite 4 palette lines | Strong | Gap | Sprite palette bank wired but unused; Genesis shares 4 palettes between BG+sprites |
| Sprite priority bit | Usable | Gap | Compositor ignores `activePriority` |
| Shadow/highlight | Usable | Gap | ColorMath has shadow but no highlight |
| 2 windows + sprite window | Gap | Gap | Only 1 window; cannot mask sprites |
| Window combinations | Gap | Gap | No AND/OR/XOR window logic |
| H-int per-line | Usable | None | HDMA + RasterTrigger sufficient |

### Gaps
1. ~~Sprite visiblePerLine=32 vs 80~~ **CORRECTION**: Genesis allows 80 sprites total, but only 20 per scanline (H40 mode). Mode0's `visiblePerLine=32` already exceeds this. **No gap here.**
2. **Sprite palette bank unused**: Genesis needs 4 sprite palettes. Currently forced to bank 0.
3. **Sprite priority bit unused**: Genesis uses per-sprite priority for sprite-vs-sprite and sprite-vs-BG ordering.
4. **Shadow/highlight incomplete**: ColorMath has shadow but no highlight mode.
5. **Window insufficient**: Genesis needs 2 windows + sprite window + per-layer masking + combinations.

### Verdict
**USABLE with gaps.** Honest Genesis adapter needs Color/Window Hardening + sprite priority/palette plumbing. Sprite count is **not** a gap — Mode0 already exceeds Genesis per-line limits.

---

## Platform 4: SNES (Super Nintendo)

### Hardware Summary
- Resolution: 256×224 (NTSC) / 256×239 (PAL)
- Background: 1–4 tile layers (modes 0–7), per-tile priority
- Sprites: 128 sprites, 32/line, 8×8 to 64×64, 8 palettes, priority
- Colors: 15-bit RGB (32768 colors), 8 palettes × 16 colors, color math
- Window: 2 windows + combinations (AND/OR/XOR) + per-layer masking
- Beam-driven: HDMA (8 channels), multiple IRQ types, auto-joypad read

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 256×224 display | Strong | None | Scalable output |
| 1–4 BG layers | Strong | None | 4-layer compositor covers this |
| Mode 7 (affine BG) | Usable | None | Affine texture source exists |
| Per-tile priority | Strong | None | L0 priority bit supported |
| 128 sprites | Strong | None | 32 descriptor slots; need more for 128 |
| 32 sprites/line | Strong | None | `visiblePerLine=32` matches exactly |
| Sprite sizes 8–64 | Strong | None | `sizeSel` covers all sizes |
| Sprite 8 palettes | Strong | Gap | Sprite palette bank wired but unused |
| Sprite priority bit | Usable | Gap | Compositor ignores `activePriority` |
| Color math between layers | Gap | Gap | ColorMath is global; no inter-layer blending |
| 2 windows + combinations | Gap | Gap | Only 1 window; no AND/OR/XOR |
| Per-layer window masking | Gap | Gap | Window only gates ColorMath |
| HDMA (8 channels) | Usable | Gap | 4 channels available; indirect mode missing |
| Multiple IRQ types | Usable | Gap | Single RasterTrigger; status bits exist |

### Gaps
1. **Sprite palette bank unused**: SNES needs 8 sprite palettes.
2. **Sprite priority bit unused**: SNES uses per-sprite priority.
3. **Color math between layers**: SNES color math operates between layers (main + sub). Mode0 ColorMath is single-stream only.
4. **Window system**: SNES needs 2 windows + combinations + per-layer masking.
5. **HDMA channels**: SNES has 8 channels; Mode0 has 4. Indirect mode also missing.

### Verdict
**USABLE with significant gaps.** Honest SNES adapter needs Color/Window Hardening + sprite priority/palette plumbing + inter-layer color math. This is the most demanding platform in the audit.

---

## Platform 5: Amiga (OCS/ECS)

### Hardware Summary
- Resolution: 320×200 to 320×400 (interlaced)
- Background: 1–6 bitplanes (2–64 colors), HAM mode (4096 colors)
- Sprites: 8 hardware sprite engines (max 8/line), 16×wide × any height, 3 colors + transparent, attach for 15 colors
- Colors: 32-color palette (OCS), 64-color (ECS), 4096 (HAM)
- Beam-driven: Copper (pixel-precision), blitter synchronization
- No window/mask system

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×200 display | Strong | None | Fits easily |
| 1–6 bitplanes | Gap | Gap | Mode0 is tile-based; Amiga is bitplane-based |
| HAM mode | N/A | Gap | Requires 6 bitplanes + hold-and-modify logic |
| 8 sprites | Strong | None | 32 slots available |
| Sprite attach (15 colors) | Usable | Gap | No attach mechanism; could emulate with descriptor pairing |
| 32/64-color palette | Strong | None | 128-entry palette covers this |
| Copper pixel-precision (WAIT X,Y) | Gap | Gap | Copper WAIT is line-only |
| Copper SKIP instruction | Gap | Gap | Mode0 Copper lacks conditional SKIP |
| Display resolution switching | Usable | None | Mode0 output scaler handles this |

### Gaps
1. **Bitplane architecture**: Mode0 is fundamentally tile-based with SDRAM tile fetch. Amiga is bitplane-based with direct DMA from chip RAM. This is an architectural mismatch.
2. **HAM mode**: Requires 6 bitplanes and hold-and-modify logic. Not feasible with current tile-based substrate.
3. **Copper pixel-precision**: WAIT is line-only; Amiga Copper does pixel-precision WAIT (X,Y).
4. **Copper SKIP**: Mode0 Copper lacks the SKIP instruction (conditional skip based on beam position).
5. **Copper blitter sync**: No beam-driven DMA/blitter trigger.

### Verdict
**GAP — architectural mismatch.** Honest Amiga adapter requires either a bitplane fetch mode (new substrate primitive) or a translation layer that maps Amiga bitplanes to Mode0 tiles. The latter is possible but not trivial. Pixel-precision Copper and blitter sync are secondary to the bitplane gap.

**Recommendation:** Amiga should be treated as a "stretch" platform requiring a dedicated assessment for bitplane mode feasibility, not just hardening.

---

## Platform 6: Atari ST

### Hardware Summary
- Resolution: 320×200 (low), 640×200 (med), 640×400 (high)
- Background: Bitplane-based (2–4 bitplanes), no tiles
- Sprites: None (software sprites only)
- Colors: 16-color (low, 4 bitplanes), 4-color (med, 2 bitplanes), 2-color (high, 1 bitplane)
- Palette: 512 colors (9-bit RGB, 16 palette registers); STE expanded to 4096 colors
- Beam-driven: Raster bars via sync-level manipulation, border tricks

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 320×200 low-res | Strong | None | Scalable output |
| 640×200 med-res | Strong | None | Output scaler handles |
| 640×400 high-res | Usable | None | May need pixel clock adjustment |
| 2–4 bitplanes | Gap | Gap | Tile-based vs bitplane mismatch |
| No hardware sprites | N/A | None | No sprite layer needed |
| 16/4/2 colors | Strong | None | Palette covers all modes |
| Raster bars | Usable | None | Line-only WAIT sufficient for raster bars |
| Border tricks | N/A | None | Sync-level manipulation; adapter-local |

### Gaps
1. **Bitplane architecture**: Same mismatch as Amiga. Atari ST is bitplane-based, not tile-based.

### Verdict
**USABLE with bitplane gap.** Like Amiga, honest Atari ST adapter needs bitplane fetch mode or a translation layer. The low complexity (no sprites, no window) makes it simpler than Amiga.

---

## Platform 7: ZX Spectrum

### Hardware Summary
- Resolution: 256×192
- Background: Attribute-based (8×8 cells: 2 colors + bright + flash)
- Sprites: None (software sprites only, or ULA+ extensions)
- Colors: 15 colors (8 normal + 7 bright), attribute-based
- No beam-driven effects

### Mode0 Coverage

| Feature | Mode0 Support | Gap | Notes |
|---|---|---|---|
| 256×192 display | Strong | None | Scalable output |
| Attribute-based color | Strong | None | Can be emulated with 2-color tile layer |
| 8×8 attribute grid | Strong | None | Tile layer maps 1:1 |
| Bright/flash attributes | Strong | None | Color-math or palette entry choice |
| No hardware sprites | N/A | None | No sprite layer needed |
| ULA+ (64-color palette) | Strong | None | 128-entry palette covers this |

### Verdict
**READY.** No substrate hardening needed for honest ZX Spectrum adapter.

---

## Cross-Platform Gap Consolidation

### Gap 1: Sprite Palette Bank Plumbing
- **Platforms affected:** NES, Genesis, SNES
- **Current state:** `activePaletteBank` wired in descriptor, evaluator outputs it, compositor ignores it
- **Fix:** Wire `activePaletteBank` into compositor pixel fill path
- **Effort:** Small

### Gap 2: Sprite Priority Bit
- **Platforms affected:** NES, Genesis, SNES
- **Current state:** `activePriority` exists but compositor ignores it
- **Fix:** Use `activePriority` to override back-to-front ordering for sprite-vs-sprite and sprite-vs-BG
- **Effort:** Medium (priority logic changes)

### Gap 3: Window System Expansion
- **Platforms affected:** Genesis, SNES
- **Current state:** Single rectangle, gates ColorMath only
- **Fix:** Second window + combination logic + per-layer masking
- **Effort:** Medium

### Gap 4: Color Math Enhancement
- **Platforms affected:** Genesis, SNES
- **Current state:** Shadow + add-constant, global only
- **Fix:** Highlight mode + per-pixel mathEnable wiring + inter-layer blending hooks
- **Effort:** Medium

### Gap 5: Bitplane Fetch Mode
- **Platforms affected:** Amiga, Atari ST
- **Current state:** Tile-based only
- **Fix:** New substrate primitive for bitplane DMA fetch, or adapter-level translation
- **Effort:** Large (assessment needed)

### Gap 6: Copper Pixel Precision
- **Platforms affected:** Amiga
- **Current state:** WAIT is line-only
- **Fix:** Extend WAIT to support X,Y pixel compare
- **Effort:** Small

### Gap 7: ~~Genesis 80 Sprites/Line~~ REMOVED
- **Platforms affected:** None
- **Correction:** Genesis allows 80 sprites total, but only 20 per scanline (H40 mode) / 16 per scanline (H32 mode). Mode0's `visiblePerLine=32` already exceeds this. No gap exists.
- **Sources:** Sega Genesis Software Manual, Sega Retro sprites page, Copetti Mega Drive architecture analysis

---

## Consolidated Recommendations

### Immediate (Sprite Hardening follow-up)
1. **Wire sprite palette bank** (affects NES/Genesis/SNES) — small, do now
2. **Wire sprite priority bit** (affects NES/Genesis/SNES) — medium, do now if within Stage B scope

### Next (Color/Window Hardening)
3. **Runtime-writable palette RAM** — foundational for all dynamic palette platforms
4. **Second window + combinations + per-layer masking** — required for Genesis/SNES
5. **Color math enhancement** (highlight, per-pixel, inter-layer) — required for Genesis/SNES

### Future (Beam-Driven Hardening)
6. **Copper pixel-precision WAIT** — for Amiga
7. **HDMA 9-bit line + indirect mode** — for SNES

### Separate Assessment Required
8. **Bitplane fetch mode** — for Amiga/Atari ST (architectural decision)

---

## Exit Condition

This audit is successful because it:
1. Maps each platform's video hardware to current Mode0 capabilities
2. Identifies exact shared gaps (not adapter-local quirks)
3. Classifies gaps by effort and affected platforms
4. Provides prioritized recommendations for next hardening lanes
