# PC Engine / TurboGrafx-16 Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Spec drafted — pending audit  
**Platform:** PC Engine / TurboGrafx-16 (NEC / Hudson Soft)  
**Tier:** 2 (medium)  
**Mode ID (proposed):** `0x9`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 256×239 (overscan) / 256×224 (safe) |
| Color depth | 4bpp tiles + 16-color sprite palettes |
| Master palette | 512 colors (9-bit RGB: 3 bits per channel) |
| Simultaneous colors | ~482 (16 palettes × 16 colors, with some overlap) |
| Refresh | ~59.8 Hz (NTSC) / ~50.0 Hz (PAL, rare) |
| Aspect | 8:7 pixel aspect (same as NES) |

The **HuC6270** Video Display Controller (VDC) generates video under control of the **HuC6260** Video Color Encoder (VCE). The VDC handles tiles, sprites, and VRAM; the VCE handles the master palette and color output.

### 1.2 Layer model

**Two layers:**
1. **Background (BG)** — single scrollable tilemap
2. **Sprites (OBJ)** — up to 64 sprites

No multi-plane background. No windowing hardware. No priority between BG tiles (BG is always the bottom layer).

### 1.3 Tile / bitmap / planar organization

**Background tiles:** 8×8 pixels, 4bpp planar (2 bits per pixel).
- Pattern table in VRAM: each tile = 32 bytes (4 bitplanes × 8 bytes)
- Tilemap: configurable size (32×32, 64×32, 128×32, or 32×64)
- Each tilemap entry = 2 bytes:
  - Bits 11:0 = pattern index
  - Bit 12 = horizontal flip
  - Bit 13 = vertical flip
  - Bit 14 = palette select (0 or 1)
  - Bit 15 = priority over sprites

**VRAM:** 64 KB (32K × 16-bit words), word-addressable.

### 1.4 Sprite system

| Parameter | Value |
|---|---|
| Count | 64 sprites |
| Per scanline | 16 sprites maximum |
| Size | 16×16 (default), 16×32, 32×16, or 32×32 (per-sprite configurable) |
| Colors | 16 colors per sprite (from one of 16 palettes) |
| Priority | Lower SAT index = higher priority (drawn on top) |

**Sprite Attribute Table (SAT):** 8 bytes per sprite (4 words).

| Word | Content |
|---|---|
| 0 | Y coordinate (visible area starts at line 64) |
| 1 | X coordinate (visible area starts at pixel 32) |
| 2 | Pattern index (VRAM address >> 6) |
| 3 | Attributes: palette index (4 bits), priority, flip H/V, width/height size bits |

**Size encoding in attributes word:**
- Bits 9:8 = width size (00=16, 01=32, 10=32, 11=32)
- Bits 11:10 = height size (same encoding)

### 1.5 Palette / color model

**VCE master palette:** 512 entries × 9-bit RGB (3 bits per channel).
- Addressed as 16 palettes × 16 colors each
- Palette 0 is typically used for background
- Palettes 1–15 for sprites and BG alternate

**BG tile palette select:** Bit 14 of tilemap entry selects palette 0 or 1 for that tile.

### 1.6 Scrolling model

**Single-plane hardware scroll:**
- `BXR` (Background X Scroll): 9-bit pixel scroll
- `BYR` (Background Y Scroll): 9-bit pixel scroll
- `MWR` (Memory Width Register): controls virtual map size (32×32, 64×32, 128×32, 32×64)

No per-tile or per-column scroll. No parallax.

### 1.7 Raster / IRQ / beam-driven behavior

**Raster interrupt (RCR):**
- `RCR` register sets the scanline where an interrupt fires (value = line + 64)
- Used for palette swaps, scroll changes, and sprite multiplexing
- Very commonly used in PC Engine games

**VBlank interrupt:** Standard frame tick.

### 1.8 DMA / blitter / display-list behavior

**VRAM-to-SATB DMA:**
- `DCR` register controls DMA
- `SATB` register sets the source address in VRAM for the sprite attribute table
- DMA auto-copies 256 bytes (64 sprites × 4 words) from VRAM to internal SATB every frame

**No general blitter.** No display-list processor.

### 1.9 Windowing / masking / priority rules

**Sprite priority:**
- Per-sprite priority bit (attribute word 3)
- 0 = sprite behind BG non-transparent pixels
- 1 = sprite in front of BG
- Sprite-to-sprite: lower SAT index wins

**BG tile priority:** Bit 15 of tilemap entry = tile in front of sprites.

No hardware windowing. No color math.

### 1.10 Memory layout and addressing model

| Region | Size | Purpose |
|---|---|---|
| VRAM | 64 KB | Tiles, tilemap, sprite patterns, SATB source |
| VCE palette | 512 × 9-bit | Master palette (separate chip) |

### 1.11 Timing-sensitive or identity-defining quirks

1. **Y-coordinate offset:** Visible Y starts at 64. Y=0 places sprite off-screen above.
2. **X-coordinate offset:** Visible X starts at 32. X=0 places sprite off-screen left.
3. **16 sprites/line:** Higher than NES (8) but lower than Genesis (20).
4. **Variable sprite sizes:** Per-sprite size config is unusual for the era; most systems use global size.
5. **Raster IRQ ubiquity:** PC Engine games rely heavily on RCR for effects. Missing raster IRQ support would break many games.

---

## 2. Pipeline Decomposition

| Stage | What PC Engine does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | VDC reads tilemap → pattern table from VRAM | `SdramTileAttributeFetch` + `SdramTileFetch` | Direct — tile+attr fetch |
| **Decode** | 4bpp planar → 16-color pixel | Tile decoder with 4bpp mode | Direct — Mode0 supports 4bpp |
| **Staging** | Internal shift registers | Tile pipeline buffers | Direct |
| **Sprite evaluation** | 64 sprites, 16/line, variable size | `SpriteEvaluator` (R2) | Approximate — Mode0 has 64 desc/32 per line. **Gap: CLOSED for desc count (64≥64); per-line limit (32≥16) closed.** |
| **Composition** | BG + sprites → priority mux → palette index | `FourLayerCompositor` | Direct — 1 BG + sprite layer |
| **Palette** | 512-entry 9-bit master palette | CW-1 palette RAM (24-bit entries) | Direct — Mode0 palette is superset |
| **Beam/raster** | RCR raster IRQ at any line | `RasterTriggerUnit` (R1) | Direct |
| **Host/control** | CPU writes VDC/VCE registers | Adapter shadow + bus emitter | Direct — thin translation layer |

---

## 3. Mode0 Mapping

### 3.1 Background layer

| PC Engine function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 256×239 tile background | `SdramTileAttributeFetch` | Set 4bpp tile mode; configure tilemap base | Direct |
| 8×8 4bpp tiles | `SdramTileFetch` | Pattern table in VRAM | Direct |
| Hardware scroll X/Y | `layer0ScrollX/Y` | Map `BXR`/`BYR` to scroll regs | Direct |
| Variable map size | `VDP_TILE_MODE` / scroll wrap | Map `MWR` to virtual map dimensions | Minor — Mode0 tilemap is fixed size; adapter may need to emulate wrap |
| Tile flip H/V | Tile descriptor | Map tilemap bits 12/13 to flip | Direct |
| Tile priority | `PixelMetadata` priority bit | Map tilemap bit 15 | Direct |
| Palette select (0/1) | `paletteBank` per tile | Map tilemap bit 14 to palette bank | Minor — Mode0 tile attr may not support per-tile palette bank |

### 3.2 Sprite layer

| PC Engine function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 64 sprites | `SpriteEvaluator` (64 desc) | Map SAT to descriptors | **Gap: Mode0 has 64 desc; PC Engine needs 64** |
| 16 sprites/scanline | `SpriteEvaluator` (32/line) | Mode0 has 32/line — exceeds PC Engine | **Direct match** |
| Variable sizes (16×16 to 32×32) | `SpriteEvaluator` descriptor | Map size bits to descriptor dimensions | Minor — Mode0 supports per-descriptor size |
| 16 colors per sprite | `SpriteEvaluator` + paletteBank | Set sprite paletteBank per descriptor | None |
| Sprite priority | `PixelMetadata` priority bit | Map per-sprite priority bit | Direct |
| SATB DMA | Host CPU updates shadow | Adapter shadow mirrors SAT; bus emits descriptor writes | No DMA in Mode0; host writes descriptors individually |
| Y/X offset (64/32) | Descriptor position | Adapter subtracts offsets on emit | Minor — translation layer only |

**Critical gap:** Both 32→64 descriptor expansion and 8→16 sprites/line are required for honest PC Engine support. Without them, the adapter supports half the sprite budget.

### 3.3 Palette

| PC Engine function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 512-entry 9-bit master palette | CW-1 palette RAM | Load 512-entry 9-bit palette into Mode0 24-bit entries | Direct — Mode0 palette is 512+ entries |
| 16 palettes × 16 colors | Palette bank organization | Map VCE palette layout to Mode0 palette banks | Minor — organization difference |
| BG palette select (0/1) | `paletteBank` per tile | Map tilemap bit 14 | Minor — Mode0 may not support per-tile palette bank |

### 3.4 Raster / IRQ

| PC Engine function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| RCR raster IRQ | `RasterTriggerUnit` | Map `RCR` to `rasterTriggerLine` | Direct |
| VBlank IRQ | `RasterTriggerUnit` at last line | Direct map | Direct |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `PCE_VDC_CTRL` | 16 bits | VDC Control Register (interrupt enables, display enable) |
| `0x01` | `PCE_BXR` | 16 bits | Background X scroll |
| `0x02` | `PCE_BYR` | 16 bits | Background Y scroll |
| `0x03` | `PCE_MWR` | 16 bits | Memory width / virtual map size |
| `0x04` | `PCE_RCR` | 16 bits | Raster counter (line + 64 for IRQ) |
| `0x05` | `PCE_SATB` | 16 bits | Sprite Attribute Table base address in VRAM |
| `0x06` | `PCE_VRAM_ADDR` | 16 bits | VRAM read/write address |
| `0x07` | `PCE_VRAM_DATA` | 16 bits | VRAM read/write data (auto-increments) |
| `0x08` | `PCE_VCE_ADDR` | 16 bits | VCE palette address (0–511) |
| `0x09` | `PCE_VCE_DATA` | 16 bits | VCE palette data (9-bit RGB) |
| `0x0A` | `PCE_DCR` | 16 bits | DMA control (VRAM-to-SATB) |
| `0x0B..0x0F` | — | — | Reserved |

### 4.2 Initialization flow

1. Host selects mode `0x9` via `MODE_SELECT`
2. Host uploads pattern table, tilemap, and sprite patterns to SDRAM
3. Host writes 512-entry VCE palette via `PCE_VCE_ADDR` + `PCE_VCE_DATA`
4. Host writes sprite attributes to adapter shadow (or VRAM for SATB DMA)
5. Host sets scroll via `PCE_BXR` / `PCE_BYR`
6. Host writes `PCE_VDC_CTRL` to enable display and interrupts

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Pattern table | Up to 64 KB | SDRAM | 4bpp planar tiles (32 bytes each) |
| Tilemap | Up to 8 KB | SDRAM | 16-bit tile entries |
| Sprite patterns | Up to 64 KB | SDRAM | 4bpp planar tiles |
| Sprite attributes | 256 bytes | Adapter shadow / SDRAM | 64 × 4-word descriptors |
| VCE palette | 1 KB | Adapter shadow / Mode0 palette | 512 × 9-bit entries |

### 4.4 Runtime control/update model

- **VRAM access:** Host writes `PCE_VRAM_ADDR` then `PCE_VRAM_DATA`. Adapter translates to SDRAM read/write.
- **Palette updates:** Write `PCE_VCE_ADDR` then `PCE_VCE_DATA`. Adapter emits palette bus writes.
- **Scroll updates:** Direct shadow update; adapter emits `layer0ScrollX/Y` bus writes.
- **SATB DMA:** Host writes `PCE_SATB` with VRAM base; adapter initiates 256-byte copy from VRAM to descriptor shadow.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank | `RasterTriggerUnit` at last line | `STATUS_STICKY` bit 0 (`RASTER_MATCH`) |
| Raster match (RCR) | `RasterTriggerUnit` at programmed line | `STATUS_STICKY` bit 0 |
| Sprite overflow | `SpriteEvaluator` | `STATUS_STICKY` bit 1 (`SPRITE_OVERFLOW`) |

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| `VDP_TILE_MODE` (4bpp) | Adapter-local `PCE_VDC_CTRL` | Adapter translates |
| `layer0ScrollX/Y` | Adapter-local `PCE_BXR/BYR` | Adapter translates |
| Palette entries | Adapter-local `PCE_VCE_*` | Adapter translates |
| Sprite descriptors | Adapter-local SAT shadow | Adapter emits bus writes |
| Raster trigger | Adapter-local `PCE_RCR` | Direct output to `RasterTriggerUnit` |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- Tile+attribute fetch (R4.1a/b) — direct match for PC Engine background
- 4bpp planar decode — exact match for PC Engine tiles
- Hardware scroll X/Y — direct match
- Raster IRQ (R1) — direct match for RCR
- Palette RAM (CW-1) — superset of 9-bit 512-entry palette

### 5.2 What is approximate

- **Sprite count:** Mode0 has 64 descriptors; PC Engine needs 64. MVP with 32 sprites is viable but not honest.
- **Sprites per line:** Mode0 has 32/line; PC Engine needs 16/line. Mode0 exceeds PC Engine requirements. The adapter must enforce the 16/line limit if authentic behavior is required.
- **Per-tile palette bank:** Mode0 tile attributes may not support per-tile palette bank selection. The adapter may need to restrict BG to a single palette or use the tile attribute byte creatively.
- **SATB DMA:** Mode0 has no DMA engine for descriptors. Host must write descriptors individually.

### 5.3 What is missing entirely

- **64 sprite descriptors + 16/line:** Requires substrate expansion (Tasks 2 and 5 in TASKS.md).
- **Variable sprite sizes:** Mode0 supports per-descriptor size, but the exact size encoding may differ.
- **Y/X coordinate offsets:** PC Engine uses Y=64 and X=32 as visible origin. The adapter must subtract these on emit.

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| Tile+attr fetch | Shared | Already proven |
| 4bpp decode | Shared | Already proven |
| 64 sprite descriptors | **Direct match** | Mode0 currently 64 |
| 16 sprites/line | **Direct match** | Mode0 currently 32 |
| 9-bit palette | Adapter-local | Mode0 palette is 24-bit; adapter maps values |
| Hardware scroll | Shared | Already proven |
| Raster IRQ | Shared | Already proven |

### 5.5 Realism for default bitstream

**Realistic with significant caveats.** The PC Engine adapter is medium complexity. The sprite system gaps (64 desc, 16/line) are the primary blockers. With 32 descriptors and 8/line, the adapter is an MVP that can run simpler games but not full-fidelity PC Engine scenes.

Estimated cost: ~300 LUT, ~250 FF (v1 with 32 sprites). With 64/16 expansion: ~500 LUT, ~400 FF.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — Basic PC Engine display:** 4bpp tile BG, 32 sprites (half limit), hardware scroll, raster IRQ, 512-entry palette.
2. **v1.1 — Full sprite support:** Expand to 64 sprites / 16 per line (requires substrate expansion).
3. **v2 — Enhanced features:** SATB DMA emulation optimization, per-tile palette bank.

### 6.2 Prerequisite substrate tasks

- **R4.1a/b Tile+Attribute Fetch** — ✅ DONE
- **R4.1b 4bpp Planar Decode** — ✅ DONE
- **R2 Sprite Evaluator — ✅ DONE (64 desc, 32/line)
- **R1 Raster Trigger** — ✅ DONE
- **CW-1 Palette RAM** — ✅ DONE
- **Sprite descriptor expansion (32→64)** — ⚠️ **Required for honest v1.1**
- **Sprite per-line expansion (8→16)** — ⚠️ **Required for honest v1.1**

### 6.3 Proof plan

**Simulation:**
- `PcEngineAdapterSim`: Test scroll, palette write, sprite descriptor upload, raster IRQ
- `VdpTopSim` regression: PC Engine-style 4bpp tile scene with 32 sprites

**Hardware proof:**
- Scenario: PC Engine test pattern or simple demo scene
- Upload pattern table, tilemap, palette, and sprite data via QSPI
- Verify scroll, sprites, and raster IRQ
- 30s capture, `analyze.py` reports `freeze=0`

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~300 (v1, 32 sprites) | Under 600 |
| LUT | ~500 (v1.1, 64 sprites / 16/line) | Under 900 |
| FF | ~250 | Under 500 |
| BSRAM | 0–1 (SAT shadow) | Under 2 |

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** 32 sprites, 8/line, no SATB DMA. Proves 4bpp tile+attr + scroll + raster.
- **v1.1:** 64 sprites, 16/line. Requires substrate expansion.
- **v2:** SATB DMA optimization, per-tile palette bank.
- **Never:** Perfect VCE color emulation, HDTV output, CD-ROM enhancements.
