# Task 31 — Scroll Table Primitive

**Status:** Artifact phase
**depends_on:** [7, 15]
**scope_boundary:** Scroll tables only. No new tile fetch formats, no new compositor math.
**delivers:**

- Separate small dual-port RAM/table primitive for per-column or per-band scroll
- Explicit distinction between line state and scroll lookup state
- Interface for Genesis VSRAM-style patterns and SNES offset-per-tile

**validation:**

- Sim: scene with per-column scroll offsets proves correct addressing
- Hardware: visible parallax effect on Tang Nano 20K

---

## 1. Goal

Add a programmable scroll table so that horizontal scroll offset can vary per screen column (or per band of columns). Today each layer has a single global `scrollX`/`scrollY`. Task 31 introduces a small lookup table — indexed by column — whose output is added to the global scroll, producing per-column parallax without widening `LinestateStore`.

---

## 2. Scope

### 2.1 In scope

1. **Scroll table RAM** — small dual-port or read-async memory holding scroll offsets. Power-of-two depth (GT-022).
   - Table A: per-column H-scroll (e.g., 64 or 128 entries, one per 8- or 10-pixel column band)
   - Table B: per-band V-scroll (optional for this slice; can be stubbed)
2. **Table address generation** — `tableAddr = hCounter / bandWidth` (or `tileX` for offset-per-tile)
3. **Scroll addition** — `effectiveScrollX = globalScrollX + tableData`
4. **Bus programming** — host writes table entries via Mode0RegBus block (e.g., `0x0900..0x09FF`)
5. **Integration with existing fetch path** — `effectiveScrollX` feeds existing `ScrollWrap` + fetch engine; no fetch-format changes
6. **Sim proof** — two columns with different scroll offsets produce visibly different horizontal positions
7. **Hardware proof** — Tang Nano 20K shows a scene where left half and right half scroll at different rates

### 2.2 Out of scope (deferred)

- New tile fetch formats (2/4/8 bpp, packed attributes — fetch-engine tasks)
- Compositor math changes (color math, priority, metadata — Task 41 already done)
- Full Genesis VSRAM replication (40-entry × 16-bit) — start with a smaller power-of-two table
- SNES mode-7-style rotation (affine background — Task 19 already done)
- Vertical scroll tables (can be added later as a follow-on; H-scroll is the higher-value primitive)

---

## 3. Architecture

### 3.1 Current state (global scroll only)

```
VdpTop:
  layer0ScrollX = io.layer0ScrollX + linestateScrollX   // one value for whole layer
  layer0.io.scrollX := layer0ScrollX
```

### 3.2 Target state (Task 31)

```
VdpTop:
  globalScrollX = io.layer0ScrollX + linestateScrollX
  tableAddr     = hCounter(9 downto 3)   // one entry per 8-pixel band, 80 entries for 640 px
  tableOffset   = scrollTable.readAsync(tableAddr)
  effectiveScrollX = globalScrollX + tableOffset
  layer0.io.scrollX := effectiveScrollX
```

### 3.3 Interface boundaries

- **Table storage** — `Mem(UInt(10 bits), initialContent = zeros)` or similar; power-of-two depth
- **Bus write port** — ` Mode0RegBus` decode at `0x0900..0x09FF` (or other reserved block)
- **Read port** — combinational `readAsync` indexed by `hCounter`-derived address
- **Output** — added to existing `scrollX` wire before `ScrollWrap`

---

## 4. Implementation Plan

### 4.1 HDL changes

1. **`ScrollTable.scala`** (new) —
   - `Mem`-backed table, power-of-two depth (GT-022)
   - One read port: `readAsync(addr)` driven by `hCounter / bandWidth`
   - One write port: bus-driven, decoded from `Mode0RegBus`
   - Optional: second table for V-scroll (defer if scope tight)
2. **`VdpTop.scala`** (diff) —
   - Instantiate `ScrollTable` per layer (or one shared with `layerSelect`)
   - Add `tableOffset` to existing `scrollX` before `ScrollWrap`
   - Wire bus decode for `0x0900..0x09FF` range
3. **Mode0RegBus decode** — extend address map with scroll-table block
4. **`ScrollTableSim.scala`** (new) —
   - Program two entries with different offsets
   - Verify fetch engine receives correct `effectiveScrollX` per column

### 4.2 Data model

| Structure | Size | Notes |
|-----------|------|-------|
| H-scroll table | 64–128 entries × 10 bits | Power-of-two depth; 64 entries = 8 bands of 80 px; 128 = finer granularity |
| V-scroll table | 0–32 entries × 10 bits | Deferred; optional stub |
| Band width | 8–16 px | Determines table entry coverage |

### 4.3 Register / bus impact

- New bus block: `SCROLL_TABLE_BASE` .. `SCROLL_TABLE_END` (e.g., `0x0900..0x09FF`)
- Each entry is one 16-bit word; low 10 bits = scroll offset
- Host can rewrite table entries mid-frame (effect visible next line due to `readAsync`)

### 4.4 Validation plan

**Checkpoint A — Simulation:**
- `ScrollTableSim`: two bands, different offsets → fetch addresses differ by expected amount
- `VdpTopSim` regression: existing global-scroll scenes still pass (table entries = 0)

**Checkpoint B — Hardware:**
- Sc31: L0 tile layer with left half scroll = 0, right half scroll = +32
- 30-second capture: visible shear/parallax between halves; OpenCV confirms different motion rates
- No regression in existing Sc8 (global parallax still works)

---

## 5. Deliverables

| File / Path | Purpose |
|-------------|---------|
| `hw/spinal/spinalhdlvdp/ScrollTable.scala` (new) | Scroll table RAM + bus interface |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` (diff) | Table integration into scroll path |
| `sim/` test additions | `ScrollTableSim` + regression proof |
| `PROJECT_PLAN/TASK_31_SCROLL_TABLE_PRIMITIVE.md` | This artifact |

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Table read collides with bus write (same cycle) | `Mem` with separate read/write ports; SpinalHDL handles collision semantics |
| Table too small for desired granularity | Start with 128 entries (≈ 5 px bands at 640 px); depth is parameterizable |
| Wrap arithmetic overflow | `ScrollWrap` already handles `(coord + scroll) mod mapWidth`; just feed it `effectiveScrollX` |
| Bus block overlaps existing range | Verify `0x0900..0x09FF` is free in `MODE0_REGISTER_BUS_SPEC.md` |
| Scope creep into new fetch format | Strict boundary: table is just a scroll offset source; fetch engine unchanged |

---

## 7. Dependencies

- **Task 7 (Layer Composition)** — DONE. Basic scroll + layer path exists.
- **Task 15 (Memory-Backed Fetch Path)** — DONE. SDRAM fetch substrate proven.
- **Task 32b (Mode0 Register Bus: Master Refactor)** — DONE. Bus available for table programming.
- **R5.4 (Scroll-Wrap Component)** — DONE. `ScrollWrap` handles `(coord + scroll) mod mapWidth`; Task 31 feeds it a richer scroll source.

---

## 8. Open Questions

1. **Table depth / band width**: 64 entries (10 px bands) vs 128 entries (5 px bands)? Deeper = smoother parallax; shallower = smaller RAM.
2. **Per-layer tables**: One table shared between L0 and L1 (with a layer-select bit), or independent tables per layer? Independent is more flexible; shared saves RAM.
3. **V-scroll deferral**: Should the initial slice include a V-scroll table, or is H-scroll alone sufficient for the first proof?
4. **Bus address block**: Confirm `0x0900..0x09FF` is unallocated in the current register map.

---

## 9. Audit Focus

- Scope compliance: no fetch-format changes, no compositor changes
- GT-022: table depth is power-of-two
- Regression: global-scroll scenes (Sc2, Sc3, Sc8) pass unchanged with table entries at 0
- Integration: `effectiveScrollX` correctly feeds existing `ScrollWrap`

---

## 10. Exit Condition

This task is done when a programmable scroll table produces per-column horizontal scroll offsets in simulation and a visible parallax shear is proven on Tang Nano 20K hardware with zero regression in existing global-scroll scenes.
