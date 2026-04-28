# Task 46 — V-Scroll Table Primitive

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Status:** DONE — implementation `90065fc`; evidence #8202; audit PASS #8204  
**Coding authorized:** CLOSED — no further Task 46 work authorized  

---

## 1. Executive Summary

Task 31 landed a per-column H-scroll table (`ScrollTable`, 128 entries, indexed by `hCounter` band, added to `scrollX`). Task 46 adds the orthogonal primitive: a **V-scroll table** that provides per-column vertical-scroll offsets (indexed by `hCounter` band, added to `scrollY`). This is the classic Genesis VSRAM-style pattern: each vertical column band can scroll vertically at an independent rate, producing shear/wave effects.

The hardware is structurally identical to the existing H-scroll table — a small `Mem`-backed lookup — and reuses the proven `ScrollTable` module. Integration adds the table output to `scrollY` before `ScrollWrap`, paralleling the existing `scrollX` path.

**Scope boundary:** V-scroll lookup state only. No new fetch formats, no additional compositor math, no changes to the tile decode pipeline.

---

## 2. Current State Analysis

### 2.1 Existing scroll architecture (Task 31)

```scala
// VdpTop.scala ~506-533
val scrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)
val scrollTable1 = ScrollTable(entries = 128, offsetWidth = 10)

// Bus decode: 0x0900..0x09FF
// Read index: hCounter(9 downto 3)  → 128 bands @ ~5 px each (640 px active)
// Output added to scrollX:
layer0.io.scrollX := io.layer0ScrollX + linestate.io.layer0ScrollX + scrollTable0Offset
layer1.io.scrollX := io.layer1ScrollX + scrollTable1Offset
```

### 2.2 Current V-scroll path (global only)

```scala
layer0.io.scrollY := io.layer0ScrollY   // global only, no table
layer1.io.scrollY := io.layer1ScrollY   // global only, no table
```

There is no per-column or per-line vertical scroll variation today. `LinestateStore` carries `layer0ScrollX` per line but no `layer0ScrollY`.

### 2.3 Available register-bus address space

The H-scroll table occupies `0x0900..0x09FF` (256 bytes, 128 entries × 2 layers). The next contiguous free block is `0x0A00..0x0AFF`, confirmed unallocated in both `MODE0_REGISTER_BUS_SPEC.md` and `VdpTop.scala` decode logic.

---

## 3. Architecture

### 3.1 Target state

```scala
VdpTop:
  // H-scroll (existing, unchanged)
  layer0.io.scrollX := io.layer0ScrollX + linestate.io.layer0ScrollX + hScrollTable0Offset
  layer1.io.scrollX := io.layer1ScrollX + hScrollTable1Offset

  // V-scroll (new)
  vScrollTableAddr  := hCounter(9 downto 3).resize(7)   // same banding as H-scroll
  vScrollTable0Offset := vScrollTable0.io.rdData
  vScrollTable1Offset := vScrollTable1.io.rdData
  layer0.io.scrollY := io.layer0ScrollY + vScrollTable0Offset
  layer1.io.scrollY := io.layer1ScrollY + vScrollTable1Offset
```

The V-scroll table is indexed by the **same horizontal band** as the H-scroll table (`hCounter(9 downto 3)`). This means each vertical strip of the screen (~5 px wide at 128 entries / 640 px) has its own independent Y-scroll offset. This is the Genesis VSRAM semantics and is the most hardware-efficient parallel to Task 31.

> **Alternative considered:** A per-row / per-band table indexed by `vCounter` would give each horizontal strip its own Y offset. This is also valid but is less commonly called a "V-scroll table" in VDP terminology (it is closer to per-line scroll). The artifact recommends per-column because (a) it parallels H-scroll structurally, (b) it is the classic effect, and (c) both can coexist if a future task adds per-row scroll via `LinestateStore` expansion.

### 3.2 Module reuse

The existing `ScrollTable` module (`hw/spinal/spinalhdlvdp/ScrollTable.scala`) is fully reusable:

```scala
val vScrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)
val vScrollTable1 = ScrollTable(entries = 128, offsetWidth = 10)
```

No RTL changes to `ScrollTable.scala`.

### 3.3 Bit-identical default behavior

`ScrollTable` initializes to all zeros (`mem.init(Seq.fill(entries)(U(0, ...)))`). Until the host programs the V-scroll table, `layer0.io.scrollY` receives `io.layer0ScrollY + 0`, which is bit-identical to the current global-only path. **Zero regression for existing scenes.**

---

## 4. Exact Changes Required

### 4.1 `VdpTop.scala`

**Change A:** Instantiate V-scroll tables.

Add near the existing H-scroll table instantiations (~line 506):
```scala
val vScrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)
val vScrollTable1 = ScrollTable(entries = 128, offsetWidth = 10)
```

**Change B:** Wire read ports.

Add near existing scroll table read wiring (~line 521):
```scala
val vScrollTable0Addr = hCounter(9 downto 3).resize(7)
val vScrollTable1Addr = hCounter(9 downto 3).resize(7)
vScrollTable0.io.rdAddr := vScrollTable0Addr
vScrollTable1.io.rdAddr := vScrollTable1Addr
val vScrollTable0Offset = vScrollTable0.io.rdData
val vScrollTable1Offset = vScrollTable1.io.rdData
```

**Change C:** Add V-scroll offsets to `scrollY`.

Current (~line 533, 652):
```scala
layer0.io.scrollY := io.layer0ScrollY
layer1.io.scrollY := io.layer1ScrollY
```

New:
```scala
layer0.io.scrollY := io.layer0ScrollY + vScrollTable0Offset
layer1.io.scrollY := io.layer1ScrollY + vScrollTable1Offset
```

**Change D:** Add register-bus decode for `0x0A00..0x0AFF`.

Add near existing scroll table decode (~line 508):
```scala
val vScrollTableRangeHit = effWrite &&
  (effAddr >= U(0x0A00, 15 bits)) &&
  (effAddr <  U(0x0B00, 15 bits))
val vScrollTableSub  = (effAddr - U(0x0A00, 15 bits))(7 downto 0)
val vScrollTableEntry = vScrollTableSub(6 downto 0)    // 7 bits = 128 entries
val vScrollTableLayer = vScrollTableSub(7)             // 0 = L0, 1 = L1
vScrollTable0.io.wrAddr := vScrollTableEntry
vScrollTable0.io.wrData := effData(9 downto 0).asUInt
vScrollTable0.io.wr     := vScrollTableRangeHit && !vScrollTableLayer
vScrollTable1.io.wrAddr := vScrollTableEntry
vScrollTable1.io.wrData := effData(9 downto 0).asUInt
vScrollTable1.io.wr     := vScrollTableRangeHit && vScrollTableLayer
```

### 4.2 `MODE0_REGISTER_BUS_SPEC.md`

Add row to §3 address map:

| Range | Purpose | Task | Source ref |
|---|---|---|---|
| `0x0A00..0x0AFF` | V-scroll table (128 entries × 2 layers × 10-bit offset) | Task 46 | `VdpTop.scala` |

### 4.3 Files that do NOT change

- `ScrollTable.scala` — already parametric, no edits.
- `ScrollWrap.scala` — just receives a richer `scrollY`; no interface change.
- `BasicPatternSource.scala` — consumes `scrollY` unchanged.
- H-scroll decode (`0x0900..0x09FF`) — untouched.

---

## 5. Resource Impact

| Item | Current (Task 45) | After Task 46 | Delta |
|---|---|---|---|
| BSRAM | 7 / 46 (16%) | 9 / 46 (20%) | +2 BSRAMs |
| Register (FF) | 6030 / 15915 (38%) | ~6030 (no FF growth) | 0 |
| Logic (LUT) | 10838 / 20736 (53%) | ~10880 | +~40 LUTs (decode + adders) |

The `ScrollTable` module uses one BSRAM per instance (128 × 10 bits = 1280 bits; Gowin BSRAM is 18 Kbit, so one BSRAM holds many such tables). Two new instances = +2 BSRAMs. The adders and decode logic are negligible.

**Headroom is ample:** 20% BSRAM, 53% logic, 38% FFs.

---

## 6. Validation Plan

### 6.1 Simulation validation

**6.1.1 `VScrollTableSim.scala` (new)**

A targeted unit/integration sim that:
- Instantiates `ScrollTable` (128 entries, 10 bits) and wires it as a V-scroll source.
- Programs two adjacent bands with different Y offsets (e.g., band 0 = 0, band 1 = 32).
- Drives `hCounter` across the boundary and verifies `scrollY` changes at the expected pixel.
- Checks that default (unprogrammed) bands return 0.

**6.1.2 `VdpTopSim` regression**

Existing global-scroll scenes (Sc2, Sc3, Sc8) must pass bit-identically with V-scroll table entries at 0. This proves no regression.

**6.1.3 New integration sim (optional but recommended)**

`VScrollShearSim` or extension to `VdpTopSim`:
- Programs V-scroll table so left half of screen has Y offset = 0, right half has Y offset = +64.
- Verifies that fetch addresses for the right half reference tiles 4 rows higher than the left half.

### 6.2 Hardware validation

**6.2.1 Build**

`make -C fpga/tang20k SCENARIO=<scroll-scenario> all`
- Must complete with 0 errors, 0 critical warnings.
- Timing must remain closed.

**6.2.2 Scenario selection**

Recommended: modify an existing scroll scenario (e.g., Sc8 — global parallax) with a short Copper program that programs the V-scroll table:
- Left half (bands 0..63): Y offset = 0
- Right half (bands 64..127): Y offset = +32 or +64

This produces a visible vertical shear: the left and right halves show different vertical positions of the same tilemap, creating a "split screen" vertical scroll effect.

Alternatively, program a sine-wave or ramp pattern into the V-scroll table for a visible wave/shear effect.

**6.2.3 Hardware proof evidence**

- Direct capture or monitor screenshot showing the vertical shear/wave.
- 30 s capture + `analyze.py` showing stable non-black output.
- Bitstream md5 and HEAD commit hash.

---

## 7. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| BSRAM shortage | Very low | High | +2 BSRAMs brings total to 9/46 (20%). Ample headroom. |
| `scrollY` adder becomes timing-critical | Very low | Medium | The adder is 10-bit, same width as existing `scrollX` adder chain which already meets timing. If violated, register the table output one cycle early. |
| Bus address overlap with future task | Very low | Medium | `0x0A00..0x0AFF` is confirmed free in spec and implementation. Document in `MODE0_REGISTER_BUS_SPEC.md`. |
| Existing scene regression | Very low | High | Table init-to-zero guarantees bit-identical default. Sim regression catches any deviation. |

---

## 8. Out-of-Scope / Deferred

Per TASKS.md boundary:
- **Per-row / per-line Y scroll** — can be added later via `LinestateStore` expansion (add `layer0ScrollY` field). Task 46 is specifically the table primitive parallel to Task 31.
- **New fetch formats** — tile fetch engine unchanged.
- **Compositor math** — no priority, color-math, or metadata changes.
- **Platform-specific VSRAM replication** — Genesis VSRAM is 40-entry × 16-bit; we use 128-entry × 10-bit, power-of-two (GT-022).

---

## 9. Audit Checklist for CyanPeak

- [ ] Architecture parallels Task 31 H-scroll table correctly.
- [ ] Register-bus address `0x0A00..0x0AFF` is free and correctly decoded.
- [ ] `ScrollTable` module reuse is appropriate (no module changes needed).
- [ ] Bit-identical default behavior is guaranteed (init-to-zero).
- [ ] Resource impact (+2 BSRAMs) is acceptable.
- [ ] Validation plan covers sim proof, regression, and hardware evidence.
- [ ] Scope boundary excludes per-row scroll, fetch formats, and compositor changes.

---

## 10. Next Steps (Post-Audit)

1. **CyanPeak audit:** Rule PASS / HOLD / FAIL on this artifact.
2. **BronzeGate PM authorization:** If audit PASS, authorize BrightForge to implement.
3. **BrightForge implementation:** Apply §4 changes, run sims, synthesize, capture hardware evidence.
4. **CyanPeak implementation audit:** Audit implementation evidence.
5. **CoralReef ledger sync:** Update `TASKS.md` to mark Task 46 DONE at implementation commit.
