# Tang20K-Scoped Mode0 Profile

**Task:** BronzeGate #9991  
**Author:** TopazCliff  
**Date:** 2026-05-15  
**Sources:** `MODE0_PLANNING.md`, `ASSESSMENT.md`, `VdpTop.scala`, `TileAttributeAssets.scala`, `RasterTriggerUnit.scala`, `BitplaneRowFetch.scala`, `BasicPatternSource.scala`, `SpriteRasterizer.scala`, `AffineAssets.scala`

---

## 1. Ceiling Statement

**New ceiling:** Amiga OCS/ECS + SNES (modes 0–3, no interlace) as the combined substrate pressure reference. Neo Geo is explicitly removed from the ceiling.

**What is no longer required after dropping Neo Geo:**
- 380 total sprites / 96 per scanline
- 4,096 active colors / 256 sub-palettes
- 65,536-entry master palette (RGB666+3)
- Dual 320-pixel line buffers
- Hardware shrinking / scaling primitive
- Sprite-centric composition model (everything-is-sprite)

---

## 2. Required Substrate Features

### Layers
- 4-layer compositor with priority + transparency (L0 > L1 > L2 > L3)
- L0 and L1: SDRAM-backed fetch (tile+attribute, bitmap+attribute, or planar)
- L2 and L3: on-chip tile fetch only (`BasicPatternSource`)
- Per-layer scroll (X/Y)
- Per-layer enable

### Sprites
- Descriptor-based sprite evaluator
- Sequential rasterizer with single pattern-RAM read port
- Per-sprite: X/Y position, pattern index, palette bank, priority, flip flags
- Sprite-sprite collision detection (Task 54 DONE)
- Sprite masking + tile-fetch budget counter (Task 55 DONE)

### Fetch Formats
- Tile + attribute (linear, packed, shuffled variants)
- Bitmap + attribute (1bpp Spectrum-style, 2bpp C64-style)
- Planar bitplane (1–8 planes via `BitplaneRowFetch`)
- Scroll-table primitive for per-column scroll

### Raster / Automation
- Single raster trigger unit (line compare + optional pixel compare)
- Copper-lite / HDMA automator (beam-synchronous register writes)
- DMA engine (bulk memory copy/fill)

### Palette / Color Math
- 128-entry × 24-bit RGB banked palette (8 banks × 16 entries)
- Runtime palette upload via auto-incrementing write interface
- Post-compositor color math (add/sub/half/intensity)
- Shadow/highlight stage

### Windowing / Masks
- Dual rectangular window units
- Per-layer window masking
- Window combination logic (AND/OR/XOR)

### Blitter / Transfer
- Blitter-class engine (rectangle copy/fill/line) — Task 49 DONE
- DMA engine for bulk VRAM/OAM/tilemap movement

### Affine / Mode 7
- Affine stepper for matrix-transformed background fetch
- 128×128 affine texture

### Events / Status
- Raster match sticky bit + IRQ
- Sprite overflow / collision sticky bits
- QSPI error sticky bit
- Transfer done/busy status

---

## 3. Hard Numeric Limits

| Limit | Value | Source | Proven / Estimated |
|---|---|---|---|
| **Visible sprites per line** | 8 | `VdpTop.scala` line 1394: `visiblePerLine = 8` | Proven (Task 57 Path 5A) |
| **Sprite descriptor count** | 8 | `VdpTop.scala` line 1380: `descCount = 8` | Proven (Task 57 Path 5A) |
| **Guaranteed live BG layers** | 2 (SDRAM-backed) + 2 (on-chip) = 4 total | `VdpTop.scala` lines 852-1180 | Proven |
| **SDRAM-backed layers** | 2 (L0, L1) | `TopTang20kHdmi.scala` lines 1459-1526 | Proven |
| **On-chip-only layers** | 2 (L2, L3) | `VdpTop.scala` lines 1170-1180: `BasicPatternSource` | Proven |
| **Palette entries** | 128 (8 banks × 16) | `TileAttributeAssets.scala` lines 51-54 | Proven |
| **Palette bits per entry** | 24 (RGB888) | `VdpTop.scala` line 1704: `Mem(Bits(24 bits), ...)` | Proven |
| **Raster triggers** | 1 (line compare + optional pixel) | `RasterTriggerUnit.scala` | Proven |
| **Sprite pattern RAM** | 16,384 × 4-bit entries = 64 unique 16×16 tiles | `VdpTop.scala` line 1503: `patternRamPtr` is 14 bits | Proven |
| **Planar plane count** | 1–8 | `BitplaneRowFetch.scala` lines 29-34 | Proven |
| **Planar default** | 5 planes | `BitplaneRowFetch.scala` line 29: `planeCount: Int = 5` | Proven |
| **Affine texture size** | 128 × 128 × 8-bit | `AffineAssets.scala` lines 22-23 | Proven |
| **On-chip tilemap (L2/L3)** | 40 × 30 tiles, 16×16 pixels, 3bpp index | `BasicPatternSource.scala` lines 33-37 | Proven |
| **Scroll table depth** | 32 entries | `VdpTop.scala` line 306: Copper depth widened 4→32 | Proven |

---

## 4. Optional / Deferred / Unsupported

### Optional (can be enabled if budget allows)
| Feature | Condition |
|---|---|
| 6th plane for EHB (64 colors) | Only after 5-plane integration is proven |
| Interlace output (256×448) | Only if SNES/Amiga adapter claims it |
| 4-layer SDRAM fetch (L2/L3) | Only if scheduler + arbiter budget permits |

### Deferred (out of Tang20K profile v1)
| Feature | Reason |
|---|---|
| HAM decoder (4096 colors) | Dedicated post-fetch logic block; too expensive for Tang20K |
| Full HDMA channel model | Copper-lite sufficient for current pressure |
| Offset-per-tile scroll | SNES Mode 2/4/6; requires scroll-table format extension |
| Interlace | Not required for v1 adapters per `kb/SNES/README.md` §3 |
| AGA-class features | 8 bitplanes / 256 colors out of scope |

### Explicitly Unsupported
| Feature | Reason |
|---|---|
| Neo Geo 380 sprites / 96 per line | DFF budget overflow proven at descCount=64 (Task 57) |
| Neo Geo 4,096 active colors | Would require ~12–16 additional BSRAM blocks; pushes into yellow/red zone |
| Hardware shrinking / scaling | No Amiga/SNES requirement |
| Dual line buffers | Neo Geo-specific architecture |
| Full-framebuffer rendering | Violates `MODE0_PLANNING.md` §2 Full Framebuffer Rule |
| 3D rasterization / shaders | Explicit non-goal per `MODE0_PLANNING.md` §1 |

---

## 5. Pressure Reduction from Old Ceiling

### What hardware pressure is removed

| Neo Geo Pressure | Old Ceiling Impact | New Profile Impact |
|---|---|---|
| 380 sprites / 96 per line | Would require massive evaluator + rasterizer expansion | **Gone.** descCount=8/visiblePerLine=8 is honest ceiling. No expansion needed. |
| 4,096 active colors | Would require palette RAM expansion to ~48–64 additional BSRAM blocks | **Gone.** 128-entry palette is sufficient for Amiga (32 colors) + SNES (256 colors). |
| 65,536 master palette | Would require wider palette addressing + storage | **Gone.** 24-bit RGB direct is sufficient. |
| Dual 320-pixel line buffers | Would require 2× 320×pixel-width line buffer BSRAMs | **Gone.** Not needed by Amiga or SNES. |
| Hardware shrinking | Would require scaling logic in rasterizer | **Gone.** Not needed by Amiga or SNES. |
| Sprite-centric composition | Would stress rasterizer differently than tilemap model | **Gone.** Normal tilemap+sprite model suffices. |

### What blocks are most likely to shrink

1. **Sprite evaluator / rasterizer:** No pressure to expand beyond descCount=8. Current sequential rasterizer with single pattern-RAM port is sufficient.
2. **Palette RAM:** 128 entries × 24-bit is fixed. No expansion needed.
3. **Compositor:** 4-layer code already exists. Only L2/L3 SDRAM fetch is a potential addition, not a requirement.
4. **Pattern RAM:** 16K × 4-bit (64 tiles) is fixed. No expansion to Neo Geo scale.

---

## 6. Expected Savings

### Rough LUT / logic impact estimate

**Important:** These are estimates based on code analysis, not post-P&R measurements. Marked accordingly.

| Area | Old Ceiling Pressure | New Profile Pressure | Estimated LUT Saving | Certainty |
|---|---|---|---|---|
| Sprite evaluator expansion | descCount=64+ target | descCount=8 fixed | **~800–1200 LUTs** | Estimate — based on Task 57 DFF overflow at descCount=64 |
| Sprite rasterizer parallelization | Parallel per-slot pattern RAM reads | Single shared pattern RAM | **~200–400 LUTs** | Estimate — arbitration + mux logic reduction |
| Palette RAM expansion | 4,096 entries × 15-bit+ | 128 entries × 24-bit (fixed) | **~0 LUTs** (BSRAM only) | Proven — no palette expansion needed |
| Line buffer substrate | Dual 320-pixel buffers | None | **~2–4 BSRAM blocks** | Estimate — no line buffers needed |
| Pattern RAM expansion | 256+ tiles for dense scenes | 64 tiles fixed | **~1–2 BSRAM blocks** | Estimate — current 16K×4-bit = 1 block |
| Shrinking logic | Per-pixel scaling in rasterizer | None | **~100–200 LUTs** | Estimate — no scaling needed |

**Total estimated LUT relief:** ~1,100–1,800 LUTs (roughly 5–9% of nominal 20K budget, or ~11–18% of current ~9,875 baseline).

**Total estimated BSRAM relief:** ~3–6 blocks (roughly 7–13% of 46-block budget).

### What stays the same (no savings)

| Feature | Why no change |
|---|---|
| 4-layer compositor | Already exists in code; only L2/L3 SDRAM fetch is potential addition |
| Planar fetch (1–8 planes) | Already implemented; 5-plane SMPTE bars HW proven (Task 3) |
| Beam automation (Copper-lite) | Already implemented (R5 DONE) |
| Color math / windowing | Already implemented (R6 DONE) |
| Blitter / DMA | Already implemented (Task 49 DONE) |
| Affine stepper | Already implemented (R8 DONE) |

---

## 7. Best Next Implementation Step

**Open a bounded "Planar Fetch Integration Hardening" lane first.**

**Why this over 4-layer SDRAM fetch:**
- Narrower scope than 4-layer expansion
- Lower risk (green-yellow zone vs yellow zone)
- Unlocks Amiga and Atari ST adapter work sooner
- Does not touch arbiter or scheduler contracts

**Lane specification:**

| Field | Value |
|---|---|
| **Scope** | Integrate `BitplaneRowFetch` (1–8 planes) into main `VdpTop` pipeline as selectable L0/L1 source; prove 5-plane fetch through scheduler + SDRAM mock |
| **Proof** | Sim: 5-plane fetch produces correct pixel sequence for known test pattern under concurrent sprite load. Bandwidth report: 5-plane row fetch + tile fetch + sprite fetch within per-line cycle budget. |
| **Resource gate** | PnR LUT delta ≤ +400–600 from current baseline (`MODE0_PLANNING.md` §2 baseline: ~9,875 LUT). If delta exceeds, fallback to 4-plane (ST-only) mode. |
| **Depends on** | Task 3 DONE, Task 57 DONE |
| **Owner** | BrightForge (RTL) / CyanPeak (audit) |
| **Coding authorized** | Requires BronzeGate PM ruling |

**Alternative if PM prefers compositor work first:** Open "4-Layer SDRAM Fetch Integration" lane with explicit scheduler slot re-analysis and per-line bandwidth proof. Higher risk but unlocks SNES honest adapter sooner.

---

## Exact Sources Cited

| Source | What it provided |
|---|---|
| `hw/spinal/spinalhdlvdp/VdpTop.scala` | descCount=8, visiblePerLine=8, 4-layer compositor (L0-L3), palette 128×24-bit, pattern RAM 16K×4-bit, spritePatternRams shared single Mem |
| `hw/spinal/spinalhdlvdp/TileAttributeAssets.scala` | Palette depth: 8 banks × 16 entries = 128; power-of-two verified (GT-022) |
| `hw/spinal/spinalhdlvdp/RasterTriggerUnit.scala` | Single trigger unit: line compare + optional pixel compare, edge-detected pulse, sticky pending/IRQ |
| `hw/spinal/spinalhdlvdp/BitplaneRowFetch.scala` | planeCount 1-8, default 5, addrWidth parameterizable |
| `hw/spinal/spinalhdlvdp/BasicPatternSource.scala` | L2/L3 on-chip source: 40×30 tilemap, 16×16 tiles, 3bpp pixel index |
| `hw/spinal/spinalhdlvdp/SpriteRasterizer.scala` | patternSelBits=6 (64 tiles), patAddrBits=14, sequential rasterizer |
| `hw/spinal/spinalhdlvdp/AffineAssets.scala` | Texture 128×128 × 8-bit |
| `PROJECT_PLAN/MODE0_PLANNING.md` §2 | Resource stop-lines: LUT 48%, FF 40%, BSRAM 37%, DSP 75% at current baseline |
| `PROJECT_PLAN/MODE0_PLANNING.md` §4 | Coverage Matrix: planar "Usable" not "Strong"; sprite system "Usable" |
| `PROJECT_PLAN/ASSESSMENT.md` §3 | Planar fetch: 2-plane limit in tile-decode mode, 5+ planes in standalone primitive |
