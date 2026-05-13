# SNES / Super Famicom VDP (Ricoh 5C77/5C78 PPU)

## 1. Video Model Summary

- **Native logical resolution:** 256×224 (Progressive) or 256×448 (Interlaced)
- **Display structure:** Up to 4 background layers plus sprite overlay
- **Overscan/interlace/active-area variants need explicit treatment if claimed**
- **Frame rate:** 50/60 Hz

## 2. Supported Features

- 8 background modes (0–7)
- 15-bit RGB (32,768 colors); 256-color palette (CGRAM)
- 128 total sprites, 32 per scanline
- 8×8 or 16×16 tiles
- High-speed DMA and HDMA (per-line updates)
- Window masks and color math
- Mode 7 affine transformations

## 3. Unsupported / Deferred Features

- **32 sprites per scanline:** Mode0 substrate limit is 8; requires sprite expansion.
- **HDMA precision:** Handled by Task 33 Copper-lite; per-line register updates may need raster trigger support.
- **Complex Mode 7 matrix math:** Affine transformations require affine primitive support.
- **Interlaced display:** 256×448 not required for v1 adapter proof.

## 4. Adapter Register Surface

- `$2100` **INIDISP:** Screen brightness and forced blank
- `$2101` **OBSEL:** Object size and pattern base address
- `$2102–$2103` **OAMADD:** OAM address
- `$2105` **BGMODE:** Background mode and tile size (8×8 or 16×16)
- `$2107–$210A` **BGnSC:** BG1–BG4 tilemap address and size
- `$210B–$210C` **BGnNBA:** BG1–BG4 character data base address
- `$210D–$2114` **BGnHOFS/VOFS:** BG1–BG4 X/Y scroll (write twice)
- `$2115` **VMAIN:** VRAM address increment mode
- `$2116–$2117` **VMADD:** VRAM address
- `$2118–$2119` **VMDATA:** VRAM data write
- `$2121` **CGADD:** CGRAM (palette) address
- `$2122` **CGDATA:** CGRAM data write (write twice)
- `$212C` **TM:** Main screen layer enable

### Mode 7 (Matrix Math)
- `$211A–$2120`: Matrix parameters (A, B, C, D) and center position (X, Y) for affine transformations

## 5. Mode0 Mapping

| SNES Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| 4 BG layers | 4-layer compositor | Map BG1–BG4 to compositor layers |
| 128 sprites | Sprite evaluation (descCount=8) | Map OAM to sprite slots; respect 8/scanline limit |
| HDMA | Raster trigger / Copper-lite | Per-line register updates at scanline boundaries |
| Window masks | Windowing primitive | Apply window masks to layers |
| Color math | Color math primitive | Add/subtract/screen blending |
| Mode 7 | Affine primitive | Matrix-transformed background |

## 6. Host Memory Layout

- **VRAM:** 64 KB
- **Tilemap data:** Configurable bases per background
- **Character data:** Configurable bases per background
- **OAM:** 512 bytes (128 sprites × 4 bytes) + 32 bytes size/flip bits
- **CGRAM:** 512 bytes (256 colors × 2 bytes)

## 7. Firmware Workflow

1. Host uploads tile/character data to VRAM
2. Host builds tilemaps for active backgrounds
3. Host loads palette into CGRAM
4. Host writes sprite data to OAM
5. Host sets scroll and mode registers
6. Host enables layers via `$212C`

## 8. Proof / Validation Plan

- **Sim:** Verify 4-layer composition and color math are coherent
- **Hardware:** Static test pattern with multiple layers; 30s capture freeze=0
- **Honesty check:** Do not claim Mode 7 support unless affine primitive is proven

## 9. Known Gaps / Gotchas

- **Overscan/interlace:** If claimed, overscan/interlace/active-area variants need explicit treatment.
- **Richer palette handling:** Windowing and color math are core presentation behaviors, not extras.
- **4-layer composition pressure:** The Mode0 compositor must support 4 layers for honest SNES adapter proof.
- **HDMA-style per-line updates:** Require beam-driven automation primitive (Task 33).
- **Minimum readiness:** Through `R6`, plus `R8` for Mode 7, per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [Super Nintendo Entertainment System (SNES) Fullchip Technical Manual](https://romhack.github.io/doc/snes/snes_technical_manual.pdf)
- [SNES PPU Rendering (NesDev Wiki / SNESDev)](https://snesdev.mesen.ca/wiki/index.php?title=PPU_rendering)

### Localized References

The following reference materials are stored locally in `kb/SNES/references/FpgaSnes`:
- `PPU.vhd`
- `LICENSE`
- `README.md`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

