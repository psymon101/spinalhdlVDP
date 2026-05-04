# Sega Master System / Game Gear VDP

## Primary References
- [Sega Master System VDP Documentation (Charles MacDonald)](https://cgfm2.emuunlim.com/smsvdp.txt) - Definitive reverse-engineering reference.
- [Sega VDP Documentation (Sega Retro)](https://www.segaretro.org/Sega_VDP) - Detailed overview of revisions and modes.

## Technical Summary
- **Processor:** Evolution of TMS9918A (315-5124, 315-5246, 315-5378).
- **Modes:** Primarily **Mode 4** (256x192, 16-color tiles, hardware scrolling).
- **Resolution:** 256x192 (Standard), 256x224, 256x240 (PAL).
- **Color:** Two 16-color palettes (32 total) from 64-color master palette (6-bit RGB). Game Gear has 4096-color palette.
- **Tiles:** 8x8 pixels, 4 bitplanes (32 bytes per tile).
- **Background:** Name Table (32x28 tiles), 2 bytes per entry (Tile index, FlipH, FlipV, Palette, Priority).
- **Sprites:** 64 total, max 8 per scanline, 8x8 or 8x16 size.
- **Scrolling:** Hardware horizontal and vertical scroll registers.
- **Interrupts:** V-Blank and H-Blank (Line) interrupts.

## Programming Sequences & Details
### I/O Ports
- `$BE`: Data Port (VRAM/CRAM Read/Write).
- `$BF`: Control Port (Address set, Register write, Status read).

### VDP Registers (Mode 4)
- **Reg 0**: Mode Control 1 (Line Interrupts, Mask leftmost 8 pixels).
- **Reg 1**: Mode Control 2 (Frame Interrupts, Sprite size).
- **Reg 2**: Name Table Base Address.
- **Reg 5**: Sprite Attribute Table Base Address.
- **Reg 6**: Sprite Pattern Generator Base Address.
- **Reg 7**: Backdrop/Border Color.
- **Reg 8**: Horizontal Scroll Value.
- **Reg 9**: Vertical Scroll Value.
- **Reg 10**: Line Interrupt Counter.

### Sprite Attribute Table (SAT) - 256 bytes
- **Y-Coordinates**: First 64 bytes (one per sprite). `$D0` terminates the list in 192-line mode.
- **X and Tile Info**: Remaining bytes. Each sprite uses 2 bytes: `[X-coordinate]` and `[Tile Index]`.

## notable Gaps
- Line interrupt precision (Reg 10 counter).
- Sprite Zoom (2x) mode.
