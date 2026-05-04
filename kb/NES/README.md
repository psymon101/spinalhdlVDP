# NES / Famicom VDP (Ricoh 2C02 PPU)

## Primary References
- [Ricoh 2C02 Technical Reference (BARRY)](https://www.nesdev.org/2C02_technical_reference.txt) - Definitive low-level reference.
- [NesDev Wiki: PPU Rendering](https://www.nesdev.org/wiki/PPU_rendering) - Detailed cycle-by-cycle rendering behavior.
- [PPU Scrolling (Loopy)](https://www.nesdev.org/wiki/PPU_scrolling) - Documentation on the internal address registers (`v` and `t`).
- [NES Technical Documentation (Brad Taylor)](http://web.archive.org/web/20120211054044/http://nesdev.parodius.com/2C02tech.txt) - Classic hardware breakdown.

## Technical Summary
- **Processor:** Ricoh 2C02 (NTSC) / 2C07 (PAL).
- **Resolution:** 256x240 pixels.
- **Color:** 64-color hardcoded system palette; 32 bytes of Palette RAM (4 background sub-palettes, 4 sprite sub-palettes).
- **Tiles:** 8x8 pixels, 2bpp planar format (16 bytes per tile).
- **Background:** Two pattern tables (256 tiles each), four logical nametables (1KB each), attribute tables (64 bytes per nametable).
- **Sprites:** 64 total (OAM), max 8 per scanline, 8x8 or 8x16 size.
- **Registers:** 8 memory-mapped registers ($2000-$2007).
- **Interrupts:** VBlank NMI at scanline 241.

## Programming Sequences & Details
### Register Map ($2000–$2007)
- `$2000` **PPUCTRL**: VBlank NMI enable, Sprite size, Background/Sprite pattern table address.
- `$2001` **PPUMASK**: Color emphasis, Sprite/Background enable.
- `$2002` **PPUSTATUS**: VBlank flag, Sprite 0 hit, Sprite overflow.
- `$2003` **OAMADDR**: OAM address.
- `$2004` **OAMDATA**: OAM data access.
- `$2005` **PPUSCROLL**: X and Y scroll (write twice).
- `$2006` **PPUADDR**: VRAM address (write twice).
- `$2007` **PPUDATA**: VRAM data access.

### Cycle-by-Cycle Rendering (Scanlines 0–239)
- **Cycle 0**: Idle.
- **Cycles 1–256**: Pixel output + 4 background fetches every 8 cycles (Nametable, Attribute, Pattern Low, Pattern High).
- **Cycle 256**: Vertical scroll increment.
- **Cycles 257–320**: Sprite evaluation (find first 8 sprites for next line) and tile data fetch.
- **Cycles 321–336**: Pre-fetch first two tiles for next scanline.

## notable Gaps
- OAM Corruption hardware bug (not emulated).
- Cycle-accurate timing quirks (emphasized in spec doc §11).
