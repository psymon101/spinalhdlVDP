# Sega Genesis VDP

## Primary References
- [Sega Genesis VDP Documentation (Charles MacDonald)](https://cgfm2.emuunlim.com/genesisvdp.txt) - The definitive guide to Genesis VDP.
- [Genesis VDP Documentation (Sega Retro)](https://www.segaretro.org/Sega_Genesis_VDP) - Detailed overview and history.

## Technical Summary
- **Processor:** Custom VDP (derived from SMS VDP).
- **Resolution:** 320x224 (H40) or 256x224 (H32) pixels.
- **Layers:** Plane A, Plane B, and Window.
- **Color:** 4 palettes of 16 colors (64 total) from 512-color master palette (9-bit RGB).
- **Sprites:** Up to 80 total, 20 per scanline (H40).
- **VRAM:** 64 KB.
- **DMA:** High-speed memory-to-VRAM DMA.

## Programming Sequences & Details
### VDP Registers (0–23)
- **Reg 0**: Mode Control 1 (H-blank interrupt enable).
- **Reg 1**: Mode Control 2 (Display enable, V-blank interrupt enable, DMA enable).
- **Reg 2**: Plane A Name Table base address.
- **Reg 3**: Window Name Table base address.
- **Reg 4**: Plane B Name Table base address.
- **Reg 5**: Sprite Attribute Table base address.
- **Reg 7**: Background/Border color.
- **Reg 10**: H-blank interrupt interval.
- **Reg 11**: Mode Control 3 (Scroll modes: per-scanline/per-column).
- **Reg 12**: Mode Control 4 (H40/H32 resolution).
- **Reg 13**: H-scroll table base address.
- **Reg 15**: VRAM address auto-increment value.
- **Reg 19–20**: DMA length.
- **Reg 21–23**: DMA source address.

### Control Port ($C00004)
- Command format: `%CD1 CD0 A13 A12 A11 A10 A9 A8 | A7 A6 A5 A4 A3 A2 A1 A0 | 0 0 0 0 0 0 0 0 | CD5 CD4 CD3 CD2 0 0 A15 A14`
- Codes (CD5-CD0): `000000` (VRAM Read), `000001` (VRAM Write), `001000` (CRAM Write), `000100` (VSRAM Write).

## notable Gaps
- 20 sprites per scanline (Mode0 limit 8).
- Multiple background planes (Mode0 expansion to 4 layers in Task 48).
- Variable sprite sizes.
