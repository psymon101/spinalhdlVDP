# TMS9918 Family VDP

## 1. Video Model Summary

- **Native logical resolution:** 256×192 pixels
- **Display structure:** Tile-centric background with sprite overlay
- **Strong framed active area** is typical
- **Frame rate:** 60 Hz (NTSC TMS9918A) / 50 Hz (PAL TMS9929A)

## 2. Supported Features

- Graphics I mode (256×192 pattern graphics)
- Graphics II mode (enhanced bitmap / Screen 2 on MSX)
- Text mode (40×24 characters, 6×8 pixels each)
- Multicolor mode (64×48 low-res color-dot)
- 32 sprites, 8×8 or 16×16 pixels
- Automatic sprite priority and collision detection
- 15 fixed colors + 1 transparent
- Up to 16 KB VRAM

## 3. Unsupported / Deferred Features

- **Multicolor mode (64×48):** Not a priority for Mode0; low-resolution color-dot mode.
- **Cycle-accurate VRAM access timing:** Not required for v1 adapter proof.
- **External video input:** Not supported in Mode0 v1.

## 4. Adapter Register Surface

- **Reg 0:** Mode Control 0 (External video enable)
- **Reg 1:** Mode Control 1 (VRAM size, Blanking, Interrupt enable, Sprite size/magnification)
- **Reg 2:** Name Table Base (Value × `$400`)
- **Reg 3:** Color Table Base (Value × `$40`)
- **Reg 4:** Pattern Generator Base (Value × `$800`)
- **Reg 5:** Sprite Attribute Table Base (Value × `$80`)
- **Reg 6:** Sprite Pattern Generator Base (Value × `$800`)
- **Reg 7:** Backdrop/Text Color (Bits 0–3: Text; Bits 4–7: Backdrop)

## 5. Mode0 Mapping

| TMS9918 Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Tile background | Tile + attribute fetch | Map name table + pattern + color tables |
| 32 sprites | Sprite evaluation (descCount=8) | Map SAT to sprite slots; handle 8/scanline limit |
| Fixed palette | Indexed palette (16 entries) | Load TMS9918 fixed palette |
| Sprite collision | Sprite collision flag | Surface collision detection to host |

## 6. Host Memory Layout

- **VRAM:** Up to 16 KB
- **Name Table:** Configurable base (Reg 2)
- **Color Table:** Configurable base (Reg 3)
- **Pattern Generator:** Configurable base (Reg 4)
- **Sprite Attribute Table (SAT):** Configurable base (Reg 5); 4 bytes per sprite
  - `Y, X, Pattern Index, Attributes (Color + Early Clock bit)`
  - Y-coordinate of 208 terminates the sprite list
- **Sprite Pattern Generator:** Configurable base (Reg 6)

## 7. Firmware Workflow

1. Host initializes VRAM and writes mode registers (0–7)
2. Host uploads pattern and color tables
3. Host builds name table
4. Host uploads sprite patterns and SAT
5. Host enables display via Reg 1

## 8. Proof / Validation Plan

- **Sim:** Verify tile + sprite composition is coherent
- **Hardware:** Static test pattern with tiles and sprites; 30s capture freeze=0
- **Family check:** Ensure SMS/GG deltas are documented separately

## 9. Known Gaps / Gotchas

- **Compact low-resolution presentation:** Preserve the compact low-resolution console/computer presentation rather than stretching to fill output.
- **Framed active area:** Strong framed active area is typical; border treatment should be explicit.
- **Mode-specific color restrictions:** Graphics I, Graphics II, Text, and Multicolor have different color constraints.
- **Sprite-per-line limits:** 4 sprites per line on original TMS9918; 8 on later variants.
- **Minimum readiness:** Through `R4` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [TMS9918A/TMS9928A/TMS9929A Video Display Processors Data Manual (Bitsavers)](http://www.bitsavers.org/components/ti/TMS9900/TMS9918A_TMS9928A_TMS9929A_Video_Display_Processors_Data_Manual_Nov82.pdf)
- [TI TMS9900 Series Documentation (Archive.org)](https://archive.org/details/bitsavers_tiTMS9900T929AVideoDisplayProcessorsDataManualNov8_6785534)
