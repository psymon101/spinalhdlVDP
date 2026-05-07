# Task 53 — Sprite Pattern Address Width Expansion

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-07  
**Status:** OPEN — PM authorization #9419  
**Governing directive:** BronzeGate #9419

**Tied back to:** TASKS.md §Task 53, ASSESSMENT.md §5 (Universal Sprite Engine Gaps), Gap 2

---

## Task

Expand sprite pattern address width and pattern RAM depth so sprites larger than 16×16 can use unique tiles instead of repeating the same 16×16 pattern.

## Purpose

The current substrate limits each sprite to one 16×16 4bpp tile (`patIdx = 0..15`, 256 entries per tile). Any sprite larger than 16×16 tile-repeats the same pattern. This blocks honest large-sprite claims:

| Platform | Max sprite size | Unique 16×16 tiles needed | Current honest? |
|---|---|---|---|
| Genesis | 32×32 | 4 | ✗ (repeats) |
| SNES | 64×64 | 16 | ✗ (repeats) |
| Neo Geo | 16×16 to 32×32 | 1–4 | partially ✗ |

A single 64×64 sprite currently consumes the entire 16-pattern table. Task 53 makes large-sprite patterns addressable.

## Current substrate

### Pattern RAM
- `VdpTop.scala:1311`: `Mem(Bits(4 bits), initialContent = VdpTop.spritePatternRamInit)`
- Current depth: 4096 entries = 16 slots × 256 entries (16×16 pixels)
- Data width: 4 bits (one 4bpp pixel nibble)
- Total: 4096 × 4 = 16384 bits = 2 KB (one Gowin BSRAM)

### Address generation
- `SpriteRasterizer.scala:53`: `patAddrBits = 12` — `{patIdx[3:0], row[3:0], col[3:0]}`
- `SpriteRasterizer.scala:204`: `io.patternRamAddr := (slotPatIdxR(3 downto 0).asBits ## finalAddr.asBits.resize(8)).asUInt`
- `SpriteEvaluator.scala:470`: `val PatIdxWidth: Int = 4`
- `SpriteEvaluator.scala:483`: `patIdx.asBits.resize(4)` in packed slot word

### Descriptor format
- Word 0: `{enabled[15], patIdx[14:11], affineEnable[10], sizeSel[9:8], paletteBank[7:5], priority[4], flipH[3], flipV[2], y[9:0]}`
- `patIdx` currently occupies bits [14:11] = 4 bits

## Scope

### In scope

1. **Expand `patIdx` width**
   - `SpriteEvaluator.PatIdxWidth`: 4 → 6 (for 64 unique patterns) or 8 (for 256 unique patterns)
   - Descriptor word 0: widen `patIdx` field; may require descriptor format version bump or reserved-bit reclamation
   - `SpriteEvaluator` pack/unpack: update `patIdx` extraction and insertion

2. **Expand pattern RAM depth**
   - Current: 4096 × 4 = 16 slots × 256 entries
   - Target depth options:
     - **Option A (minimal):** 16384 × 4 = 64 slots × 256 entries = 4× BSRAMs. Supports 64 unique 16×16 tiles. Honest for Genesis (4 tiles per 32×32 sprite, 16 sprites max) and 4 unique 64×64 sprites.
     - **Option B (recommended):** 65536 × 4 = 256 slots × 256 entries = 16× BSRAMs. Supports 256 unique tiles. Honest for SNES (16 tiles per 64×64 sprite, 16 sprites max).
   - `VdpTop.spritePatternRamInit`: expand zero-fill to match new depth
   - `spritePatternRams` `Mem` depth parameter update

3. **Update pattern RAM address generation**
   - `SpriteRasterizer.patAddrBits`: 12 → 14 (Option A) or 16 (Option B)
   - `SpriteRasterizer.io.patternRamAddr`: widen concatenation to match new `patAddrBits`
   - `VdpTop.scala:1385`: `readSync` port width must match new `patAddrBits`

4. **Update bus write interface**
   - `patternRamPtr`: currently 12 bits (`VdpTop.scala:1329`). Widen to 14 or 16 bits.
   - `patternRamPtrWriteHit` address (`0x0D11`): retain or add new pointer-register address if format changes.

5. **QSPI upload path**
   - Host sketch pattern upload loop must be aware of new depth/pointer width
   - Verify `sc45_pattern_upload()` or equivalent writes do not truncate addresses

### Out of scope

- Multi-tile sprite compositing (rendering a 32×32 sprite by stitching 4 tiles together) — this is a rasterizer/compositor change, not address-width expansion
- BPP changes (remains 4bpp)
- Affine matrix expansion
- Palette bank expansion

## Proposed addressing scheme

Keep the flat `{patIdx, row, col}` layout for simplicity:

| Option | patIdx bits | row/col bits | patAddrBits | RAM depth | BSRAMs (4bpp) |
|---|---|---|---|---|---|
| Current | 4 | 8 (4+4) | 12 | 4096 | 1 |
| A | 6 | 8 | 14 | 16384 | 4 |
| B | 8 | 8 | 16 | 65536 | 16 |

**Recommendation:** Start with **Option A** (6-bit patIdx, 14-bit address, 4 BSRAMs). It fits Genesis honesty and is the smallest sufficient change. Option B can be a follow-on lane if SNES honesty requires it.

## Proof shape

### Simulation
- `SpritePatternAddressWidthSim` (new):
  - Upload 4 unique 16×16 patterns via QSPI
  - Configure a 32×32 sprite with `sizeSel` pointing to a 2×2 tile grid
  - Verify rasterizer addresses span `patIdx`..`patIdx+3` with correct `row`/`col` within each tile
  - Verify pattern RAM read data matches uploaded tile data for all 4 sub-tiles
- Regression: all existing sprite sims PASS bit-identical (no regression for 16×16 sprites)

### Hardware
- Host sketch uploads a 32×32 test pattern (4 unique 16×16 tiles forming a larger image)
- 30 s capture, direct visual review confirms the 32×32 sprite shows 4 distinct tiles, not 4 repeats of the same tile
- Resource report: confirm BSRAM count increase matches option chosen
- Timing: 0 violations

## Risk / Complexity

| Item | Risk | Mitigation |
|---|---|---|
| Descriptor format change | Medium — may break existing sims/firmware if word layout shifts | Keep `patIdx` in same word; grow into reserved bits or add format-version bit |
| BSRAM budget | Low-Medium — 4× BSRAMs is ~4 KB, well within Tang Nano 20K (~52 KB BSRAM) | Verify before implementation; fall back to Option A if Option B exceeds budget |
| Bus pointer width | Low — simple register widen | Match `patternRamPtr` to `patAddrBits` |

## Expected commits

1. `SpriteEvaluator.scala`: `PatIdxWidth` bump + pack/unpack
2. `SpriteRasterizer.scala`: `patAddrBits` + `patternRamAddr` widen
3. `VdpTop.scala`: pattern RAM depth + init + pointer width + readSync port
4. `SpritePatternAddressWidthSim.scala`: new sim (discriminator + proof)
5. Host sketch: pattern upload loop aware of new depth (if needed)

## Checkpoints

- **A:** control/register contract — descriptor format, bus addresses, pointer width
- **B:** simulation proof — `SpritePatternAddressWidthSim` PASS + regression PASS
- **C:** hardware proof — 32×32 unique-tile sprite visible on HDMI, resource/timing clean

## Next expected owner

`BrightForge`: implementation of checkpoint A–C.
`CyanPeak`: audit of proof packet.
