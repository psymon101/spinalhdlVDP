# Commodore 64 VDP (MOS 6567/6569 VIC-II)

## 1. Video Model Summary

- **Native logical resolution:** 320×200 pixels (character cell based)
- **Display structure:** Character/tile or bitmap modes with per-cell color attributes
- **Border/background:** Software-controlled via `$D020`/`$D021`; part of platform identity
- **Frame rate:** 50 Hz (PAL 6569) / 60 Hz (NTSC 6567)

## 2. Supported Features

- Standard text mode (40×25 characters)
- Multicolor text mode (4×8 double-wide pixels per character)
- High-res bitmap mode (320×200, 1bpp)
- Multicolor bitmap mode (160×200, 2bpp)
- 8 hardware sprites (24×21 pixels, high-res or multicolor)
- Raster interrupt at programmable scanline
- 16-color fixed system palette (circuitry-aware reference required)

## 3. Unsupported / Deferred Features

- **Cycle-accurate "bad line" contention:** VIC-II stalls the CPU every 8th scanline in text mode. Mode0 does not emulate this host-side contention.
- **Exact VIC-II timing tricks:** Open-border and other cycle-exact tricks are separate hardening work, not baseline adapter assumptions.
- **Multicolor sprite flip:** Addressed in Task 52.

## 4. Adapter Register Surface

- `$D000–$D00F`: Sprite X/Y coordinates (8 pairs)
- `$D010`: MSB of Sprite X-coordinates
- `$D011`: Control Register 1 (Vertical scroll, Screen height, Screen enable, Bitmap mode)
- `$D012`: Raster counter (write to set IRQ line)
- `$D015`: Sprite enabled bits
- `$D016`: Control Register 2 (Horizontal scroll, Screen width, Multicolor mode)
- `$D018`: Memory pointers (Video matrix and Character base)
- `$D019`: Interrupt status (reading clears flags)
- `$D01A`: Interrupt control (Enable IRQ sources)
- `$D020–$D021`: Border and Background colors

## 5. Mode0 Mapping

| C64 Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| 40×25 text | Tilemap + attribute fetch | Map character ROM to tile data |
| 320×200 bitmap | Bitmap fetch | Present as 1bpp with color RAM pairing |
| 160×200 multicolor | Bitmap fetch (2bpp) | Present as 2bpp with color RAM pairing |
| 8 sprites | Sprite evaluation (descCount=8) | Map sprite coordinates and patterns |
| Raster IRQ | Raster trigger / Copper-lite | Fire IRQ at programmable scanline |
| Border/Background | Background fill | Honor `$D020`/`$D021` relationship |

## 6. Host Memory Layout

- **Video matrix:** 1KB at configurable base (text mode)
- **Character ROM:** 4KB (default) or user-defined at configurable base
- **Bitmap:** 8KB at configurable base
- **Color RAM:** 1KB (`$D800`–`$DBFF`, only lower 4 bits used in text mode)
- **Sprite data:** 64 bytes per sprite pattern (24×21 = 63 bytes + 1 pad)
- **Visible range:** 16 KB switched via CIA registers

## 7. Firmware Workflow

1. Host uploads character set or bitmap data to VIC-II visible memory
2. Host sets video matrix and bitmap base pointers via `$D018`
3. Host configures sprites via `$D000`–`D00F` and `$D015`
4. Host sets border/background colors via `$D020`–`$D021`
5. Host enables raster interrupts via `$D01A` if needed

## 8. Proof / Validation Plan

- **Sim:** Verify text mode, bitmap mode, and sprite display do not break existing substrate
- **Hardware:** Static test pattern with border, sprites, and raster split; 30s capture freeze=0
- **Palette:** Use circuitry-aware reference (e.g., Pepto's palette), not generic RGB

## 9. Known Gaps / Gotchas

- **Border/background relationship:** `$D020` (border) and `$D021` (background) interaction is part of the C64 visual character and should be treated as a requirement, not a decorative afterthought.
- **Palette accuracy:** Generic RGB approximations are not acceptable. Use a circuitry-aware C64 palette reference.
- **Native font:** The default C64 character ROM must be included as baseline text/tile asset.
- **Bad lines:** Every 8th scanline in text mode, the VIC-II stalls the CPU for 40 cycles to fetch character pointers. This is not emulated in Mode0 v1.
- **Minimum readiness:** Through `R3` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [The MOS 6567/6569 Video Interface Chip II (Christian Ludscheidt)](https://www.zimmers.net/cbemirror/cbm/c64/programming/documents/vic-ii.txt)
- [Ultimate Commodore 64 Reference Guide (mist64/c64ref)](https://github.com/mist64/c64ref)
