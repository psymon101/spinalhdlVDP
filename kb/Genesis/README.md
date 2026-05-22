# Sega Genesis VDP

## 1. Video Model Summary

- **Native logical resolution:** 320×224 (H40) or 256×224 (H32) pixels
- **Display structure:** Multi-layer tilemaps (Plane A, Plane B, Window) plus sprites
- **Frame rate:** 50/60 Hz

## 2. Supported Features

- Plane A and Plane B scrollable tilemaps
- Window plane (fixed or scrollable)
- 4 palettes of 16 colors (64 total) from 512-color master palette (9-bit RGB)
- Up to 80 sprites total, 20 per scanline (H40)
- 8×8 to 32×32 sprite sizes
- High-speed memory-to-VRAM DMA
- Per-scanline and per-column scroll modes
- H-blank interrupt

## 3. Unsupported / Deferred Features

- **20 sprites per scanline:** Mode0 substrate limit is 8; requires sprite expansion.
- **Multiple background planes:** Mode0 expansion to 4 layers (Task 48) needed for honest 3-plane composition.
- **Variable sprite sizes:** Not all sizes may be supported in Mode0 v1.
- **Shadow/highlight:** Part of platform character but requires post-compositor color behavior.

## 4. Adapter Register Surface

- **Reg 0:** Mode Control 1 (H-blank interrupt enable)
- **Reg 1:** Mode Control 2 (Display enable, V-blank interrupt enable, DMA enable)
- **Reg 2:** Plane A Name Table base address
- **Reg 3:** Window Name Table base address
- **Reg 4:** Plane B Name Table base address
- **Reg 5:** Sprite Attribute Table base address
- **Reg 7:** Background/Border color
- **Reg 10:** H-blank interrupt interval
- **Reg 11:** Mode Control 3 (Scroll modes: per-scanline/per-column)
- **Reg 12:** Mode Control 4 (H40/H32 resolution)
- **Reg 13:** H-scroll table base address
- **Reg 15:** VRAM address auto-increment value
- **Reg 19–20:** DMA length
- **Reg 21–23:** DMA source address

### Control Port (`$C00004`)
- Command format: `%CD1 CD0 A13 A12 A11 A10 A9 A8 | A7 A6 A5 A4 A3 A2 A1 A0 | 0 0 0 0 0 0 0 0 | CD5 CD4 CD3 CD2 0 0 A15 A14`
- Codes (CD5–CD0): `000000` (VRAM Read), `000001` (VRAM Write), `001000` (CRAM Write), `000100` (VSRAM Write)

## 5. Mode0 Mapping

| Genesis Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Plane A / B | Tile fetch (L0, L1) | Map to scrollable tilemaps |
| Window | Tile fetch (L2) | Fixed or independent scroll window |
| Sprites | Sprite evaluation | Map 80 SAT entries to 8 available slots (v1 limit) |
| Scroll | Scroll tables | Use Mode0 linestate/scroll-table primitive |
| DMA | Memory-to-VRAM copy | Use host transport or Mode0 DMA if available |

## 6. Host Memory Layout

- **VRAM:** 64 KB
- **Plane A/B Name Tables:** Configurable bases
- **Window Name Table:** Configurable base
- **Sprite Attribute Table:** Configurable base
- **H-Scroll Table:** Configurable base (Reg 13)
- **Pattern data:** Stored in VRAM

## 7. Firmware Workflow

1. Host uploads pattern data to VRAM
2. Host builds name tables for Plane A, Plane B, and Window
3. Host uploads sprite patterns and SAT
4. Host sets scroll tables and scroll modes
5. Host enables display and interrupts
6. Host may use DMA for VRAM updates during vblank

## 8. Proof / Validation Plan

- **Sim:** Verify multi-plane composition is coherent and does not break substrate
- **Hardware:** Static test pattern with two planes and sprites; 30s capture freeze=0
- **Scope check:** Do not claim full Genesis fidelity if Mode0 substrate lacks required primitives

## 9. Known Gaps / Gotchas

- **Wide-console presentation:** Preserve the wide-console presentation feel and document how non-square native pixels are treated.
- **Active-area framing:** Overscan treatment should be explicit.
- **Palette banks and priority:** Palette banks and priority interactions matter; shadow/highlight behavior is part of the platform character.
- **Sprite overflow / list behavior:** Linked-list sprite behavior and overflow handling may need adapter-level attention.
- **Minimum readiness:** Through `R6`, with scroll-table primitive complete, per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [Sega Genesis VDP Documentation (Charles MacDonald)](https://cgfm2.emuunlim.com/genesisvdp.txt)
- [Genesis VDP Documentation (Sega Retro)](https://www.segaretro.org/Sega_Genesis_VDP)

### Localized References

The following reference materials are stored locally in `kb/Genesis/references/Nuked-MD-FPGA`:
- `ym7101.v`
- `LICENSE`
- `README.md`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

