# NES / Famicom Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-28  
**Status:** Spec drafted — pending audit  
**Platform:** Nintendo Entertainment System / Famicom  
**Tier:** 2 (medium)  
**Mode ID (proposed):** `0x3`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 256×240 pixels (PAL); 256×224 visible (NTSC) |
| Color depth | 2bpp tiles + attribute-selected palettes |
| Master palette | 64 colors (6-bit: 2 bits per channel, with emphasis bits) |
| Simultaneous colors | 25 (4 BG palettes × 3 colors + 4 sprite palettes × 3 colors + 1 backdrop) |
| Refresh | ~60.1 Hz (NTSC) / ~50.0 Hz (PAL) |
| Aspect | Non-square; 8:7 pixel aspect on NTSC |

The **PPU (Picture Processing Unit)** — Ricoh RP2C02 (NTSC) or RP2C07 (PAL) — is a custom IC that generates composite video from tile, sprite, and palette data. It operates at 3× the CPU clock (~5.37 MHz NTSC).

### 1.2 Layer model

The NES has **two layers**:

1. **Background (BG)** — tilemap-based, scrollable as a single plane
2. **Sprites (OBJ)** — up to 64 sprites, 8 per scanline

Sprites can appear **behind or in front of** the background on a per-sprite basis.

### 1.3 Tile / bitmap / planar organization

**Tiles:** 8×8 pixels, 2bpp (planar, NES-style).
- 2 bitplanes per tile: bitplane 0 (low) + bitplane 1 (high)
- Each bitplane is 8 bytes; total 16 bytes per tile
- Pattern tables: 2 × 4KB = 8KB total (256 tiles × 16 bytes × 2 tables)
  - Pattern Table 0: `$0000-$0FFF` (typically background tiles)
  - Pattern Table 1: `$1000-$1FFF` (typically sprite tiles)

**Background layer:**
- **Nametables:** 4 × 1KB logical, 2KB physical VRAM
  - Each nametable: 32×30 tiles = 960 bytes
  - Attribute table: 64 bytes per nametable (2×2 tile palette assignment)
- Only 2KB VRAM on console → 2 physical nametables
  - Remaining 2 are "mirrors" (point to physical tables)
  - Cartridge controls mirroring: horizontal, vertical, or 4-screen (with extra VRAM)

**Attribute table layout:**
- Each byte controls a 32×32 pixel block (4×4 tiles)
- 2 bits per 16×16 pixel quadrant within the block
- This means palette selection has **16×16 pixel granularity** (2×2 tiles)

### 1.4 Sprite system

**OAM (Object Attribute Memory):** 256 bytes DRAM, internal to PPU.
- 64 sprites × 4 bytes each:
  - Byte 0: Y position (vertical, `$00-$EF` visible, `$F0-$FF` off-screen)
  - Byte 1: Tile index (0..255, selects from pattern table)
  - Byte 2: Attributes:
    - Bits 1:0 = palette (0..3)
    - Bit 2 = priority (0=front, 1=behind BG)
    - Bit 3 = flip horizontal
    - Bit 4 = flip vertical
    - Bit 5 = sprite size (0=8×8, 1=8×16) — when `PPUCTRL[5]=1`
  - Byte 3: X position

**Sprite size:**
- Default: 8×8 pixels (tile from one pattern table)
- Optional: 8×16 pixels (`PPUCTRL[5]=1`) — top half from tile index, bottom from tile index+1; pattern table selected by bit 0 of tile index

**Sprite limits:**
- 64 sprites total per frame
- **8 sprites per scanline** — excess sprites are dropped (not drawn)
- Sprite overflow flag: set if >8 sprites on a line

**Sprite-0 hit:**
- When a non-transparent pixel of sprite 0 overlaps a non-transparent BG pixel, the `SPRITE_0_HIT` flag sets.
- Used for raster splits (e.g., status bars) without IRQ support.

**Sprite evaluation:**
- Cycles 1-256: BG tile fetching + rendering
- Cycles 257-320: Sprite evaluation + OAM loading
- Cycles 321-340: Prefetch next scanline

### 1.5 Palette / color model

Palette RAM: 32 bytes at `$3F00-$3F1F` in PPU address space.

| Address | Purpose |
|---|---|
| `$3F00` | Universal backdrop color |
| `$3F01-$3F03` | Background palette 0 (colors 1-3; color 0 = transparent/backdrop) |
| `$3F04` | Mirror of backdrop |
| `$3F05-$3F07` | Background palette 1 |
| `$3F08` | Mirror of backdrop |
| `$3F09-$3F0B` | Background palette 2 |
| `$3F0C` | Mirror of backdrop |
| `$3F0D-$3F0F` | Background palette 3 |
| `$3F10` | Mirror of backdrop (writing here also writes `$3F00`) |
| `$3F11-$3F13` | Sprite palette 0 |
| `$3F14` | Mirror of backdrop |
| `$3F15-$3F17` | Sprite palette 1 |
| `$3F18` | Mirror of backdrop |
| `$3F19-$3F1B` | Sprite palette 2 |
| `$3F1C` | Mirror of backdrop |
| `$3F1D-$3F1F` | Sprite palette 3 |

**Color encoding:** 6 bits (`xxBBGGRR`) referencing 64-color master palette.  
**Color emphasis:** `PPUMASK` bits 7:5 modify all colors simultaneously (tint R/G/B).

### 1.6 Scrolling model

**Single-plane scroll:** The entire background can scroll as one unit.
- `PPUSCROLL` (write twice): X scroll, then Y scroll
- Fine scroll: pixel-level (0..7 within a tile, then tile-level)
- **No per-tile or per-column scroll** on the base NES
- Some mappers add IRQ-based scanline counters for scroll splits

**Nametable selection:** `PPUCTRL[1:0]` selects base nametable (0..3), which also controls coarse scroll wrap-around.

**4-screen mirroring:** With cartridge VRAM, all 4 nametables are unique. This enables seamless scrolling in both directions without mirroring artifacts.

### 1.7 Raster / IRQ / beam-driven behavior

**VBlank NMI:**
- Triggered at start of scanline 241 (NTSC)
- Standard mechanism for CPU to update PPU state safely

**Sprite-0 hit:**
- Used for raster splits when no IRQ is available
- CPU polls `PPUSTATUS[6]` in a busy loop
- When hit occurs, CPU knows it has reached a specific scanline

**No dedicated raster IRQ on base NES.** Some cartridge mappers (MMC3, etc.) add scanline counters that trigger CPU IRQs.

### 1.8 DMA / blitter / display-list behavior

**OAM DMA:**
- CPU writes `$4014` with page number → PPU DMA copies 256 bytes from CPU RAM to OAM
- Takes ~513 CPU cycles; halts CPU during transfer
- Must happen during VBlank to avoid OAM corruption

**No blitter.** No display-list processor. The PPU is a fixed-function scanline renderer.

### 1.9 Windowing / masking / priority rules

**Sprite priority:**
- Per-sprite priority bit (attribute byte bit 2)
- 0 = sprite in front of BG
- 1 = sprite behind BG non-transparent pixels
- Sprite-to-sprite priority: lower OAM index wins (earlier sprites are on top)

**Background priority:**
- BG tile attribute does not have priority; BG is always the bottom layer
- Some mappers add BG priority per-tile via CHR-ROM banking tricks

**No windowing / masking hardware.** No color math.

### 1.10 Memory layout and addressing model

| Region | Size | Location | Purpose |
|---|---|---|---|
| Pattern Tables | 8 KB | Cartridge CHR-ROM/RAM | Tile graphics (2bpp planar) |
| Nametables | 2 KB | Console VRAM | Tile maps (2 physical tables) |
| Attribute tables | 64 bytes × 2 | End of each nametable | 2×2 tile palette assignment |
| Palette RAM | 32 bytes | PPU internal | 8 palettes + backdrop |
| OAM | 256 bytes | PPU internal | 64 sprite descriptors |

### 1.11 Timing-sensitive or identity-defining quirks

1. **Attribute table granularity:** Palette selection is per-16×16-pixel block (2×2 tiles), not per-tile. This is a visible limitation that NES artists work around.
2. **Sprite flicker:** Games intentionally cycle sprite order each frame to work around the 8/line limit, producing flicker.
3. **OAM decay:** OAM is DRAM and decays if PPU rendering is disabled for too long. The CPU must DMA fresh OAM each frame.
4. **VBlank timing:** All PPU updates (except palette) must happen during VBlank. Writing during active display causes visual corruption.
5. **Palette mirroring:** `$3F00`, `$3F04`, `$3F08`, `$3F0C`, `$3F10` all share the backdrop color. Writing to any updates all.
6. **Color emphasis:** `PPUMASK` emphasis bits tint the entire screen — used for fade effects and underwater scenes.

---

## 2. Pipeline Decomposition

| Stage | What the NES does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | PPU fetches nametable byte → attribute byte → 2 pattern table bytes per tile | `SdramTileAttributeFetch` (R4.1a/b) + `SdramTileFetch` | Direct — tile+attr fetch proven |
| **Decode** | 2bpp planar → 4-color pixel + palette select | `BitmapFetch` / tile decoder | Direct — NES 2bpp planar is same as R4.1b |
| **Staging** | Internal shift registers hold current + next tile | Tile fetch pipeline buffers | Direct |
| **Sprite evaluation** | Per-scanline: check OAM Y vs scanline, load 8 sprites into secondary OAM | `SpriteEvaluator` (R2) | Approximate — Mode0 has 64 desc/32 per line; NES has 64 desc/8 per line. **Gap: CLOSED for desc count (64≥64); per-line limit (32≥8) closed.** |
| **Composition** | BG pixel + up to 8 sprite pixels → priority mux → palette index | `FourLayerCompositor` (Task 48) | Direct — 1 BG layer + sprite layer |
| **Palette** | 32-byte palette RAM → 6-bit color | CW-1 palette RAM (24-bit entries) | Direct — Mode0 palette is superset |
| **Beam/raster** | VBlank NMI at line 241; sprite-0 hit for split | `RasterTriggerUnit` (R1) + sprite-0 hit flag | Direct — raster IRQ proven; sprite-0 hit proven (Task 29) |
| **Host/control** | CPU writes PPU registers / OAM DMA | Adapter shadow + bus emitter | Direct — thin translation layer |

---

## 3. Mode0 Mapping

### 3.1 Background layer

| NES function | Mode0 primitive | Adapter responsibility | MCU action |
|---|---|---|---|
| 256×240 tile background | `SdramTileAttributeFetch` (tile+attr) | Set `VDP_TILE_MODE=0x01` (NES planar), `VDP_ATTR_MODE=1` (2×2 packing) | Upload nametable + attributes to SDRAM |
| 8×8 2bpp tiles | Pattern tables in CHR-ROM/RAM | `SdramTileFetch` reads 2bpp planar tiles | Upload pattern table to SDRAM |
| 2×2 attribute packing | `VDP_ATTR_MODE=1` | Adapter sets attribute mode | Host uploads attribute table |
| Scroll X/Y | `layer0ScrollX/Y` | Map `PPUSCROLL` writes to scroll regs | Write scroll values |
| Nametable select | `VDP_TILE_MODE` / scroll wrap | Map `PPUCTRL[1:0]` to nametable base + coarse scroll | Write base nametable |
| 4-screen mirroring | Scroll wrap + dual nametable bases | Adapter sets nametable bases for quadrants | Configure mirroring mode |

### 3.2 Sprite layer

| NES function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 64 sprites | `SpriteEvaluator` (64 desc) | Map OAM to sprite descriptors | **Gap: Mode0 has 64 desc; NES needs 64** |
| 8 sprites/scanline | `SpriteEvaluator` (8/line limit) | Same limit — direct match | None |
| Sprite overflow | `STATUS_STICKY` bit 1 | Direct map | None — proven |
| Sprite-0 hit | `STATUS_STICKY` bit 4 | Direct map | None — Task 29 proven |
| 8×8 or 8×16 sprites | `SpriteEvaluator` descriptor format | Map OAM tile index + size bit to descriptor | Minor — descriptor word layout differs |
| Sprite flip X/Y | Sprite descriptor flags | Map OAM attribute bits to descriptor flags | None |
| Sprite priority (behind BG) | `PixelMetadata` priority bit | Map OAM priority bit | None — compositor supports priority |
| OAM DMA | Host CPU copies OAM to adapter shadow | Adapter shadow RAM mirrors OAM; bus emits descriptor writes | No DMA in Mode0; host must write descriptors individually |

**Critical gap:** The 32→64 sprite descriptor expansion is required for honest NES sprite support. Without it, the adapter can only support 32 sprites (half the NES limit). This is acceptable for an MVP but must be documented as an honest gap.

### 3.3 Palette

| NES function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 8 palettes (4 BG + 4 sprite) | CW-1 palette RAM | Map `$3F00-$3F1F` to palette entries | Mode0 has 256 entries; NES uses ~32 |
| Backdrop color mirroring | Palette write handler | Writing any backdrop mirror updates entry 0 | Adapter must replicate mirroring behavior |
| Color emphasis | Global tint | Map `PPUMASK[7:5]` to global color adjustment | **Gap:** Mode0 has no global tint/emphasis. Adapter can approximate by rewriting all palette entries, but this is expensive. Out of scope for v1. |

### 3.4 Raster / IRQ

| NES function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| VBlank NMI | `RasterTriggerUnit` at line 241 | Map `PPUCTRL[7]` (NMI enable) to `rasterTriggerEnable` | Direct |
| Sprite-0 hit raster split | `RasterTriggerUnit` + sprite-0 hit | Host polls `STATUS_STICKY[4]` or uses raster IRQ at line Y | NES typically uses busy-polling; adapter can offer either |
| Mapper IRQ (MMC3 etc.) | `RasterTriggerUnit` | Cartridge mapper IRQs are outside PPU scope | Out of scope — mapper-specific |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `NES_PPUCTRL` | 8 bits | `$2000` equivalent: base nametable, increment mode, sprite pattern table, BG pattern table, sprite size, master/slave, NMI enable |
| `0x01` | `NES_PPUMASK` | 8 bits | `$2001` equivalent: grayscale, show left 8px BG/sprites, enable BG/sprites, color emphasis R/G/B |
| `0x02` | `NES_PPUSTATUS` | 8 bits | Read-only: vblank, sprite-0 hit, sprite overflow |
| `0x03` | `NES_OAMADDR` | 8 bits | OAM write address |
| `0x04` | `NES_OAMDATA` | 8 bits | OAM write data |
| `0x05` | `NES_PPUSCROLL` | 16 bits | Scroll X (8 bits) + Scroll Y (8 bits) |
| `0x06` | `NES_PPUADDR` | 16 bits | PPU address latch (for `$2006/$2007` VRAM access) |
| `0x07` | `NES_PPUDATA` | 8 bits | PPU data read/write |
| `0x08` | `NES_PAL_ADDR` | 8 bits | Palette RAM address ($00-$1F) |
| `0x09` | `NES_PAL_DATA` | 8 bits | Palette RAM data (6-bit color) |
| `0x0A` | `NES_OAM_DMA` | 8 bits | OAM DMA page trigger (writing initiates 256-byte DMA from CPU RAM) |
| `0x0B..0x0F` | — | — | Reserved |

### 4.2 Initialization flow

1. Host selects mode `0x3` via `MODE_SELECT`
2. Host uploads pattern tables, nametables, and attribute tables to SDRAM
3. Host writes palette entries via `NES_PAL_ADDR` + `NES_PAL_DATA`
4. Host writes sprite descriptors to OAM shadow via `NES_OAMADDR` + `NES_OAMDATA` (or `NES_OAM_DMA`)
5. Host sets scroll via `NES_PPUSCROLL`
6. Host writes `NES_PPUCTRL` (base nametable, NMI enable)
7. Host writes `NES_PPUMASK` (enable BG + sprites)

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Pattern Table 0 (BG) | 4 KB | SDRAM | 2bpp planar tiles (256 tiles × 16 bytes) |
| Pattern Table 1 (Sprites) | 4 KB | SDRAM | 2bpp planar tiles |
| Nametable + Attribute | 1 KB per table | SDRAM | 960 bytes nametable + 64 bytes attribute |
| Palette | 32 bytes | Adapter shadow / Mode0 palette RAM | 32 × 6-bit entries |
| OAM | 256 bytes | Adapter shadow | 64 × 4-byte descriptors |

### 4.4 Runtime control/update model

- **PPU register writes:** Direct shadow update + immediate bus emit for scroll, layer enable, etc.
- **Palette updates:** Write `NES_PAL_ADDR` then `NES_PAL_DATA`. Adapter emits palette bus writes.
- **OAM updates:** Write `NES_OAMADDR` then `NES_OAMDATA` per byte. For bulk upload, host writes `NES_OAM_DMA` with a page number; adapter performs 256 consecutive reads from CPU RAM and updates shadow.
- **VRAM access:** `NES_PPUADDR` + `NES_PPUDATA` emulate the NES VRAM port. Adapter translates to SDRAM reads/writes.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank | `RasterTriggerUnit` at line 241 | `STATUS_STICKY` bit 0 (`RASTER_MATCH`) + optional NMI |
| Sprite-0 hit | `SpriteEvaluator` | `STATUS_STICKY` bit 4 (`SPRITE_0_HIT`) |
| Sprite overflow | `SpriteEvaluator` | `STATUS_STICKY` bit 1 (`SPRITE_OVERFLOW`) |
| PPUSTATUS read | Adapter shadow | Combined from sticky bits + live vblank state |

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| `VDP_TILE_MODE` (NES planar) | Adapter-local `NES_PPUCTRL` | Adapter translates |
| `VDP_ATTR_MODE` (2×2 packing) | Adapter-local `NES_PPUCTRL` | Adapter translates |
| `layer0ScrollX/Y` | Adapter-local `NES_PPUSCROLL` | Adapter translates |
| `LAYER_ENABLE` (BG + sprites) | Adapter-local `NES_PPUMASK` | Adapter translates |
| Palette entries | Adapter-local `NES_PAL_*` | Adapter translates |
| Sprite descriptors | Adapter-local OAM shadow | Adapter emits bus writes to sprite descriptor RAM |
| Raster trigger | Adapter-local `NES_PPUCTRL[7]` | Direct output to `RasterTriggerUnit` |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- Tile+attribute fetch (R4.1a/b) — direct match for NES background
- 2bpp planar decode (R4.1b) — exact match for NES tiles
- 2×2 attribute packing (R4.1c) — exact match for NES attribute tables
- Sprite evaluation (R2) — 8/line limit matches NES; overflow and sprite-0 hit proven
- Palette RAM (CW-1) — superset of NES 6-bit palette
- Raster IRQ (R1) — VBlank and line-match proven

### 5.2 What is approximate

- **Sprite count:** Mode0 has 64 descriptors; NES has 64. The adapter can support 32 sprites (half the NES limit) without substrate expansion. This is acceptable for many games but not all.
- **OAM DMA:** Mode0 has no DMA engine for sprite descriptors. The host must write descriptors individually via the bus. This is slower than NES OAM DMA but functionally equivalent.
- **Color emphasis:** Mode0 has no global tint register. The adapter can approximate by rewriting all palette entries, but this is not efficient and is out of scope for v1.

### 5.3 What is missing entirely

- **64 sprite descriptors:** Requires substrate expansion (32→64). Estimated cost: +200-400 LUT depending on implementation.
- **Mapper IRQs:** MMC3 and other mapper scanline counters are cartridge-specific. Out of scope.
- **PPU open bus behavior:** NES PPU has open-bus read quirks. Out of scope.
- **PAL/NTSC timing differences:** Mode0 uses a fixed HDMI output timing. The adapter does not reproduce NES composite signal artifacts (dot crawl, color artifacts).

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| Tile+attr fetch | Shared | Already proven |
| 2bpp planar decode | Shared | Already proven |
| 64 sprite descriptors | **Direct match** | Mode0 currently 64; NES needs 64 |
| Sprite-0 hit / overflow | Shared | Already proven |
| 6-bit palette | Adapter-local | Mode0 palette is 24-bit; adapter maps NES 6-bit values |
| Color emphasis | Adapter-local (v2?) | No Mode0 equivalent |
| OAM DMA | Adapter-local | Mode0 has no DMA for descriptors; adapter emulates with bus writes |

### 5.5 Realism for default bitstream

**Realistic with caveat.** The NES adapter is medium complexity. The only substrate blocker is sprite descriptor count. With 32 descriptors (half NES limit), the adapter is viable as an MVP. With 64 descriptors, it is fully honest.

Estimated cost: ~250 LUT, ~200 FF. Well within Tang Nano 20K headroom.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — Basic NES display:** Tile+attr background, 32 sprites (half limit), palette, scroll, sprite-0 hit, VBlank NMI.
2. **v1.1 — Full OAM:** Expand to 64 sprites (requires substrate task or adapter-local secondary OAM).
3. **v2 — Enhanced features:** Color emphasis approximation, mapper IRQ passthrough (optional).

### 6.2 Prerequisite substrate tasks

- **R4.1a/b Tile+Attribute Fetch** — ✅ DONE
- **R4.1b 2bpp Planar Decode** — ✅ DONE
- **R4.1c 2×2 Attribute Packing** — ✅ DONE
- **R2 Sprite Evaluator — ✅ DONE (64 desc, 32/line)
- **R1 Raster Trigger** — ✅ DONE
- **Task 29 Sprite-0 Hit** — ✅ DONE
- **Sprite descriptor expansion (32→64)** — ⚠️ **Required for honest v1.1**

### 6.3 Proof plan

**Simulation:**
- `NesAdapterSim`: Test PPU register mapping, scroll, palette, sprite descriptor upload, sprite-0 hit
- `VdpTopSim` regression: NES-style tile+attr scene with 32 sprites

**Hardware proof:**
- Scenario: NES test pattern or simple homebrew demo (e.g., `Alter Ego` title screen or `NESert Golfing`)
- Upload pattern tables, nametable, attributes, palette, and OAM via QSPI
- Verify scroll, sprites, and palette
- 30s capture, `analyze.py` reports `freeze=0`
- Sprite-0 hit proof: status bar at fixed Y using sprite-0 hit raster split

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~250 (v1, 32 sprites) | Under 500 |
| LUT | ~450 (v1.1, 64 sprites) | Under 800 |
| FF | ~200 | Under 500 |
| BSRAM | 0–1 (OAM shadow) | Under 2 |

The 32-sprite v1 is well within budget. The 64-sprite v1.1 needs the substrate expansion first.

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** 32 sprites, no color emphasis, no mapper IRQs. Proves tile+attr + sprite pipeline on a real platform.
- **v1.1:** 64 sprites (honest NES limit). Requires substrate expansion.
- **v2:** Color emphasis, optional mapper IRQ passthrough.
- **Never:** Cycle-accurate PPU timing, PAL/NTSC composite artifacts, open-bus behavior.
