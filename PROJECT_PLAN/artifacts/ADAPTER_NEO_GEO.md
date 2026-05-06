# Neo Geo Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Spec drafted — pending audit  
**Platform:** Neo Geo (SNK)  
**Tier:** 3 (high)  
**Mode ID (proposed):** `0xE`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 320×224 pixels |
| Color depth | 4bpp tiles + 16-color palettes |
| Master palette | 65,536 colors (16-bit RGB: 5 bits per channel + 1 shadow bit) |
| Simultaneous colors | 4,096 (256 palettes × 16 colors) |
| Refresh | ~59.2 Hz |
| Aspect | 8:7 pixel aspect |

The Neo Geo uses a unique **"line-sprite"** architecture. There is no traditional background tilemap; instead, the entire screen is constructed from sprites.

### 1.2 Layer model

**Two conceptual layers:**
1. **Fix Layer** — 40×32 tile overlay for HUD/text (top priority, non-scrollable in the traditional sense)
2. **Sprite Layer** — Everything else (backgrounds, characters, objects) is made of sprites

The **sprite system** is the defining feature of Neo Geo graphics.

### 1.3 Tile / sprite organization

**Fix layer tiles:** 8×8 pixels, 4bpp (16 colors).
- 40×32 tilemap = 1,280 tiles
- Tilemap stored in VRAM at `$7000-$74FF`
- Each tile entry = 2 bytes: tile index + palette + flip bits

**Sprites:** 16 pixels wide, variable height (1–32 tiles = 16–512 pixels).
- Each sprite is a vertical strip of 16×16 tiles
- Sprites can be **chained** horizontally using the "horizontal link" bit
- Sprites can be **shrunk** vertically and horizontally using SCB2 coefficients
- Maximum 380 sprites in the sprite list
- Maximum 96 sprites per scanline

**VRAM memory map:**

| Region | Size | Address | Purpose |
|---|---|---|---|
| SCB1 | 28 KB | `$0000-$6FFF` | Sprite tile indices and attributes |
| FIX | 1.25 KB | `$7000-$74FF` | Fix layer tilemap |
| SCB2 | 512 B | `$8000-$81FF` | Sprite shrink coefficients |
| SCB3 | 512 B | `$8200-$83FF` | Sprite Y-position, height, sticky bits |
| SCB4 | 512 B | `$8400-$85FF` | Sprite X-position, horizontal link bits |

### 1.4 Sprite system

| Parameter | Value |
|---|---|
| Count | 380 sprites in list |
| Per scanline | 96 sprites maximum |
| Size | 16px wide × 16–512px tall (1–32 tiles) |
| Colors | 16 colors per sprite (from one of 256 palettes) |
| Priority | Per-sprite priority bit + order in sprite list |
| Shrinking | Hardware vertical and horizontal scaling (0–100%) |
| Sticky bit | Chains sprites for synchronized movement |

**Sprite Control Blocks (SCB):**

| SCB | Address | Content |
|---|---|---|
| SCB1 | `$0000+` | Per-tile: tile index, palette, flip H/V |
| SCB2 | `$8000+` | Per-sprite: shrink coefficients (H/V) |
| SCB3 | `$8200+` | Per-sprite: Y-position (496−Y), height, sticky |
| SCB4 | `$8400+` | Per-sprite: X-position, horizontal link |

**Y-coordinate encoding:** Y = 496 − SCB3_value. This allows off-screen positioning above the display.

**Shrinking:**
- Vertical shrink: 0–255 coefficient (0 = invisible, 255 = full height)
- Horizontal shrink: 0–15 coefficient (0 = invisible, 15 = full width)

### 1.5 Palette / color model

**Palette RAM:** 256 palettes × 16 colors = 4,096 entries × 16-bit RGB.
- 16-bit color = `RRRRRGGGGGGBBBBB` (5 bits per channel) + 1 shadow bit
- The shadow bit darkens the color when enabled

**Palette organization:**
- Palettes 0–255 are all available simultaneously
- Each sprite selects one palette via SCB1 attributes
- Fix layer tiles also select palettes

### 1.6 Scrolling model

**No hardware scroll registers.** The Neo Geo achieves scroll by:
- Repositioning sprites (changing SCB3/SCB4)
- Using the "sticky bit" to chain sprites and move groups together
- Since backgrounds are made of sprites, "scrolling" is just sprite movement

### 1.7 Raster / IRQ / beam-driven behavior

**VBlank interrupt:** Standard frame tick.

**Raster interrupts:** Not present on Neo Geo base hardware. Some games use CPU-timed loops for raster effects.

**Auto-anim:** Hardware can automatically cycle through animation frames for specified sprites.

### 1.8 DMA / blitter / display-list behavior

**No DMA engine.** The 68K CPU writes to VRAM via the LSPC data port.
- `$3C0000` = VRAM data
- `$3C0002` = VRAM address
- `$3C0004` = VRAM auto-increment

**No blitter.** No display-list processor.

### 1.9 Windowing / masking / priority rules

**Fix layer priority:**
- Fix layer is always drawn on top of sprites unless the sprite has the "fix priority" bit set
- In practice, Fix layer = HUD/text, Sprites = everything else

**Sprite priority:**
- Per-sprite priority bit
- Lower sprite index = drawn first (bottom)
- Higher sprite index = drawn last (top)
- The line-sprite renderer processes sprites in index order

**No windowing hardware.** No masking beyond sprite transparency.

### 1.10 Memory layout and addressing model

| Region | Size | Purpose |
|---|---|---|
| VRAM | 64 KB + 4 KB internal | SCB data, Fix layer tilemap |
| Palette RAM | 8 KB | 4,096 × 16-bit colors |
| Sprite ROM | Variable (game-dependent) | Sprite tile graphics (external ROM) |
| Fix ROM | Variable (game-dependent) | Fix layer tile graphics (external ROM) |

**Note:** Neo Geo sprite and fix graphics are stored in external ROM, not VRAM. The VRAM only holds SCB data (attributes). This is fundamentally different from most other platforms where tile patterns are in VRAM.

### 1.11 Timing-sensitive or identity-defining quirks

1. **Line-sprite architecture:** No background layer. Everything is sprites. This is the defining Neo Geo characteristic.
2. **Sprite shrinking:** Hardware scaling is used extensively for depth effects (e.g., characters walking into the background).
3. **Sticky bit:** Allows groups of sprites to move as a unit. Essential for large characters.
4. **96 sprites/line:** Very high sprite density.
5. **External ROM for graphics:** Sprite patterns are not in VRAM. The adapter must either fetch from external ROM or pre-load patterns into SDRAM.

---

## 2. Pipeline Decomposition

| Stage | What Neo Geo does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | LSPC reads SCB1 → fetches sprite tiles from external ROM | `SdramTileFetch` (for Fix) + `SpriteEvaluator` (for sprites) | Approximate — Neo Geo tiles are in external ROM, not VRAM |
| **Decode** | 4bpp planar → 16-color pixel | Tile decoder with 4bpp mode | Direct |
| **Staging** | Line buffers for sprite compositing | Sprite pipeline buffers | Direct |
| **Sprite evaluation** | 380 sprites, 96/line, variable height, shrinking | `SpriteEvaluator` (R2) | Approximate — Mode0 has 64 desc/32 per line. **Gap: needs 380 desc and 96/line** |
| **Composition** | Fix layer + sprite layer → priority | `FourLayerCompositor` | Direct — Fix = top layer, sprites = bottom |
| **Palette** | 4,096-entry × 16-bit palette RAM | CW-1 palette RAM (24-bit entries) | Direct — Mode0 palette may need expansion to 4,096 entries |
| **Beam/raster** | VBlank only | `RasterTriggerUnit` (R1) | Direct |
| **Host/control** | 68K writes VRAM / palette via LSPC port | Adapter shadow + bus emitter | Direct |

---

## 3. Mode0 Mapping

### 3.1 Fix layer

| Neo Geo function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 40×32 tile overlay | `SdramTileAttributeFetch` | Set 4bpp tile mode; configure 40×32 tilemap | Direct |
| 8×8 4bpp tiles | `SdramTileFetch` | Pattern table in SDRAM | Direct |
| Fix priority over sprites | `PixelMetadata` priority bit | Fix layer always high priority | Direct |
| Tile palette select | `paletteBank` per tile | Map Fix tile palette bits | Minor |

### 3.2 Sprite layer

| Neo Geo function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 380 sprites | `SpriteEvaluator` (64 desc) | Map SCB to descriptors | **Gap: Mode0 has 64 desc; Neo Geo needs 380** |
| 96 sprites/line | `SpriteEvaluator` (32/line) | Mode0 has 32/line; Neo Geo needs 96 | **Large gap — remains open** |
| Variable height (16–512px) | `SpriteEvaluator` descriptor | Map SCB3 height to descriptor | Minor — Mode0 supports variable height |
| Sprite shrinking (H/V) | N/A | **Gap:** Mode0 has no hardware shrinking | **Medium** — defining Neo Geo feature |
| Horizontal link (chaining) | `SpriteEvaluator` | Chain sprites by X-position offset | Minor — adapter can handle linking |
| Sticky bit (group movement) | Adapter-local | Group sprites with same sticky bit | Minor — adapter tracks groups |
| 16 colors per sprite | `SpriteEvaluator` + paletteBank | Set sprite paletteBank per descriptor | None |
| Sprite priority | `PixelMetadata` priority bit | Map SCB1 priority bit | Direct |
| Y/X coordinate offsets | Descriptor position | Adapter handles 496−Y encoding | Minor |

### 3.3 Palette

| Neo Geo function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 4,096-entry 16-bit palette | CW-1 palette RAM | Map palette RAM to Mode0 palette | Minor — Mode0 currently has 512-entry palette. **May need expansion to 4,096** |
| Shadow bit | Adapter-local | Darken color when shadow bit set | Minor — can pre-process in palette |
| 256 palettes × 16 colors | Palette bank organization | Map Neo Geo palette layout | Minor |

### 3.4 External ROM / Graphics Storage

| Neo Geo function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| Sprite patterns in external ROM | SDRAM / Flash | Adapter pre-loads sprite patterns into SDRAM | **Medium** — Neo Geo expects on-demand ROM fetch; Mode0 uses VRAM/SDRAM |
| Fix patterns in external ROM | SDRAM / Flash | Pre-load Fix patterns into SDRAM | Minor |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `NEO_VRAM_DATA` | 16 bits | VRAM data read/write |
| `0x01` | `NEO_VRAM_ADDR` | 16 bits | VRAM address |
| `0x02` | `NEO_VRAM_MOD` | 16 bits | VRAM auto-increment value |
| `0x03` | `NEO_PAL_ADDR` | 16 bits | Palette RAM address (0–4095) |
| `0x04` | `NEO_PAL_DATA` | 16 bits | Palette RAM data (16-bit color) |
| `0x05` | `NEO_FIX_MAP_ADDR` | 16 bits | Fix layer tilemap address |
| `0x06` | `NEO_FIX_MAP_DATA` | 16 bits | Fix layer tilemap data |
| `0x07` | `NEO_SCB3_ADDR` | 16 bits | SCB3 (Y/height/sticky) address |
| `0x08` | `NEO_SCB3_DATA` | 16 bits | SCB3 data |
| `0x09` | `NEO_SCB4_ADDR` | 16 bits | SCB4 (X/link) address |
| `0x0A` | `NEO_SCB4_DATA` | 16 bits | SCB4 data |
| `0x0B` | `NEO_CTRL` | 8 bits | Bit 0 = display enable; bit 1 = auto-anim enable (v2) |
| `0x0C` | `NEO_IRQ_ACK` | 8 bits | Interrupt acknowledge |
| `0x0D..0x0F` | — | — | Reserved |

### 4.2 Initialization flow

1. Host selects mode `0xE` via `MODE_SELECT`
2. Host uploads sprite patterns and Fix patterns to SDRAM
3. Host writes SCB data (SCB1, SCB2, SCB3, SCB4) to VRAM
4. Host writes Fix layer tilemap
5. Host writes palette RAM (4,096 entries)
6. Host writes `NEO_CTRL` to enable display

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Sprite patterns | Variable (game-dependent) | SDRAM | 4bpp planar tiles (16×16 pixels = 128 bytes each) |
| Fix patterns | Variable | SDRAM | 4bpp planar tiles (8×8 pixels = 32 bytes each) |
| SCB1 (tile indices + attrs) | 28 KB | SDRAM / Adapter shadow | Per-tile sprite attributes |
| SCB2 (shrink) | 512 B | SDRAM / Adapter shadow | Per-sprite shrink coefficients |
| SCB3 (Y/height/sticky) | 512 B | SDRAM / Adapter shadow | Per-sprite position and height |
| SCB4 (X/link) | 512 B | SDRAM / Adapter shadow | Per-sprite position and link |
| Fix tilemap | 1.25 KB | SDRAM | 40×32 tile entries |
| Palette RAM | 8 KB | Adapter shadow / Mode0 palette | 4,096 × 16-bit entries |

### 4.4 Runtime control/update model

- **VRAM/SCB access:** Host writes address then data. Adapter translates to SDRAM.
- **Palette updates:** Write `NEO_PAL_ADDR` then `NEO_PAL_DATA`. Adapter emits palette bus writes.
- **Sprite updates:** Host modifies SCB3/SCB4 for position, SCB2 for shrink. Adapter updates descriptor shadow.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank | `RasterTriggerUnit` at last line | `STATUS_STICKY` bit 0 |
| Sprite overflow (>96/line) | `SpriteEvaluator` | Not directly detectable in Mode0 with current limits |

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| Fix layer tilemap | Adapter-local `NEO_FIX_MAP_*` | Adapter translates |
| Sprite descriptors | Adapter-local SCB shadow | Adapter emits bus writes |
| Palette entries | Adapter-local `NEO_PAL_*` | Adapter translates |
| Display enable | Adapter-local `NEO_CTRL` | Adapter translates |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- 4bpp tile decode — direct match
- Sprite evaluation (basic) — Mode0 has 64 desc
- Palette RAM — superset (may need expansion)
- Raster triggers — direct match
- Fix layer — direct match as a simple tilemap

### 5.2 What is approximate

- **Sprite count and density:** With 32 desc and 8/line, the adapter can only show a tiny fraction of Neo Geo scenes.
- **External ROM model:** Neo Geo fetches patterns on-demand from external ROM. Mode0 requires patterns in SDRAM. The adapter must pre-load all patterns.
- **Palette size:** Mode0 currently has 512 entries. Neo Geo needs 4,096. May require palette expansion.

### 5.3 What is missing entirely

- **380 sprite descriptors + 96/line:** Massive gap. Requires major substrate expansion.
- **Sprite shrinking:** Hardware scaling is a defining Neo Geo feature. No Mode0 equivalent.
- **Sticky bit grouping:** Can be handled in adapter logic but is not a primitive.
- **Auto-anim:** Hardware animation cycling. Out of scope for v1.

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| 4bpp tile decode | Shared | Already proven |
| Fix layer tilemap | Shared | Already proven |
| 380 sprite descriptors | **Shared expansion needed** | Mode0 currently 64 |
| 96 sprites/line | **Shared expansion needed** | Mode0 currently 64 |
| Sprite shrinking | Adapter-local (never?) | No Mode0 equivalent |
| 16-bit palette | Adapter-local | Mode0 uses 24-bit |
| Palette expansion (512→4096) | **Shared expansion needed** | Mode0 currently 512 entries |
| External ROM fetch | Adapter-local | Pre-load into SDRAM |

### 5.5 Realism for default bitstring

**Not realistic in default bitstring.** The Neo Geo adapter requires 380 sprites, 96/line, and sprite shrinking — all major architectural gaps. Even a limited MVP (32 sprites, 8/line, no shrinking) cannot reproduce Neo Geo's line-sprite architecture meaningfully.

Estimated cost: ~300 LUT, ~250 FF (v1 limited Fix + 32 sprites). With full expansion: ~1500+ LUT, ~1000 FF.

**Explicitly excluded from default bitstring** per `MODE0_GAP_TASKLIST.md` until sprite expansion lands.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — Fix layer + limited sprites:** Fix layer tilemap, 32 sprites, no shrinking, static sizes.
2. **v1.1 — Sprite group management:** Add sticky bit tracking and horizontal linking.
3. **v1.2 — Full sprite support:** Expand to 380 sprites / 96 per line (requires substrate expansion).
4. **v2 — Sprite shrinking (optional):** Software approximation or honest gap.

### 6.2 Prerequisite substrate tasks

- **R4.1a/b Tile+Attribute Fetch** — ✅ DONE
- **R4.1b 4bpp Planar Decode** — ✅ DONE
- **R2 Sprite Evaluator — ✅ DONE (64 desc, 32/line)
- **R1 Raster Trigger** — ✅ DONE
- **CW-1 Palette RAM** — ✅ DONE (512 entries)
- **Sprite descriptor expansion (32→380)** — ⚠️ **Required for honest v1.2**
- **Sprite per-line expansion (8→96)** — ⚠️ **Required for honest v1.2**
- **Palette expansion (512→4096)** — ⚠️ **May be required**

### 6.3 Proof plan

**Simulation:**
- `NeoGeoAdapterSim`: Test Fix layer, sprite upload, palette write
- `VdpTopSim` regression: Neo Geo-style Fix layer + limited sprites

**Hardware proof:**
- Scenario: Neo Geo title screen or static scene (e.g., `Metal Slug` title)
- Upload Fix patterns, tilemap, limited sprite set, palette via QSPI
- Verify Fix layer rendering
- 30s capture, `analyze.py` reports `freeze=0`

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~300 (v1 Fix + 32 sprites) | Under 500 |
| LUT | ~1500+ (v1.2 full) | Under 2000 |
| FF | ~250 | Under 500 |
| BSRAM | 0–1 | Under 2 |

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** Fix layer only + 32 static sprites. Proves tilemap + basic sprite pipeline.
- **v1.1:** Sprite linking and sticky bits.
- **v1.2:** 380 sprites, 96/line. Requires massive substrate expansion.
- **v2:** Sprite shrinking approximation (honest gap if too expensive).
- **Never:** Perfect line-sprite renderer, external ROM interface, auto-anim.
