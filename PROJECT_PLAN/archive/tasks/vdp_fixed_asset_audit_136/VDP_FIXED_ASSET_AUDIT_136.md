# VDP-FIXED-ASSET-AUDIT-136 — Fixed Value Audit

**Date:** 2026-06-14  
**Audit Owner:** CyanPeak  
**Task ID:** WHOLE-VDP-136  
**Status:** Complete

## 1. Memory-level Fixed Values

| File | Line | Construct | Current Behavior | Host-Write | Classification |
|---|---|---|---|---|---|
| `BasicPatternSource.scala` | 34 | `Mem.init` | On-chip tile map ROM | No | **DEMO** |
| `BasicPatternSource.scala` | 35 | `Mem.init` | On-chip tile row ROM | No | **DEMO** |
| `SdramTileAttrFetch.scala` | 239 | `Mem.init` | Boot-init tile map | Yes (SDRAM) | **DEMO** |
| `SdramTileAttrFetch.scala` | 241 | `Mem.init` | Boot-init attr map | Yes (SDRAM) | **DEMO** |
| `SdramTileAttrFetch.scala` | 243 | `Mem.init` | Boot-init tile rows | Yes (SDRAM) | **DEMO** |
| `SdramTileAttrFetch.scala` | 247 | `Mem.init` | Boot-init planar plane 0 | Yes (SDRAM) | **DEMO** |
| `SdramTileAttrFetch.scala` | 251 | `Mem.init` | Boot-init planar plane 1 | Yes (SDRAM) | **DEMO** |
| `SpriteEvaluator.scala` | 134 | `Mem.init` | Parked-sprite defaults | Yes | **OK** |
| `SpriteRasterizer.scala` | 100 | `Mem.init` | Line buffer zero-init | No | **OK** |
| `VdpTop.scala` | 1827 | `Mem.init` | Affine texture ROM | No | **GAP** |
| `VdpTop.scala` | 1838 | `Mem.init` | Sprite pattern RAM defaults | Yes | **OK** |
| `VdpTop.scala` | 1930 | `Mem.init` | Palette RAM boot defaults | Yes | **OK** |

## 2. Asset-Object Constants

| Object | File | Content | Classification |
|---|---|---|---|
| `AffineAssets` | `AffineAssets.scala` | Hardcoded UV texture data | **GAP** |
| `BasicPatternSource` | `BasicPatternSource.scala` | Hardcoded checkerboard/stripe patterns | **DEMO** |
| `TileAttributeAssets` | `TileAttributeAssets.scala` | Hardcoded 16/256-color palette data | **OK** |
| `PlanarTileAssets` | `PlanarTileAssets.scala` | Hardcoded planar test pattern | **DEMO** |

## 3. Hardcoded Addresses & Defaults

| File | Line | Value | Description | Classification |
|---|---|---|---|---|
| `VdpTop.scala` | 1045 | `0x3000` | `BITMAP_BASE` POR default | **OK** |
| `VdpTop.scala` | 1047 | `0x4000` | `ATTR_BASE` POR default | **OK** |
| `SdramTileAttrFetch.scala` | 37 | `0x3000 / 0x4000` | Hardcoded L0 boot bases | **OK** |
| `VdpTop.scala` | 312 | `0x0400..0x05FF` | Copper program RAM address | **OK** |
| `VdpTop.scala` | 329 | `0x0B00..0x0B4F` | DMA register range | **OK** |
| `VdpTop.scala` | 338 | `0x0C00..0x0D0F` | Blitter register range | **OK** |

## 4. Remediation Recommendations

### **GAP** Items
- **affineTexture (`VdpTop.scala`):** This is the most critical gap. The engine supports affine transformation, but the source texture is immutable. 
  - **Recommendation:** Implement Task 34 to wire the host-write path to this memory.

### **DEMO** Items
- **BasicPatternSource Patterns:** These are hardcoded fallbacks.
  - **Recommendation:** Document that `BasicPatternSource` is a static verification tool and not intended for user-defined runtime backgrounds.
- **SdramTileAttrFetch Boot ROMs:** These consume BSRAM/LUTs to prime SDRAM with a test pattern.
  - **Recommendation:** Encourage the use of `skipSdramInit = true` for production builds to reclaim resources, as established in Task #9026.

## 5. Audit Conclusion
The RTL is largely host-programmable with the notable exception of the **affine texture**. The remaining fixed assets are legitimate power-on defaults or legacy verification patterns.
