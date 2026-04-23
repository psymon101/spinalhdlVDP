# Task 47 — DMA-Style Transfer Primitive

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Status:** Awaiting CyanPeak audit  
**Coding authorized:** NO — implementation waits for artifact audit PASS + PM authorization  

---

## 1. Executive Summary

Today every write to internal VDP state (sprite descriptors, scroll tables, linestate, control registers) is a single 16-bit word driven by the host over QSPI or by the Copper FIFO. There is no block-transfer primitive: uploading 32 sprite descriptors requires 256 individual bus transactions, and clearing a tilemap region requires the host to write each word.

Task 47 adds a bounded **DMA engine** inside `VdpTop` that autonomously sequences block writes after the host programs source/destination/length registers. It supports two modes:

1. **FILL** — write a constant 16-bit value to a consecutive address range.
2. **COPY** — read from a small internal staging buffer and write to a consecutive destination range.

The DMA runs in the pixel-clock domain, generates one write per cycle, and yields to external (QSPI) and Copper writes via lowest-priority insertion into the existing `effWrite` path. Completion signals through the existing `statusSticky` / IRQ surface.

**Scope boundary:** Block-transfer primitive only. No blitter raster ops (no pixel blending, no source-masking, no RLE), no platform-specific DMA command sets.

---

## 2. Current State Analysis

### 2.1 Register-bus architecture (Task 32b)

```scala
// TopTang20kHdmi.scala
val regBusArbiter = RegBusArbiter(3)   // 0=bootstrap > 1=qspi > 2=animator
video.io.regBus <> regBusArbiter.io.mixed

// VdpTop.scala
val extHit     = io.regBus.enable
val effWrite   = (extHit || copperPopped)
val effAddr    = Mux(extHit, io.regBus.addr, copperAddr)
val effData    = Mux(extHit, io.regBus.data, copperData)
```

All consumers (linestate, scroll tables, sprite descriptors, control registers) decode `effAddr`/`effData`/`effWrite`. Every write is exactly one 16-bit word.

### 2.2 Why DMA is needed

| Workload | Words today | Host cycles today | With DMA |
|---|---|---|---|
| Clear 32 sprite descriptors | 256 | 256 QSPI transactions | 1 setup + DMA runs autonomously |
| Zero 128-entry scroll table | 128 | 128 QSPI transactions | 1 setup + DMA runs autonomously |
| Fill 480-line linestate | 480 | 480 QSPI transactions | 1 setup + DMA runs autonomously |
| Copy 16 descriptors to new slots | 128 read + 128 write | 256 host transactions | 128 staging writes + 1 setup + DMA copy |

### 2.3 Available register-bus address space

The H-scroll table uses `0x0900..0x09FF`, V-scroll `0x0A00..0x0AFF`. The next contiguous block is `0x0B00..0x0BFF`, confirmed free in both `MODE0_REGISTER_BUS_SPEC.md` and `VdpTop.scala` decode logic.

Proposed allocation:
- `0x0B00` — `DMA_DST` (destination start address, 15 bits)
- `0x0B01` — `DMA_LEN` (transfer length in words minus 1, 10 bits; 0 = 1 word)
- `0x0B02` — `DMA_FILL` (fill value for FILL mode, 16 bits)
- `0x0B03` — `DMA_CTRL` (control / trigger / status)
- `0x0B10..0x0B4F` — DMA staging buffer (64 × 16-bit words, for COPY mode source)

---

## 3. Architecture

### 3.1 Target state

```scala
VdpTop:
  // Existing path unchanged except effWrite/effAddr/effData now include dma
  val effWrite = (extHit || copperPopped || dmaWr)
  val effAddr  = PriorityMux(extHit -> io.regBus.addr,
                             copperPopped -> copperAddr,
                             dmaWr -> dmaAddr)
  val effData  = PriorityMux(extHit -> io.regBus.data,
                             copperPopped -> copperData,
                             dmaWr -> dmaData)

  // DMA engine
  val dma = DmaEngine()
  dma.io.busAddr := effAddr
  dma.io.busData := effData
  dma.io.busWr   := effWrite && dmaRangeHit   // control register writes
  dma.io.dmaAddr := /* sequential dest during transfer */
  dma.io.dmaData := /* fill value or staging buffer read */
  dma.io.dmaWr   := /* true during active transfer */
  dma.io.busy    := /* routed to status sticky bit */
  dma.io.done    := /* routed to status sticky bit */
```

### 3.2 `DmaEngine` module

```scala
case class DmaEngine() extends Component {
  val io = new Bundle {
    // Control register write port (from bus decode)
    val busAddr = in UInt(15 bits)
    val busData = in Bits(16 bits)
    val busWr   = in Bool()

    // DMA-generated write port (merged into effWrite)
    val dmaAddr = out UInt(15 bits)
    val dmaData = out Bits(16 bits)
    val dmaWr   = out Bool()

    // Status
    val busy = out Bool()
    val done = out Bool()
  }

  // Control registers
  val dstReg  = Reg(UInt(15 bits)) init 0
  val lenReg  = Reg(UInt(10 bits)) init 0
  val fillReg = Reg(Bits(16 bits)) init 0
  val ctrlReg = Reg(Bits(16 bits)) init 0   // {go[0], mode[1], done_ack[2]}

  // Staging buffer: 64 x 16, dual-port (host write, DMA read)
  val staging = Mem(Bits(16 bits), 64)

  // FSM
  val idle :: running :: done :: Nil = Enum(3)
  val state = Reg(idle) init idle
  val counter = Reg(UInt(10 bits)) init 0

  // ... decode and FSM logic ...
}
```

### 3.3 Priority and bus sharing

The DMA has **lowest priority** in the `effWrite` path:
1. External (QSPI/bootstrap) writes always win
2. Copper FIFO pops win if no external write
3. DMA writes only when neither external nor Copper is active

If an external write arrives during a DMA transfer, the DMA pauses for that cycle (counter does not increment, `dmaWr` is false) and resumes on the next free cycle. This guarantees zero interference with host commands or Copper timing.

### 3.4 Completion signaling

Two new bits in the existing `statusSticky` word (0x0320):
- Bit 8: `DMA_DONE` — set when transfer completes. Write-1-to-clear.
- Bit 9: `DMA_BUSY` — set while transfer is active. Read-only (not sticky; reflects live state).

These bits fit in the currently unused upper bits of the 16-bit sticky word and reuse the existing `STATUS_ENABLE` / IRQ mask infrastructure.

---

## 4. Exact Changes Required

### 4.1 `VdpTop.scala`

**Change A:** Instantiate `DmaEngine`.

```scala
val dmaEngine = DmaEngine()
```

**Change B:** Add DMA control register decode.

```scala
val dmaRangeHit = effWrite &&
  (effAddr >= U(0x0B00, 15 bits)) &&
  (effAddr <  U(0x0B10, 15 bits))

dmaEngine.io.busAddr := effAddr
dmaEngine.io.busData := effData
dmaEngine.io.busWr   := dmaRangeHit
```

**Change C:** Wire staging buffer write port.

```scala
val stagingRangeHit = effWrite &&
  (effAddr >= U(0x0B10, 15 bits)) &&
  (effAddr <  U(0x0B50, 15 bits))
when(stagingRangeHit) {
  staging.write(
    address = (effAddr - U(0x0B10, 15 bits))(5 downto 0).asUInt,
    data    = effData
  )
}
```

**Change D:** Merge DMA writes into `effWrite`/`effAddr`/`effData` with lowest priority.

Current:
```scala
val effWrite = (extHit || copperPopped)
val effAddr  = Mux(extHit, io.regBus.addr, copperAddr)
val effData  = Mux(extHit, io.regBus.data, copperData)
```

New:
```scala
val dmaWr = dmaEngine.io.dmaWr
val effWrite = (extHit || copperPopped || dmaWr)
val effAddr  = PriorityMux(
  extHit       -> io.regBus.addr,
  copperPopped -> copperAddr,
  dmaWr        -> dmaEngine.io.dmaAddr
)
val effData  = PriorityMux(
  extHit       -> io.regBus.data,
  copperPopped -> copperData,
  dmaWr        -> dmaEngine.io.dmaData
)
```

**Change E:** Add DMA status bits to sticky word.

Expand `evBus` from 10 bits to include `dmaEngine.io.done`:
```scala
val evBus = (B(0, 8 bits) ## dmaEngine.io.done ##
             spriteBgHitPulse ## sprite0HitPulse ##
             ...)
```

And expose `dmaEngine.io.busy` as a read-only bit in the status word (non-sticky, combinational).

### 4.2 `MODE0_REGISTER_BUS_SPEC.md`

Add rows:

| Range | Purpose | Task | Source ref |
|---|---|---|---|
| `0x0B00` | `DMA_DST` — destination start address | Task 47 | `VdpTop.scala` |
| `0x0B01` | `DMA_LEN` — transfer length minus 1 (10 bits) | Task 47 | `VdpTop.scala` |
| `0x0B02` | `DMA_FILL` — fill value (16 bits) | Task 47 | `VdpTop.scala` |
| `0x0B03` | `DMA_CTRL` — {go[0], mode[1], done_ack[2]} | Task 47 | `VdpTop.scala` |
| `0x0B10..0x0B4F` | DMA staging buffer (64 × 16-bit) | Task 47 | `VdpTop.scala` |

### 4.3 `DmaEngine.scala` (new)

Self-contained module with FSM, control registers, and staging buffer port.

### 4.4 Files that do NOT change

- `TopTang20kHdmi.scala` — DMA is entirely inside VdpTop; no arbiter change needed.
- `RegBusArbiter.scala` — master count stays 3.
- `Mode0RegBus.scala` — bundle unchanged.
- `QspiDecoder.scala` — no new QSPI sel codes needed.

---

## 5. Resource Impact

| Item | Current (Task 46) | After Task 47 | Delta |
|---|---|---|---|
| Register (FF) | ~5960 / 15915 (38%) | ~6020 | +~60 FFs (control regs + FSM) |
| Logic (LUT) | ~8175 | ~8250 | +~75 LUTs (FSM + mux + adder) |
| BSRAM | 7 / 46 (16%) | 7 / 46 | 0 — staging buffer is 64×16 = 1024 bits, inferred as LUT-RAM |

**Headroom is ample.** The DMA is a small sequential FSM.

---

## 6. Validation Plan

### 6.1 Simulation validation

**6.1.1 `DmaEngineSim.scala` (new)**

Unit sim for the `DmaEngine` module:
- **Case A:** FILL mode — program `DMA_DST=0x0800`, `DMA_LEN=7`, `DMA_FILL=0xABCD`, trigger. Verify 8 writes to 0x0800..0x0807 with data=0xABCD.
- **Case B:** COPY mode — preload staging[0..3] with {0x1111, 0x2222, 0x3333, 0x4444}, program `DMA_DST=0x0900`, `DMA_LEN=3`, mode=COPY, trigger. Verify 4 writes to 0x0900..0x0903 with correct data.
- **Case C:** Pause-on-external-write — start FILL, inject an external write mid-transfer, verify DMA pauses (counter holds), then resumes.
- **Case D:** Zero-length — `DMA_LEN=0` → single write, `DMA_DONE` sets immediately after.
- **Case E:** Done sticky — verify `DMA_DONE` bit sets in `statusSticky` and clears on write-1-to-clear.

**6.1.2 `VdpTopSim` regression**

Existing scenarios (Sc1, Sc5, Sc8) must pass unchanged with DMA inactive (control regs at 0).

### 6.2 Hardware validation

**6.2.1 Build**

`make -C fpga/tang20k SCENARIO=5 all` (or any existing scenario).
- Must complete with 0 errors.
- Timing must remain closed.

**6.2.2 Scenario selection**

A small modification to an existing scenario's Copper program:
- At frame start, write `DMA_FILL=0x0000`, `DMA_DST=0x0800` (sprite descriptor block), `DMA_LEN=31`, `DMA_CTRL=GO|FILL`.
- This clears 32 sprite descriptor slots in ~1.3 µs instead of 32 QSPI transactions.
- Hardware proof: verify that previously-enabled sprites disappear after the Copper triggers the DMA fill.

Alternatively, a Pico-side QSPI script that triggers the same DMA fill and then enables a sprite.

**6.2.3 Hardware proof evidence**

- Direct capture or monitor screenshot showing the DMA effect.
- Bitstream md5 and HEAD commit hash.

---

## 7. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| DMA pauses too long due to frequent Copper/external writes | Low | Medium | DMA yields one cycle at a time; worst case is transfer takes longer, not corruption. Host can check `DMA_BUSY` before assuming completion. |
| `PriorityMux` on `effAddr`/`effData` adds combinational delay | Very low | Medium | 3:1 PriorityMux is tiny. If timing is tight, register `dmaAddr`/`dmaData` one cycle early. |
| Staging buffer too small (64 words) for some workloads | Low | Low | 64 words covers 8 sprite descriptors or 64 scroll-table entries. For larger blocks, host chains multiple DMA transactions. Document in spec. |
| Address overlap with future tasks | Very low | Medium | `0x0B00..0x0B4F` is confirmed free. Document in `MODE0_REGISTER_BUS_SPEC.md`. |

---

## 8. Out-of-Scope / Deferred

Per TASKS.md boundary:
- **Blitter raster ops** — no pixel blending, no source masking, no RLE, no transparency logic.
- **Platform-specific DMA command sets** — no Genesis-style VSRAM DMA, no SNES-style HDMA transfer.
- **SDRAM DMA** — transfers are within the internal register/Mem address space only. SDRAM bulk upload remains the QSPI upload path (Task 34).
- **Burst / priority-preempt modes** — one word per cycle, lowest priority, no configurator complexity.

---

## 9. Audit Checklist for CyanPeak

- [ ] DMA architecture stays within VdpTop; no TopTang20kHdmi or arbiter changes.
- [ ] Priority ordering (ext > copper > dma) is correct and preserves existing behavior.
- [ ] Register-bus address `0x0B00..0x0B4F` is free and correctly decoded.
- [ ] Staging buffer size (64 words) is documented and justified.
- [ ] Status signaling reuses existing sticky/IRQ infrastructure.
- [ ] Validation plan covers fill, copy, pause, zero-length, and done-sticky cases.
- [ ] Scope boundary excludes blitter ops, SDRAM DMA, and platform-specific command sets.

---

## 10. Next Steps (Post-Audit)

1. **CyanPeak audit:** Rule PASS / HOLD / FAIL on this artifact.
2. **BronzeGate PM authorization:** If audit PASS, authorize BrightForge to implement.
3. **BrightForge implementation:** Apply §4 changes, run sims, synthesize, capture hardware evidence.
4. **CyanPeak implementation audit:** Audit implementation evidence.
5. **CoralReef ledger sync:** Update `TASKS.md` to mark Task 47 DONE at implementation commit.
