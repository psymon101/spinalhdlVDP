# Task 49 — Blitter-Class Block Transfer Engine

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Status:** artifact draft — awaiting CyanPeak audit  
**Coding authorized:** NO

---

## 1. Executive Summary

Task 47 added a linear DMA engine: FILL a consecutive run with a constant, or COPY from a 64-word staging buffer to a consecutive destination. Task 49 extends this into a **rectangular blitter** that can fill or copy 2-D regions with independent source and destination stride, enabling autonomous tilemap clears, sprite-block copies, and scroll-table band updates without host per-word intervention.

The blitter reuses the Task 47 integration pattern (lowest-priority insertion into the `effWrite` path, status via `evBus`), adds a dedicated **512 × 16-bit source/store RAM**, and exposes rectangular geometry registers (width, height, source stride, destination stride, source address, destination address).

**Scope boundary:** Block-transfer engine only. No pixel blending/alpha, no RLE, no full platform adapter, no CPU-side software renderer.

---

## 2. Current State Analysis

### 2.1 Task 47 DMA baseline

`DmaEngine.scala` provides:
- FILL: write `fillReg` to `dstReg .. dstReg+lenReg`
- COPY: read staging buffer `[0..lenReg]` → write `dstReg .. dstReg+lenReg`
- One write per free cycle, lowest priority behind ext/copper
- Status: `DMA_DONE` sticky (bit 8), `DMA_BUSY` live

Integration in `VdpTop`:
```scala
val effWrite = (extHit || copperPopped || dmaWr)
val effAddr  = Mux(extHit, io.regBus.addr,
               Mux(copperPopped, copperFifo.io.pop.payload(30 downto 16).asUInt,
                                 dmaEngine.io.dmaAddr))
```

### 2.2 What the DMA cannot do today

| Desired operation | DMA limitation |
|---|---|
| Clear a 32×24 tilemap region | Linear only; no row stride |
| Copy a 16×16 sprite pattern to descriptor slot | 64-word staging limit; no 2-D addressing |
| Copy every 80th word (vertical band fill) | No stride control |
| Copy from one bus address to another | No read-back path from bus-writable structures |

### 2.3 Architectural constraint: no unified read bus

The Mode0 register bus (`Mode0RegBus`) is write-only: `addr`, `data`, `enable`. Internal structures (linestate, scroll tables, sprite descriptors) are distributed Mem/Reg instances with no unified read-back path to a bus master. Adding such a path would touch every consumer and violate the "block-transfer engine only" scope.

**Resolution:** The blitter keeps a **dedicated dual-port source RAM** writable by the host via bus writes and readable by the blitter engine. Copies are **source-RAM → destination-bus**. This is bounded, predictable, and matches how classic 2-D blitters worked (source in local chip RAM, destination in frame-buffer address space).

### 2.4 Available address space

| Range | Current use |
|---|---|
| `0x0B00..0x0B03` | DMA control registers |
| `0x0B10..0x0B4F` | DMA staging buffer (64 words) |
| `0x0B50..0x0BFF` | **Free** |
| `0x0C00..0x0FFF` | **Free** (within 15-bit bus; above sprite desc reserved block) |

Proposed allocation:
- `0x0C00..0x0C07` — blitter control registers (8 words)
- `0x0C10..0x0D0F` — blitter source/store RAM (512 words, 10-bit address)

---

## 3. Proposed Design

### 3.1 BlitterEngine component

```scala
case class BlitterEngine() extends Component {
  val ctrlBaseAddr    = 0x0C00
  val srcRamBaseAddr  = 0x0C10
  val srcRamWords     = 512
  val srcRamAddrBits  = log2Up(srcRamWords)  // 9

  val io = new Bundle {
    // Bus write port — host programs control regs AND loads source RAM.
    val busAddr = in  UInt(15 bits)
    val busData = in  Bits(16 bits)
    val busWr   = in  Bool()

    // Higher-priority master active this cycle (ext or copper).
    val busBusy = in  Bool()

    // Blitter-generated write port (merged into effWrite at lowest prio).
    val blitAddr = out UInt(15 bits)
    val blitData = out Bits(16 bits)
    val blitWr   = out Bool()

    // Status.
    val busy = out Bool()
    val done = out Bool()   // one-cycle pulse on completion
  }
  ...
}
```

### 3.2 Control registers

| Address | Name | Width | Description |
|---|---|---|---|
| `0x0C00` | `BLIT_CTRL` | 16 | `{done_ack[3], mode[2:1], go[0]}` — mode: `0`=RECT_FILL, `1`=RECT_COPY, `2`=LINE_FILL, `3`=reserved |
| `0x0C01` | `BLIT_WIDTH` | 10 | Words per row minus 1 (0 = 1 word) |
| `0x0C02` | `BLIT_HEIGHT` | 10 | Rows minus 1 (0 = 1 row) |
| `0x0C03` | `BLIT_DST_ADDR` | 15 | Destination start address (register-bus space) |
| `0x0C04` | `BLIT_DST_STRIDE` | 15 | Destination row increment in words |
| `0x0C05` | `BLIT_SRC_ADDR` | 9 | Source start address inside source RAM (COPY mode only) |
| `0x0C06` | `BLIT_SRC_STRIDE` | 9 | Source row increment in words (COPY mode only) |
| `0x0C07` | `BLIT_FILL_VAL` | 16 | Constant fill value (FILL mode only) |

**Notes:**
- All registers are in the pixel clock domain, written via `busWr` when `busAddr` is in the control range.
- `go` is self-clearing (same pattern as `DmaEngine.goReg`).
- `done_ack` is self-clearing; writing 1 to bit 3 of `BLIT_CTRL` gates the sticky `done` edge for hosts that poll.

### 3.3 Source/store RAM

- 512 × 16-bit, dual-port inferred Mem.
- **Write port:** driven by host bus writes to `0x0C10..0x0D0F`.
- **Read port:** driven by blitter engine read address during COPY mode.
- At 8192 bits, this is still LUT-RAM inferable on Gowin (same as Task 47's 64×16 staging at 1024 bits). Gowin typically maps up to ~16–32 Kbit to distributed RAM.

### 3.4 Rectangular addressing FSM

The FSM maintains two counters:
- `colCounter`  — 0 .. WIDTH
- `rowCounter`  — 0 .. HEIGHT

State machine:
```
IDLE → RUN → DONE → IDLE
```

In `RUN`:
```scala
val srcAddr = srcBase + rowCounter * srcStride + colCounter
val dstAddr = dstBase + rowCounter * dstStride + colCounter
io.blitAddr := dstAddr
io.blitData := Mux(modeReg === RECT_FILL, fillReg, srcRam.readAsync(srcAddr))
io.blitWr   := !io.busBusy
```

Row/column advance:
- When `!busBusy` and `blitWr` fires:
  - If `colCounter == widthReg`: `colCounter := 0; rowCounter := rowCounter + 1`
  - Else: `colCounter := colCounter + 1`
- When `rowCounter == heightReg` and `colCounter == widthReg`: transition to `DONE`.

**Stride semantics:**
- `DST_STRIDE = WIDTH + 1` → packed rows (no gap).
- `DST_STRIDE > WIDTH + 1` → skip words between rows (e.g., tilemap stride).
- `SRC_STRIDE` same for source RAM.

### 3.5 LINE_FILL mode (mode = 2)

A convenience sub-mode that treats `HEIGHT = 0` and `DST_STRIDE = 1` implicitly, reducing the 2-D counters to the Task 47 linear case but with the wider control surface. This lets the host use the blitter for simple linear fills without re-learning the Task 47 register map.

### 3.6 Lowest-priority arbitration

Same pattern as Task 47:
```scala
val blitWr = blitterEngine.io.blitWr
val effWrite = (extHit || copperPopped || dmaWr || blitWr)
```

**DMA vs blitter co-arbitration:** Both run at lowest priority. If both are active in the same cycle, priority is fixed: `dmaWr` wins over `blitWr` (or vice versa — the artifact recommends `dmaWr > blitWr` to preserve Task 47 latency). The loser holds its counters and resumes on the next free cycle.

```scala
val effAddr = Mux(extHit,      io.regBus.addr,
              Mux(copperPopped, copperAddr,
              Mux(dmaWr,        dmaEngine.io.dmaAddr,
                              blitterEngine.io.blitAddr)))
```

### 3.7 Status integration

`evBus` expansion (currently 16 bits):
- Bit 8: `DMA_DONE` (Task 47)
- Bit 9: `BLIT_DONE` — new
- Bit 10: `BLIT_BUSY` live — host can read via future status word; not sticky

The blitter `done` pulse is one cycle, OR'd into `evBus` bit 9, latched into `statusStickyReg`.

`STATUS_ENABLE` (`0x0321`) gets a matching bit 9 IRQ mask.

### 3.8 Scope compliance: what is NOT included

- **No read-back from bus-writable structures.** The blitter cannot read sprite descriptors or scroll tables directly; the host must load the source data into `0x0C10..0x0D0F` first.
- **No pixel blending / alpha / minterms.** This is a block-transfer engine, not a raster-op unit.
- **No source clipping.** The host must ensure `SRC_ADDR + HEIGHT*SRC_STRIDE + WIDTH` fits inside 512 words.
- **No overlap detection.** If destination overlaps source RAM, behavior is undefined (same as classic blitters).

---

## 4. Register-Bus Specification Update

Add the following rows to `MODE0_REGISTER_BUS_SPEC.md` §3:

| Address | Name | Description | Task | Source files |
|---|---|---|---|---|
| `0x0C00` | `BLIT_CTRL` | `{done_ack[3], mode[2:1], go[0]}` | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C01` | `BLIT_WIDTH` | Words per row minus 1 (10 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C02` | `BLIT_HEIGHT` | Rows minus 1 (10 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C03` | `BLIT_DST_ADDR` | Destination start address (15 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C04` | `BLIT_DST_STRIDE` | Destination row increment (15 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C05` | `BLIT_SRC_ADDR` | Source start in local RAM (9 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C06` | `BLIT_SRC_STRIDE` | Source row increment (9 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C07` | `BLIT_FILL_VAL` | Fill constant (16 bits) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |
| `0x0C10..0x0D0F` | Blitter source/store RAM (512 × 16-bit) | Task 49 | `VdpTop.scala`, `BlitterEngine.scala` |

Update `STATUS_STICKY` bit layout §3.1.1:
- Bit 9: `BLIT_DONE` — sticky, write-1-to-clear
- Bit 10: `BLIT_BUSY` — live read-only (not sticky)

---

## 5. Validation Plan

### 5.1 Unit simulation (`BlitterEngineSim`)

A standalone `Shim` that wraps `BlitterEngine` and replicates the VdpTop `effWrite` mux + bus decode exactly.

| Case | What it proves | Expected |
|---|---|---|
| 1 | RECT_FILL 4×3 region with `0xABCD` | 12 writes at correct (addr, data) with `DST_STRIDE=4` |
| 2 | RECT_COPY 2×2 from source RAM offsets 0,1,4,5 → destination packed | 4 writes with correct source data |
| 3 | Strided copy: `SRC_STRIDE=8`, `DST_STRIDE=8`, `WIDTH=3`, `HEIGHT=2` | Row gap respected; writes land at dst+0..3 then dst+8..11 |
| 4 | Pause under `busBusy`: assert busy after 3 writes; verify counter holds, then resume | Total 12 writes, no duplicates, order preserved |
| 5 | LINE_FILL mode (`mode=2`, `WIDTH=7`, `HEIGHT=0`) | 8 consecutive writes, bit-identical to Task 47 linear FILL semantics |
| 6 | Done pulse is exactly one cycle wide | `done` high for 1 cycle, `busy` falls same cycle |
| 7 | Zero-size (`WIDTH=0`, `HEIGHT=0`) → single write | 1 write, then done |

### 5.2 Regression simulation

- `VdpTopSim`: blitter inactive (all control regs at reset 0) → bit-identical to pre-Task-49.
- `FourLayerCompositorSim`: unchanged, passes.

### 5.3 Tang Nano 20K build / PnR

- Build Scenario 5 with blitter instantiated but inactive.
- Target: 0 errors, timing closes.
- Resource delta estimate:
  - LUTs: +~150–250 (rectangular counter + stride multiplier + priority mux widening)
  - FFs: +~60 (row/col counters + control regs)
  - BSRAM: 0 (512×16 = 8192 bits → LUT-RAM inferred, same as Task 47)

### 5.4 Hardware proof

**Option A (preferred, same precedent as Tasks 46–48):** sim-proven primitive + regression-neutral Sc5 hardware capture accepted as sufficient.

**Option B (if audit/PM requests):** bounded Copper program that:
1. Loads a 4×4 tile pattern into blitter source RAM (`0x0C10..0x0C1F`)
2. Programs `BLIT_DST_ADDR = 0x0900` (scroll table), `WIDTH=3`, `HEIGHT=3`, `DST_STRIDE=4`
3. Triggers RECT_COPY
4. Verifies scroll-table bands show the copied pattern on the next frame

Option B is a scenario-level test and is adjacent to the "no platform-specific adapter" boundary; it should only be attempted with PM sign-off.

---

## 6. Files to Touch

| File | Change |
|---|---|
| `hw/spinal/spinalhdlvdp/BlitterEngine.scala` | **New** — rectangular blitter FSM + control regs + 512-word source RAM |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` | Instantiate `BlitterEngine`; merge `blitWr` into `effWrite`/`effAddr`/`effData`; decode `0x0C00..0x0D0F`; wire `blitDone` into `evBus` bit 9 |
| `hw/spinal/spinalhdlvdp/BlitterEngineSim.scala` | **New** — 7-case unit proof |
| `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` | Add 9 rows for `0x0C00..0x0D0F` and update `STATUS_STICKY` layout |

**Files NOT touched (scope compliance):**
- `TopTang20kHdmi.scala` (no new top-level IO)
- `DmaEngine.scala` (unchanged; coexists)
- `SpriteEvaluator.scala`, `ScrollTable.scala`, `BasicPatternSource.scala`
- `RegBusArbiter.scala`, `Mode0RegBus.scala`

---

## 7. Risk Assessment

| Risk | Mitigation |
|---|---|
| Stride multiplier adds combinational path | Use adder, not multiplier: `nextDst = dstBase + rowCounter * dstStride` implemented as accumulator (`rowBase += dstStride` each row). Width is small (10×15 bits) so Gowin maps to a few LUTs. |
| 512-word source RAM consumes BSRAM | 8192 bits is below typical Gowin BSRAM threshold; expect LUT-RAM inference (same positive outcome as Task 47). |
| DMA vs blitter co-arbitration deadlocks | Fixed priority `dmaWr > blitWr`; both hold counters when blocked. No cross-dependencies. |
| Address space collision with future tasks | `0x0C00..0x0D0F` is well above current allocations and below the `0x1000..0x7FFF` reserved expansion block. |

---

## 8. Open Questions

1. **DMA vs blitter priority ordering:** Should `dmaWr` win over `blitWr` (preserving Task 47 latency) or should blitter win (larger transfers prefer lower latency)? The artifact recommends `dmaWr > blitWr` for backward compatibility; PM may override.
2. **Hardware proof depth:** Same open question as Tasks 46–48 — no existing scenario programs the blitter. Sim-proven + regression-neutral hardware is the default closure path per precedent; a visible Copper-driven blit test is a scenario-level addition requiring PM/audit approval.
3. **Source RAM size:** 512 words is sufficient for a 32×16 tile pattern (512 words) or a 64×8 band. If audit finds this too small, expansion to 1K words (`0x0C10..0x0E0F`) is address-space compatible.
