# Commodore 64 VDP (MOS 6567/6569 VIC-II)

## Primary References
- [The MOS 6567/6569 Video Interface Chip II (Christian Ludscheidt)](https://www.zimmers.net/cbemirror/cbm/c64/programming/documents/vic-ii.txt) - Detailed register and timing breakdown.
- [Ultimate Commodore 64 Reference Guide (mist64/c64ref)](https://github.com/mist64/c64ref) - Comprehensive reference archive.

## Technical Summary
- **Processor:** MOS 6567 (NTSC) / 6569 (PAL).
- **Resolution:** 320x200 pixels.
- **Color:** 16-color fixed system palette.
- **Modes:**
    - **Standard Text:** 40x25 characters.
    - **Multicolor Text:** 4x8 double-width pixels per character.
    - **High-Res Graphics:** 320x200 1bpp.
    - **Multicolor Graphics:** 160x200 2bpp.
- **Sprites:** 8 hardware sprites, 24x21 pixels, high-res (1bpp) or multicolor (2bpp).
- **Raster Interrupts:** Can trigger an IRQ at a programmable scanline.
- **Memory:** 16KB visible range (switched via CIA registers).

## Programming Sequences & Details
### Key VIC-II Registers ($D000–$D02E)
- `$D000–$D00F`: Sprite X/Y coordinates (8 pairs).
- `$D010`: MSB of Sprite X-coordinates.
- `$D011`: Control Register 1 (Vertical Scroll, Screen height, Screen enable, Bitmap mode).
- `$D012`: Raster counter (Write to set IRQ line).
- `$D015`: Sprite enabled bits.
- `$D016`: Control Register 2 (Horizontal Scroll, Screen width, Multicolor mode).
- `$D018`: Memory pointers (Video matrix and Character base).
- `$D019`: Interrupt status (Reading clears flags).
- `$D01A`: Interrupt control (Enable IRQ sources).
- `$D020–$D021`: Border and Background colors.

### "Bad Lines"
- Every 8th scanline in text mode, the VIC-II stalls the CPU for 40 cycles to fetch character pointers.

### Sprite Multi-color
- In multicolor mode, pixels are 2 bits wide. Sprite colors are determined by shared registers `$D025` and `$D026` plus the individual sprite color register.

## notable Gaps
- Cycle-accurate "Bad line" contention.
- Exact fix for multicolor sprite flip (addressed in Task 52).
