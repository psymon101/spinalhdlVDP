# ZX Spectrum VDP (ULA)

## Primary References
- [ZX Spectrum ULA technical documentation (Chris Smith)](http://www.zxdesign.info/book.shtml) - The definitive guide to ULA timing and behavior.
- [Spectrum for Everyone - ULA details](https://spectrumforeveryone.com/technical/zx-spectrum-ula-details/)

## Technical Summary
- **Processor:** Custom Uncommitted Logic Array (ULA).
- **Resolution:** 256x192 pixels.
- **Border:** Software-controlled border color ($FE port).
- **Attributes:** 1 byte per 8x8 pixel block.
    - Format: `[Flash | Bright | Paper (3 bits) | Ink (3 bits)]`
- **Color:** 8 base colors, two brightness levels (Bright 0/1), total 15 effective colors (Bright-Black is same as Black).
- **Memory Map:**
    - Bitmap: `$4000 - $57FF` (6,144 bytes).
    - Attributes: `$5800 - $5AFF` (768 bytes).

## Programming Sequences & Details
### Bitmap Addressing (Non-Linear)
The memory layout is designed to simplify Z80 address calculation for character rows but is non-linear for full-screen bitmaps.
Address bits for offset in `$4000`:
`00 [Y7 Y6] [Y2 Y1 Y0] [Y5 Y4 Y3] [X4 X3 X2 X1 X0]`

### Attribute Byte ($5800+)
- Bits 0-2: Ink (Foreground) color.
- Bits 3-5: Paper (Background) color.
- Bit 6: Brightness (applies to both Ink and Paper).
- Bit 7: Flash (alternates Ink and Paper at ~1.5 Hz).

### Port $FE (Border Control)
- Bits 0-2: Border color.

## Notable Gaps
- **Contention:** The ULA stalls the Z80 when accessing "contended memory" ($4000-$7FFF). Mode0 substrate currently ignores host-side contention.
- **Flash Timing:** Handled by the adapter or host; Mode0 doesn't have a native 1.5Hz blinker.
