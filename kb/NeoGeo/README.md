# Neo Geo VDP (LSPC / VDC)

## Primary References
- [NeoGeoDev Wiki](https://wiki.neogeodev.org) - The definitive community resource.
- [Neo Geo Official Development Manual](https://wiki.neogeodev.org/index.php?title=Official_development_manual) - SNK internal technical specifications.

## Technical Summary
- **Architecture:** "Line Sprite" architecture. No background tilemaps; everything is a sprite.
- **Resolution:** 320x224 pixels.
- **Layers:**
    - **Fix Layer:** 40x32 8x8 tile overlay (top-level HUD/text).
    - **Sprite Layer:** Chained tall sprites (up to 512px height) forming backgrounds and objects.
- **Sprites:** 380 total, 96 per scanline.
    - **Size:** 16px wide, variable height (1-32 tiles).
    - **Shrinking:** Hardware-based vertical and horizontal scaling-down.
    - **Sticky Bit:** Chaining sprites together for synchronized movement.
- **Color:** 256 sub-palettes of 16 colors (4,096 total); 65,536 master palette (RGB666+3).
- **VRAM:** 64KB + 4KB internal RAM, accessed via `$3C0000` data port.
- **Line Buffer:** Dual 320-pixel line buffers for flicker-free high-density sprite rendering.

## Programming Sequences & Details
### VRAM Memory Map
- `$0000–$6FFF` **SCB1**: Sprite tile indices and attributes (Palette, FlipH, FlipV).
- `$7000–$74FF` **FIX**: Fix layer map (40x32 tiles).
- `$8000–$81FF` **SCB2**: Shrink values (Horizontal/Vertical).
- `$8200–$83FF` **SCB3**: Y-position, Height, and Sticky bits.
- `$8400–$85FF` **SCB4**: X-position and Horizontal Link bits.

### Sprite Control Blocks (SCB)
- **SCB3**: Y-Position stored as `496 - Y`. Height is 1-32 tiles.
- **SCB4**: X-Position (0-511). Horizontal Link bit places next sprite at `X + 16`.
- **Shrinking**: SCB2 contains coefficients for vertical (0-255) and horizontal (0-15) shrinking.

### Register Interface (68k B-Bus)
- `$3C0000`: `VRAM_DATA`.
- `$3C0002`: `VRAM_ADDR`.
- `$3C0004`: `VRAM_MOD` (Auto-increment value).

## notable Gaps
- 96 sprites per scanline (Mode0 substrate limited to 32/8).
- Hardware shrinking logic.
- Massive palette capacity (4096 colors).
