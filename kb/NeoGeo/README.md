# Neo Geo VDP (LSPC / VDC)

## 1. Video Model Summary

- **Native logical resolution:** 320×224 pixels
- **Display structure:** "Line Sprite" architecture — no background tilemaps; everything is a sprite
- **Frame rate:** 60 Hz

## 2. Supported Features

- Fix Layer: 40×32 8×8 tile overlay (top-level HUD/text)
- Sprite Layer: Chained tall sprites (up to 512 px height) forming backgrounds and objects
- 380 total sprites, 96 per scanline
- 16 px wide, variable height (1–32 tiles)
- Hardware-based vertical and horizontal scaling-down
- Sticky bit for chaining sprites together
- 256 sub-palettes of 16 colors (4,096 total); 65,536 master palette (RGB666+3)
- Dual 320-pixel line buffers for flicker-free high-density sprite rendering

## 3. Unsupported / Deferred Features

- **96 sprites per scanline:** Mode0 substrate limited to 32/8; massive sprite expansion required.
- **Hardware shrinking logic:** Not supported in Mode0 v1.
- **Massive palette capacity:** 4,096 active colors exceeds Mode0 palette RAM.
- **Line buffer architecture:** Dual line buffers are not part of Mode0 substrate.

## 4. Adapter Register Surface

- `$3C0000`: `VRAM_DATA`
- `$3C0002`: `VRAM_ADDR`
- `$3C0004`: `VRAM_MOD` (Auto-increment value)

### VRAM Memory Map
- `$0000–$6FFF` **SCB1:** Sprite tile indices and attributes (Palette, FlipH, FlipV)
- `$7000–$74FF` **FIX:** Fix layer map (40×32 tiles)
- `$8000–$81FF` **SCB2:** Shrink values (Horizontal/Vertical)
- `$8200–$83FF` **SCB3:** Y-position, Height, and Sticky bits
- `$8400–$85FF` **SCB4:** X-position and Horizontal Link bits

### Sprite Control Blocks (SCB)
- **SCB3:** Y-Position stored as `496 - Y`. Height is 1–32 tiles.
- **SCB4:** X-Position (0–511). Horizontal Link bit places next sprite at `X + 16`.
- **Shrinking:** SCB2 contains coefficients for vertical (0–255) and horizontal (0–15) shrinking.

## 5. Mode0 Mapping

| Neo Geo Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Fix layer | Tile + attribute fetch | Map FIX map to tile overlay |
| Sprite backgrounds | Sprite evaluation (descCount=8/32) | Chain sprites to form background strips |
| Sprite objects | Sprite evaluation | Map SCB to sprite slots |
| Scaling | Not supported in v1 | Document as deferred |
| Rich palette | Palette RAM | Subset to Mode0 palette capacity |

## 6. Host Memory Layout

- **VRAM:** 64 KB + 4 KB internal RAM
- **SCB1–SCB4:** Stored in VRAM at fixed regions
- **FIX map:** 40×32 tiles at `$7000`
- **Sprite tile data:** Stored in VRAM

## 7. Firmware Workflow

1. Host uploads sprite tile data to VRAM
2. Host builds FIX map for HUD/text overlay
3. Host configures SCB1–SCB4 for all visible sprites
4. Host sets palette via palette RAM interface
5. Host enables display

## 8. Proof / Validation Plan

- **Sim:** Verify Fix layer + sprite composition is coherent
- **Hardware:** Static test pattern with Fix layer and sprite background; 30s capture freeze=0
- **Honesty check:** Do not claim full Neo Geo sprite density if Mode0 substrate cannot support it

## 9. Known Gaps / Gotchas

- **Arcade presentation:** Preserve the arcade presentation rather than flattening it into a generic console-style tilemap output.
- **Sprite-centric composition:** Heavily sprite-centric composition; adapter must handle high sprite counts gracefully.
- **Large object integration:** Large objects and tile/sprite integration rules are complex.
- **Zoom/scaling semantics:** Hardware shrinking is part of platform identity but not supported in Mode0 v1.
- **Sprite ordering and priority feel:** Part of the Neo Geo visual character.
- **Minimum readiness:** Through `R7` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [NeoGeoDev Wiki](https://wiki.neogeodev.org)
- [Neo Geo Official Development Manual](https://wiki.neogeodev.org/index.php?title=Official_development_manual)

### Localized References

The following reference materials are stored locally in `kb/NeoGeo/references/NeoGeo_MiSTer`:
- `lspc2_a2.v`
- `lspc2_clk.v`
- `lspc_regs.v`
- `lspc_timer.v`
- `neo_273.v`
- `neo_b1.v`
- `neo_cmc.v`
- `hshrink.v`
- `videosync.v`
- `irq.v`
- `zmc2_dot.v`
- `autoanim.v`
- `slow_cycle.v`
- `fast_cycle.v`
- `linebuffer.v`
- `LICENSE`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

