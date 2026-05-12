# Sega Master System / Game Gear VDP

## 1. Video Model Summary

- **Native logical resolution:** 256×192 (Standard), 256×224, 256×240 (PAL)
- **Display structure:** Tilemap layers plus sprite system
- **Frame rate:** 50/60 Hz
- **Related platforms:** Evolution of TMS9918A VDP; treated as related but not visually identical

## 2. Supported Features

- Mode 4 (256×192, 16-color tiles, hardware scrolling)
- Two 16-color palettes (32 total) from 64-color master palette (6-bit RGB)
- Game Gear: 4096-color palette (12-bit RGB)
- 8×8 pixel tiles, 4 bitplanes (32 bytes per tile)
- 64 total sprites, max 8 per scanline
- 8×8 or 8×16 sprite sizes
- Hardware horizontal and vertical scroll registers
- V-Blank and H-Blank (Line) interrupts

## 3. Unsupported / Deferred Features

- **Sprite Zoom (2×) mode:** Not required for v1 adapter proof.
- **Line interrupt precision:** Reg 10 counter behavior may need verification.
- **Game Gear viewport conflation:** Must not be silently conflated with Master System full-frame output.

## 4. Adapter Register Surface

- `$BE`: Data Port (VRAM/CRAM Read/Write)
- `$BF`: Control Port (Address set, Register write, Status read)
- **Reg 0:** Mode Control 1 (Line Interrupts, Mask leftmost 8 pixels)
- **Reg 1:** Mode Control 2 (Frame Interrupts, Sprite size)
- **Reg 2:** Name Table Base Address
- **Reg 5:** Sprite Attribute Table Base Address
- **Reg 6:** Sprite Pattern Generator Base Address
- **Reg 7:** Backdrop/Border Color
- **Reg 8:** Horizontal Scroll Value
- **Reg 9:** Vertical Scroll Value
- **Reg 10:** Line Interrupt Counter

## 5. Mode0 Mapping

| SMS/GG Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Tile background | Tile + attribute fetch | Map name table to tilemap with flip/palette/priority bits |
| 64 sprites | Sprite evaluation (descCount=8) | Map SAT to sprite slots; respect 8/scanline limit |
| Hardware scroll | Scroll registers | Surface H/V scroll to host |
| Line interrupts | Raster trigger | Fire H-Blank IRQ at programmed interval |

## 6. Host Memory Layout

- **VRAM:** 16 KB
- **Name Table:** 32×28 tiles, 2 bytes per entry (Tile index, FlipH, FlipV, Palette, Priority)
- **Sprite Attribute Table (SAT):** 256 bytes
  - First 64 bytes: Y-coordinates (one per sprite); `$D0` terminates list in 192-line mode
  - Remaining bytes: X-coordinate and Tile Index per sprite

## 7. Firmware Workflow

1. Host uploads tile patterns to VRAM
2. Host builds name table with tile indices and attributes
3. Host uploads sprite patterns and SAT
4. Host sets scroll values (Reg 8, Reg 9)
5. Host enables display and interrupts via Reg 1

## 8. Proof / Validation Plan

- **Sim:** Verify tilemap + sprite composition does not break substrate
- **Hardware:** Static test pattern with scrolling and sprites; 30s capture freeze=0
- **Platform distinction:** Document Master System framing vs. Game Gear viewport differences explicitly

## 9. Known Gaps / Gotchas

- **Master System vs. Game Gear:** These should be treated as related but not visually identical presentation targets. Game Gear viewport/windowing must not be silently conflated with Master System full-frame output.
- **Palette format:** Differs from earlier TMS-family expectations; document SMS palette banks and GG 12-bit palette separately.
- **Sprite limits:** Max 8 per scanline; overflow behavior should be documented.
- **Scrolling-window presentation:** Hardware scroll registers create scrolling-window behavior that should be verified.
- **Minimum readiness:** Through `R4` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [Sega Master System VDP Documentation (Charles MacDonald)](https://cgfm2.emuunlim.com/smsvdp.txt)
- [Sega VDP Documentation (Sega Retro)](https://www.segaretro.org/Sega_VDP)
