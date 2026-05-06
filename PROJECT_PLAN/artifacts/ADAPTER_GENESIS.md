# Sega Genesis / Mega Drive Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Spec drafted — pending audit  
**Platform:** Sega Genesis / Mega Drive  
**Tier:** 3 (high)  
**Mode ID (proposed):** `0xB`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 320×224 (H40 mode) or 256×224 (H32 mode) |
| Color depth | 4bpp tiles + 4 palettes of 16 colors |
| Master palette | 512 colors (9-bit RGB: 3 bits per channel) |
| Simultaneous colors | 64 (4 palettes × 16 colors) + 1 backdrop |
| Refresh | ~59.9 Hz (NTSC) / ~49.7 Hz (PAL) |
| Aspect | 32:35 pixel aspect (H40) or 8:7 (H32) |

The **Sega Genesis VDP** is a custom chip derived from the Master System VDP, heavily enhanced for 16-bit gaming.

### 1.2 Layer model

**Three background layers + sprites:**
1. **Plane A (Scroll A)** — primary scrollable tilemap
2. **Plane B (Scroll B)** — secondary scrollable tilemap (typically parallax)
3. **Window** — fixed non-scrollable tilemap (typically for HUD/status bar)
4. **Sprites** — up to 80 sprites

Plane A and Window share the same VRAM space but are rendered differently. The Window replaces a horizontal region of Plane A (top or bottom, configurable).

### 1.3 Tile / bitmap / planar organization

**Tiles:** 8×8 pixels, 4bpp (16 colors).
- Pattern table: up to 2048 tiles × 32 bytes = 64 KB
- Each tile = 4 bitplanes × 8 bytes = 32 bytes

**Tilemap entry:** 2 bytes
- Bits 10:0 = tile index
- Bit 11 = horizontal flip
- Bit 12 = vertical flip
- Bit 13 = palette select (0–3)
- Bit 14 = priority (1 = in front of sprites)
- Bit 15 = unused

**Tilemap size:** Configurable from 32×28 to 128×128 (varies by mode).

**VRAM:** 64 KB.

### 1.4 Sprite system

| Parameter | Value |
|---|---|
| Count | 80 sprites |
| Per scanline | 20 sprites (H40) or 16 sprites (H32) |
| Size | 8×8 to 32×32 (per-sprite, via size table in VRAM) |
| Colors | 15 colors + transparent (from one of 4 palettes) |
| Priority | Lower link order = higher priority (drawn on top) |

**Sprite Attribute Table:** 8 bytes per sprite (4 words).

| Word | Content |
|---|---|
| 0 | Y position (10 bits, offset by 128) |
| 1 | Size + link data |
| 2 | Attributes: priority, palette, flip H/V, tile index high bits |
| 3 | X position (10 bits) |

**Link system:** Sprites are chained in a linked list. The VDP processes sprites in link order, not memory order.

**Sprite masking:** Sprite 0 can mask all lower-priority sprites on a line when its X=0.

### 1.5 Palette / color model

**Color RAM (CRAM):** 4 palettes × 16 colors = 64 entries × 9-bit RGB.
- Background palettes: 0 and 1
- Sprite palettes: 2 and 3
- Backdrop color: entry 0 of palette 0

### 1.6 Scrolling model

**Per-plane hardware scroll:**
- Plane A: `HScrollA`, `VScrollA`
- Plane B: `HScrollB`, `VScrollB`
- Window: No scroll (fixed)

**Scroll modes:**
- Full-screen scroll (one value for entire plane)
- Per-column vertical scroll (8-pixel columns)
- Per-scanline horizontal scroll (line-by-line)

### 1.7 Raster / IRQ / beam-driven behavior

**H-blank interrupt:**
- `Reg 10` sets the interval (every N lines)
- Used for per-line effects: palette swaps, scroll changes, raster splits

**V-blank interrupt:** Standard frame tick.

**External interrupt:** Light gun / paddle (out of scope).

### 1.8 DMA / blitter / display-list behavior

**DMA engine:**
- 68K RAM → VRAM
- VRAM → CRAM
- VRAM → VSRAM
- VRAM fill (single value written to a region)
- VRAM copy (region to region)

DMA is triggered by setting the DMA length and source address, then writing to the control port.

### 1.9 Windowing / masking / priority rules

**Window:**
- Replaces either the top N lines or the bottom N lines of Plane A
- Non-scrollable — perfect for HUDs

**Priority:**
- Per-tile priority (Plane A/B)
- Per-sprite priority
- Lower link-order sprites on top

**Sprite masking:** Sprite 0 at X=0 masks all lower-priority sprites on that line.

### 1.10 Memory layout and addressing model

| Region | Size | Purpose |
|---|---|---|
| VRAM | 64 KB | Patterns, tilemaps, sprite attributes, H-scroll table |
| CRAM | 64 × 9-bit | Palette RAM |
| VSRAM | 40 bytes | Vertical scroll values (20 for Plane A, 20 for Plane B) |

### 1.11 Timing-sensitive or identity-defining quirks

1. **Shadow/highlight mode:** Setting a VDP register enables shadow/highlight effects. Non-priority sprites/tiles can darken or brighten pixels below them. This is a defining Genesis visual feature.
2. **Sprite masking:** Sprite 0 at X=0 is a commonly used technique to limit sprites per line.
3. **Link-based sprite order:** The linked-list sprite processing is unusual. Games rely on it for dynamic sprite management.
4. **H-scroll table in VRAM:** Horizontal scroll values are fetched from VRAM per line, allowing complex parallax and wave effects.

---

## 2. Pipeline Decomposition

| Stage | What Genesis does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | VDP reads tilemap → pattern table from VRAM for Plane A, B, Window | `SdramTileAttributeFetch` + `SdramTileFetch` | Direct — tile+attr fetch |
| **Decode** | 4bpp planar → 16-color pixel | Tile decoder with 4bpp mode | Direct |
| **Staging** | Internal shift registers | Tile pipeline buffers | Direct |
| **Sprite evaluation** | 80 sprites, 20/16 per line, linked list | `SpriteEvaluator` (R2) | Approximate — Mode0 has 64 desc/32 per line. **Gap: needs 80 desc and 20/16/line** |
| **Composition** | Plane A + Plane B + Window + Sprites → priority mux | `FourLayerCompositor` (Task 48) | Approximate — Mode0 has 4 layers; Genesis needs A+B+Window+Sprites. **Window is a hard gap** |
| **Palette** | 64-entry × 9-bit CRAM | CW-1 palette RAM (24-bit entries) | Direct — Mode0 palette is superset |
| **Beam/raster** | H-blank IRQ every N lines + VBlank | `RasterTriggerUnit` (R1) | Direct |
| **Host/control** | 68K writes VDP registers / VRAM / DMA | Adapter shadow + bus emitter | Direct — thin translation layer |

---

## 3. Mode0 Mapping

### 3.1 Background layers

| Genesis function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| Plane A scrollable tilemap | `SdramTileAttributeFetch` | Set 4bpp tile mode; configure tilemap base | Direct |
| Plane B scrollable tilemap | `SdramTileAttributeFetch` | Second tilemap instance | Direct — Mode0 supports multiple layers |
| Window (non-scrollable) | N/A | **Gap:** Mode0 has no "window" primitive that replaces a region of a layer | **Medium** — Window behavior must be approximated or documented as gap |
| Per-plane scroll X/Y | `layer0ScrollX/Y` (Plane A), `layer1ScrollX/Y` (Plane B) | Map scroll regs to Mode0 layer scroll | Direct |
| Per-line H-scroll | `layer0ScrollX` updated per line | H-scroll table fetched from VRAM; adapter updates scroll each line | Minor — requires Mode0 scroll to be writable per line |
| Per-column V-scroll | `layer0ScrollY` updated per 8px column | VSRAM provides per-column scroll | Minor — Mode0 may not support per-column scroll natively |
| Tile flip H/V | Tile descriptor | Map tilemap bits 11/12 | Direct |
| Tile priority | `PixelMetadata` priority bit | Map tilemap bit 14 | Direct |
| Tile palette select (0–3) | `paletteBank` per tile | Map tilemap bit 13 | Minor — Mode0 tile attr byte may need extension |

### 3.2 Sprite layer

| Genesis function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 80 sprites | `SpriteEvaluator` (64 desc) | Map SAT to descriptors | **Gap: Mode0 has 64 desc; Genesis needs 80** |
| 20 sprites/line (H40) | `SpriteEvaluator` (8/line) | Mode0 limit is 32/line | **Gap: needs 20/line** |
| Linked-list sprite order | `SpriteEvaluator` | Mode0 uses index order | **Minor gap:** linked list vs index order may affect visual priority in edge cases |
| Variable sprite sizes | `SpriteEvaluator` descriptor | Map size table to descriptor dimensions | Minor |
| Sprite masking | Adapter-local logic | Sprite 0 at X=0 suppresses lower priorities | Minor — can be handled in adapter |
| Shadow/highlight | N/A | **Gap:** Mode0 has no shadow/highlight mode | **Medium** — defining Genesis visual feature missing |

### 3.3 Palette

| Genesis function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 64-entry 9-bit CRAM | CW-1 palette RAM | Map CRAM to palette entries 0..63 | Direct — Mode0 palette is superset |
| 4 palettes × 16 colors | Palette bank organization | Map Genesis palette layout to Mode0 banks | Minor |
| Backdrop color | Palette entry 0 | Map CRAM entry 0 | Direct |

### 3.4 DMA

| Genesis function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 68K RAM → VRAM DMA | Host QSPI upload | Host uploads data via QSPI; no DMA engine in Mode0 | Minor — functional equivalent, slower |
| VRAM fill / copy | `BlitterEngine` (Task 49) | Map to Mode0 blitter fill/copy commands | Minor — command set differs |
| VRAM → CRAM DMA | Palette bus writes | Adapter translates to palette updates | Minor |

### 3.5 Raster / IRQ

| Genesis function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| H-blank IRQ every N lines | `RasterTriggerUnit` | Map `Reg 10` interval to periodic trigger | Direct |
| V-blank IRQ | `RasterTriggerUnit` at last line | Direct map | Direct |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `GEN_R0` | 8 bits | Mode Control 1 (H-blank IRQ enable, palette select) |
| `0x01` | `GEN_R1` | 8 bits | Mode Control 2 (Display enable, V-blank IRQ, DMA enable) |
| `0x02` | `GEN_R2` | 8 bits | Plane A Name Table base address |
| `0x03` | `GEN_R3` | 8 bits | Window Name Table base address |
| `0x04` | `GEN_R4` | 8 bits | Plane B Name Table base address |
| `0x05` | `GEN_R5` | 8 bits | Sprite Attribute Table base address |
| `0x06` | `GEN_R7` | 8 bits | Background / Border color |
| `0x07` | `GEN_R10` | 8 bits | H-blank interrupt interval |
| `0x08` | `GEN_R11` | 8 bits | Scroll mode (full / column / line) |
| `0x09` | `GEN_R12` | 8 bits | H40/H32 resolution select |
| `0x0A` | `GEN_R13` | 8 bits | H-scroll table base address |
| `0x0B` | `GEN_R15` | 8 bits | VRAM auto-increment |
| `0x0C` | `GEN_DMA_LEN` | 16 bits | DMA length |
| `0x0D` | `GEN_DMA_SRC` | 24 bits | DMA source address |
| `0x0E` | `GEN_VRAM_ADDR` | 16 bits | VRAM address |
| `0x0F` | `GEN_VRAM_DATA` | 16 bits | VRAM data |
| `0x10` | `GEN_VSRAM_ADDR` | 8 bits | VSRAM address |
| `0x11` | `GEN_VSRAM_DATA` | 16 bits | VSRAM data (vertical scroll values) |
| `0x12` | `GEN_CRAM_ADDR` | 8 bits | CRAM address |
| `0x13` | `GEN_CRAM_DATA` | 16 bits | CRAM data (9-bit color) |
| `0x14` | `GEN_CTRL` | 8 bits | Bit 0 = display enable; bit 1 = shadow/highlight enable (v2) |

### 4.2 Initialization flow

1. Host selects mode `0xB` via `MODE_SELECT`
2. Host uploads pattern table, tilemaps (Plane A, Plane B, Window), and sprite patterns to SDRAM
3. Host writes CRAM (64 palette entries)
4. Host writes sprite attributes
5. Host sets scroll values (VSRAM + H-scroll table)
6. Host writes `GEN_R1` to enable display and interrupts

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Pattern table | Up to 64 KB | SDRAM | 4bpp planar tiles (32 bytes each) |
| Plane A tilemap | Up to 8 KB | SDRAM | 16-bit tile entries |
| Plane B tilemap | Up to 8 KB | SDRAM | 16-bit tile entries |
| Window tilemap | Up to 8 KB | SDRAM | 16-bit tile entries |
| Sprite patterns | Up to 32 KB | SDRAM | 4bpp planar tiles |
| Sprite attributes | 320 bytes | Adapter shadow / SDRAM | 80 × 4-word descriptors |
| H-scroll table | Up to 512 bytes | SDRAM | Per-line scroll values |
| CRAM | 128 bytes | Adapter shadow / Mode0 palette | 64 × 9-bit entries |

### 4.4 Runtime control/update model

- **VRAM/VSRAM/CRAM access:** Host writes address then data. Adapter translates to SDRAM or palette bus.
- **Scroll updates:** Write to VSRAM or H-scroll table. Adapter emits `layerNScrollX/Y` bus writes.
- **DMA:** Host sets `GEN_DMA_LEN` and `GEN_DMA_SRC`, then triggers. Adapter translates to QSPI upload or `BlitterEngine` commands.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank | `RasterTriggerUnit` at last line | `STATUS_STICKY` bit 0 |
| H-blank (every N lines) | `RasterTriggerUnit` at interval | `STATUS_STICKY` bit 0 |
| Sprite overflow | `SpriteEvaluator` | `STATUS_STICKY` bit 1 |
| DMA completion | Adapter-local | Adapter sets flag when done |

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| `VDP_TILE_MODE` (4bpp) | Adapter-local `GEN_R0/R1` | Adapter translates |
| `layer0/1ScrollX/Y` | Adapter-local scroll regs | Adapter translates |
| Palette entries 0..63 | Adapter-local `GEN_CRAM_*` | Adapter translates |
| Sprite descriptors | Adapter-local shadow | Adapter emits bus writes |
| Raster trigger | Adapter-local `GEN_R10` | Direct output |
| Display enable | Adapter-local `GEN_CTRL[0]` | Adapter translates |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- Tile+attribute fetch (R4.1a/b) — direct match
- 4bpp planar decode — exact match
- Multi-layer composition (Task 48) — Mode0 has 4 layers; Genesis needs A+B+Sprites
- Palette RAM — superset
- Raster IRQ — direct match
- Hardware scroll — direct match

### 5.2 What is approximate

- **Window layer:** Mode0 has no "window" primitive. The adapter can approximate by using a separate layer or by restricting Plane A to a region, but exact Window behavior (replacing a horizontal strip of Plane A) is not natively supported.
- **Per-column V-scroll:** Mode0 scroll registers are per-layer, not per-column. The adapter may need to update scroll rapidly or document as a gap.
- **Linked-list sprite order:** Mode0 uses fixed index order. Most games work fine with index order, but games that dynamically reorder sprites via links may behave differently.
- **DMA:** Mode0 has no DMA engine. Host uploads via QSPI are functionally equivalent but slower.

### 5.3 What is missing entirely

- **80 sprite descriptors + 20/16 per line:** Major gap. Requires substrate expansion (Tasks 2 and 5).
- **Shadow/highlight mode:** A defining Genesis visual feature. Mode0 has no equivalent. Out of scope for v1.
- **Sprite masking:** Can be handled in adapter logic, but is not a Mode0 primitive.

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| Tile+attr fetch | Shared | Already proven |
| 4bpp decode | Shared | Already proven |
| Multi-layer composition | Shared (Task 48) | Mode0 has 4 layers |
| 80 sprite descriptors | **Shared expansion needed** | Mode0 currently 64 |
| 20 sprites/line | **Shared expansion needed** | Mode0 currently 64 |
| 9-bit palette | Adapter-local | Mode0 uses 24-bit |
| Hardware scroll | Shared | Already proven |
| Shadow/highlight | Adapter-local (v2?) | No Mode0 equivalent |
| Window | Adapter-local | No Mode0 equivalent |

### 5.5 Realism for default bitstream

**Not realistic in default bitstring without substrate expansion.** The Genesis adapter requires 80 sprites and 20/line, which are significant substrate gaps. With 32 sprites and 8/line, the adapter is a very limited MVP.

Estimated cost: ~350 LUT, ~300 FF (v1 with 32 sprites). With 80/20 expansion: ~700 LUT, ~600 FF.

**Explicitly excluded from default bitstring** per `MODE0_GAP_TASKLIST.md` until sprite expansion lands.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — Basic Genesis display:** Plane A + B, 32 sprites (limited), 4bpp tiles, scroll, raster IRQ.
2. **v1.1 — Window approximation:** Add Window layer approximation using Mode0 layer priority or clipping.
3. **v1.2 — Full sprite support:** Expand to 80 sprites / 20 per line (requires substrate expansion).
4. **v2 — Shadow/highlight:** Approximate shadow/highlight mode using palette tricks or Mode0 enhancements.

### 6.2 Prerequisite substrate tasks

- **R4.1a/b Tile+Attribute Fetch** — ✅ DONE
- **R4.1b 4bpp Planar Decode** — ✅ DONE
- **Task 48 FourLayerCompositor** — ✅ DONE
- **R2 Sprite Evaluator — ✅ DONE (64 desc, 32/line)
- **R1 Raster Trigger** — ✅ DONE
- **CW-1 Palette RAM** — ✅ DONE
- **Sprite descriptor expansion (32→80)** — ⚠️ **Required for honest v1.2**
- **Sprite per-line expansion (8→20)** — ⚠️ **Required for honest v1.2**

### 6.3 Proof plan

**Simulation:**
- `GenesisAdapterSim`: Test dual-plane scroll, tile priority, sprite upload, raster IRQ
- `VdpTopSim` regression: Genesis-style 4bpp dual-plane scene

**Hardware proof:**
- Scenario: Genesis test pattern or static scene (e.g., `Sonic the Hedgehog` Green Hill Zone tileset)
- Upload pattern tables, tilemaps, palette, sprites via QSPI
- Verify dual-plane parallax scroll
- 30s capture, `analyze.py` reports `freeze=0`

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~350 (v1, 32 sprites) | Under 600 |
| LUT | ~700 (v1.2, 80 sprites / 20/line) | Under 1200 |
| FF | ~300 | Under 600 |
| BSRAM | 0–2 | Under 3 |

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** Plane A+B, 32 sprites, 8/line, no Window, no shadow/highlight.
- **v1.1:** Window approximation.
- **v1.2:** 80 sprites, 20/line. Requires substrate expansion.
- **v2:** Shadow/highlight approximation.
- **Never:** Perfect DMA timing, light gun support, 32X compatibility.
