# TMS9918 Family VDP

## Primary References
- [TMS9918A/TMS9928A/TMS9929A Video Display Processors Data Manual (Bitsavers)](http://www.bitsavers.org/components/ti/TMS9900/TMS9918A_TMS9928A_TMS9929A_Video_Display_Processors_Data_Manual_Nov82.pdf) - Original 1982 data manual.
- [TI TMS9900 Series Documentation (Archive.org)](https://archive.org/details/bitsavers_tiTMS9900T929AVideoDisplayProcessorsDataManualNov8_6785534) - TI series collection.

## Technical Summary
- **Resolution:** 256x192 pixels.
- **Color:** 15 fixed colors + 1 transparent.
- **Modes:**
    - **Graphics I:** 256x192 pattern graphics.
    - **Graphics II:** Enhanced bitmap mode (Screen 2 on MSX).
    - **Multicolor:** 64x48 low-res color-dot mode.
    - **Text:** 40x24 characters (6x8 pixels each).
- **Sprites:** 32 sprite planes, 8x8 or 16x16 pixels, automatic priority, collision detection.
- **Memory:** Up to 16KB VRAM.

## Programming Sequences & Details
### Registers (0–7)
- **Reg 0**: Mode Control 0 (External video enable).
- **Reg 1**: Mode Control 1 (VRAM size, Blanking, Interrupt enable, Sprite size/magnification).
- **Reg 2**: Name Table Base (Value × $400).
- **Reg 3**: Color Table Base (Value × $40).
- **Reg 4**: Pattern Generator Base (Value × $800).
- **Reg 5**: Sprite Attribute Table Base (Value × $80).
- **Reg 6**: Sprite Pattern Generator Base (Value × $800).
- **Reg 7**: Backdrop/Text Color (Bits 0-3: Text color, Bits 4-7: Backdrop color).

### Sprite Attribute Table (SAT)
- 4 bytes per sprite: `Y, X, Pattern Index, Attributes (Color + Early Clock bit)`.
- A Y-coordinate of 208 terminates the sprite list.

## notable Gaps
- Multicolor mode (64x48) not a priority for Mode0.
- Cycle-accurate VRAM access timing.
