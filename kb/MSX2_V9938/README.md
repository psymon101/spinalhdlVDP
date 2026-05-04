# MSX2 VDP (Yamaha V9938 / MSX-Video)

## Primary References
- [Yamaha V9938 Technical Data Book](https://grauw.nl/articles/v9938-technical-data-book/) - Official specification from Yamaha.
- [V9938 MSX-Video Technical Reference (MSX Assembly Page)](http://map.grauw.nl/resources/video/v9938.php) - Excellent modern summary.

## Technical Summary
- **Processor:** Yamaha V9938.
- **Resolution:** 256x192 to 512x212 pixels (interlacing to 424 lines).
- **Modes:**
    - **T1/T2:** Text modes (40/80 columns).
    - **G1-G3:** TMS9918 compatible pattern modes.
    - **G4-G7:** Bitmap modes (Screen 5-8). G7 is 256-color (RGB332).
- **Color:** 16 palette entries from 512-color space (9-bit RGB).
- **Sprites:** 32 total, max 8 per scanline (Sprite Mode 2), multi-color per line support.
- **Command Engine:** Hardware acceleration for VRAM copies, fills, line drawing, and logical operations.
- **Scrolling:** Dedicated vertical hardware scroll register.

## Programming Sequences & Details
### Command Engine Registers (R#32 to R#46)
- **R#32-35**: Source X/Y (SX, SY).
- **R#36-39**: Destination X/Y (DX, DY).
- **R#40-43**: Number of dots X/Y (NX, NY).
- **R#44**: Color/Data byte (CLR).
- **R#45**: Argument (ARG - Direction, Source/Dest RAM).
- **R#46**: Command Opcode (CMD).

### Common Command Opcodes
- `0x50` **PSET**: Draw pixel.
- `0x70` **LINE**: Draw line.
- `0x80` **LMMV**: Logical Move VDP to VRAM (Fill).
- `0x90` **LMMM**: Logical Move VRAM to VRAM (Copy with logical ops).
- `0xC0` **HMMV**: High-speed Fill.
- `0xD0` **HMMM**: High-speed Copy.

### Status Register 2 (S#2)
- **Bit 0 (CE)**: Command Executing (Wait until 0).
- **Bit 7 (TR)**: Transfer Ready.

## notable Gaps
- Hardware Command Engine (Blitter) exact register set.
- Interlaced display (424 lines).
