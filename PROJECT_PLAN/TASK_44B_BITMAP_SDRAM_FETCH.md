# Task 44b — Bitmap SDRAM Fetch + Upload Path

**Status:** Artifact phase  
**depends_on:** [44]  
**scope_boundary:** SDRAM-backed bitmap row fetch + data upload/init only. No decoder changes, no register map changes, no platform adapter semantics.  
**delivers:**

- `BitmapRowFetch` SDRAM-domain module for linear bitmap + attribute row reads
- Scheduler client-1 slot activation + arbiter wiring
- Live `bitmapByte` / `attrByte` delivery to existing `BitmapFetch` decoder
- Host upload or bootstrap init path for bitmap+attribute data into SDRAM
- Sim proof and hardware proof of SDRAM-backed bitmap rendering

**validation:**

- Sim: `BitmapRowFetchSim` proves correct SDRAM addressing and byte delivery per pixel
- Hardware: at least one unambiguous Sc44 hardware proof with SDRAM-backed bitmap+attribute data

---

## 1. Goal

Complete the Task 44 bitmap substrate by adding the **missing SDRAM fetch path**. Today `BitmapFetch` (Task 44 CP-A/B) has a proven combinational decoder and register block, but its `bitmapByte` / `attrByte` inputs are driven by a deterministic test generator (`a87bcd3`). Task 44b replaces that generator with a real SDRAM row-buffer fetch so that bitmap data lives in external memory and is read line-by-line like the existing tile fetch engine.

---

## 2. Scope

### 2.1 In scope

1. **`BitmapRowFetch` module (new)** — SDRAM-domain FSM:
   - Accepts `fetchGrant` / `fetchSlotValid` / `fetchPreAnnounce` from scheduler.
   - Computes `sdramAddr = bitmapBase + line * bitmapStride + byteOffset` for each row.
   - Computes `attrAddr = attrBase + line * attrStride + attrByteOffset` for attribute row.
   - Issues SDRAM read bursts through arbiter client 1.
   - Double-latch CDC to pixel domain (same pattern as `SdramTileAttributeFetch`).
   - Exposes pixel-domain read: `bitmapByteOf(col) → Bits(8)`, `attrByteOf(col) → Bits(8)`.
2. **Scheduler + arbiter wiring**:
   - Configure scheduler slot 2 or 3 with `clientId = 1` for bitmap fetch.
   - `SdramArbiter` routes client-1 requests to SDRAM controller.
   - Client 0 (tile fetch) and client 1 (bitmap fetch) share bandwidth; scheduler ensures no overlap.
3. **Top-level integration**:
   - Instantiate `BitmapRowFetch` in `TopTang20kHdmi`.
   - Wire its pixel-domain outputs into `BitmapFetch.io.bitmapByte` / `attrByte`.
   - Remove the deterministic test generator from `a87bcd3`.
4. **Data upload / init path**:
   - Option A: Extend `vdp_upload.c` / `libvdp` with a bitmap+attribute upload helper.
   - Option B: Synthesized initial SDRAM load (bootstrap ROM) for Sc44 proof.
   - The chosen option must be documented and reproducible.
5. **Sim proof** — `BitmapRowFetchSim`:
   - Program bitmap base with test pattern in SDRAM model.
   - Trigger row fetch for a specific line.
   - Verify `bitmapByte` and `attrByte` outputs match expected data at each column.
6. **Hardware proof** — Sc44d (SDRAM-backed):
   - Upload a bitmap+attribute test image into SDRAM.
   - Enable bitmap mode; capture 30s HDMI output.
   - OpenCV stability analysis confirms deterministic, non-corrupted rendering.

### 2.2 Out of scope (deferred)

- Changes to `BitmapFetch` decoder logic — it is proven and frozen.
- Changes to `0x0350..0x0356` register block — it is proven and frozen.
- Platform adapter register maps — still substrate work only.
- Blitter / copy engine — Task 49.
- Simultaneous tile + bitmap display — one mode active at a time (existing L0 mux).

---

## 3. Architecture

### 3.1 Current state (Task 44 closed at `a87bcd3`)

```
VdpTop:
  BitmapFetch.io.bitmapByte ← test pattern generator (deterministic XOR)
  BitmapFetch.io.attrByte   ← test pattern generator (cell-indexed)
  layer0Index ← Mux(bitmapEnable, BitmapFetch.io.pixelIndex, ...)
```

### 3.2 Target state (Task 44b)

```
TopTang20kHdmi:
  scheduler.slot(2).clientId := 1   // bitmap fetch client
  
  bitmapRowFetch.io.fetchGrant     := scheduler.io.grant (gated to slot 2)
  bitmapRowFetch.io.fetchSlotValid := scheduler.io.slotValid(2)
  bitmapRowFetch.io.fetchLine      := fetchLineReg
  bitmapRowFetch.io.bitmapBase     := bitmapBaseReg
  bitmapRowFetch.io.bitmapStride   := bitmapStrideReg
  bitmapRowFetch.io.attrBase       := attrBaseReg
  bitmapRowFetch.io.attrStride     := attrStrideReg
  
  sdramArbiter.client(1) ← bitmapRowFetch.sdramReq
  
  VdpTop:
    BitmapFetch.io.bitmapByte ← bitmapRowFetch.io.bitmapByte
    BitmapFetch.io.attrByte   ← bitmapRowFetch.io.attrByte
```

### 3.3 Interface boundaries

- **SDRAM side**: `BitmapRowFetch` drives `sdramAddr`, `sdramRd` via arbiter client 1.
- **Pixel side**: `BitmapRowFetch` produces combinational `bitmapByte(col)` and `attrByte(col)` from internal line buffers.
- **CDC**: Double-latch or FIFO-based crossing from sdramClockDomain to pixel clock (same proven pattern as `SdramTileAttributeFetch`).

---

## 4. Implementation Plan

### 4.1 HDL changes

1. **`BitmapRowFetch.scala` (new)** — SDRAM-domain row fetch:
   - FSM states: `Idle → FetchBitmapRow → FetchAttrRow → Wait → Done`
   - Row buffer: two `Mem(Bits(8 bits), MaxRowBytes)` instances (bitmap + attribute).
   - Address generator: `base + line * stride + byteIdx`.
   - CDC: `StreamFifoCC` or double-latch to pixel domain.
   - Pixel-domain read: `bitmapByte = rowBuf(bitmapCol / 8)` with sub-byte bit select deferred to `BitmapFetch`.

2. **`SdramArbiter.scala` (diff)** — add client 1:
   - Extend `grantClientId` width if needed.
   - Add `client(1).req` / `client(1).addr` / `client(1).rd` inputs.
   - Round-robin or priority arbitration between client 0 (tile) and client 1 (bitmap).

3. **`TopTang20kHdmi.scala` (diff)** — wiring:
   - Instantiate `BitmapRowFetch`.
   - Route scheduler slot 2 signals.
   - Connect arbiter client 1.
   - Connect `BitmapRowFetch` outputs to `VdpTop.BitmapFetch` inputs.

4. **Bootstrap / upload path**:
   - If Option A (host upload): extend `libvdp` with `vdp_upload_bitmap()` helper.
   - If Option B (synthesized init): add bitmap+attribute boot ROMs and copy-to-SDRAM sequence.

### 4.2 Data model

**SDRAM layout (same as Task 44 artifact):**
```
Bitmap region @ BITMAP_BASE:
  row 0: byte[0] .. byte[bitmapStride-1]
  row 1: byte[bitmapStride] .. byte[2*bitmapStride-1]
  ...

Attribute region @ ATTR_BASE:
  row 0: byte[0] .. byte[attrStride-1]
  row 1: byte[attrStride] .. byte[2*attrStride-1]
  ...
```

### 4.3 Register / bus impact

- **No new register addresses.** Task 44b reuses the existing `0x0350..0x0356` block.
- Safe-boundary commit already proven in Task 44.

### 4.4 Validation plan

**Checkpoint A — Simulation:**
- `BitmapRowFetchSim`: SDRAM model pre-loaded with test bitmap + attribute rows. Trigger fetch for line N. Verify delivered `bitmapByte` / `attrByte` match pre-loaded data at multiple columns.
- `VdpTopSim` regression: tile-mode scenes pass with bitmap mode disabled.
- `UnifiedRegMapSim`: no new registers; existing block unchanged.

**Checkpoint B — Hardware:**
- Sc44d: SDRAM-backed bitmap mode. Upload test image via QSPI or bootstrap. Enable bitmap mode. 30s capture + OpenCV stability analysis.
- Regression: existing Sc8 tile-mode scene still passes.

---

## 5. Deliverables

| File / Path | Purpose |
|---|---|
| `hw/spinal/spinalhdlvdp/BitmapRowFetch.scala` (new) | SDRAM-domain row fetch + line buffer |
| `hw/spinal/spinalhdlvdp/SdramArbiter.scala` (diff) | Client-1 arbitration |
| `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` (diff) | Wiring integration |
| `sim/` test additions | `BitmapRowFetchSim` + regression |
| `PROJECT_PLAN/TASK_44B_BITMAP_SDRAM_FETCH.md` | This artifact |

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Two SDRAM clients (tile + bitmap) contend for bandwidth | Scheduler slots already separate clients in time; arbiter resolves same-cycle conflicts. Slot 2/3 can be disabled when bitmap mode is off. |
| `BitmapRowFetch` FSM complexity comparable to `SdramTileAttributeFetch` (~700 LoC) | Scope is smaller: no tile map indirection, no attribute packing modes, no planar/shuffled decode. Linear addressing only. |
| CDC line buffer underrun | Same double-latch/FIFO pattern as proven tile fetch. Pixel-domain read waits for buffer fill. |
| No host upload path for bitmap data | Option B (synthesized init) provides a no-firmware proof path; upload path can be added later without HDL changes. |
| Regression in tile fetch | Bitmap client only active when `bitmapEnable=1`; arbiter client 1 requests are gated by bitmap enable. |

---

## 7. Dependencies

- **Task 44 (Raw Bitmap + Attribute Fetch Primitive)** — DONE. Provides `BitmapFetch` decoder, register block, and L0 mux.
- **Task 30 (Pre-Announced Arbiter Grant)** — DONE. Scheduler + arbiter substrate.
- **Task 32b (Mode0 Register Bus: Master Refactor)** — DONE. Register decode stable.

---

## 8. Open Questions

1. **Upload vs bootstrap init**: Should Sc44d use host QSPI upload or synthesized SDRAM init?
   - *Recommendation: bootstrap init for CP-B proof* (no firmware dependency). Upload path added as a follow-on firmware task.
2. **Scheduler slot allocation**: Use slot 2 (currently disabled) or slot 3?
   - *Recommendation: slot 2* — it is already disabled and available for client 1.
3. **Simultaneous tile + bitmap fetch**: Should both ever be active?
   - *Recommendation: no. One mode at a time via `bitmapEnable`. Future tasks may add compositor blending.*

---

## 9. Audit Focus

- Scope compliance: no decoder changes, no register map changes.
- `BitmapRowFetch` addresses are linear (base + line×stride + offset), not tile-mapped.
- CDC is consistent with existing `SdramTileAttributeFetch` pattern.
- Arbiter client 1 does not corrupt client 0 when bitmap mode is disabled.
- Regression: all existing scenes pass unchanged.

---

## 10. Exit Condition

This task is done when:
1. Simulation proves `BitmapRowFetch` delivers correct `bitmapByte` / `attrByte` from SDRAM model data.
2. Hardware proves a visible SDRAM-backed bitmap+attribute scene on Tang Nano 20K with 30s stability.
3. Existing tile-mode scenes regress cleanly.
