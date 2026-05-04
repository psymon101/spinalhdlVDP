# PC Engine VDC (HuC6270)

## Primary References
- [HuC6270 Technical Manual (Archive.org)](https://archive.org/details/huc6270-cmos-video-display-controller-manual) - Original scanned technical manual.
- [MagicEngine Hardware Doc: PC Engine VDC](http://www.magicengine.com/pce_project/doc/pce_vdc.html) - Concise register and timing reference.

## Technical Summary
- **Processor:** Hudson Soft HuC6270 Video Display Controller (VDC).
- **Resolution:** 256x239 to 512x240 pixels.
- **Background:** One scrollable layer of 8x8 tiles (planar 4bpp).
- **Sprites:** 64 total, max 16 per scanline.
- **Color:** Managed by separate VCE (HuC6260); 512-color space (9-bit RGB).
- **VRAM:** 64 KB (32K x 16-bit words).

## Programming Sequences & Details
### Register Map ($00–$13)
- `$00` **MAWR**: Memory Address Write Register.
- `$01` **MARR**: Memory Address Read Register.
- `$02` **VWR/VRR**: VRAM Data Read/Write (auto-increments).
- `$05` **CR**: Control Register (Interrupts, BG/Sprite enable).
- `$06` **RCR**: Raster Counter Register (Raster IRQ line + 64).
- `$07` **BXR**: Background X-Scroll.
- `$08` **BYR**: Background Y-Scroll.
- `$09` **MWR**: Memory Width Register (Virtual BG size).
- `$0F` **DCR**: DMA Control Register (VRAM-to-SATB).
- `$13` **SATB**: Sprite Attribute Table Base Address.

### Sprite Attribute Table (SAT) Format (8 bytes per sprite)
- **Word 0**: Y-Coordinate (Visible area starts at 64).
- **Word 1**: X-Coordinate (Visible area starts at 32).
- **Word 2**: Pattern Index (VRAM address >> 6).
- **Word 3**: Attributes (Palette Index, Priority, FlipH, FlipV, Width, Height).

## notable Gaps
- Variable sprite sizes (16x16 to 32x64).
- 16 sprites per scanline (Mode0 substrate limit is 8).
