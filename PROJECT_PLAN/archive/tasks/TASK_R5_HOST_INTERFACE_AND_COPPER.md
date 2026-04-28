# TASK_R5_HOST_INTERFACE_AND_COPPER.md

**Status:** CLOSED (`32a87ff`) — Host interface and Copper implemented, audited, and integrated  
**Created:** 2026-04-14  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef

---

## 1. Task Name

R5 Host Interface + Copper Coprocessor

---

## 2. Purpose

Provide a **safe, host-programmable control surface** for the VDP and a **minimal copper coprocessor** for mid-frame raster effects. This closes the gap between the external MCU/CPU and the internal pixel pipeline, and lays the register-write infrastructure required by all upcoming primitives (planar, shuffled, affine, raster effects).

Architectural rule:
- the external host owns command, control, status, and asset upload
- the VDP owns pixel generation, composition, raster timing, and beam-synchronous behavior
- this interface exists to let a host program the video processor, not to move display processing into firmware

**Why now:**
- R1 through R4.1 have built the pixel pipeline, but every register and linestate update is still hardcoded in `TopTang20kHdmi.scala`.
- Without a host interface, planar/shuffled/affine tasks would require FPGA rebuilds for every scene change, blocking external integration.
- The copper is the proven 2D-VDP pattern (icestation-32, Gameduino, Genesis) for scanline effects; adding it now avoids a later rewrite of the register-write path.

---

## 3. Primitive Boundary

### In Scope

- **HostInterface component**
  - QSPI clock-domain adapter + **16-entry command FIFO**
  - Indirect register access: `VDP_ADDR`, `VDP_DATA`, `VDP_INC`, `VDP_STATUS`, `VDP_CTRL`
  - CommandParser in pixel domain that applies writes at the **safe boundary** (`hCounter === 0`, see §7)
- **Copper component**
  - 4-instruction engine: `WAIT`, `WRITE`, `WRITE_SEQ`, `JUMP`
  - **1KB program RAM** (512 × 16-bit words), host-writable when disabled
  - Executes in pixel clock domain, enqueueing register writes into the same safe-boundary path as the host parser
- **Unified register-write bus**
  - Replaces raw `lsWriteAddr`/`lsWriteData`/`lsWriteEnable` in `VdpTop`
  - Drives linestate, palette, scroll registers, layer enables, and copper config
- **Top-level wiring**
  - Instantiate `HostInterface` and `Copper` in `TopTang20kHdmi`
  - Wire QSPI pads (or loopback/sim stubs) at the top level
- **Sim + hardware proof**

### Explicitly Out of Scope

- NO QSPI protocol bit-bang implementation inside the VDP (assume an external QSPI controller block or FPGA hard IP)
- NO DMA/autonomous blitter (R6 concern)
- NO audio path integration
- NO sprite attribute upload via copper in this task (copper can write the register base, but sprite DMA logic is separate)
- NO planar/shuffled fetch engine changes (R7 concern)
- NO affine matrix hardware (R8 concern)
- NO full scroll-table primitive (R4.2 concern)
- NO window/color-math stage (R6 concern)

---

## 4. Dependencies

- R1 Raster Trigger Unit (proven)
- R2 Two-Pass Sprite Evaluator (proven)
- R3 Static Fetch-Slot Scheduler (proven)
- R4 / R4.1 Tile + Attribute Fetch Primitive (proven, `9dfeb9f`)
- `LinestateStore` with prepare/commit (unchanged internal structure, but write interface will be wrapped)

---

## 5. Interfaces

### HostInterface

```scala
case class HostInterface() extends Component {
  val io = new Bundle {
    // QSPI / host clock domain side
    val hostAddr  = in  UInt(3 bits)   // register select (0=VDP_ADDR, 1=VDP_DATA, ...)
    val hostData  = in  Bits(16 bits)
    val hostWr    = in  Bool()
    val hostRd    = in  Bool()
    val hostRdata = out Bits(16 bits)

    // Pixel clock domain side
    val hCounter  = in  UInt(10 bits)
    val vCounter  = in  UInt(10 bits)

    // Unified register-write output (valid for one cycle at safe boundary)
    val regAddr   = out UInt(15 bits)
    val regData   = out Bits(16 bits)
    val regWr     = out Bool()
  }
}
```

**Host register map (`hostAddr`):**

| Offset | Name | Description |
|--------|------|-------------|
| 0 | `VDP_ADDR` | Target address in VDP space (15 bits; bit 15 reserved for future bank select) |
| 1 | `VDP_DATA` | Write data; triggers FIFO enqueue |
| 2 | `VDP_INC` | Auto-increment after write (default = 1) |
| 3 | `VDP_STATUS` | `{fifo_full, fifo_empty, vblank, line[9:2]}` |
| 4 | `HOST_CTRL` (`VDP_CTRL` in current code) | `{irq_enable, copper_enable, flush_fifo}` — host-side control shadow register |

### Copper

```scala
case class Copper() extends Component {
  val io = new Bundle {
    val hCounter  = in UInt(10 bits)
    val vCounter  = in UInt(10 bits)
    val enabled   = in Bool()         // from VDP_CTRL

    // Program RAM host write interface (only valid when enabled==false)
    val progAddr  = in UInt(9 bits)
    val progData  = in Bits(16 bits)
    val progWr    = in Bool()

    // Register-write output (same safe-boundary contract as HostInterface)
    val regAddr   = out UInt(15 bits)
    val regData   = out Bits(16 bits)
    val regWr     = out Bool()
  }
}
```

### Modified VdpTop Interface

The raw linestate write ports have been replaced with a single unified register-write port (already delivered in the current codebase):

```scala
val regWriteAddr   = in UInt(15 bits)
val regWriteData   = in Bits(16 bits)
val regWriteEnable = in Bool()
```

**Layering clarification:** `hostAddr` selects **host-side shadow/status registers** (`VDP_ADDR`, `VDP_DATA`, `VDP_INC`, `VDP_STATUS`, `HOST_CTRL`). Only writes to `VDP_DATA` enqueue entries into the internal VDP register-space FIFO. The target address for those queued writes is the value held in the host-side `VDP_ADDR` shadow register. The internal VDP register space (accessed by the pixel-domain `CommandParser`) is a separate 15-bit address map; `0x0310` inside that map is the VDP register-space control register consumed by `VdpTop`.

Inside `VdpTop`, the unified register-write bus is decoded directly:
- `0x0000–0x01DF` → `LinestateStore` prepare side
- `0x0200–0x027F` → palette RAM (128 entries, banked)
- `0x0300–0x030F` → scroll / layer-enable / raster control registers
- `0x0400–0x07FF` → copper program RAM (mirror of copper `progWr` path)

*(Exact decode ranges are illustrative; alignment was adjusted for simplicity in implementation.)*

---

## 6. Data Model

### Persistent

- **Command FIFO**: 16 entries × 31 bits `{addr[14:0], data[15:0]}` — dual-clock, GT-022 safe if implemented as registers or power-of-two inferred RAM.
- **Copper program RAM**: 512 × 16 bits (1KB) — power-of-two depth, GT-022 safe.
- **Host-side shadow registers**: `VDP_ADDR` (16 bits, top bit reserved), `VDP_INC` (8 bits), `VDP_CTRL` (8 bits).

### Per-Line / Dynamic

- **CommandParser buffer**: Up to 16 deferred `(addr, data)` pairs; all applied atomically at the safe boundary.
- **Copper state**: 10-bit target X, 10-bit target Y, 9-bit PC.

### FIFO / CommandParser Contract

The following behavior contract is enforced by `HostInterface` and relied upon by firmware:

1. **Queue contents**: The FIFO holds only **register-write queue entries** (`{addr[14:0], data[15:0]}`), not a generic command stream.
2. **Ordering**: Host writes preserve strict FIFO order. A `VDP_DATA` write enqueues the current `VDP_ADDR` value paired with the data word.
3. **Drain rate**: The pixel-domain `CommandParser` emits **one write per cycle** while the drain window is open (`hCounter === 0` or during vblank).
4. **Vblank behavior**: During vertical blanking, writes may drain continuously without buffering.
5. **`flush_fifo`**: The bit is named in the `HOST_CTRL` / `VDP_CTRL` register map but is **not yet implemented** in the current codebase. When implemented, the intended semantics are host-domain FIFO reset (discard all queued entries). Until then, hosts must avoid overflow by monitoring `fifo_full`.

### GT-022 Checklist

- [ ] Copper program RAM depth = 512 (power-of-two)
- [ ] FIFO depth = 16 (power-of-two) if inferred from block RAM
- [ ] Register-map decode does not introduce non-power-of-two initialized memories

---

## 7. Timing Model

### CommandParser Safe-Boundary Rule

> Host and copper register writes are **buffered** during active video and **applied atomically** at a safe boundary.

**Chosen safe boundary:** `hCounter === 0` (start of visible line, immediately after the line-buffer swap at `hTotal - 1`).

**Rationale:**
- The R4.1 scheduler fires its fetch grant at `hTotal - 1`.
- Applying register changes at `hTotal - 1` could race with the grant edge (BrightForge concern in #6814).
- Applying at `hCounter === 0` gives a clean one-cycle separation: swap happens at `hTotal - 1`, fetch starts, and the new linestate for the *next* line's fill becomes visible at `hCounter === 0` without colliding with the grant strobe.

**Vblank behavior:** During vertical blanking (`vCounter >= vActive`), writes are applied immediately without buffering.

### Copper Execution

- Runs in the pixel clock domain
- `WAIT` stalls until `(hCounter, vCounter)` match the internal target registers
- `WRITE` / `WRITE_SEQ` enqueue into the same safe-boundary buffer used by the host parser
- At `hCounter === 0`, any pending copper writes are applied alongside any pending host writes (host writes take precedence if both target the same address in the same cycle)

### Clock Domains

- **Host clock domain** → QSPI adapter → FIFO write port
- **Pixel clock domain** → FIFO read port → CommandParser + Copper → RegisterMap

---

## 8. Memory / Bandwidth Impact

### On-Chip RAM

| Resource | Size | Notes |
|----------|------|-------|
| Command FIFO | 16 × 31 ≈ 496 bits | Small; can be register-based or inferred RAM |
| Copper RAM | 512 × 16 = 8,192 bits = 1KB | Single power-of-two BRAM/BSRAM |
| Register-map decode | ~0 RAM | Pure combinational routing |

### Bandwidth Impact

- **Zero SDRAM impact** — host interface and copper operate entirely on-chip.
- **Zero pixel pipeline impact** — the register-write bus is a single-cycle event at `hCounter === 0` per line.

---

## 9. Platform Reuse

| Platform | Benefit |
|----------|---------|
| NES | Host can rewrite scroll / linestate for raster splits |
| C64 | Host can update color RAM indirectly |
| Genesis | Copper enables scanline palette updates and H-scroll effects |
| SNES | Host + copper together approximate HDMA behavior |
| Custom MCU | QSPI interface allows any microcontroller to drive the VDP |

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| CDC FIFO overflow / underflow | 16-entry FIFO + `fifo_full` status bit visible to host; sim asserts on overflow |
| Register-write races with R4.1 grant | Apply at `hCounter === 0`, not `hTotal - 1` |
| Copper program RAM host write during execution | Gate `progWr` with `!copper_enable` in hardware; sim asserts if violated |
| Copper WAIT never matches (hang) | Timeout or watchdog in sim; not required in hardware |
| Linestate applied mid-line | Safe-boundary parser guarantees `hCounter === 0` or vblank only |
| GT-022 violation on copper RAM | Explicit `require(isPow2(512))` |
| Sim-to-hardware CDC mismatch | Verilator sim must exercise both clock domains with independent stimuli |

---

## 11. Validation Plan

### Dedicated Sims

1. **HostInterfaceSim**
   - Burst 8 writes from host clock domain, verify they appear at `hCounter === 0` in pixel domain
   - Verify `VDP_INC` auto-increment behavior
   - Verify `fifo_full` assertion before overflow

2. **CopperSim**
   - Load a program that `WAIT`s for `y=100` and writes a palette entry
   - Verify the write appears exactly at `(x=0, y=100)` in pixel domain
   - Verify `WRITE_SEQ` emits multiple register writes
   - Verify `JUMP` restarts a loop correctly

3. **UnifiedRegMapSim**
   - Host writes linestate entry 10, verify `LinestateStore` prepare side updates
   - Host writes palette entry, verify palette RAM updates
   - Host writes layer-enable register, verify `VdpTop` behavior changes

### Regression Sims (must rerun)

- `VdpTopSim`
- `TileAttributeFetchSim`
- `SpriteEvaluatorSim`
- `RasterTriggerUnitSim`
- `FetchSlotSchedulerSim`

### Assertions Required

```scala
// FIFO overflow is impossible in correct host behavior, but assert in sim
assert(!fifoOverflow)

// Copper program writes only when disabled
assert(io.progWr -> !enabled)

// Register writes only at safe boundary or vblank
assert(io.regWr -> (hCounter === 0 || vCounter >= vActive))
```

---

## 12. Hardware Proof

### Static Proof Scene

**Pattern 6 (Grid)** with the copper disabled. Host uploads a small program that:
1. Sets `layer0ScrollX = 0`, `layer1ScrollX = 0`
2. Enables layer 0 only
3. Writes a palette entry so the grid lines are bright cyan on black

Expected: crisp 64-pixel grid, no tearing, uniform colors.

### Motion Proof Scene

**Pattern 5 (Checkerboard)** scrolling horizontally at 1 px/frame. Host writes `layer0ScrollX` via the indirect register interface once per frame during vblank.

Expected: smooth scroll, no jitter, checkerboard wraps seamlessly.

### Copper Proof Scene

Copper program loaded before enabling:
```
WAIT y=100
WRITE layer_enable, 0x01   // disable L1 below line 100
WAIT y=300
WRITE layer_enable, 0x03   // re-enable L1 above line 300
JUMP 0
```

Expected: static scene where the top 100 lines and bottom 180 lines show both layers, while the middle 200 lines show only layer 0 — a clean horizontal split that proves the copper can modify registers mid-frame.

---

## 13. Audit Questions

CyanPeak to verify:

1. **Scope compliance:** Is the copper limited to 4 instructions (`WAIT`, `WRITE`, `WRITE_SEQ`, `JUMP`)? No general-purpose ALU or branching beyond `JUMP`?
2. **CDC safety:** Is the host-to-pixel crossing implemented only through the dual-clock FIFO? No hidden combinational paths?
3. **Safe-boundary enforcement:** Are ALL register writes (host + copper) applied exclusively at `hCounter === 0` or during vblank?
4. **R4.1 grant race:** Does the register application at `hCounter === 0` avoid any collision with the scheduler grant at `hTotal - 1`?
5. **GT-022 compliance:** Is copper program RAM a power-of-two depth (512)? Is the FIFO depth a power-of-two?
6. **Copper RAM safety:** Can the host corrupt an executing copper program? (Should be gated by `copper_enable`.)
7. **Regression proof:** Do all existing sims pass after the `lsWrite*` interface is removed?
8. **Hardware proof:** Does the copper horizontal-split scene render correctly on Tang Nano 20K?

---

## 14. Constraints / Gotcha Check

- [x] **GT-022:** Copper RAM = 512 words (power-of-two)
- [x] **GT-022:** FIFO = 16 entries (power-of-two) or register-based
- [x] **CDC:** Host clock and pixel clock are treated as asynchronous
- [x] **Safe boundary:** No register write occurs mid-line (`hCounter > 0 && hCounter < hActive`)
- [x] **No hardware before sim:** All three sims must pass before Gowin build
- [x] **Palette preserved:** 24-bit RGB retained; no regression to 16-bit
- [x] **Cleanup:** Old `lsWrite*` ports fully removed or cleanly deprecated in `VdpTop`

---

## 15. Exit Condition

This task is done when the host interface (indirect registers + 16-entry FIFO + safe-boundary parser) and the copper coprocessor (4 instructions + 1KB RAM) are both integrated, all regression sims pass, and a hardware copper horizontal-split scene proves that mid-frame register writes work correctly.

---

## Short-Form Summary

```markdown
## Task
R5 Host Interface + Copper Coprocessor

## Purpose
Provide a safe host-programmable control surface and a minimal copper for
mid-frame raster effects, enabling external MCU integration and unlocking
all upcoming planar/affine/raster primitives.

## Scope
- in scope: HostInterface with indirect registers + 16-entry FIFO
- in scope: CommandParser applying writes at hCounter===0 or vblank
- in scope: Copper with WAIT/WRITE/WRITE_SEQ/JUMP + 1KB program RAM
- in scope: Unified register-write bus replacing raw linestate ports
- out of scope: QSPI bit-bang controller, DMA blitter, audio, planar fetch

## Dependencies
- R1–R4.1 all proven (9dfeb9f baseline)
- LinestateStore prepare/commit mechanism

## Interfaces
- Host: VDP_ADDR, VDP_DATA, VDP_INC, VDP_STATUS, VDP_CTRL
- Copper: progAddr/progData/progWr (host side), regAddr/regData/regWr (pixel side)
- VdpTop: regWriteAddr/regWriteData/regWriteEnable (unified bus)

## Timing
- Host writes cross into pixel domain via dual-clock FIFO
- All writes applied atomically at hCounter===0 or during vblank
- Copper executes in pixel domain, enqueuing writes into the same safe buffer

## Risks
- FIFO overflow (mitigate: 16 entries + full flag)
- Grant race (mitigate: apply at hCounter===0, not hTotal-1)
- GT-022 violation (mitigate: explicit power-of-two checks)

## Validation
- sim: HostInterfaceSim, CopperSim, UnifiedRegMapSim
- regression: VdpTopSim, TileAttributeFetchSim, SpriteEvaluatorSim,
  RasterTriggerUnitSim, FetchSlotSchedulerSim
- hardware: static grid, scrolling checkerboard, copper horizontal split

## Audit Focus
- Scope compliance, CDC safety, safe-boundary enforcement, R4.1 grant race
  avoidance, GT-022, copper RAM safety, regression proof, hardware copper split

## Exit Condition
This task is done when host interface + copper are integrated, all regression
sims pass, and a hardware copper horizontal-split scene proves mid-frame
register writes work correctly.
```
