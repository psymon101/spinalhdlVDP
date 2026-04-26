# TASK_R4_1B_PLANAR_FETCH.md

**Status:** DONE — Planar fetch mode implemented and hardware-proven (Scenario 9) (pending CyanPeak artifact audit per #7095)
**Created:** 2026-04-14
**Coding Owner:** BrightForge
**Audit Owner:** CyanPeak
**PM/Coordination:** CoralReef (covering BronzeGate)

---

## 1. Task Name

R4.1b Planar Tile Decode (NES-style 2-plane 2bpp)

---

## 2. Purpose

Generalize the proven R4.1 SDRAM tile+attribute fetch path with a
second tile-data decoding mode: **planar** (bit-plane) layout, the
storage format used by NES, SMS, and Sega Genesis VDPs. The current
R4 path decodes **packed 4bpp** rows — fixed-bit-depth pixels packed
sequentially. Planar splits each bit of a pixel into separate memory
planes, which is the canonical retro-platform tile encoding.

**Why now:**
- R4.1 proved the multi-slot scheduler can pace a single SDRAM client.
- Adding planar decode validates the R4 fetch architecture as
  general-purpose for retro tile formats, not a 4bpp-packed dead-end.
- Unblocks NES/SMS/Genesis BG visualization on the existing hardware.
- Per CoralReef #7095, highest-leverage next major lane.

---

## 3. Primitive Boundary

### In Scope

- **Planar decode logic** in (or alongside) `SdramTileAttributeFetch`:
  - **NES-style 2bpp**: 2 bit-planes per tile, 8 bytes per plane,
    16 bytes per 8×8 tile. plane[0] supplies pixel bit 0, plane[1]
    supplies pixel bit 1. Result: 0..3 index per pixel.
  - Decode produces the same `pixelIndex` width as today (4 bits)
    by zero-extending the 2-bit planar result.
- **Mode select**: a `tileDecodeMode` config bit (or 2-bit field for
  future expansion) routed through the unified register bus
  (probably `0x0310` VDP_CTRL or a dedicated `0x0311 VDP_TILE_MODE`).
- **Asset path**: small NES-style planar test tile set in
  `TileAttributeAssets` (or a new `PlanarTileAssets`) at a disjoint
  SDRAM region from R4's packed data.
- **Bootstrap**: extend `TopTang20kHdmi`'s boot FSM to:
  - Upload the planar test data to SDRAM if needed (boot-copy ROM)
  - Set `VDP_TILE_MODE` to planar before enabling rendering
- **Sim coverage**: extend `TileAttributeFetchSim` (or add
  `PlanarFetchSim`) with planar-decode cases.
- **Hardware proof**: a static planar-tile checkerboard (banks 1-2-3,
  showing index 0/1/2/3 mapping through the same banked palette).

### Explicitly Out of Scope

- **NO packed-attribute decode** (NES 2-bit-per-tile attribute
  packing). That's R4.1c / future. Keep the existing per-tile
  attribute byte (R4 format) for the planar mode in this task.
- **NO 4-plane (SMS/Genesis 4bpp planar) decode** — defer until
  2-plane is silicon-proven.
- **NO new fetch engine** — extend the existing one with a mode
  switch. A separate engine doubles BSRAM and doubles the integration
  surface.
- **NO compositor/palette changes** — planar decode lands the same
  pixel index into the same line buffer.
- **NO scroll-table primitive** (R4.2-future concern).
- **NO sprite-side changes**.
- **R5.3 cleanup** (`copperEnable` → `VDP_CTRL` bit): include it ONLY
  if the same `VDP_CTRL` register is touched for `tileDecodeMode`.
  Otherwise defer.

---

## 4. Dependencies

- R4 / R4.1 SDRAM tile+attribute fetch (proven, `0d4331c`)
- R5 / R5.2 host interface + register bus (proven, `0d4331c`)
- `LinestateStore`, scheduler, compositor — unchanged

---

## 5. Interfaces

### New register

| Addr | Name | Width | Description |
|------|------|------:|-------------|
| `0x0311` | `VDP_TILE_MODE` | 16 | bit[0]=0 packed-4bpp (R4 default), bit[0]=1 planar-2bpp |

(If R5.3 is folded in, also `0x0310 VDP_CTRL` bit assignments are
finalised here. Otherwise the existing `copperEnable` direct port
stays.)

### `SdramTileAttributeFetch` change

Add an input `tileDecodeMode: UInt(1 bits)` (or `UInt(2 bits)`
reserved-room). The `sFetchRowWait0`/`sFetchRowWait1` states
reinterpret the 32-bit words based on this mode:
- packed mode (current behaviour): word0/word1 form a 64-bit row of
  16 × 4bpp pixels (R4 baseline)
- planar mode: word0 is plane 0 bytes, word1 is plane 1 bytes; the
  unpacker emits 8 × 2bpp pixels per plane-pair (could be byte 0 of
  word0 + byte 0 of word1 → 8 pixels; remaining 7 bytes per word
  cover the rest of the tile or the next tile, configurable)

**Note**: NES tiles are 8×8, but our existing engine assumes 16×16
tiles. For R4.1b, we decode planar data into the same 16-pixel-wide
tile slot, treating bytes 0/1/2/3 of word0 as plane-0 across 16
columns and bytes 0/1/2/3 of word1 as plane-1. This keeps
addressing identical to R4 and only changes the unpacker — minimal
blast radius.

### Modified `VdpTop`

Decode `0x0311` writes into `tileDecodeModeReg` and pass to fetch.

---

## 6. Data Model

### Persistent

- **`tileDecodeModeReg`**: 1-2 bit Reg, default packed (= R4 backwards
  compatible). Updates at safe boundary (`hCounter === 0`) just like
  `layerEnableReg`.
- **Planar test tile bytes**: ~32 bytes per 4-tile set, in
  `PlanarTileAssets` (new file) or new fields in `TileAttributeAssets`.
  Stored in SDRAM at a region disjoint from R4's `0x8000` row data.

### Per-line / dynamic

No new state. The unpacker register file (`unpackRow`, `unpackBank`,
`unpackPrio`) is sized adequately for both modes.

### GT-022 Checklist

- [ ] Any new ROM `Mem` is power-of-two depth
- [ ] No non-power-of-two memories introduced

---

## 7. Timing Model

Identical to R4.1 — same fetch slots, same FSM, same per-tile read
budget. Planar mode decodes fewer non-zero pixels per tile (2bpp vs
4bpp) but doesn't change the SDRAM access pattern.

`tileDecodeModeReg` updates at line boundary (safe-boundary rule from
R5/R5.1) so a mid-frame mode flip never tears a single tile.

---

## 8. Memory / Bandwidth Impact

### SDRAM

- Same byte/cycle budget. Planar mode reads 16 bytes per tile (8
  bytes per plane × 2 planes) — same as packed mode's 8 bytes per
  64-bit row × 2 reads. Net read count per tile is unchanged.

### On-chip

- `tileDecodeModeReg`: ~1 flip-flop
- `PlanarTileAssets` ROM: ~64 bytes (4 tiles × 16 bytes), padded to
  the next power-of-two depth for GT-022.

---

## 9. Platform Reuse

| Platform | Benefit |
|----------|---------|
| NES | Native BG tile format support |
| SMS | Native 4bpp planar (covered by future 4-plane extension) |
| Sega Genesis | Native VRAM tile format (planar 4bpp; covered later) |
| Custom | Lets adapters supply planar-laid-out BG data without a re-encode pass |

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| Planar unpack mis-orders bits → wrong colors | Sim cross-check decoded pixel index against software reference for known tiles |
| Mode switch tears a tile mid-fetch | `tileDecodeModeReg` updates only at `hCounter===0`; same safe-boundary as `layerEnableReg` |
| Asset ROM clash with R4 SDRAM regions | Place planar test data at `0xA000` (disjoint from R4's `0x6000`/`0x7000`/`0x8000`) |
| FSM modification regresses packed mode | Branch logic via `Mux(tileDecodeMode, planarUnpack, packedUnpack)`; both paths exercised in sim |
| Hardware boot-copy bandwidth tight | Planar test set is small (~64 bytes); negligible boot-time addition |

---

## 11. Validation Plan

### Sim cases (extend `TileAttributeFetchSim`)

1. **Mode switch**: write `0x0311 = 0` (packed), confirm R4 baseline
   pixel matches; write `0x0311 = 1` (planar), confirm planar decode
   produces expected 2bpp index.
2. **Planar pattern correctness**: decode a known plane-0/plane-1
   byte pair, assert 8 pixels match expected 2-bit indices.
3. **Mid-line mode flip is safe**: drive `0x0311` write mid-active;
   confirm the change appears at the next `hCounter===0` boundary,
   not mid-line.
4. **Banked palette + planar**: planar 2-bit index + attribute bank
   together render correctly through the 128-entry palette.

### Regression sims (must rerun)

- `VdpTopSim`, `SpriteEvaluatorSim`, `RasterTriggerUnitSim`,
  `FetchSlotSchedulerSim`, `HostInterfaceSim`, `CopperSim`,
  `UnifiedRegMapSim`

### Assertions

- Mode register only updates at `hCounter === 0` or vblank
- Planar decode preserves index range 0..3
- No new memories with non-power-of-two depth

---

## 12. Hardware Proof

### Proof scene: "Planar Quadrant"

- Bootstrap uploads the planar test tile set to SDRAM at `0xA000`,
  then writes `0x0311 = 1` via the host interface FSM
- Static frame (no scroll) shows 4 quadrants, each rendering the
  same planar tile through banks 1-4
- Each quadrant displays a different 2-bit pattern (00, 01, 10, 11
  → 4 distinct colors per bank)
- Verifies: planar decode correctness + bank routing + mode register

### Regression scene

- Same R4.1b proof scene with `tileDecodeMode = 0` must render
  bit-identical to current R5.2 baseline (`0d4331c`).

### OpenCV verification

Re-run `/tmp/r5_60fps.py` 30 s capture; expect:
- Top L1 band still ≤2 big-jumps (within R4.2 detection limit)
- L0 bands still within the same noise floor as R4.2 closure

---

## 13. Audit Questions

CyanPeak to verify:

1. **Scope compliance**: only planar decode + mode register; no
   packed-attribute, no 4-plane, no compositor changes
2. **Mode safety**: `tileDecodeModeReg` updates at safe boundary
3. **Backward compatibility**: packed-mode rendering byte-identical
   to R5.2 baseline (regression sim + hardware spot-check)
4. **GT-022**: any new memories are power-of-two
5. **Sim coverage**: all 4 dedicated cases pass
6. **Regression**: 7 existing sims pass
7. **Hardware**: planar quadrant scene renders correctly + no
   regression on packed scene

---

## 14. Constraints / Gotcha Check

- [ ] **No hardware before sim**: all planar sim cases pass first
- [ ] **Safe boundary**: mode register at `hCounter===0`
- [ ] **GT-022**: power-of-two for any new memory
- [ ] **Disjoint SDRAM regions**: planar at `0xA000`, no overlap
  with R4 `0x6000`/`0x7000`/`0x8000`
- [ ] **Cleanup**: R5.3 (`copperEnable` → `VDP_CTRL` bit) folded
  ONLY if `VDP_CTRL` is touched; otherwise stays as backlog item

---

## 15. Exit Condition

This task is done when:
- `tileDecodeMode = 1` produces correct planar 2bpp output on the
  hardware planar-quadrant scene
- `tileDecodeMode = 0` produces output bit-identical to R5.2 baseline
  on the existing R4 proof scene
- All 4 planar sim cases pass
- All 7 regression sims pass
- 60 fps OpenCV capture shows no regression beyond the R4.2 noise
  floor

---

## Staged Implementation Plan

| Stage | Scope | Output |
|-------|-------|--------|
| 1 | Planar decode logic + `tileDecodeMode` input on fetch engine | `SdramTileAttributeFetch.scala` updated; sim case 2 passes |
| 2 | `PlanarTileAssets` + boot-copy of planar data into SDRAM | New asset file; boot ROM updated |
| 3 | `VDP_TILE_MODE` register at `0x0311` + safe-boundary commit | `VdpTop.scala` updated; sim case 1 + 3 pass |
| 4 | `TopTang20kHdmi` bootstrap writes mode + uploads planar data; hardware proof scene | TopTang updated; static planar-quadrant scene captured |
| 5 | `UnifiedRegMapSim` extension for `0x0311`, full regression, OpenCV | All sims green; closeout packet |

---

## Short-Form Summary

```markdown
## Task
R4.1b Planar Tile Decode (NES-style 2-plane 2bpp)

## Purpose
Add a second tile decoding mode (planar bit-planes) to the proven R4.1
fetch engine. Unlocks NES/SMS/Genesis BG patterns on existing hardware.

## Scope
- in scope: 2-plane 2bpp planar decode in SdramTileAttributeFetch
- in scope: VDP_TILE_MODE register at 0x0311, safe-boundary commit
- in scope: small planar test asset set + boot-copy
- in scope: Planar Quadrant hardware proof scene
- out of scope: packed-attribute decode, 4-plane SMS/Genesis, new
  fetch engine, compositor changes, scroll table, sprites, R5.3

## Dependencies
- R4.1 (`9dfeb9f`), R5.2 (`0d4331c`)

## Interfaces
- New: VDP_TILE_MODE @ 0x0311
- Modified: SdramTileAttributeFetch gets tileDecodeMode input

## Timing
- Mode register updates at hCounter===0 (safe-boundary rule)
- SDRAM access pattern unchanged from R4.1

## Risks
- Planar bit-order mismatch (mitigate: sim cross-check)
- Packed-mode regression (mitigate: byte-identical hardware check)

## Validation
- sim: TileAttributeFetchSim cases 8-11 (mode switch, planar pattern,
  mid-line safety, planar+bank)
- regression: 7 existing sims
- hardware: Planar Quadrant + no-regression packed scene + OpenCV

## Audit Focus
Scope compliance, mode safety, backward compatibility, GT-022, sim
coverage, regression, hardware proof

## Exit Condition
Planar mode renders correctly, packed mode unchanged, all sims pass,
hardware OpenCV no regression vs R4.2 noise floor.
```
