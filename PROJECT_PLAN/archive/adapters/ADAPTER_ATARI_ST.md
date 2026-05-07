# Atari ST Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-28  
**Status:** Spec drafted — pending audit  
**Platform:** Atari ST (STF / STFM / STE / Mega ST)  
**Tier:** 1 (lightweight)  
**Mode ID (proposed):** `0x8`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 320×200 (low), 640×200 (medium), 640×400 (high) |
| Color depth | 4bpp (16 colors), 2bpp (4 colors), 1bpp (2 colors) |
| Palette | 16 entries × 9-bit RGB (3 bits per channel) = 512 colors |
| Refresh | 50 Hz (PAL) / 60 Hz (NTSC-like) |
| Aspect | Non-square pixels; 320×200 is the canonical game resolution |

The **SHIFTER** IC generates video by reading bitplane data from RAM via the **MMU**, combining bits through a palette lookup table, and outputting analog RGB (or monochrome on pin 2).

### 1.2 Layer model

Atari ST has **exactly one background layer** and **no hardware sprites**. All moving objects are software-rendered via the CPU (or Blitter on STE/Mega ST). This makes the ST one of the simplest adapters to implement — there is no sprite system, no multi-layer composition, and no scroll hardware.

### 1.3 Tile / bitmap / planar organization

The ST uses **planar bitplanes**, not chunky pixels:

- **Low resolution:** 4 bitplanes (320×200×4bpp)
  - Each 16-pixel horizontal span requires 4 words (1 word per plane)
  - Words are **word-interleaved**: plane 0 word, plane 1 word, plane 2 word, plane 3 word, then next 16-pixel group
  - Screen memory: 32,000 bytes
  - Bytes per scanline: 160 (4 planes × 40 words = 160 bytes)

- **Medium resolution:** 2 bitplanes (640×200×2bpp)
  - 2 words per 16 pixels
  - 16,000 bytes total; 80 bytes/line

- **High resolution:** 1 bitplane (640×400×1bpp)
  - 1 word per 16 pixels
  - 32,000 bytes total; 80 bytes/line (400 lines)

**Critical memory layout detail:** The SHIFTER latches 4 words (low res) per load pulse, one from each plane. The first pixel of a 16-pixel group is composed of the **high bit** of each of the 4 words. This is standard bitplane shift-register output.

### 1.4 Sprite system

**None.** The ST has no hardware sprite support. Games use:
- CPU `movem.l` blitting for software sprites
- STE/Mega ST **Blitter** (block transfer) for accelerated software sprites
- Raster interrupts for color-cycling effects that simulate motion

For Mode0 adapter purposes, the sprite evaluator can be left entirely disabled (`LAYER_ENABLE` sprite bit = 0). The adapter does not need to map any sprite registers.

### 1.5 Palette / color model

The palette is stored in the SHIFTER as 16 registers, each 9 bits (RRRGGGBBB):

| Register | Address | Bits |
|---|---|---|
| `palette[0]` | `$FFFF8240` | `[8:0]` |
| `palette[1]` | `$FFFF8242` | `[8:0]` |
| ... | ... | ... |
| `palette[15]` | `$FFFF825E` | `[8:0]` |

Palette writes take effect **immediately** (no safe-boundary delay). This is a timing-visible quirk: rapid palette changes during active display produce "raster bars."

### 1.6 Scrolling model

**No hardware scroll registers.** The ST cannot hardware-scroll the display. Scrolling is achieved by:
- Redrawing the screen (CPU)
- Changing the screen base address (`$FFFF8201/8203`) to point to a different part of a larger logical framebuffer
- Using the Blitter to copy blocks

For a Mode0 adapter, scrolling is **host-managed** via screen base address updates. The adapter maps the ST screen base pointer to `BITMAP_BASE_LO/HI`.

### 1.7 Raster / IRQ / beam-driven behavior

The **MFP 68901** provides timer-based interrupts, including **Timer B** which is commonly used for raster effects:

| Source | Typical use |
|---|---|
| Timer B (event count mode) | Fires at a programmed horizontal position; used for palette splits, color bars, sync scrolling |
| VBL interrupt | Vertical blank; standard frame tick |
| HBL interrupt | Horizontal blank; rarely used due to 68000 timing constraints |

The **GLUE** chip generates `DE`, `VSYNC`, `HSYNC`, and blanking signals. It also handles the 50/60 Hz switch (`$FFFF820A` bit 1).

**Raster split mechanism:** Programs set Timer B to count events (DE pulses) and trigger an interrupt at a specific line. In the ISR, they rewrite palette registers to create color splits.

### 1.8 DMA / blitter / display-list behavior

- **No display-list processor.** The SHIFTER is a dumb framebuffer scanner.
- **Blitter** (STE/Mega ST only): Hardware block transfer for rectangular copies, fills, and line drawing. The Blitter has its own set of registers (`$FFFF8A00+`).
- **No DMA-based video fetch.** The MMU reads words from RAM and passes them to the SHIFTER on demand.

### 1.9 Windowing / masking / priority rules

None. Single layer, no windowing, no masking, no priority.

### 1.10 Memory layout and addressing model

| Region | Size | Purpose |
|---|---|---|
| Screen RAM | 32 KB (low) / 16 KB (med) / 32 KB (high) | Framebuffer bitplanes |
| Palette | 32 bytes | 16 × 16-bit palette registers |

Screen base address (`$FFFF8201/8203`) is a **24-bit address with the low byte hardwired to `$00`** (256-byte alignment). The MMU reads from this base and auto-increments.

### 1.11 Timing-sensitive or identity-defining quirks

1. **Immediate palette effect:** Palette writes show up on the current scanline, not at frame start. This is exploited for raster bars.
2. **No hardware scroll:** All scroll is software. The "sync scroll" trick uses Timer B + screen base manipulation to create coarse horizontal scroll.
3. **Border removal:** By manipulating GLUE sync registers (`$FFFF820A`), demos can remove the border and display in the normally blank area. This is **out of scope** for the adapter.
4. **STE enhancements:** The STE adds a hard scroll register (`$FFFF8265` — fine horizontal scroll, 0..15 pixels) and a vertical fine scroll register. These are STE-specific and may be a v2 adapter feature.

---

## 2. Pipeline Decomposition

| Stage | What the ST does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | MMU reads bitplane words from RAM; SHIFTER latches 4 words per 16-pixel group | `PlanarLineFetch` (R7.1) | Direct — bitplane fetch with word-interleaved layout |
| **Decode** | SHIFTER extracts 1 bit from each plane word to form a 4-bit pixel index | `BitplaneReconstruct` + palette lookup | Direct — Mode0 already does this |
| **Staging / buffering** | SHIFTER internal 64-bit shift register | Line buffer in `PlanarLineFetch` | Direct |
| **Sprite evaluation** | None | N/A | N/A |
| **Composition** | Single layer only | `FourLayerCompositor` with L0 only | Direct — trivial |
| **Palette / color** | 16-entry × 9-bit palette RAM | CW-1 runtime palette RAM (24-bit entries) | Direct — Mode0 palette is superset |
| **Beam/raster control** | Timer B raster IRQ + VBL | `RasterTriggerUnit` (R1) | Direct — trigger on line Y |
| **Host/control plane** | 68000 writes to SHIFTER/MFP/GLUE regs | Adapter shadow + bus emitter | Direct — thin translation layer |

---

## 3. Mode0 Mapping

### 3.1 Core display functions

| ST function | Mode0 primitive | Adapter responsibility | MCU action |
|---|---|---|---|
| 320×200 16-color display | `PlanarLineFetch` (4 planes) + `BITMAP_CTRL` | Set `BITMAP_CTRL` to planar-4 mode; set `BITMAP_BASE_*` to screen base | Upload bitplane data to SDRAM; write base address |
| 640×200 4-color display | `PlanarLineFetch` (2 planes) | Same, but planar-2 mode | Same |
| 640×400 mono display | `PlanarLineFetch` (1 plane) | Same, but planar-1 mode | Same |
| Palette (16 entries) | CW-1 palette RAM (`0x0600/0x0601`) | Map `$FFFF8240..$825E` writes to palette entries 0..15 | Write palette via adapter-local regs |
| Screen base address | `BITMAP_BASE_LO/HI` | Map `$FFFF8201/8203` to bitmap base | Write base address |
| Resolution switch | `BITMAP_CTRL` mode bits | Map `$FFFF8260` to `BITMAP_CTRL` mode | Write resolution reg |

### 3.2 Raster effects

| ST function | Mode0 primitive | Adapter responsibility | MCU action |
|---|---|---|---|
| Timer B raster split | `RasterTriggerUnit` | Map Timer B line value to `rasterTriggerLine` | Set trigger line; ISR on host handles palette swap |
| VBL interrupt | `RasterTriggerUnit` + `STATUS_STICKY` | Map VBL to `RASTER_MATCH` at line 0 or max | Enable raster IRQ |
| Immediate palette effect | CW-1 palette RAM (no safe-boundary) | Adapter writes palette directly to `0x0600/0x0601` without safe-boundary delay | Write palette reg — effect is immediate |

**Note on immediate palette:** Mode0's palette RAM is safe-boundary-committed by default. For authentic ST raster-bar effects, the adapter may need to bypass safe-boundary commit for palette writes. This is a **substrate gap**: Mode0 does not currently support "immediate" palette writes. The adapter can approximate raster bars by using the Copper to rewrite palette at specific lines, which is actually more accurate than immediate writes because it gives deterministic timing.

### 3.3 STE enhancements (v2 adapter)

| ST function | Mode0 primitive | Adapter responsibility | Gap |
|---|---|---|---|
| Fine horizontal scroll (`$FFFF8265`) | `layer0ScrollX` | Map fine scroll reg to scroll X | Minor — scroll already exists |
| Fine vertical scroll (`$FFFF8265` bit 8) | `layer0ScrollY` | Map to scroll Y | Minor |
| Blitter | `BlitterEngine` (Task 49) | Map Blitter regs to Mode0 blitter | Medium — Blitter registers differ significantly |
| DMA sound | None | N/A | Out of scope (audio) |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `ST_RES` | 8 bits | Resolution: 0=low (320×200×4bpp), 1=medium (640×200×2bpp), 2=high (640×400×1bpp) |
| `0x01` | `ST_BASE_HI` | 8 bits | Screen base address [23:16] |
| `0x02` | `ST_BASE_MID` | 8 bits | Screen base address [15:8] (low byte hardwired to 0) |
| `0x03` | `ST_PAL_IDX` | 8 bits | Palette index to write (0..15) |
| `0x04` | `ST_PAL_DATA` | 16 bits | Palette data [8:0] = RRRGGGBBB |
| `0x05` | `ST_RASTER_LINE` | 8 bits | Timer B raster trigger line (0=off) |
| `0x06` | `ST_RASTER_ENABLE` | 8 bits | Bit 0 = raster IRQ enable |
| `0x07` | `ST_CTRL` | 8 bits | Bit 0 = display enable; bit 1 = STE fine-scroll enable (v2) |
| `0x08..0x0F` | — | — | Reserved |

### 4.2 Initialization flow

1. Host selects mode `0x8` via `MODE_SELECT`
2. Host uploads bitplane data to SDRAM at chosen base address
3. Host writes `ST_BASE_HI/MID` to point to SDRAM base
4. Host writes `ST_RES` to select resolution
5. Host writes palette entries via `ST_PAL_IDX` + `ST_PAL_DATA`
6. Host writes `ST_CTRL[0]=1` to enable display
7. (Optional) Host sets `ST_RASTER_LINE` + `ST_RASTER_ENABLE` for raster splits

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Bitplane framebuffer | 32 KB (low) / 16 KB (med) / 32 KB (high) | SDRAM | Word-interleaved planar |
| Palette | 32 bytes | Adapter shadow / Mode0 palette RAM | 16 × 9-bit entries |

The host is responsible for converting chunky or other formats to ST planar layout before upload.

### 4.4 Runtime control/update model

- **Palette updates:** Write `ST_PAL_IDX` then `ST_PAL_DATA`. Adapter emits 3-word palette bus sequence (same as ZX border emitter).
- **Screen base update:** Write `ST_BASE_HI/MID`. Adapter emits `BITMAP_BASE_LO/HI` bus writes.
- **Raster split:** Host sets `ST_RASTER_LINE`. When `RasterTriggerUnit` fires, host ISR rewrites palette or scroll.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| Raster match | `RasterTriggerUnit` | `STATUS_STICKY` bit 0 (`RASTER_MATCH`) |
| VBL | Vsync edge | Can be derived from `RasterTriggerUnit` at line 0 or last line |

No adapter-specific readback registers for v1. Host shadows palette and base address in MCU RAM.

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| `BITMAP_CTRL` mode | Adapter-local `ST_RES` → bus emit | Adapter translates |
| `BITMAP_BASE_*` | Adapter-local `ST_BASE_*` → bus emit | Adapter translates |
| Palette entries 0..15 | Adapter-local `ST_PAL_*` → bus emit | Adapter translates |
| `LAYER_ENABLE` | Adapter-local `ST_CTRL[0]` → bus emit | Adapter translates |
| `RasterTriggerUnit.line` | Adapter-local `ST_RASTER_LINE` | Direct output (not bus) |
| Scroll X/Y | Global Mode0 | Host writes directly if STE fine scroll used |
| Window / color-math | Not used for ST | N/A |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- Planar fetch (R7.1) — direct match for ST bitplanes
- Palette RAM (CW-1) — superset of ST 9-bit palette
- Raster triggers (R1) — direct match for Timer B splits
- Single-layer composition — trivial

### 5.2 What is approximate

- **Immediate palette effect:** Mode0 palette is safe-boundary-committed. ST raster bars that rely on mid-line palette changes cannot be reproduced exactly. Workaround: Use Copper to rewrite palette at specific lines, which gives deterministic line-aligned splits rather than mid-line color changes.
- **No hardware scroll:** Mode0 has `layer0ScrollX/Y` registers. The ST adapter can use these for coarse scroll, but this is not authentic ST behavior (the ST has no scroll registers). The adapter should document this as a "convenience enhancement," not a fidelity claim.

### 5.3 What is missing entirely

- **STE Blitter:** The STE Blitter is a distinct engine from Mode0's `BlitterEngine` (Task 49). Mapping Blitter operations would require a translation layer. Out of scope for v1.
- **STE fine scroll:** Hardware fine scroll registers (`$FFFF8265`) are STE-only. Can be mapped to `layer0ScrollX/Y` in v2.
- **Border removal / overscan:** Demoscene border tricks manipulate GLUE sync timing. Out of scope.
- **Mono output:** Mode0 outputs RGB only. Monochrome composite output is not supported.

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| Planar fetch word-interleave | Shared | Already in `PlanarLineFetch` |
| 9-bit palette | Adapter-local | Mode0 uses 24-bit palette; adapter truncates/shifts ST 9-bit values |
| Raster IRQ | Shared | `RasterTriggerUnit` already exists |
| Blitter | Adapter-local (v2) | STE Blitter semantics differ from Mode0 blitter |
| Fine scroll | Shared (v2) | Reuse `layer0ScrollX/Y` |

### 5.5 Realism for default bitstream

**Fully realistic.** Atari ST is the cheapest adapter in the target set. No sprites, no multi-layer, no complex fetch. Estimated cost: ~100 LUT, ~50 FF. Strongly recommended as the **first Tier 1 adapter** after MODE_SELECT infrastructure is proven.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — Basic ST display:** Planar fetch (4bpp/2bpp/1bpp), palette, screen base, display enable. No scroll, no raster, no Blitter.
2. **v1.1 — Raster splits:** Add `ST_RASTER_LINE` + `ST_RASTER_ENABLE` mapping to `RasterTriggerUnit`.
3. **v2 — STE enhancements:** Fine scroll (`layer0ScrollX/Y`), optional Blitter mapping.

### 6.2 Prerequisite substrate tasks

- **R7.1 Planar Fetch** — ✅ DONE
- **R1 Raster Trigger** — ✅ DONE
- **CW-1 Palette RAM** — ✅ DONE
- **Task 44 Bitmap+Attribute Fetch** — ✅ DONE (used for base address control)

**No new substrate work required for v1.**

### 6.3 Proof plan

**Simulation:**
- `AtariStAdapterSim`: Test resolution switch, palette write, base address change, raster trigger line
- `VdpTopSim` regression: planar fetch scene with ST-style bitplane data

**Hardware proof:**
- Scenario: static 320×200 16-color image (Atari ST Neochrome or Degas format)
- Upload bitplane data to SDRAM via QSPI
- Set base address, resolution, palette
- 30s capture, `analyze.py` reports `freeze=0`
- Raster split proof: mid-screen palette change via `RasterTriggerUnit`

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~100 | Well under 500 |
| FF | ~50 | Well under 500 |
| BSRAM | 0 | None |
| Delta vs current baseline | Negligible | No concern |

The Atari ST adapter is the **lowest-risk adapter** in the entire platform set.

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** 320×200 16-color planar display + palette + base address. Proves the adapter pattern on the simplest possible platform.
- **v1.1:** Add raster splits.
- **v2:** Add STE fine scroll + optional Blitter.
- **Never:** Border removal, sync scroll, audio DMA.
