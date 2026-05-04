# SNES VDP (Ricoh 5C77/5C78 PPU)

## Primary References
- [Super Nintendo Entertainment System (SNES) Fullchip Technical Manual](https://romhack.github.io/doc/snes/snes_technical_manual.pdf) - Extensive hardware guide.
- [SNES PPU Rendering (NesDev Wiki / SNESDev)](https://snesdev.mesen.ca/wiki/index.php?title=PPU_rendering) - Technical wiki notes.

## Technical Summary
- **Processor:** Ricoh 5C77 (PPU1) and 5C78 (PPU2).
- **Resolution:** 256x224 (Progressive) or 256x448 (Interlaced).
- **Modes:** 8 background modes (0-7).
- **Color:** 15-bit RGB (32,768 colors); 256-color palette (CGRAM).
- **Sprites:** 128 total, 32 per scanline.
- **DMA:** High-speed DMA and HDMA (per-line updates).

## Programming Sequences & Details
### Key Registers ($2100–$213F)
- `$2100` **INIDISP**: Screen brightness and forced blank.
- `$2101` **OBSEL**: Object size and pattern base address.
- `$2102–$2103` **OAMADD**: OAM address.
- `$2105` **BGMODE**: Background mode and tile size (8x8 or 16x16).
- `$2107–$210A` **BGnSC**: BG1–BG4 tilemap address and size.
- `$210B–$210C` **BGnNBA**: BG1–BG4 character data base address.
- `$210D–$2114` **BGnHOFS/VOFS**: BG1–BG4 X/Y scroll (write twice).
- `$2115` **VMAIN**: VRAM address increment mode.
- `$2116–$2117` **VMADD**: VRAM address.
- `$2118–$2119` **VMDATA**: VRAM data write.
- `$2121` **CGADD**: CGRAM (palette) address.
- `$2122` **CGDATA**: CGRAM data write (write twice).
- `$212C` **TM**: Main screen layer enable.

### Mode 7 (Matrix Math)
- `$211A–$2120`: Matrix parameters (A, B, C, D) and center position (X, Y) for affine transformations.

## notable Gaps
- 32 sprites per scanline (Mode0 limit 8).
- HDMA precision (handled by Task 33 Copper-lite).
- Complex Mode 7 matrix math.
