# Amiga OCS/ECS VDP (Denise/Agnus)

## 1. Video Model Summary

- **Native logical resolutions:**
  - Lores: 320×200 (NTSC) / 320×256 (PAL)
  - Hires: 640×200 (NTSC) / 640×256 (PAL)
  - Interlace: Doubles vertical resolution
- **Display structure:** Planar bitplane display with display-window placement
- **Frame rate:** 50 Hz (PAL) / 60 Hz (NTSC)

## 2. Supported Features

- 1 to 6 bitplanes (up to 32 colors standard; 64 with EHB)
- HAM (Hold-And-Modify): 6 bitplanes, 4096 colors
- EHB (Extra-Half-Brite): 32 standard + 32 half-brightness entries
- 8 hardware sprites, 16px wide, variable height
- Copper co-processor and Blitter DMA
- Display-window placement and border timing

## 3. Unsupported / Deferred Features

- **HAM/EHB Decoder:** Requires post-fetch logic block in Mode0.
- **Cycle-Accurate DMA:** Agnus cadencing is not emulated.
- **Full Copper:** Mode0 Copper is optimized for register writes, not exact sub-pixel bus timing.

## 4. Adapter Register Surface

- **BPLxPTH/BPLxPTL:** Bitplane DMA pointers
- **BPLCONx:** Bitplane control registers
- **DIWxSTRT/DIWxSTOP:** Display window start/stop
- **DDFxSTRT/DDFxSTOP:** Data fetch start/stop
- **COLORxx:** Palette registers (32 entries)
- **SPRXPOS/SPRxCTL/SPRxDATA:** Sprite position, control, and data
- **COPxLC:** Copper list location
- **BLTxPT/BLTxMOD/BLTCONx:** Blitter pointers, modulo, and control

## 5. Mode0 Mapping

| Amiga Function | Mode0 Primitive | Adapter Responsibility |
|---|---|---|
| Bitplanes | Planar fetch | Map BPLxPTH/BPLxPTL to `vdp_mode0_write_linestate` |
| Sprites | Sprite evaluation | Map pairs to sprite slots (64 available) |
| Copper | Copper | Translate instruction list to Copper RAM |
| Blitter | Blitter | Basic copy/fill/line operations |
| Window | Windowing | Clamp output to DIW boundaries (2 windows available) |

## 6. Host Memory Layout

- **Chip RAM:** Up to 2 MB (OCS/ECS)
- **Bitplane data:** Interleaved or linear depending on Agnus DMA setup
- **Sprite data:** Attached to sprite DMA pointers
- **Copper list:** Instruction list in Chip RAM (MOVE, WAIT, SKIP)

## 7. Firmware Workflow

1. Host allocates Chip RAM for bitplanes, sprites, and Copper list
2. Host sets bitplane pointers (BPLxPTH/BPLxPTL)
3. Host loads palette into COLORxx registers
4. Host configures display window (DIWxSTRT/DIWxSTOP)
5. Host enables DMA and display via DMACON
6. Host may use Copper for raster effects and Blitter for graphics operations

## 8. Proof / Validation Plan

- **Sim:** Verify planar fetch and display window behave correctly
- **Hardware:** Static test pattern with display window and sprites; 30s capture freeze=0
- **Scope check:** Do not claim HAM or full Copper support unless primitives are proven

## 9. Known Gaps / Gotchas

- **Display-window feel:** Preserve the display-window feel instead of pretending to be a generic framebuffer. Non-square presentation and mode-specific width choices should be documented.
- **Border timing:** Display-window placement and border timing are part of the machine's look.
- **Bitplane composition look:** The planar bitplane composition appearance is central to Amiga identity.
- **Sprite priority:** Stronger sprite priority semantics than many other platforms.
- **Minimum readiness:** Through `R7`, with `R5` especially important, per `MODE0_PLANNING.md` §3 (Strategic Roadmap)

## 10. Reference Links

- [Amiga Hardware Reference Manual](https://archive.org/details/Amiga_Hardware_Reference_Manual_1991_Addison_Wesley)
- [Amiga Graphics Guide](http://amigadev.elowar.com/read/ADCD_2.1/Hardware_Manual_guide/node01A8.html)

### Localized References

The following reference materials are stored locally in `kb/Amiga_OCS_ECS/references/amiga_replacement_project`:
- `denise.v`
- `README.md`
- `ATTRIBUTION.md`

See `ATTRIBUTION.md` in that directory for source URL, author, and license.

