# NES / Famicom VDP (Ricoh 2C02 PPU)

## 1. Video Model Summary

- **Native logical resolution:** 256×240 pixels
- **Display structure:** Tile + attribute background with sprite overlay
- **Overscan:** Active area may intentionally not use every output pixel
- **Frame rate:** 60 Hz (NTSC 2C02) / 50 Hz (PAL 2C07)

## 2. Supported Features

- 8×8 pixel tiles, 2bpp planar format
- Two pattern tables (256 tiles each)
- Four logical nametables (1 KB each)
- Attribute tables (64 bytes per nametable)
- 64 total sprites (OAM), max 8 per scanline
- 8×8 or 8×16 sprite sizes
- VBlank NMI at scanline 241
- 64-color hardcoded system palette; 32 bytes of Palette RAM

## 3. Unsupported / Deferred Features

- **Sprite-0 hit semantics:** Used for raster splits; may require raster trigger support.
- **OAM corruption hardware bug:** Not emulated in Mode0 v1.
- **Cycle-accurate timing:** Emphasized in spec doc but not required for v1 adapter proof.

## 4. Adapter Register Surface

- `$2000` **PPUCTRL:** VBlank NMI enable, Sprite size, Background/Sprite pattern table address
- `$2001` **PPUMASK:** Color emphasis, Sprite/Background enable
- `$2002` **PPUSTATUS:** VBlank flag, Sprite 0 hit, Sprite overflow
- `$2003` **OAMADDR:** OAM address
- `$2004` **OAMDATA:** OAM data access
- `$2005` **PPUSCROLL:** X and Y scroll (write twice)
- `$2006` **PPUADDR:** VRAM address (write twice)
- `$2007` **PPUDATA:** VRAM data access

## 5. Mode0 Mapping

| NES Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Tile background | Tile + attribute fetch | Map nametable + attribute to tilemap |
| 8×8 sprites | Sprite evaluation (descCount=8) | Map OAM to sprite slots |
| Attribute-table color | Cell-attribute color | Preserve 2×2 tile color granularity |
| Scroll | Scroll registers | Handle PPUSCROLL writes |
| VBlank NMI | Raster trigger | Fire NMI at scanline 241 |

## 6. Host Memory Layout

- **Pattern tables:** 8 KB total (two 4 KB tables at `$0000` and `$1000` in PPU VRAM)
- **Nametables:** 4 × 1 KB (mirrored to 2 KB or 4 KB depending on cart)
- **Attribute tables:** 64 bytes per nametable
- **Palette RAM:** 32 bytes (4 background sub-palettes, 4 sprite sub-palettes)
- **OAM:** 256 bytes (64 sprites × 4 bytes)

## 7. Firmware Workflow

1. Host uploads pattern tiles to pattern tables
2. Host builds nametable and attribute tables
3. Host loads palette into Palette RAM
4. Host writes sprite data to OAM via `$2004` or OAMDMA
5. Host sets scroll via `$2005`
6. Host enables display via `$2001`

## 8. Proof / Validation Plan

- **Sim:** Adapter-local proof showing mode mapping is coherent and does not break existing substrate
- **Hardware:** Static test pattern renders correctly; 30s capture freeze=0
- **Overscan check:** Verify modest overscan treatment if claimed

## 9. Known Gaps / Gotchas

- **Overscan/cropping:** Active area may intentionally not use every output pixel; document intended presentation on fixed HDMI output.
- **Palette accuracy:** Use an NES-appropriate palette reference, not generic RGB.
- **Attribute-table granularity:** 2×2 tile color granularity is part of the NES look and must be preserved.
- **Sprite-per-line limits:** Max 8 sprites per scanline; overflow flag behavior may need adapter attention.
- **Minimum readiness:** Through `R4` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [Ricoh 2C02 Technical Reference (BARRY)](https://www.nesdev.org/2C02_technical_reference.txt)
- [NesDev Wiki: PPU Rendering](https://www.nesdev.org/wiki/PPU_rendering)
- [PPU Scrolling (Loopy)](https://www.nesdev.org/wiki/PPU_scrolling)
- [NES Technical Documentation (Brad Taylor)](http://web.archive.org/web/20120211054044/http://nesdev.parodius.com/2C02tech.txt)

### Localized References

The following reference materials are stored locally in `kb/NES/references/verilog-nes`:
- `PPU.v`
- `Background.v`
- `PaletteLookupRGB.v`
- `Sprites.v`
- `SpriteRasterizerPriority.v`
- `PPUPatternTableAddress.v`
- `PPUAttributeAddress.v`
- `PPUTileAddress.v`
- `PPUSprite8x8TileAddress.v`
- `PPUIncrementX.v`
- `PPUIncrementY.v`
- `PPUChipEnable.v`
- `Shift16.v`
- `Shift8.v`
- `ShiftParallelLoad8.v`
- `README.md`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

