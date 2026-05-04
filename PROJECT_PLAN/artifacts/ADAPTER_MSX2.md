# MSX2 Adapter — Mode0 Mapping Spec

**Version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-05-04  
**Status:** Spec drafted — pending audit  
**Platform:** MSX2 (Yamaha V9938 / MSX-VIDEO)  
**Tier:** 2 (medium)  
**Mode ID (proposed):** `0xA`

---

## 1. Platform Video Hardware Study

### 1.1 Display model

| Parameter | Value |
|---|---|
| Logical resolution | 256×192 to 512×212 (interlaced to 424 lines) |
| Color depth | 2bpp (G1/G2) / 4bpp (G4/G5) / 8bpp (G6/G7) |
| Master palette | 512 colors (9-bit RGB: 3 bits per channel) |
| Simultaneous colors | 16 (G1-G3) / 256 (G4-G7) |
| Refresh | ~60 Hz (NTSC) / ~50 Hz (PAL) |
| Aspect | Non-square pixels |

The **Yamaha V9938** (MSX-VIDEO) is a major evolution of the TMS9918A. It adds bitmap modes, hardware scroll, a command engine (blitter), and an expanded palette.

### 1.2 Layer model

**One background layer + sprites.** No multi-plane BG. No windowing.

### 1.3 Tile / bitmap / planar organization

**Screen modes:**

| Mode | Name | Resolution | Colors | Organization |
|---|---|---|---|---|
| T1 | Text 1 | 40×24 | 2 (fg/bg) | Character-based |
| T2 | Text 2 | 80×24 | 2 (fg/bg) | Character-based |
| G1 | Graphics 1 | 32×24 tiles | 16 | TMS9918A-compatible pattern |
| G2 | Graphics 2 | 32×24 tiles | 16 | TMS9918A-compatible bitmap |
| G3 | Graphics 3 | 32×24 tiles | 16 | TMS9918A-compatible pattern |
| G4 | Screen 4 | 256×212 | 16 | 4bpp bitmap (planar) |
| G5 | Screen 5 | 256×212 | 16 | 4bpp bitmap (planar) |
| G6 | Screen 6 | 512×212 | 4 | 2bpp bitmap (planar) |
| G7 | Screen 7 | 256×212 | 256 | 8bpp bitmap (RGB332) |

**Bitmap modes (G4-G7):** Planar bitplanes for G4/G5/G6, packed RGB332 for G7.
- G4/G5: 4 bitplanes (16 colors), 256×212 = 27,040 bytes per plane = ~108 KB total
- G6: 2 bitplanes (4 colors), 512×212 = ~54 KB total
- G7: 1 byte per pixel (RGB332), 256×212 = ~54 KB

**VRAM:** 128 KB (64 KB on some early MSX2 models; 128 KB standard).

### 1.4 Sprite system

**Sprite Mode 1 (TMS9918A-compatible):**
- 32 sprites, 4/line, 1 color, 8×8 or 16×16

**Sprite Mode 2 (MSX2-enhanced):**
- 32 sprites, 8/line (when enabled), multi-color per line
- Color attribute table allows different colors per sprite per line

**Sprite Attribute Table (Mode 2):** 4 bytes per sprite + 16-byte color attribute table.

### 1.5 Palette / color model

**Palette RAM:** 16 registers × 9-bit RGB (512-color space).
- Same 16-color palette as TMS9918A for G1-G3 modes
- For G4-G7, the palette is used to map 4bpp/8bpp indices to 9-bit colors

### 1.6 Scrolling model

**Hardware vertical scroll:** Dedicated register for coarse vertical scroll.
- `R#23` (vertical scroll register): offsets the display vertically
- No horizontal hardware scroll in the V9938 (added in V9958/MSX2+)

### 1.7 Raster / IRQ / beam-driven behavior

**Line interrupt:** V9938 supports a line interrupt (similar to SMS).
- `R#19` (interrupt line)
- `R#0` bit 4 = line interrupt enable

**VBlank interrupt:** Standard.

### 1.8 DMA / blitter / display-list behavior

**Command Engine (hardware blitter):**
- PSET, LINE, LMMV (fill), LMMM (copy), HMMV (high-speed fill), HMMM (high-speed copy)
- Source/destination coordinates, dimensions, logical operations
- Runs independently of CPU; status polled via S#2

**No DMA-based video fetch.** CPU writes to VRAM via data port.

### 1.9 Windowing / masking / priority rules

- **Sprite priority:** Same as TMS9918A — higher index on top
- **Sprite vs BG:** Sprites always on top of BG
- **No windowing, no masking, no color math**

### 1.10 Memory layout and addressing model

| Region | Size | Purpose |
|---|---|---|
| VRAM | 128 KB | Patterns, nametables, bitmap data, sprite patterns, sprite attributes |
| Palette | 16 × 9-bit | Color registers |

### 1.11 Timing-sensitive or identity-defining quirks

1. **Command engine independence:** The blitter runs in parallel with the CPU. Games poll S#2 bit 0 (CE) to wait for completion.
2. **No horizontal scroll:** V9938 lacks horizontal scroll. Games fake it by shifting the pattern table or using the CPU. (V9958 adds it.)
3. **Interlace mode:** G4-G7 support interlace for 424-line display. This doubles the VRAM bandwidth requirement.
4. **Sprite color attribute table:** Mode 2 sprites can have different colors on different scanlines via the color attribute table.

---

## 2. Pipeline Decomposition

| Stage | What MSX2 does | Mode0 primitive | Match quality |
|---|---|---|---|
| **Fetch** | VDC reads nametable → pattern table (G1-G3) or bitplane data (G4-G7) from VRAM | `SdramTileAttributeFetch` (G1-G3) / `PlanarLineFetch` (G4-G6) / `BitmapRowFetch` (G7) | Direct — multiple fetch paths already proven |
| **Decode** | 2bpp/4bpp/8bpp → pixel + palette lookup | Tile decoder / bitplane reconstruct | Direct |
| **Staging** | Internal shift registers | Line buffers | Direct |
| **Sprite evaluation** | 32 sprites, 4 or 8/line | `SpriteEvaluator` (R2) | Approximate — Mode0 has 32 desc/8 per line. **Sprite Mode 2 color attribute table not in Mode0** |
| **Composition** | BG + sprites | `FourLayerCompositor` | Direct |
| **Palette** | 16-entry × 9-bit palette RAM | CW-1 palette RAM (24-bit entries) | Direct — Mode0 palette is superset |
| **Beam/raster** | Line interrupt + VBlank | `RasterTriggerUnit` (R1) | Direct |
| **Host/control** | CPU writes VDP registers + VRAM port + command engine | Adapter shadow + bus emitter + command engine proxy | Medium — command engine needs adapter-local handling |

---

## 3. Mode0 Mapping

### 3.1 Background layer

| MSX2 function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| G1-G3 pattern modes | `SdramTileAttributeFetch` | TMS9918A-compatible mapping | Direct |
| G4-G6 bitmap (4bpp/2bpp planar) | `PlanarLineFetch` | Set planar mode; configure bitplane base addresses | Direct — planar fetch proven |
| G7 bitmap (8bpp RGB332) | `BitmapRowFetch` | Set packed 8bpp mode | Direct — bitmap fetch proven |
| Hardware vertical scroll | `layer0ScrollY` | Map `R#23` to scroll Y | Direct |
| No horizontal scroll | N/A | N/A | V9938 has no H-scroll; adapter documents as honest gap |

### 3.2 Sprite layer

| MSX2 function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| 32 sprites | `SpriteEvaluator` (32 desc) | Direct match | None |
| 8 sprites/line (Mode 2) | `SpriteEvaluator` (8/line limit) | Direct match | None |
| Sprite Mode 2 color attr table | N/A | **Gap:** Mode0 has no per-line sprite color attribute table | Medium — each sprite line can have a different color in MSX2 |
| Sprite-0 hit / overflow | `STATUS_STICKY` | Direct map | None |

### 3.3 Palette

| MSX2 function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| 16-entry 9-bit palette | CW-1 palette RAM | Map V9938 palette to Mode0 entries 0..15 | Direct |
| G7 RGB332 direct color | Mode0 24-bit palette | Map RGB332 → 24-bit via palette or direct | Minor — Mode0 can store RGB332 as 24-bit with truncation |

### 3.4 Command Engine (Blitter)

| MSX2 function | Mode0 primitive | Adapter responsibility | Gap / risk |
|---|---|---|---|
| PSET, LINE, LMMV, LMMM, HMMV, HMMM | `BlitterEngine` (Task 49) | Map V9938 command engine ops to Mode0 blitter | Medium — V9938 command set differs from Mode0 blitter; adapter needs translation layer or honest gap |
| Command polling (S#2 CE bit) | Adapter-local status | Adapter tracks command completion | Minor — can be faked with timers or host polling |

### 3.5 Raster / IRQ

| MSX2 function | Mode0 primitive | Adapter responsibility | Notes |
|---|---|---|---|
| Line interrupt | `RasterTriggerUnit` | Map `R#19` to `rasterTriggerLine` | Direct |
| VBlank interrupt | `RasterTriggerUnit` at last line | Direct map | Direct |

---

## 4. MCU-Visible Adapter Contract

### 4.1 Register map (adapter-local)

| Offset | Name | Width | Description |
|---|---|---|---|
| `0x00` | `MSX2_R0` | 8 bits | Mode / control register 0 |
| `0x01` | `MSX2_R1` | 8 bits | Mode / control register 1 |
| `0x02` | `MSX2_R2` | 8 bits | Pattern name table base |
| `0x03` | `MSX2_R3` | 8 bits | Color table base |
| `0x04` | `MSX2_R4` | 8 bits | Pattern generator base |
| `0x05` | `MSX2_R5` | 8 bits | Sprite attribute table base |
| `0x06` | `MSX2_R6` | 8 bits | Sprite pattern generator base |
| `0x07` | `MSX2_R7` | 8 bits | Text / backdrop color |
| `0x08` | `MSX2_R8` | 8 bits | Color register 0 (palette) |
| `0x09` | `MSX2_R9` | 8 bits | Color register 1 (palette) |
| `0x0A` | `MSX2_R10` | 8 bits | Color register 2 (palette) |
| `0x0B` | `MSX2_R11` | 8 bits | Color register 3 (palette) |
| `0x0C` | `MSX2_R18` | 8 bits | Vertical scroll register |
| `0x0D` | `MSX2_R19` | 8 bits | Interrupt line |
| `0x0E` | `MSX2_VRAM_ADDR` | 16 bits | VRAM address |
| `0x0F` | `MSX2_VRAM_DATA` | 8 bits | VRAM data |
| `0x10` | `MSX2_CMD_SX` | 16 bits | Command source X |
| `0x11` | `MSX2_CMD_SY` | 16 bits | Command source Y |
| `0x12` | `MSX2_CMD_DX` | 16 bits | Command destination X |
| `0x13` | `MSX2_CMD_DY` | 16 bits | Command destination Y |
| `0x14` | `MSX2_CMD_NX` | 16 bits | Command width |
| `0x15` | `MSX2_CMD_NY` | 16 bits | Command height |
| `0x16` | `MSX2_CMD_CLR` | 8 bits | Command color/data |
| `0x17` | `MSX2_CMD_ARG` | 8 bits | Command argument (direction, logic) |
| `0x18` | `MSX2_CMD_OPCODE` | 8 bits | Command opcode (trigger) |
| `0x19` | `MSX2_CMD_STATUS` | 8 bits | Read-only: command executing flag |

### 4.2 Initialization flow

1. Host selects mode `0xA` via `MODE_SELECT`
2. Host uploads pattern table, nametable/color table, and sprite data to SDRAM
3. Host writes palette registers
4. Host sets mode registers (R0-R1) for desired screen mode
5. Host writes `MSX2_R1` to enable display

### 4.3 Asset upload expectations

| Asset | Size | Destination | Format |
|---|---|---|---|
| Pattern table | 2–6 KB (G1-G3) | SDRAM | 1bpp/2bpp tiles |
| Nametable + Color table | 768–6 KB | SDRAM | Tile indices + color sets |
| Bitmap data (G4-G7) | 54–108 KB | SDRAM | Planar or packed pixels |
| Sprite patterns | 2 KB | SDRAM | 1bpp sprite tiles |
| Sprite attributes | 128 bytes | Adapter shadow / SDRAM | 32 × 4-byte descriptors |

### 4.4 Runtime control/update model

- **VRAM access:** Host writes `MSX2_VRAM_ADDR` then `MSX2_VRAM_DATA`. Adapter translates to SDRAM.
- **Palette updates:** Write `MSX2_R8`–`MSX2_R11`. Adapter emits palette bus writes.
- **Scroll updates:** Write `MSX2_R18`. Adapter emits `layer0ScrollY` bus write.
- **Command engine:** Host writes command parameters then opcode. Adapter translates to Mode0 `BlitterEngine` or executes locally.

### 4.5 Status/IRQ/readback expectations

| Status | Source | Mode0 mapping |
|---|---|---|
| VBlank | `RasterTriggerUnit` | `STATUS_STICKY` bit 0 |
| Line interrupt | `RasterTriggerUnit` at programmed line | `STATUS_STICKY` bit 0 |
| Sprite overflow | `SpriteEvaluator` | `STATUS_STICKY` bit 1 |
| Command executing | Adapter-local | `MSX2_CMD_STATUS` bit 0 |

### 4.6 Native Mode0 global control vs adapter-local control

| Control | Owner | How set |
|---|---|---|
| `VDP_TILE_MODE` / `VDP_BITMAP_MODE` | Adapter-local `MSX2_R0/R1` | Adapter translates mode bits |
| `layer0ScrollY` | Adapter-local `MSX2_R18` | Adapter translates |
| Palette entries | Adapter-local `MSX2_R8-R11` | Adapter translates |
| Sprite descriptors | Adapter-local shadow | Adapter emits bus writes |
| Raster trigger | Adapter-local `MSX2_R19` | Direct output |
| Blitter commands | Adapter-local command regs | Adapter translates to `BlitterEngine` |

---

## 5. Honest Gaps

### 5.1 What Mode0 supports well

- Tile+attribute fetch (G1-G3) — direct match
- Planar bitmap fetch (G4-G6) — direct match
- Packed bitmap fetch (G7) — direct match
- Sprite evaluation (32 desc, 8/line) — direct match for Mode 2
- Palette RAM — superset
- Raster IRQ — direct match
- Vertical scroll — direct match

### 5.2 What is approximate

- **Horizontal scroll:** V9938 has no H-scroll. Mode0 has `layer0ScrollX`. The adapter can offer it as a convenience, but it's not authentic MSX2 behavior.
- **Command engine:** Mode0 `BlitterEngine` (Task 49) has a different command set than V9938. Mapping is approximate. Some V9938 commands (line drawing with logical ops) may not map cleanly.
- **Interlace:** Mode0 does not currently support interlaced output. G4-G7 interlace modes (424 lines) cannot be reproduced exactly.

### 5.3 What is missing entirely

- **Sprite Mode 2 color attribute table:** MSX2 allows per-line sprite colors via a 16-byte color attribute table. Mode0 has no equivalent.
- **Command engine exact semantics:** V9938 command engine supports complex logical operations and VRAM-to-VRAM copies with transparency. Mode0 blitter may not support all operations.
- **Text modes (T1/T2):** 40×24 and 80×24 text modes require character generator support. Mode0 tiles are 8×8 graphics, not text characters.

### 5.4 Adapter-local vs shared substrate

| Feature | Verdict | Rationale |
|---|---|---|
| Tile+attr fetch (G1-G3) | Shared | Already proven |
| Planar bitmap fetch (G4-G6) | Shared | Already proven |
| Packed bitmap fetch (G7) | Shared | Already proven |
| 32 sprites / 8 per line | Shared | Direct match |
| 9-bit palette | Adapter-local | Mode0 uses 24-bit |
| Command engine | Adapter-local | Mode0 blitter differs |
| Vertical scroll | Shared | Already proven |
| Raster IRQ | Shared | Already proven |

### 5.5 Realism for default bitstream

**Realistic with caveats.** MSX2 is medium complexity. The command engine and sprite color attribute table are the main gaps. For G4-G7 bitmap modes, the adapter is quite honest. For G1-G3 tile modes, it's fully honest (TMS9918A-compatible).

Estimated cost: ~250 LUT, ~200 FF (G1-G3 / G4-G7 basic). Command engine adds ~150 LUT if mapped.

---

## 6. Development Plan

### 6.1 Adapter development order

1. **v1 — MSX2 tile modes (G1-G3):** TMS9918A-compatible display + MSX2-enhanced sprites + vertical scroll + line interrupt.
2. **v1.1 — Bitmap modes (G4-G7):** Add planar/packed bitmap fetch paths.
3. **v2 — Command engine:** Map V9938 commands to Mode0 blitter or adapter-local execution.

### 6.2 Prerequisite substrate tasks

- **R4.1a/b Tile+Attribute Fetch** — ✅ DONE
- **R7.1 Planar Line Fetch** — ✅ DONE
- **R4.1d Bitmap Row Fetch** — ✅ DONE
- **R2 Sprite Evaluator** — ✅ DONE (32 desc, 8/line)
- **R1 Raster Trigger** — ✅ DONE
- **CW-1 Palette RAM** — ✅ DONE
- **Task 49 BlitterEngine** — ⚠️ **Required for v2 command engine**

### 6.3 Proof plan

**Simulation:**
- `Msx2AdapterSim`: Test mode switch, scroll, palette, sprite upload, line interrupt
- `VdpTopSim` regression: MSX2-style tile scene and bitmap scene

**Hardware proof:**
- Scenario: MSX2 demo scene (e.g., `Space Manbow` title or `Aleste` static screen)
- Upload tile/bitmap data, palette, sprites via QSPI
- Verify scroll and raster effects
- 30s capture, `analyze.py` reports `freeze=0`

### 6.4 Resource / stop-line concerns

| Metric | Estimate | Stop-line |
|---|---|---|
| LUT | ~250 (v1 tile modes) | Under 500 |
| LUT | ~400 (v1.1 + bitmap) | Under 700 |
| LUT | ~550 (v2 + command engine) | Under 900 |
| FF | ~200 | Under 500 |
| BSRAM | 0–1 | Under 2 |

### 6.5 Minimum viable adapter vs higher-fidelity follow-ons

- **MVP (v1):** G1-G3 tile modes + 32 sprites + vertical scroll + line interrupt.
- **v1.1:** Add G4-G7 bitmap modes.
- **v2:** Add command engine proxy.
- **Never:** Interlace output, perfect text mode character generator, V9958 horizontal scroll.
