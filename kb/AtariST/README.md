# Atari ST VDP (Shifter)

## Primary References
- [Atari ST Shifter technical documentation (Info-Coach)](http://www.info-coach.fr/atari/hardware/video.php) - Comprehensive register and timing breakdown.

## Technical Summary
- **Processor:** Shifter (Video Shift Register) + GLUE (Timing) + MMU (Address).
- **Resolutions:**
    - **Low:** 320x200, 16 colors (4 bitplanes), 50/60 Hz.
    - **Medium:** 640x200, 4 colors (2 bitplanes), 50/60 Hz.
    - **High:** 640x400, 2 colors (1 bitplane), 71.2 Hz (Monochrome).
- **Color:** 16 palette registers, 9-bit RGB (512 colors) on ST; 12-bit RGB (4,096 colors) on STE.
- **Memory:** Exactly 32,000 bytes (32 KB) of contiguous Chip RAM.
- **Format:** Bitplane interleaving in 16-bit words.

## Programming Sequences & Details
### Shifter Registers ($FFFF82xx)
- `$FFFF8201/03/0D`: Video Base Address (High, Mid, Low).
- `$FFFF8205/07/09`: Video Counter (Current read pointer).
- `$FFFF820A`: Sync Mode (Bit 0: External Sync, Bit 1: 0=60Hz, 1=50Hz).
- `$FFFF8240–$FFFF825F`: Palette Registers (16 words).
- `$FFFF8260`: Resolution (0=Low, 1=Medium, 2=High).

### ST Palette Format (3-bit)
- `0000 0RRR 0GGG 0BBB`.

### STE Palette Format (4-bit)
- `0000 Rrrr Gggg Bbbb` (uppercase = original bits, lowercase = new LSBs).

### Bitplane Interleaving (Low Res)
- 4 words define 16 pixels. Word 0 = Bit 0 of all 16 pixels, Word 1 = Bit 1, etc.

## notable Gaps
- 71.2 Hz monochrome mode (Mode0 targeting 60Hz standard).
- Exact interleaving format (Option A: host pre-interleaves; Option B: Shuffled fetch).
