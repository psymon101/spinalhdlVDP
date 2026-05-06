# SNES / Super Famicom Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Spec drafted — pending audit  
**Platform:** Super Nintendo Entertainment System / Super Famicom  
**Tier:** 4 (very high)  
**Mode ID (proposed):** `0xC`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 256×224 (progressive) or 256×448 (interlaced) |
| Color depth | 2bpp–8bpp tiles depending on mode |
| Master palette | 32,768 colors (15-bit RGB: 5 bits per channel) |
| Simultaneous colors | 256 (from CGRAM) |
| Refresh | ~60.1 Hz (NTSC) / ~50.0 Hz (PAL) |
| Aspect | 8:7 pixel aspect |

The SNES uses two custom chips: **PPU1** (5C77, handles rendering and VRAM access) and **PPU2** (5C78, handles output, color math, and interlace).

### 1.2 Layer model

**Up to 4 background layers + sprites:**
- BG1, BG2, BG3, BG4 (availability depends on mode)
- OBJ (sprite layer)

**Background modes (0–7):**

| Mode | BG1 | BG2 | BG3 | BG4 | Notes |
|---|---|---|---|---|---|
| 0 | 4bpp | 4bpp | 4bpp | 4bpp | Most common |
| 1 | 4bpp | 4bpp | 2bpp | — | Common |
| 2 | 4bpp | 4bpp | — | — | Offset-per-tile |
| 3 | 8bpp | 4bpp | — | — | Large BG1 |
| 4 | 8bpp | 2bpp | — | — | Offset-per-tile |
| 5 | 4bpp | 2bpp | — | — | 512-pixel horizontal |
| 6 | 4bpp | — | — | — | Offset-per-tile, 512px |
| 7 | 8bpp | — | — | — | Affine transform (Mode 7) |

### 1.3 Tile / bitmap / planar organization

**Tiles:** 8×8 pixels, 2bpp/4bpp/8bpp depending on mode.
- 2bpp: 2 bitplanes × 8 bytes = 16 bytes
- 4bpp: 4 bitplanes × 8 bytes = 32 bytes
- 8bpp: 8 bitplanes × 8 bytes = 64 bytes

**Tilemap entry:** 2 bytes
- Bits 9:0 = tile index
- Bit 10 = horizontal flip
- Bit 11 = vertical flip
- Bits 13:12 = palette select (0–7 for 4bpp, 0–3 for 8bpp in some modes)
- Bit 14 = priority
- Bit 15 = unused (or used in offset-per-tile modes)

**VRAM:** 64 KB (word-addressable).

### 1.4 Sprite system

| Parameter | Value |
|---|---|
| Count | 128 sprites |
| Per scanline | 32 sprites maximum |
| Size | 8×8, 16×16, 32×32, 64×64 (global size + per-sprite size bits) |
| Colors | 16 colors per sprite (from one of 8 palettes) |
| Priority | Per-sprite priority bit + OAM index order |

**OAM:** 512 bytes for sprite attributes + 32 bytes for size/MSB bits.

**Second OAM (internal):** Used by PPU during rendering; not CPU-accessible.

### 1.5 Palette / color model

**CGRAM (Color Generator RAM):** 256 entries × 15-bit RGB.
- BG palettes: entries 0–127
- Sprite palettes: entries 128–255
- Entry 0 of each BG palette = transparent

### 1.6 Scrolling model

**Per-BG hardware scroll:**
- Each BG has independent `BGnHOFS` and `BGnVOFS` registers
- Write twice (low byte, then high byte) to set 10-bit scroll value

**Offset-per-tile (Modes 2, 4, 6):**
- Special tilemap data provides per-tile scroll offsets
- Enables parallax and column-based effects without CPU intervention

**Mode 7 affine transform:**
- Matrix parameters A, B, C, D + center X, Y
- Allows rotation, scaling, and perspective

### 1.7 Raster / IRQ / beam-driven behavior

**HBlank/VBlank IRQ:**
- `NMITIMEN` register enables NMI (VBlank) and IRQ (HBlank or programmable)
- `HTIME` / `VTIME` registers set IRQ trigger position

**HDMA:**
- Transfers data to PPU registers during HBlank
- Up to 8 channels
- Used for per-line palette, scroll, and register updates without CPU intervention

### 1.8 DMA / blitter / display-list behavior

**DMA:**
- General DMA: block transfer between bus addresses
- HDMA: per-line register updates during active display
- Both halt the CPU during transfer

**No blitter.** No display-list processor.

### 1.9 Windowing / masking / priority rules

**Windowing:**
- Two windows (Window 1, Window 2) with configurable left/right or top/bottom bounds
- Windows can be combined with AND/OR/XOR logic
- Each BG and sprite layer can be masked by windows independently

**Color math:**
- Addition or subtraction of colors between layers
- Half-color math for transparency effects
- Clip-to-black / clip-to-color modes

**Priority:**
- Per-tile priority bit
- Per-sprite priority bit
- Fixed layer order: BG3 (low) → BG1/BG2 → Sprites → BG3 (high, in Mode 1)

### 1.10 Memory layout and addressing model

| Region | Size | Purpose |
|---|---|---|
| VRAM | 64 KB | Tiles, tilemaps |
| CGRAM | 256 × 15-bit | Palette RAM |
| OAM | 512 + 32 bytes | Sprite attributes + size/MSB |

### 1.11 Timing-sensitive or identity-defining quirks

1. **Mode 7:** The affine transform mode is iconic (Super Mario Kart, F-Zero). Requires matrix math per pixel.
2. **HDMA:** Ubiquitous in SNES games for color gradients, parallax, and effects. Missing HDMA breaks many visual effects.
3. **32 sprites/line:** Very high sprite density for the era.
4. **Color math:** Add/sub blend modes are used for transparency, shadows, and fades.
5. **Interlace:** Rarely used but doubles vertical resolution.

---

## 2. Pipeline Decomposition

| Stage | What SNES does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | PPU reads tilemap → pattern table from VRAM for up to 4 BGs | `SdramTileAttributeFetch` + `SdramTileFetch` | Direct — tile+attr fetch |
| **Decode** | 2bpp/4bpp/8bpp planar → pixel | Tile decoder | Direct |
| **Staging** | Internal shift registers | Tile pipeline buffers | Direct |
| **Sprite evaluation** | 128 sprites, 32/line | `SpriteEvaluator` (R2) | Approximate — Mode0 has 64 desc/32 per line. **Gap: needs 128 desc and 32/line** |
| **Composition** | Up to 4 BGs + sprites → priority + color math | `FourLayerCompositor` + color math | Approximate — Mode0 has 4 layers; color math is a **gap** |
| **Palette** | 256-entry × 15-bit CGRAM | CW-1 palette RAM (24-bit entries) | Direct — Mode0 palette is superset |
| **Beam/raster** | HBlank/VBlank IRQ + HDMA | `RasterTriggerUnit` + Copper/HDMA | Approximate — HDMA is a **gap** |
| **Host/control** | CPU writes PPU registers / VRAM / DMA | Adapter shadow + bus emitter | Direct |

---

## 3. Mode0 Mapping

### 3.1 Background layers

| SNES function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 4 BG layers (Mode 0) | `SdramTileAttributeFetch` × 4 | Configure 4 tilemap+pattern fetches | Direct — Mode0 has 4 layers |
| 2bpp/4bpp/8bpp tiles | `SdramTileFetch` | Set tile depth per mode | Direct |
| Per-BG scroll X/Y | `layer0/1/2/3ScrollX/Y` | Map `BGnHOFS/VOFS` to scroll regs | Direct |
| Offset-per-tile (Modes 2,4,6) | `SdramTileAttributeFetch` with offset | Special tilemap data provides per-tile offsets | Minor — Mode0 may not support offset-per-tile natively |
| Tile flip H/V | Tile descriptor | Map tilemap bits 10/11 | Direct |
| Tile priority | `PixelMetadata` priority bit | Map tilemap bit 14 | Direct |
| Mode 7 affine transform | N/A | **Gap:** Mode0 has no affine transform primitive | **Large gap** — requires matrix math per pixel |

### 3.2 Sprite layer

| SNES function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 128 sprites | `SpriteEvaluator` (64 desc) | Map OAM to descriptors | **Gap: Mode0 has 64 desc; SNES needs 128** |
| 32 sprites/line | `SpriteEvaluator` (8/line) | Mode0 limit is 32/line | **Gap: needs 32/line** |
| Variable sizes | `SpriteEvaluator` descriptor | Map size/MSB bits to descriptor | Minor |
| 16 colors per sprite | `SpriteEvaluator` + paletteBank | Set sprite paletteBank | None |
| Sprite priority | `PixelMetadata` priority bit | Map per-sprite priority | Direct |

### 3.3 Palette

| SNES function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 256-entry 15-bit CGRAM | CW-1 palette RAM | Map CGRAM to Mode0 palette entries | Direct — Mode0 palette is superset |
| 8 palettes × 16 colors (BG) | Palette bank organization | Map SNES palette layout | Minor |
| 8 palettes × 16 colors (Sprites) | Palette bank organization | Map SNES palette layout | Minor |

### 3.4 Windowing / Color Math

| SNES function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| Window 1 / Window 2 | `WindowUnit` | Map window bounds to Mode0 window regs | Minor — Mode0 window may be simpler |
| Window logic (AND/OR/XOR) | `WindowUnit` | Mode0 may not support complex window logic | Minor |
| Color math (add/sub) | N/A | **Gap:** Mode0 has no color math unit | **Medium** — affects transparency and shadow effects |
| Half-color math | N/A | **Gap** | **Medium** |

### 3.5 HDMA / Raster

| SNES function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| HDMA per-line register updates | `Copper` (R3) | Map HDMA channels to Copper programs | Minor — Copper can achieve similar per-line updates |
| HBlank/VBlank IRQ | `RasterTriggerUnit` | Direct map | Direct |
| Programmable IRQ (HTIME/VTIME) | `RasterTriggerUnit` | Map to trigger line/position | Direct |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `SNES_INIDISP` | 8 bits | Screen brightness + forced blank |
| `0x01` | `SNES_OBSEL` | 8 bits | Object size + pattern base address |
| `0x02` | `SNES_OAMADDL` | 8 bits | OAM address (low) |
| `0x03` | `SNES_OAMADDH` | 8 bits | OAM address (high) + priority rotation |
| `0x04` | `SNES_BGMODE` | 8 bits | Background mode (0–7) + tile size |
| `0x05` | `SNES_BG1SC` | 8 bits | BG1 tilemap address + size |
| `0x06` | `SNES_BG2SC` | 8 bits | BG2 tilemap address + size |
| `0x07` | `SNES_BG3SC` | 8 bits | BG3 tilemap address + size |
| `0x08` | `SNES_BG4SC` | 8 bits | BG4 tilemap address + size |
| `0x09` | `SNES_BG12NBA` | 8 bits | BG1/BG2 pattern base addresses |
| `0x0A` | `SNES_BG34NBA` | 8 bits | BG3/BG4 pattern base addresses |
| `0x0B` | `SNES_BG1HOFS` | 16 bits | BG1 horizontal scroll |
| `0x0C` | `SNES_BG1VOFS` | 16 bits | BG1 vertical scroll |
| `0x0D` | `SNES_BG2HOFS` | 16 bits | BG2 horizontal scroll |
| `0x0E` | `SNES_BG2VOFS` | 16 bits | BG2 vertical scroll |
| `0x0F` | `SNES_BG3HOFS` | 16 bits | BG3 horizontal scroll |
| `0x10` | `SNES_BG3VOFS` | 16 bits | BG3 vertical scroll |
| `0x11` | `SNES_BG4HOFS` | 16 bits | BG4 horizontal scroll |
| `0x12` | `SNES_BG4VOFS` | 16 bits | BG4 vertical scroll |
| `0x13` | `SNES_VMAIN` | 8 bits | VRAM address increment mode |
| `0x14` | `SNES_VMADD` | 16 bits | VRAM address |
| `0x15` | `SNES_VMDATA` | 16 bits | VRAM data write |
| `0x16` | `SNES_CGADD` | 8 bits | CGRAM (palette) address |
| `0x17` | `SNES_CGDATA` | 16 bits | CGRAM data write (15-bit color) |
| `0x18` | `SNES_TM` | 8 bits | Main screen layer enable |
| `0x19` | `SNES_TS` | 8 bits | Sub screen layer enable |
| `0x1A` | `SNES_CGWSEL` | 8 bits | Color math control |
| `0x1B` | `SNES_CGADSUB` | 8 bits | Color math add/subtract select |
| `0x1C` | `SNES_HDMAEN` | 8 bits | HDMA channel enable |
| `0x1D` | `SNES_NMITIMEN` | 8 bits | NMI/IRQ enable |
| `0x1E` | `SNES_RDOBJ` | 8 bits | Read-only: sprite overflow + collision |

### 4.2 Initialization flow

1. Host selects mode `0xC` via `MODE_SELECT`
2. Host uploads pattern tables, tilemaps, and sprite patterns to SDRAM
3. Host writes CGRAM (256 palette entries)
4. Host writes OAM (sprite attributes + size/MSB)
5. Host sets scroll values for each BG
6. Host writes `SNES_TM` to enable layers
7. Host writes `SNES_INIDISP` to enable display

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Pattern tables | Up to 64 KB | SDRAM | 2bpp/4bpp/8bpp planar tiles |
| Tilemaps | Up to 32 KB | SDRAM | 16-bit tile entries |
| Sprite patterns | Up to 32 KB | SDRAM | 4bpp planar tiles |
| OAM | 544 bytes | Adapter shadow | 128 sprite descriptors + size/MSB table |
| CGRAM | 512 bytes | Adapter shadow / Mode0 palette | 256 × 15-bit entries |

### 4.4 Runtime control/update model

- **VRAM access:** Host writes `SNES_VMADD` then `SNES_VMDATA`. Adapter translates to SDRAM.
- **Palette updates:** Write `SNES_CGADD` then `SNES_CGDATA`. Adapter emits palette bus writes.
- **Scroll updates:** Write to `SNES_BGnHOFS/VOFS`. Adapter emits `layerNScrollX/Y` bus writes.
- **HDMA:** Host configures HDMA table; adapter translates to Copper program or handles via bus emits.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank NMI | `RasterTriggerUnit` at last line | `STATUS_STICKY` bit 0 |
| HBlank/Programmable IRQ | `RasterTriggerUnit` | `STATUS_STICKY` bit 0 |
| Sprite overflow | `SpriteEvaluator` | `STATUS_STICKY` bit 1 |
| Time-over (too many tiles) | Adapter-local | Not directly detectable in Mode0 |

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| `VDP_TILE_MODE` / depth | Adapter-local `SNES_BGMODE` | Adapter translates mode bits |
| `layer0/1/2/3ScrollX/Y` | Adapter-local scroll regs | Adapter translates |
| Palette entries | Adapter-local `SNES_CGADD/CGDATA` | Adapter translates |
| Sprite descriptors | Adapter-local OAM shadow | Adapter emits bus writes |
| Layer enable | Adapter-local `SNES_TM` | Adapter translates |
| Raster trigger | Adapter-local `SNES_NMITIMEN` | Direct output |
| HDMA | Adapter-local `SNES_HDMAEN` | Translates to Copper or bus emits |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- Tile+attribute fetch — direct match
- Multi-layer composition (up to 4 layers) — direct match
- 2bpp/4bpp/8bpp decode — direct match
- Palette RAM — superset
- Hardware scroll — direct match
- Raster IRQ — direct match

### 5.2 What is approximate

- **HDMA:** Mode0's Copper (R3) can achieve per-line register updates, but the programming model differs from SNES HDMA. Adapter can translate HDMA tables to Copper programs.
- **Windowing:** Mode0 `WindowUnit` supports basic windowing. Complex window logic (AND/OR/XOR) may not map cleanly.
- **Offset-per-tile:** Mode0 tile attributes may not support per-tile scroll offsets. The adapter may need to approximate or document as a gap.

### 5.3 What is missing entirely

- **128 sprite descriptors + 32/line:** Major gap. Requires substrate expansion.
- **Mode 7 affine transform:** No Mode0 equivalent. Large architectural gap.
- **Color math (add/sub/blend):** No Mode0 equivalent. Affects many visual effects.
- **Interlace:** Mode0 does not support interlaced output.

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| Tile+attr fetch | Shared | Already proven |
| Multi-layer composition | Shared (Task 48) | Mode0 has 4 layers |
| 2bpp/4bpp/8bpp decode | Shared | Already proven |
| 128 sprite descriptors | **Shared expansion needed** | Mode0 currently 64 |
| 32 sprites/line | **Shared expansion needed** | Mode0 currently 64 |
| 15-bit palette | Adapter-local | Mode0 uses 24-bit |
| Hardware scroll | Shared | Already proven |
| Mode 7 | Adapter-local (never?) | No Mode0 equivalent |
| Color math | Adapter-local (never?) | No Mode0 equivalent |
| HDMA | Adapter-local | Copper can approximate |

### 5.5 Realism for default bitstream

**Not realistic in default bitstring.** The SNES adapter requires 128 sprites, 32/line, Mode 7, and color math — all major gaps. Even a limited MVP (32 sprites, 8/line, no Mode 7, no color math) is only a shadow of SNES capability.

Estimated cost: ~400 LUT, ~350 FF (v1 limited MVP). With full expansion: ~1000+ LUT, ~800 FF.

**Explicitly excluded from default bitstring** per `MODE0_GAP_TASKLIST.md`.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — Limited SNES display:** Mode 0/1 (4bpp BGs), 32 sprites, basic scroll, no Mode 7, no color math.
2. **v1.1 — HDMA proxy:** Map HDMA to Copper programs.
3. **v1.2 — Full sprite support:** Expand to 128 sprites / 32 per line (requires substrate expansion).
4. **v2 — Mode 7 approximation:** Software-rendered affine transform or honest gap documentation.

### 6.2 Prerequisite substrate tasks

- **R4.1a/b Tile+Attribute Fetch** — ✅ DONE
- **R4.1b 2bpp/4bpp/8bpp Decode** — ✅ DONE
- **Task 48 FourLayerCompositor** — ✅ DONE
- **R2 Sprite Evaluator — ✅ DONE (64 desc, 32/line)
- **R1 Raster Trigger** — ✅ DONE
- **CW-1 Palette RAM** — ✅ DONE
- **R3 Copper** — ✅ DONE (for HDMA proxy)
- **Sprite descriptor expansion (32→128)** — ⚠️ **Required for honest v1.2**
- **Sprite per-line expansion (8→32)** — ⚠️ **Required for honest v1.2**

### 6.3 Proof plan

**Simulation:**
- `SnesAdapterSim`: Test mode switch, 4-layer scroll, sprite upload, raster IRQ
- `VdpTopSim` regression: SNES-style 4bpp multi-layer scene

**Hardware proof:**
- Scenario: SNES test pattern or simple scene (e.g., `Super Mario World` title screen tiles)
- Upload pattern tables, tilemaps, palette, sprites via QSPI
- Verify multi-layer scroll
- 30s capture, `analyze.py` reports `freeze=0`

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~400 (v1 limited) | Under 700 |
| LUT | ~1000+ (v1.2 full) | Under 1500 |
| FF | ~350 | Under 700 |
| BSRAM | 0–2 | Under 4 |

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** Mode 0/1, 32 sprites, 8/line, no Mode 7, no color math.
- **v1.1:** HDMA proxy via Copper.
- **v1.2:** 128 sprites, 32/line. Requires substrate expansion.
- **v2:** Mode 7 approximation (honest gap if not possible).
- **Never:** Color math, interlace, cycle-accurate PPU.
