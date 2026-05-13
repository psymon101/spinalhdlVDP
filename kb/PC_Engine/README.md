# PC Engine VDC (HuC6270)

## 1. Video Model Summary

- **Native logical resolution:** 256×239 to 512×240 pixels
- **Display structure:** One scrollable layer of 8×8 tiles (planar 4bpp) plus sprites
- **Frame rate:** 50/60 Hz

## 2. Supported Features

- One scrollable background layer of 8×8 tiles (planar 4bpp)
- 64 total sprites, max 16 per scanline
- 512-color space (9-bit RGB) via separate VCE (HuC6260)
- 64 KB VRAM (32K × 16-bit words)
- VRAM-to-SATB DMA
- Raster IRQ via RCR register

## 3. Unsupported / Deferred Features

- **Variable sprite sizes:** 16×16 to 32×64 not required for v1.
- **16 sprites per scanline:** Mode0 substrate limit is 8; requires sprite expansion.
- **512×240 resolution:** May require Mode0 horizontal scaling support.

## 4. Adapter Register Surface

- `$00` **MAWR:** Memory Address Write Register
- `$01` **MARR:** Memory Address Read Register
- `$02` **VWR/VRR:** VRAM Data Read/Write (auto-increments)
- `$05` **CR:** Control Register (Interrupts, BG/Sprite enable)
- `$06` **RCR:** Raster Counter Register (Raster IRQ line + 64)
- `$07` **BXR:** Background X-Scroll
- `$08` **BYR:** Background Y-Scroll
- `$09` **MWR:** Memory Width Register (Virtual BG size)
- `$0F` **DCR:** DMA Control Register (VRAM-to-SATB)
- `$13` **SATB:** Sprite Attribute Table Base Address

### Sprite Attribute Table (SAT) Format (8 bytes per sprite)
- **Word 0:** Y-Coordinate (Visible area starts at 64)
- **Word 1:** X-Coordinate (Visible area starts at 32)
- **Word 2:** Pattern Index (VRAM address >> 6)
- **Word 3:** Attributes (Palette Index, Priority, FlipH, FlipV, Width, Height)

## 5. Mode0 Mapping

| PC Engine Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Scrollable tile layer | Tile + attribute fetch | Map virtual BG to tilemap with scroll |
| 64 sprites | Sprite evaluation (descCount=8) | Map SAT to sprite slots; respect 8/scanline limit |
| 512-color palette | Palette RAM (VCE) | Load 9-bit RGB palette |
| Raster IRQ | Raster trigger | Fire IRQ at programmed line |

## 6. Host Memory Layout

- **VRAM:** 64 KB (32K × 16-bit words)
- **Background tilemap:** Configurable virtual size via MWR
- **Sprite Attribute Table:** Configurable base via SATB register
- **VCE palette:** 512-color space managed separately

## 7. Firmware Workflow

1. Host uploads tile patterns to VRAM
2. Host builds background tilemap
3. Host uploads sprite patterns and SAT
4. Host sets scroll values (BXR, BYR)
5. Host enables display and interrupts via CR

## 8. Proof / Validation Plan

- **Sim:** Verify scrollable tile + sprite composition is coherent
- **Hardware:** Static test pattern with scrolling and sprites; 30s capture freeze=0

## 9. Known Gaps / Gotchas

- **Low-resolution arcade-console feel:** Preserve the low-resolution arcade-console feel rather than stretching blindly to fill output.
- **Visible area treatment:** Should be documented explicitly.
- **Bank/palette usage:** Bank/palette usage is part of the PC Engine look.
- **Tile/sprite mix:** Strong sprite emphasis; adapter must balance tile background with sprite overlay.
- **Minimum readiness:** Through `R5` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [HuC6270 Technical Manual (Archive.org)](https://archive.org/details/huc6270-cmos-video-display-controller-manual)
- [MagicEngine Hardware Doc: PC Engine VDC](http://www.magicengine.com/pce_project/doc/pce_vdc.html)

### Localized References

The following reference materials are stored locally in `kb/PC_Engine/references/TurboGrafx16_MiSTer`:
- `huc6260.vhd`
- `huc6270.vhd`
- `huc6202.vhd`
- `pce_top.vhd`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

