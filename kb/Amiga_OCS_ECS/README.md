# Amiga OCS/ECS VDP (Denise/Agnus)

## Primary References
- [Amiga Hardware Reference Manual](https://archive.org/details/Amiga_Hardware_Reference_Manual_1991_Addison_Wesley) - The "Red Book", definitive reference for all chipset registers.
- [Amiga Graphics Guide](http://amigadev.elowar.com/read/ADCD_2.1/Hardware_Manual_guide/node01A8.html)

## Technical Summary
- **Processors:** Agnus (DMA/Timing), Denise (Video/Bitplanes), Paula (Interrupts).
- **Resolutions:**
    - **Lores:** 320x200 (NTSC) / 320x256 (PAL).
    - **Hires:** 640x200 (NTSC) / 640x256 (PAL).
    - **Interlace:** Doubles vertical resolution.
- **Bitplanes:** 1 to 6 bitplanes. 
    - **HAM (Hold-And-Modify):** 6 bitplanes, allows 4096 colors on screen.
    - **EHB (Extra-Half-Brite):** 6 bitplanes, entries 32-63 are half-brightness of 0-31.
- **Sprites:** 8 sprites, 16 pixels wide, variable height. Linked in pairs for 15 colors.
- **Copper:** Co-processor capable of beam-synchronous register writes (WAIT/MOVE/SKIP).
- **Blitter:** DMA engine for fast memory moves, area fills, and line drawing with bitwise logic (minterms).

## Programming Sequences & Details
### Bitplane DMA
Controlled by `BPLxPTH/BPLxPTL` (pointers) and `BPLCONx` (control). Bitplanes are fetched as interleaved or linear depending on Agnus DMA setup.

### Copper Instructions
- **MOVE:** `0 <register_offset> <value>`
- **WAIT:** `1 <y_pos> <x_pos> | 1 <mask_y> <mask_x>`
- **SKIP:** Similar to WAIT but skips next instruction if beam reached.

### HAM Mode Logic
Uses 6 bits per pixel:
- `00`: Use Palette Entry (from bits 5:2 of pixel).
- `01`: Modify Blue (replace B with bits 5:2).
- `10`: Modify Red (replace R with bits 5:2).
- `11`: Modify Green (replace G with bits 5:2).

## Notable Gaps
- **Cycle-Accurate DMA:** Agnus has a strict DMA slot cadence. Mode0 uses a more flexible but less timing-rigid arbiter.
- **HAM/EHB Decoder:** Requires a dedicated post-fetch/pre-compositor logic block in Mode0.
- **Blitter Minterms:** Mode0 Blitter (Task 49) handles basic copy/fill; full 256-op Amiga minterm support is a future extension.
