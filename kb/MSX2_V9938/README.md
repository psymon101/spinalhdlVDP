# MSX2 VDP (Yamaha V9938 / MSX-Video)

## 1. Video Model Summary

- **Native logical resolution:** 256×192 to 512×212 pixels (interlacing to 424 lines)
- **Display structure:** Multiple distinct screen modes with different tile/bitmap expectations
- **Visible window / border treatment varies by screen mode**
- **Frame rate:** 50/60 Hz

## 2. Supported Features

- Text modes (T1/T2: 40/80 columns)
- TMS9918 compatible pattern modes (G1–G3)
- Bitmap modes (G4–G7, Screen 5–8)
- G7: 256-color RGB332
- 16 palette entries from 512-color space (9-bit RGB)
- 32 sprites, max 8 per scanline (Sprite Mode 2)
- Multi-color per line support
- Hardware acceleration: VRAM copies, fills, line drawing, logical operations
- Dedicated vertical hardware scroll register

## 3. Unsupported / Deferred Features

- **Hardware Command Engine (Blitter):** Exact register set and behavior not fully mapped to Mode0.
- **Interlaced display (424 lines):** Not required for v1 adapter proof.
- **Line interrupts and mode changes:** Can be visually important but are deferred.

## 4. Adapter Register Surface

- **R#32–35:** Source X/Y (SX, SY)
- **R#36–39:** Destination X/Y (DX, DY)
- **R#40–43:** Number of dots X/Y (NX, NY)
- **R#44:** Color/Data byte (CLR)
- **R#45:** Argument (ARG — Direction, Source/Dest RAM)
- **R#46:** Command Opcode (CMD)
- **S#2 (Status Register 2):**
  - Bit 0 (CE): Command Executing
  - Bit 7 (TR): Transfer Ready

### Common Command Opcodes
- `0x50` **PSET:** Draw pixel
- `0x70` **LINE:** Draw line
- `0x80` **LMMV:** Logical Move VDP to VRAM (Fill)
- `0x90` **LMMM:** Logical Move VRAM to VRAM (Copy with logical ops)
- `0xC0` **HMMV:** High-speed Fill
- `0xD0` **HMMM:** High-speed Copy

## 5. Mode0 Mapping

| MSX2 Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Multiple screen modes | Mode selection / adapter dispatch | Route host mode requests to correct Mode0 primitive |
| Bitmap modes G4–G7 | Bitmap fetch | Present as linear or planar bitmap |
| Text modes T1/T2 | Tile + attribute fetch | Map to tilemap with 40/80 column widths |
| Sprites | Sprite evaluation (descCount=8) | Map Sprite Mode 2 to sprite slots |
| Command engine | Blitter (Task 49) | Map basic copy/fill commands |

## 6. Host Memory Layout

- **VRAM:** 128 KB (V9938) or 192 KB (V9958)
- **Screen mode-dependent layouts:** Each mode has distinct nametable, pattern, and color table bases
- **Sprite Attribute Table:** Mode 2 format with multi-color per line

## 7. Firmware Workflow

1. Host selects screen mode via control registers
2. Host uploads pattern/tile/bitmap data to VRAM
3. Host sets palette via palette registers
4. Host configures sprites via SAT
5. Host issues display enable and interrupt control

## 8. Proof / Validation Plan

- **Sim:** Verify mode switching and bitmap display are coherent
- **Hardware:** Static test pattern in one supported mode; 30s capture freeze=0
- **Mode honesty:** Do not claim support for modes not explicitly mapped

## 9. Known Gaps / Gotchas

- **Mode-sensitive presentation:** Visible window / border treatment varies by screen mode. Do not flatten into one "MSX-like" view.
- **Palette behavior:** Palette is mode-sensitive; document which modes use which palette behavior.
- **Command engine gap:** Full blitter command set is not mapped in Mode0 v1.
- **Stronger control bus discipline:** MSX2 requires more rigorous register sequencing than TMS9918 family.
- **Minimum readiness:** Through `R5` per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [Yamaha V9938 Technical Data Book](https://grauw.nl/articles/v9938-technical-data-book/)
- [V9938 MSX-Video Technical Reference (MSX Assembly Page)](http://map.grauw.nl/resources/video/v9938.php)

### Localized References

The following reference materials are stored locally in `kb/MSX2_V9938/references/MSX_MiSTer`:
- `vdp.vhd`
- `vdp_graphic4567.vhd`
- `vdp_graphic123m.vhd`
- `vdp_text12.vhd`
- `vdp_sprite.vhd`
- `vdp_linebuf.vhd`
- `vdp_doublebuf.vhd`
- `vdp_hvcounter.vhd`
- `vdp_colordec.vhd`
- `vdp_register.vhd`
- `vdp_interrupt.vhd`
- `vdp_ssg.vhd`
- `vdp_vga.vhd`
- `vdp_ntsc_pal.vhd`
- `vdp_wait_control.vhd`
- `vdp_command.vhd`
- `vdp_spinforam.vhd`
- `vdp_package.vhd`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

