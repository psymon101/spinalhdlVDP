# TMS9918-Family Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-28  
**Status:** Spec drafted — pending audit  
**Platforms covered:**
- Base: TMS9918A (ColecoVision / SG-1000 / MSX1-class)
- Delta A: Sega Master System (Mode 4 VDP)
- Delta B: Sega Game Gear  
**Tier:** 1 (base) / 2 (SMS/GG)  
**Mode IDs (proposed):** `0x4` = TMS9918-family base / `0x5` = Master System / `0x6` = Game Gear

---

## Family Architecture Rationale

The Sega Master System VDP and Game Gear VDP are direct evolutionary descendants of the Texas Instruments TMS9918A. They retain backwards compatibility with TMS9918A modes (Modes 0-3) while adding an enhanced native mode (Mode 4 for SMS/GG). This family document covers the **TMS9918A base architecture** first, then documents **platform-specific deltas** for SMS and GG.

---

# Part A — TMS9918A Base Architecture

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Resolution | 256×192 pixels (all modes except Text) |
| Refresh | ~59.9 Hz (NTSC) / ~49.7 Hz (PAL) |
| Colors | 16 fixed colors (15 visible + 1 transparent) |
| Palette | **Fixed** — not programmable. 16 pre-defined colors. |

The TMS9918A generates composite video directly. It has its own **16KB VRAM** on a private bus, separate from CPU memory.

### 1.2 Layer model

**36 hardware layers** (from back to front):
1. External video input (genlock — unused on most systems)
2. Background color (border)
3. Background pattern layer
4. Sprite layer 0 (lowest priority)
5. ...
6. Sprite layer 31 (highest priority)

Sprites are **monochrome** (1 color + transparent). Background tiles are **2-color** (foreground + background) in Graphics modes.

### 1.3 Tile / bitmap / planar organization

**Tiles ("Patterns"):** 8×8 pixels, 1bpp (2 colors) in Graphics modes.
- Pattern table: 256 tiles × 8 bytes = 2KB
- Each byte = 1 scanline of the tile (8 pixels, 1 bit each)
- Color is selected per group of 8 tiles (Graphics I) or per 8-pixel row within a tile (Graphics II)

**Screen modes:**

| Mode | Resolution | Colors | Tiles | Sprites | Use case |
|---|---|---|---|---|---|
| Mode 0 (Text) | 40×24 chars (240×192) | 2 colors global | 256 6×8 chars | None | Text only |
| Mode 1 (Graphics I) | 32×24 tiles (256×192) | 2 colors per 8-tile group | 256 8×8 tiles | 32 | Most games |
| Mode 2 (Graphics II) | 32×24 tiles (256×192) | 2 colors per 8-pixel row | 768 8×8 tiles | 32 | Detailed graphics |
| Mode 3 (Multicolor) | 64×48 blocks | 1 color per 4×4 block | N/A | 32 | Rarely used |

**VRAM layout (Mode 1 — most common):**

| Region | Size | Content |
|---|---|---|
| Pattern Table | 2KB | 256 tile graphics |
| Color Table | 32 bytes | 8 color sets (2 colors each, for groups of 8 tiles) |
| Name Table | 768 bytes | 32×24 tile indices |
| Sprite Attribute Table | 128 bytes | 32 sprites × 4 bytes |
| Sprite Pattern Table | 2KB | 256 sprite tile graphics |

**VRAM layout (Mode 2 — Bitmap):**
- Pattern Table: 6KB (768 tiles × 8 bytes) — 3 independent pattern tables
- Color Table: 6KB (768 color sets × 8 bytes) — 3 independent color tables
- Name Table: 768 bytes
- Sprite tables: same as Mode 1

**Critical:** In Mode 2, each 8-pixel row within a tile can have its own 2-color pair. This enables near-bitmap quality within the 2-color-per-row constraint.

### 1.4 Sprite system

| Parameter | Value |
|---|---|
| Count | 32 sprites |
| Per scanline | 4 sprites maximum |
| Size | 8×8 or 16×16 (global setting via register) |
| Magnification | 2× zoom (global setting) — makes sprites 16×16 or 32×32 |
| Colors | 1 color per sprite + transparent |
| Priority | Higher sprite index = higher priority (drawn on top) |
| Collision | Hardware collision flag (any two non-transparent sprite pixels overlap) |

**Sprite Attribute Table (128 bytes, 4 bytes per sprite):**

| Byte | Content |
|---|---|
| 0 | Y position |
| 1 | X position |
| 2 | Pattern index (tile number) |
| 3 | Color / Early Clock bit |

**Early Clock:** If set, sprite is shifted 32 pixels left. Used for partial off-screen positioning.

**Sprite Y=$D0:** Terminates sprite list early (sprites after this index are not processed).

### 1.5 Palette / color model

The TMS9918A has a **fixed 16-color palette**. Colors cannot be changed.

| Index | Color |
|---|---|
| 0 | Transparent |
| 1 | Black |
| 2 | Medium Green |
| 3 | Light Green |
| 4 | Dark Blue |
| 5 | Light Blue |
| 6 | Dark Red |
| 7 | Cyan |
| 8 | Medium Red |
| 9 | Light Red |
| 10 | Dark Yellow |
| 11 | Light Yellow |
| 12 | Dark Green |
| 13 | Magenta |
| 14 | Gray |
| 15 | White |

### 1.6 Scrolling model

**No hardware scrolling.** The TMS9918A has no scroll registers.
- Coarse scroll: Change Name Table base address to point to different tilemap region
- Smooth scroll: Software-only — redraw the entire name table each frame

### 1.7 Raster / IRQ / beam-driven behavior

- **No raster interrupts.** The TMS9918A has no line-counter or beam-compare logic.
- **VBlank interrupt:** Available via status register bit 7. CPU polls or uses external interrupt logic.
- **No HBlank interrupt.**

### 1.8 DMA / blitter / display-list behavior

**No DMA, no blitter, no display-list.** The CPU writes bytes to VRAM through the VDP data port. The VDP auto-increments its internal address register after each write.

### 1.9 Windowing / masking / priority rules

- **Sprite priority:** Higher index sprites are drawn on top of lower index sprites.
- **Sprite vs background:** Sprites are always drawn on top of the background (except transparent pixels).
- **No windowing, no masking, no color math.**

### 1.10 Memory layout and addressing model

| Region | Size | CPU access |
|---|---|---|
| VRAM | 16KB | Via VDP data port only (auto-increment) |
| VDP registers | 8 write-only registers | Via VDP control port |
| VDP status | 1 read-only register | Via VDP control port |

**VDP Registers:**

| Reg | Name | Function |
|---|---|---|
| R0 | Mode Control 1 | M3, M4, External video enable |
| R1 | Mode Control 2 | 16×16 sprites, magnify, screen enable, interrupt enable |
| R2 | Name Table Base | Upper 4 bits of name table address in VRAM |
| R3 | Color Table Base | Upper 8 bits of color table address (Mode 1) or upper 4 bits (Mode 2) |
| R4 | Pattern Generator Base | Upper 5 bits of pattern table address |
| R5 | Sprite Attribute Base | Upper 7 bits of sprite attribute table address |
| R6 | Sprite Pattern Base | Upper 3 bits of sprite pattern table address |
| R7 | Backdrop Color | 4-bit color index for border/transparent areas |

### 1.11 Timing-sensitive or identity-defining quirks

1. **Fixed palette:** The iconic TMS9918A look comes from its specific 16 fixed colors. Any adapter must use this exact palette for authenticity.
2. **4-sprite-per-line limit:** Lower than most contemporaries. Games use sprite multiplexing (repositioning sprites during frame) or simply accept the limit.
3. **No scroll:** The lack of hardware scroll is a defining limitation. Games either don't scroll (static screens) or use coarse tilemap updates.
4. **Mode 2 bitmap:** The 2-color-per-8-pixel-row constraint in Mode 2 produces a distinctive "color-clash" look similar to ZX Spectrum but at coarser granularity.
5. **Sprite Y=$D0 terminator:** An off-screen sprite at Y=$D0 stops all further sprite processing. This is used to hide unused sprites without rewriting the attribute table.

---

## 2. Pipeline Decomposition (TMS9918A)

| Stage | What TMS9918A does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | VDP reads name table → pattern table → color table from private VRAM | `SdramTileAttributeFetch` + `SdramTileFetch` | Direct — tile+attr fetch |
| **Decode** | 1bpp pattern + 2-color color set → 2-color pixel | Tile decoder with 1bpp mode | Direct — Mode0 supports 1bpp |
| **Staging** | Internal shift register | Tile pipeline buffers | Direct |
| **Sprite evaluation** | 32 sprites, 4/line, monochrome | `SpriteEvaluator` (R2) | Approximate — Mode0 has 32 desc/8 per line. TMS has 32 desc/4 per line. **Sprite color is 1-color (monochrome) vs Mode0 multi-color** |
| **Composition** | BG + sprites (priority by index) | `FourLayerCompositor` | Direct — L0 = BG, sprite layer on top |
| **Palette** | 16 fixed colors | CW-1 palette RAM | Direct — load fixed palette at init |
| **Beam/raster** | VBlank only | `RasterTriggerUnit` at line 0 or last line | Approximate — no mid-frame raster IRQ on TMS |
| **Host/control** | CPU writes VDP registers + VRAM port | Adapter shadow + bus emitter | Direct |

---

## 3. Mode0 Mapping (TMS9918A)

### 3.1 Background layer

| TMS function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 256×192 tile background | `SdramTileAttributeFetch` + `SdramTileFetch` | Set tile mode to 1bpp, configure name table base | Mode0 `VDP_TILE_MODE` may need 1bpp tile decode mode |
| 2-color tiles (Mode 1) | Tile decoder with color table lookup | Map TMS Color Table to Mode0 palette bank selection | Each 8-tile group gets 2 colors from fixed palette |
| 2-color-per-row (Mode 2) | Tile decoder with per-row color | Map 3 independent pattern+color tables | More complex memory layout |
| 40×24 text (Mode 0) | `SdramTileFetch` with 6×8 font | Map text mode to tile fetch with 6-wide chars | Mode0 may not support 6×8 directly — approximate with 8×8 and masking |
| 64×48 multicolor (Mode 3) | `SdramTileFetch` with 4×4 blocks | Map to 8×8 tiles with repeated pixels | Rarely used; low priority |

### 3.2 Sprite layer

| TMS function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 32 sprites | `SpriteEvaluator` (32 desc) | Direct match | None |
| 4 sprites/scanline | `SpriteEvaluator` (8/line limit) | Mode0 limit is 8/line — superset | None (adapter can enforce 4/line if needed for authenticity) |
| 1 color per sprite | `SpriteEvaluator` paletteBank | Set sprite palette to single color per descriptor | Minor — Mode0 sprites use multi-color palette; adapter sets paletteBank to force 1-color |
| Sprite collision | `STATUS_STICKY` bit | Hardware collision not in Mode0 | **Gap:** Mode0 has no hardware sprite-sprite collision. Adapter can approximate in software or leave as honest gap. |
| 16×16 / magnified sprites | `SpriteEvaluator` descriptor | Map TMS sprite size/magnify bits to descriptor dimensions | Minor — Mode0 supports variable sizes via descriptor |

### 3.3 Palette

| TMS function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 16 fixed colors | CW-1 palette RAM | Load canonical TMS palette into entries 0..15 at init | Direct — palette is ROM-like, never changes |
| Transparent (index 0) | Alpha/transparency in compositor | Ensure compositor treats palette entry 0 as transparent | Direct |

### 3.4 Raster / IRQ

| TMS function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| VBlank status | `RasterTriggerUnit` + `STATUS_STICKY` | Map VBlank to `RASTER_MATCH` | Direct |
| No raster IRQ | N/A | N/A | TMS has no raster IRQ; adapter does not need to emulate one |

---

## 4. MCU-Visible Adapter Contract (TMS9918A Base)

### 4.1 Register map

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `TMS_R0` | 8 bits | Mode Control 1 (M3, M4, external video) |
| `0x01` | `TMS_R1` | 8 bits | Mode Control 2 (16×16 sprites, magnify, screen enable, IRQ enable) |
| `0x02` | `TMS_R2` | 8 bits | Name Table Base Address [13:10] |
| `0x03` | `TMS_R3` | 8 bits | Color Table Base Address |
| `0x04` | `TMS_R4` | 8 bits | Pattern Generator Base Address [13:11] |
| `0x05` | `TMS_R5` | 8 bits | Sprite Attribute Table Base Address [13:7] |
| `0x06` | `TMS_R6` | 8 bits | Sprite Pattern Generator Base Address [13:11] |
| `0x07` | `TMS_R7` | 8 bits | Backdrop / Text color |
| `0x08` | `TMS_VRAM_ADDR` | 16 bits | VRAM read/write address |
| `0x09` | `TMS_VRAM_DATA` | 8 bits | VRAM read/write data |
| `0x0A` | `TMS_STATUS` | 8 bits | Read-only: interrupt flag, 5th sprite flag, collision flag |
| `0x0B..0x0F` | — | — | Reserved |

### 4.2 Initialization flow

1. Host selects mode `0x4` via `MODE_SELECT`
2. Host uploads pattern table, color table, name table, sprite patterns, sprite attributes to SDRAM
3. Host writes VDP registers (R0-R7) to configure mode and table bases
4. Host writes `TMS_R1[6]=1` to enable screen

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Pattern Table | 2KB (Mode 1) or 6KB (Mode 2) | SDRAM | 1bpp tiles (8 bytes each) |
| Color Table | 32 bytes (Mode 1) or 6KB (Mode 2) | SDRAM | 2-color sets |
| Name Table | 768 bytes | SDRAM | 32×24 tile indices |
| Sprite Patterns | 2KB | SDRAM | 1bpp sprite tiles |
| Sprite Attributes | 128 bytes | Adapter shadow / SDRAM | 32 × 4-byte descriptors |

### 4.4 Runtime control/update model

- **VRAM access:** Host writes `TMS_VRAM_ADDR` then `TMS_VRAM_DATA`. Adapter translates to SDRAM read/write.
- **Register updates:** Direct shadow update; adapter emits bus writes for global Mode0 registers.
- **Sprite updates:** Write sprite attribute table in VRAM or adapter shadow.

### 4.5 Status/IRQ/readback

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank / interrupt | `RasterTriggerUnit` | `STATUS_STICKY` bit 0 |
| 5th sprite flag | `SpriteEvaluator` overflow | `STATUS_STICKY` bit 1 |
| Collision flag | Not in Mode0 | **Gap** — no hardware sprite-sprite collision |

---

## 5. Honest Gaps (TMS9918A)

### 5.1 Well-supported
- Tile+attribute fetch, 1bpp decode, sprite evaluation, palette RAM, VBlank timing.

### 5.2 Approximate
- **Text mode (40×24):** Mode0 tiles are 8×8. 6×8 text mode may need approximation with 8×8 tiles and masking, or the adapter can restrict to 32×24 Graphics modes.
- **Sprite monochrome:** Mode0 sprites are multi-color via palette. Adapter can force single-color by setting all palette entries in a bank to the same color.

### 5.3 Missing
- **Hardware sprite-sprite collision:** Mode0 has no equivalent. Out of scope.
- **Composite video artifacts:** TMS9918A produces NTSC composite with color artifacts. Mode0 outputs HDMI RGB. Out of scope.

### 5.4 Realism for default bitstream
**Fully realistic.** TMS9918A adapter is very low cost (~150 LUT). Good candidate for default bitstream.

---

# Part B — Master System VDP Delta

## 6. Master System VDP Enhancements over TMS9918A

| Feature | TMS9918A | Master System VDP (Mode 4) |
|---|---|---|
| Resolution | 256×192 | 256×192, 256×224, 256×240 |
| Colors | 16 fixed | 32 from 64-color palette (6-bit RGB) |
| Tile colors | 2 colors per tile (or per row) | 16 colors per tile (4bpp) |
| Sprites | 32 total, 4/line, 1 color | 64 total, 8/line, 15 colors |
| Sprite size | 8×8 or 16×16 | 8×8 or 8×16 |
| Scroll | None | Hardware X/Y scroll |
| Raster IRQ | None | Line interrupts |
| Tile flip | None | H/V flip per tile |
| Tile priority | None | BG tile priority over sprites |
| VRAM bus | 8-bit | 16-bit |

### 6.1 Mode 4 — Native SMS mode

Mode 4 is enabled by setting bit 2 of VDP Register 0. This is the mode used by virtually all SMS games.

**Background layer (Mode 4):**
- 32×28 tilemap (256×224 logical; 32×24 = 256×192 visible)
- Each tile: 8×8 pixels, 4bpp (16 colors)
- Each tile entry in name table: **2 bytes**
  - Bits 8:0 = tile index (0..511)
  - Bit 9 = horizontal flip
  - Bit 10 = vertical flip
  - Bit 11 = palette select (0 = palette 0, 1 = palette 1)
  - Bit 12 = priority (1 = tile in front of sprites)
  - Bits 15:13 = unused (can be used by game engine)

**VRAM layout (Mode 4, canonical):**

| Region | Size | Address Range |
|---|---|---|
| Pattern Table (BG + sprites) | 16KB | `$0000-$3FFF` |
| Name Table | 1.75KB | `$3800-$3EFF` |
| Sprite Attribute Table | 256 bytes | `$3F00-$3FFF` |

**CRAM (Color RAM):**
- 32 entries × 6-bit RGB = 64 colors total
- Palette 0: entries 0..15 (for BG tiles with palette bit = 0)
- Palette 1: entries 16..31 (for BG tiles with palette bit = 1, AND for all sprites)
- Sprites can ONLY use palette 1 (entries 16..31)

**Important CRAM quirk:** SMS VDP CRAM is write-only. CPU cannot read it back.

### 6.2 Sprite system (Mode 4)

| Parameter | Value |
|---|---|
| Count | 64 sprites |
| Per scanline | 8 sprites maximum |
| Size | 8×8 or 8×16 (global setting via register 1 bit 0) |
| Colors | 15 colors + transparent (from palette 1, entries 16..31) |
| Priority | Lower index sprites drawn on top (same as TMS9918A) |
| Y coordinate | `$D0` = terminate sprite list (same as TMS9918A) |
| Zoom | 2× zoom available (register 1 bit 1) |

**Sprite Attribute Table (256 bytes, 4 bytes per sprite):**

| Byte | Content |
|---|---|
| 0 | Y position |
| 1 | X position |
| 2 | Pattern index (tile number) |
| 3 | Unused on SMS (on GG: bits 7:4 = X MSB for 10-bit X) |

**Note:** SMS sprites do NOT have flip or palette select per-sprite. They always use palette 1.

### 6.3 Scrolling (Mode 4)

| Register | Function |
|---|---|
| R8 | Scroll X (horizontal) — 8-bit pixel scroll |
| R9 | Scroll Y (vertical) — 8-bit pixel scroll |
| R0 bit 7 | Line interrupt enable |
| R10 | Line counter (for line interrupts) |

**Scroll lock:** Register 0 bit 5 locks the top 8 rows from scrolling (useful for status bars).

**Line interrupts:** When enabled, the VDP triggers an interrupt every time the scanline counter (R10) reaches zero. The counter decrements each scanline and auto-reloads. This allows raster effects (palette swaps, scroll changes) at any scanline.

### 6.4 Mode0 Mapping (SMS Mode 4)

| SMS function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 256×192/224/240 tile BG | `SdramTileAttributeFetch` + `SdramTileFetch` | Set 4bpp tile mode; configure 32×28 name table | None — tile+attr proven |
| 4bpp tiles (16 colors) | `SdramTileFetch` with 4bpp decode | Tile mode may need 4bpp planar encoding | Minor — verify Mode0 tile decoder supports 4bpp planar |
| 2 palettes × 16 colors | CW-1 palette RAM | Map CRAM to palette entries; palette 0 = entries 0..15, palette 1 = entries 16..31 | None |
| 64 sprites, 8/line | `SpriteEvaluator` (32 desc) | **Gap:** Mode0 has 32 desc; SMS needs 64. MVP with 32 acceptable. | Medium — same gap as NES |
| Sprite 15 colors | `SpriteEvaluator` + paletteBank | Set sprite paletteBank to palette 1 | None |
| Hardware scroll X/Y | `layer0ScrollX/Y` | Map R8/R9 to scroll regs | Direct |
| Line interrupts | `RasterTriggerUnit` | Map R10 line counter to triggerLine | Direct |
| Tile flip H/V | Tile descriptor / `SdramTileFetch` | Map name table bits 9/10 to flip flags | Direct — Mode0 supports tile flip |
| Tile priority | `PixelMetadata` priority bit | Map name table bit 12 to priority | Direct — compositor supports priority |
| Left column mask | WindowUnit or clip | R0 bit 5 locks top 8 rows; first column can be blanked | Minor — can approximate with window |

---

## 7. MCU-Visible Adapter Contract (SMS Mode 4)

### 7.1 Register map (SMS VDP)

SMS uses the same 11 VDP registers as TMS9918A plus additional registers:

| Reg | Name | Description |
|---|---|---|
| R0 | Mode Control 1 | Mode 4 enable (bit 2), line IRQ enable (bit 7), sprite shift (bit 3) |
| R1 | Mode Control 2 | Screen enable, IRQ enable, 8×16 sprites (bit 0), sprite zoom (bit 1) |
| R2 | Name Table Base | [13:11] shifted (value × $400) |
| R3 | — | Unused in Mode 4 |
| R4 | — | Unused in Mode 4 |
| R5 | Sprite Attribute Base | [13:7] shifted (value × $80) |
| R6 | Sprite Pattern Base | [13:11] shifted (value × $800) |
| R7 | Backdrop Color | 6-bit color from palette 1 (entries 16..31) |
| R8 | Scroll X | Horizontal scroll value |
| R9 | Scroll Y | Vertical scroll value |
| R10 | Line Counter | Line interrupt counter |

Plus VRAM data port and CRAM data port (distinguished by top 2 bits of address = `11` for CRAM).

### 7.2 Initialization flow

1. Host selects mode `0x5` via `MODE_SELECT`
2. Host uploads pattern table, name table, sprite patterns, sprite attributes to SDRAM
3. Host writes CRAM (32 palette entries)
4. Host writes VDP registers R0-R10
5. Host sets `R1[6]=1` to enable screen

---

# Part C — Game Gear Delta

## 8. Game Gear VDP Enhancements over SMS

The Game Gear VDP is **functionally identical** to the SMS VDP (Mode 4) with these differences:

| Feature | Master System | Game Gear |
|---|---|---|
| Resolution | 256×192 | **160×144** (window into 256×192) |
| Colors | 32 from 64 (6-bit) | 32 from **4096 (12-bit RGB)** |
| CRAM size | 32 bytes (32 × 6-bit) | 64 bytes (32 × 12-bit) |
| CRAM format | 1 byte per entry | **2 bytes per entry** (12-bit color) |
| Start button | N/A (Pause = NMI) | Dedicated Start button register |
| PSG | Mono | Stereo |

### 8.1 Viewport behavior

The Game Gear LCD is 160×144 pixels. The VDP still renders a full 256×192 (or 256×224/240) frame internally, but only the **center 160×144 window** is visible.

- Horizontal: pixels 48..207 of 256 (160 visible)
- Vertical: pixels 24..167 of 192 (144 visible) — roughly; exact centering varies

This means:
- Sprites outside the visible window are still processed (and count toward the 8/line limit)
- Games designed for GG may place objects in the "hidden" border area
- Master System games running on GG via adapter show the full frame scaled down

### 8.2 CRAM / palette differences

**Master System CRAM write:**
- Write address with top 2 bits = `11` → selects CRAM
- 1 byte per write = 6-bit color (`00BBGGRR`)

**Game Gear CRAM write:**
- Write address with top 2 bits = `11` → selects CRAM
- **2 bytes per write** = 12-bit color (`0000BBBBGGGGRRRR`)
- Little-endian: low byte first, then high byte

**Adapter implication:** The GG adapter must write 2 bytes per CRAM entry. The SMS adapter writes 1 byte per entry. The CRAM address auto-increments after each write.

### 8.3 Mode0 Mapping (Game Gear)

| GG function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 160×144 viewport | WindowUnit or crop | Define visible window within 256×192 render | Direct — `WIN0_X/Y` can clip to 160×144 |
| 12-bit palette | CW-1 palette RAM | Map 12-bit GG colors to 24-bit Mode0 palette | Direct — Mode0 palette is superset |
| Same tiles/sprites as SMS | Same as SMS | Same mapping as SMS Mode 4 | Direct |
| Start button | Host GPIO / status reg | Separate register read | Out of VDP scope |

---

## 9. Shared Honest Gaps (Family-wide)

### 9.1 Well-supported
- Tile+attribute fetch, sprite evaluation, palette RAM, raster triggers, scroll.

### 9.2 Approximate
- **Sprite count (SMS/GG):** Mode0 has 32 descriptors; SMS/GG need 64. MVP with 32 acceptable.
- **TMS9918A text mode:** 6×8 characters not natively supported by Mode0 tiles. Approximate with 8×8.
- **Game Gear viewport:** Mode0 window can clip, but the "hidden border" behavior (sprites still evaluated outside window) may not be perfectly replicated.

### 9.3 Missing
- **Hardware sprite collision (TMS):** No Mode0 equivalent.
- **SMS/GG sprite zoom:** Mode0 may not support 2× zoomed sprites directly. Adapter can use larger pattern tiles or scale in compositor.
- **Line interrupt auto-reload (SMS):** TMS9918A has no line counter. SMS line counter auto-reloads. `RasterTriggerUnit` may need periodic reprogramming or auto-reload feature.

### 9.4 Realism for default bitstream

| Platform | Realism |
|---|---|
| TMS9918A (Coleco/MSX1/SG-1000) | ✅ Fully realistic — ~150 LUT |
| Master System (Mode 4) | ✅ Realistic with 32-sprite MVP; honest with 64-sprite expansion (~250 LUT) |
| Game Gear | ✅ Realistic with 32-sprite MVP; viewport clipping adds ~20 LUT |

---

## 10. Development Plan

### 10.1 Order

1. **v1 — TMS9918A base:** Graphics I/II modes, 32 sprites, fixed palette, no scroll.
2. **v1.1 — SMS Mode 4:** Add 4bpp tiles, hardware scroll, line interrupts, tile flip/priority.
3. **v1.2 — Game Gear:** Add 12-bit palette, 160×144 viewport window.

### 10.2 Prerequisites

- R4.1a/b/c Tile+Attribute Fetch — ✅ DONE
- R2 Sprite Evaluator — ✅ DONE (32 desc; 64 needed for honest SMS/GG)
- R1 Raster Trigger — ✅ DONE
- CW-1 Palette RAM — ✅ DONE
- Mode0 tile decoder 4bpp support — ⚠️ Verify before SMS v1.1

### 10.3 Proof plan

**TMS9918A:** ColecoVision or MSX1 static screen (e.g., `Antarctic Adventure` title)
**SMS:** `Sonic the Hedgehog` title screen or `Alex Kidd` static scene
**GG:** `Sonic Triple Trouble` or `Shining Force Gaiden` title

### 10.4 Resource estimates

| Platform | LUT | FF | BSRAM |
|---|---|---|---|
| TMS9918A | ~150 | ~80 | 0 |
| SMS Mode 4 | ~250 | ~150 | 0 |
| Game Gear | ~270 | ~160 | 0 |

---

## 11. Platform Summary Table

| Feature | TMS9918A | SMS Mode 4 | Game Gear |
|---|---|---|---|
| Resolution | 256×192 | 256×192/224/240 | 160×144 window |
| Tile depth | 1bpp (2 colors) | 4bpp (16 colors) | 4bpp (16 colors) |
| Palette | 16 fixed | 32 from 64 (6-bit) | 32 from 4096 (12-bit) |
| Sprites | 32, 4/line, 1 color | 64, 8/line, 15 colors | 64, 8/line, 15 colors |
| Scroll | None | Hardware X/Y | Hardware X/Y |
| Raster IRQ | None | Line counter | Line counter |
| Tile flip | None | H/V | H/V |
| Tile priority | None | Yes | Yes |
| Estimated LUT | ~150 | ~250 | ~270 |
