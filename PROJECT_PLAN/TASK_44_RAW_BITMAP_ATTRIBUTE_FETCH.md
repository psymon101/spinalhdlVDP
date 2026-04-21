# Task 44 — Raw Bitmap + Attribute Fetch Primitive

**Status:** Artifact phase  
**depends_on:** [17, 32a]  
**scope_boundary:** Raw bitmap + attribute fetch only. No platform-exact register maps, no blitter, no full adapter semantics.  
**delivers:**

- Linear bitmap fetch path suitable for bitmap-first adapters
- Attribute overlay / color-source path for bitmap+attribute display models
- Register-bus controlled base addresses and mode controls
- Stable SDRAM layout contract for bitmap rows and attribute rows

**validation:**

- Sim: bitmap + attribute scenes prove row fetch, attribute application, and palette selection
- Hardware: visible bitmap+attribute proof on Tang Nano 20K with 30s OpenCV stability analysis

---

## 1. Goal

Add a **linear bitmap fetch primitive** so that Mode0 can render non-tiled bitmap modes (ZX Spectrum, C64 hires/multicolor) in addition to the existing tile-engine substrate. Today every fetch path assumes a tile map indirection: `tileMap[tx,ty] → tileRow[tileIdx, rowInTile]`. Task 44 introduces a direct linear row fetch: `pixelByte[row, col/8]` with a parallel attribute/color lookup per cell.

This is a hard prerequisite for:
- **ZX Spectrum adapter** — the ULA is fundamentally bitmap+attribute, not tile-based.
- **C64 bitmap modes** — VIC-II hires and multicolor modes use a linear screen buffer + color RAM.

---

## 2. Scope

### 2.1 In scope

1. **Bitmap fetch engine** — new `BitmapFetch` module or extension to `SdramTileAttributeFetch`:
   - Linear row buffer read from SDRAM (not tile-map indirection).
   - Configurable bits-per-pixel: 1bpp (Spectrum, C64 hires), 2bpp (C64 multicolor).
   - Byte-aligned row stride; host programs base address and bytes-per-row.
   - Ping-pong line buffer output compatible with existing L0 pixel interface.
2. **Attribute fetch path** — parallel SDRAM row fetch for cell attributes:
   - One attribute byte per N×M cell (e.g. 8×8 for Spectrum, 8×8 for C64).
   - Attribute applied to all pixels in the cell.
   - Separate base address from bitmap data.
3. **Pixel reconstruction** — bitmap bits → `pixelIndex` + `paletteBank` compatible with existing palette path:
   - 1bpp: bit → index {0,1}; attribute supplies palette bank.
   - 2bpp: bit-pair → index {0..3}; attribute supplies palette bank.
4. **Register bus controls** — host-programmable base addresses and mode:
   - Bitmap base address (32-bit, split across two 16-bit registers)
   - Attribute base address (32-bit, split across two 16-bit registers)
   - Mode / dimension register (bpp, cell width, enable)
5. **Sim proof** — Sc44a (Spectrum-style 1bpp + attr), Sc44b (C64 hires-style 1bpp + color), Sc44c (C64 multicolor 2bpp).
6. **Hardware proof** — Tang Nano 20K visible proof with 30s OpenCV stability.

### 2.2 Out of scope (deferred)

- Full ZX Spectrum or C64 adapter register map — this is substrate only.
- Blitter / copy engine — Task 49.
- Flash-attribute blink (ZX Spectrum) — firmware-level animation, not a fetch primitive.
- Non-byte-aligned bitmap widths — keep stride byte-aligned for simplicity.
- Sprite changes — Task 45 handles sprite capacity.
- Horizontal / vertical fine scroll on bitmap layer — can reuse existing ScrollWrap if host updates base address; no new scroll hardware in this task.

---

## 3. Architecture

### 3.1 Current state (tile-based fetch only)

```
SdramTileAttributeFetch:
  tileMap[tx,ty] → tileIdx
  attrMap[tx,ty] → {bank, priority}
  tileRow[tileIdx, rowInTile] → pixel data
  Output: pixelIndex(4), pixelPaletteBank(3), pixelPriority(1)
```

### 3.2 Target state (Task 44 adds bitmap fetch)

```
BitmapFetch (new, or mode inside existing fetch engine):
  bitmapBase + row * rowStride + col/8  → pixel byte
  attrBase  + row * attrStride + col/cellW → attribute byte
  
  Pixel decode (1bpp):
    bit = pixelByte(col/8)[7 - (col%8)]
    pixelIndex = bit ? attr.ink : attr.paper
    paletteBank = attr.bright ? brightBank : normalBank
    
  Pixel decode (2bpp / C64 multicolor):
    pair = pixelByte(col/8)[7-2*(col%4) : 6-2*(col%4)]
    pixelIndex = f(pair, attr)  // table lookup from attribute
    paletteBank = attr.bank

  Output: pixelIndex(4), pixelPaletteBank(3), pixelPriority(1)
```

### 3.3 Integration boundary

- **Data source**: SDRAM, read via the existing scheduler slot mechanism (reuse `fetchGrant`/`fetchSlotValid`).
- **Output**: feeds the existing `layer0SdramPixel` / `layer0SdramBank` / `layer0SdramPriority` mux in `VdpTop`.
- **Mode selection**: `VDP_TILE_MODE` or a new `BITMAP_CTRL` register selects tile vs bitmap fetch path. Default = tile (backward compat).
- **Line buffer**: reuse the existing ping-pong buffer architecture or instantiate a second one for bitmap rows.

---

## 4. Implementation Plan

### 4.1 HDL changes

1. **`BitmapFetch.scala` (new)** — Linear bitmap + attribute fetch engine:
   - SDRAM-domain FSM: reads one row of bitmap bytes + one row of attribute bytes.
   - Pixel-domain: unpacks bytes into individual pixels, applies attribute per cell.
   - Supports 1bpp and 2bpp decode paths via mode register.
   - Ping-pong line buffer output matching `SdramTileAttributeFetch` pixel interface.
   - Scheduler coupling: accepts `fetchGrant`/`fetchSlotValid`/`fetchPreAnnounce`.

2. **`VdpTop.scala` (diff)** — Source mux extension:
   - Add `bitmapEnableReg` (safe-boundary committed).
   - When bitmap enable = 1, route `BitmapFetch.io.pixel*` to `layer0Sdram*` instead of `SdramTileAttributeFetch.io.pixel*`.
   - When bitmap enable = 0, existing tile path unchanged (zero regression).

3. **Mode0 register bus decode** — new block `0x0350..0x0355`:
   | Address | Name | Bits | Description |
   |---|---|---|---|
   | `0x0350` | `BITMAP_CTRL` | [15:0] | enable[0], bpp[2:1] (0=1bpp, 1=2bpp), cellWidth[6:3] (log2, e.g. 3=8px), reserved |
   | `0x0351` | `BITMAP_BASE_LO` | [15:0] | Low 16 bits of bitmap SDRAM base |
   | `0x0352` | `BITMAP_BASE_HI` | [15:0] | High 7 bits of bitmap SDRAM base (bits [22:16]) |
   | `0x0353` | `ATTR_BASE_LO` | [15:0] | Low 16 bits of attribute SDRAM base |
   | `0x0354` | `ATTR_BASE_HI` | [15:0] | High 7 bits of attribute SDRAM base |
   | `0x0355` | `BITMAP_STRIDE` | [15:0] | Bytes per bitmap row |
   | `0x0356` | `ATTR_STRIDE` | [15:0] | Bytes per attribute row |
   | `0x0357..0x035F` | — | — | Reserved for Task 44 expansion |

4. **`MODE0_REGISTER_BUS_SPEC.md` (diff)** — Document new block.

5. **`BitmapFetchSim.scala` (new)** — Unit sim:
   - Program bitmap base with test pattern (checkerboard).
   - Program attribute base with alternating ink/paper.
   - Verify pixel output matches expected decode.

### 4.2 Data model

**SDRAM layout (host-programmable base addresses):**

```
Bitmap region @ BITMAP_BASE:
  row 0: byte[0] .. byte[rowStride-1]   // left to right
  row 1: byte[rowStride] .. byte[2*rowStride-1]
  ...

Attribute region @ ATTR_BASE:
  row 0: byte[0] .. byte[attrStride-1]   // one attr per cell
  row 1: byte[attrStride] .. byte[2*attrStride-1]
  ...
```

**Attribute byte format (Spectrum-style default):**
```
  bit [7]    : flash (ignored by hardware; firmware can animate)
  bit [6]    : bright
  bit [5:3]  : paper color (3 bits → palette bank offset)
  bit [2:0]  : ink color (3 bits → palette bank offset)
```

**C64 hires attribute byte format (alternative mode):**
```
  bit [7:4]  : background color
  bit [3:0]  : foreground color
```

### 4.3 Register / bus impact

- New contiguous block: `0x0350..0x035F` (16 addresses, fits in existing reserved space).
- Safe-boundary commit for all control registers (same shadow + `hCounter===0` pattern).
- No change to existing tile-path register addresses.

### 4.4 Validation plan

**Checkpoint A — Simulation:**
- `BitmapFetchSim`: 1bpp checkerboard + alternating attributes → correct pixelIndex/bank output.
- `BitmapFetchSim`: 2bpp multicolor pattern → correct pixelIndex/bank output.
- `VdpTopSim` regression: tile-mode scenes still pass with `bitmapEnable=0`.
- `UnifiedRegMapSim`: register writes to `0x0350..0x0355` propagate correctly.

**Checkpoint B — Hardware:**
- Sc44a: ZX Spectrum-style screen — 1bpp bitmap + 8×8 attributes. Visible color blocks.
- Sc44b: C64 hires-style — 1bpp bitmap + per-cell color. Visible pattern with two colors per cell.
- Sc44c: C64 multicolor-style — 2bpp bitmap + per-cell color. Visible 4-color cells.
- 30-second capture + OpenCV stability analysis for each.
- Regression: existing tile-mode Sc8 still passes.

---

## 5. Deliverables

| File / Path | Purpose |
|---|---|
| `hw/spinal/spinalhdlvdp/BitmapFetch.scala` (new) | Linear bitmap + attribute fetch engine |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` (diff) | Bitmap/tile source mux + register decode |
| `sim/` test additions | `BitmapFetchSim` + regression proof |
| `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` (diff) | Document `0x0350..0x035F` block |
| `PROJECT_PLAN/TASK_44_RAW_BITMAP_ATTRIBUTE_FETCH.md` | This artifact |

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Two fetch engines (tile + bitmap) compete for SDRAM bandwidth | Reuse existing scheduler slots; bitmap fetch uses same grant/slotValid as tile fetch. Only one engine is active per frame. |
| Bitmap row stride doesn't match display width | `BITMAP_STRIDE` is host-programmable; display width is fixed at 640. Host sets stride to match source bitmap (e.g. 32 for Spectrum, 40 for C64). |
| Attribute byte format incompatible across platforms | Define one default format (Spectrum) and one alternative (C64). Future adapter tasks can add format select bits. |
| 2bpp bit ordering differs between C64 and other platforms | `BITMAP_CTRL.bpp` selects decode function; C64 multicolor bit-pair ordering is isolated in one mux. |
| Regression in existing tile path | Bitmap enable defaults to 0; tile path is completely unchanged when disabled. |

---

## 7. Dependencies

- **Task 17 (Shuffled Fetch Path)** — DONE. Proves the fetch engine can support multiple decode modes.
- **Task 32a (Mode0 Register Bus: Spec & Naming Lock)** — DONE. Provides the address allocation rules and safe-boundary commit pattern.
- **Task 32b (Mode0 Register Bus: Master Refactor)** — DONE. Bus decode path is stable.
- **Task 30 (Pre-Announced Arbiter Grant)** — DONE. Scheduler slot mechanism proven; bitmap fetch reuses it.

---

## 8. Open Questions

1. **Fetch engine integration**: Should `BitmapFetch` be a standalone module, or a mode inside `SdramTileAttributeFetch`?
   - *Recommendation: standalone module.* It has fundamentally different addressing (linear vs tile-mapped) and keeping it separate avoids complicating the proven tile fetch path.
2. **Display scaling**: ZX Spectrum is 256×192, C64 is 320×200. Our output is 640×480.
   - *Recommendation: host sets bitmap dimensions via registers; hardware displays the bitmap at 1:1 pixel scale centered or top-left in the 640×480 frame. Scaling is a future compositor feature, not this task.*
3. **Multiple bitmap layers**: Could L1 also be a bitmap layer?
   - *Recommendation: defer. Task 44 enables L0 bitmap only. L1 remains the existing on-chip BasicPatternSource or tile fetch.*
4. **Attribute flash (Spectrum)**: Should hardware blink ink/paper at 1.5 Hz?
   - *Recommendation: no. Flash is a firmware animation feature. The hardware supplies the attribute byte; firmware can toggle the flash bit and re-upload.*

---

## 9. Audit Focus

- Scope compliance: no blitter, no platform adapter register maps, no sprite changes.
- Tile path regression: all existing tile-mode scenes pass with `bitmapEnable=0`.
- Bitmap path correctness: 1bpp and 2bpp decode produce expected pixels.
- Register bus: `0x0350..0x0355` decode correctly and commit at safe boundary.
- SDRAM layout contract is documented and stable for host toolchains.

---

## 10. Exit Condition

This task is done when:
1. Simulation proves 1bpp and 2bpp bitmap + attribute fetch produces correct pixel data.
2. Hardware proves a visible bitmap+attribute scene on Tang Nano 20K with 30s stability.
3. Existing tile-mode scenes regress cleanly (zero pixel behavior change when bitmap mode is disabled).
4. The SDRAM layout contract and register map are documented for external host use.
