# Task 40 — First Platform Adapter (C64 Raster+Sprite Smoke)

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Status:** artifact draft — awaiting CyanPeak audit  
**Coding authorized:** NO

---

## 1. Executive Summary

Tasks 45–49 closed the broad Mode0 substrate backlog. The hardware now has: raster triggers, sprite evaluation, four-layer composition, scroll tables, DMA, and a blitter. Task 40 is the first proof that this substrate can support a real platform adapter.

This artifact defines a **C64-style platform adapter** that exposes VIC-II-like register semantics on top of Mode0 primitives. The adapter is not a cycle-accurate emulator; it is an honest mapping that lets a host (or Copper) write familiar C64-style control registers and see correct raster-split + sprite behavior on the Tang Nano 20K.

**Scope boundary:** First adapter only. No cycle-accurate VIC-II claim. No additional platforms. No new broad substrate primitives unless a substrate gap is proven during adapter design.

---

## 2. Current State Analysis

### 2.1 Proven substrate primitives

| Primitive | Status | How C64 adapter will use it |
|---|---|---|
| RasterTriggerUnit (R1) | DONE | Raster IRQ at programmed line ($D012) |
| SpriteEvaluator (R2) | DONE | 32 sprite descriptors, 8 visible/line, slot priority |
| 4-Layer Compositor (Task 48) | DONE | L0 = C64 text/bitmap, L1 = color RAM overlay, sprites on top |
| Scroll Tables (Tasks 31/46) | DONE | Per-line X/Y offset for raster splits |
| DMA (Task 47) | DONE | Block copy for sprite/screen RAM uploads |
| Blitter (Task 49) | DONE | Rectangular tile fills for screen clears |
| Copper (R5.3) | DONE | Frame-start register programming |
| Status/IRQ (Task 35) | DONE | RASTER_MATCH sticky bit (already wired) |

### 2.2 C64 VIC-II behavior subset to emulate

The full VIC-II is too large for a first adapter. The artifact bounds emulation to the subset needed for an honest "two-bar split + sprites" demo:

| VIC-II Register | C64 Addr | Mode0 Mapping | In Scope? |
|---|---|---|---|
| Control 1 (den, bmm, ecm, rsel, yscroll) | $D011 | `VDP_CTRL` + `LAYER_ENABLE` + scrollY | Yes |
| Raster line | $D012 | `RasterTriggerUnit.triggerLine` | Yes |
| Sprite enable | $D015 | `SpriteEvaluator.descEnabled` slots 0..7 | Yes |
| Control 2 (mcm, csel, xscroll) | $D016 | `VDP_TILE_MODE` + scrollX | Partial |
| Sprite X position | $D000..$D00E | `SpriteEvaluator.descX` | Yes (8 slots) |
| Sprite Y position | $D001..$D00F | `SpriteEvaluator.descY` | Yes (8 slots) |
| Sprite color / multicolor | $D027..$D02E | `SpriteEvaluator` paletteBank | Partial |
| Sprite X MSB | $D010 | `descX` bit 8 (already 10-bit) | Yes |
| IRQ mask / status | $D019/$D01A | `STATUS_STICKY` / `STATUS_ENABLE` | Yes |
| Border color | $D020 | palette[0] or dedicated border reg | Yes |
| Background 0 color | $D021 | palette[1] | Yes |
| Screen RAM base | $D018 high nibble | `layer0` tileMap base | No — fixed for demo |
| Char set base | $D018 low nibble | `layer0` pattern base | No — fixed for demo |
| Sprite pointers | $07F8..$07FF | `SpriteEvaluator.descPatternIdx` | No — fixed for demo |

**Out of scope for Task 40:** sprite-sprite collision detection (registers exist but behavior is pass-through), sprite-background collision, full $D018 bank switching, badline emulation, light-pen, open borders, and interlace. These are honest gaps documented in §7.

### 2.3 Adapter architecture

The adapter is a **thin translation layer**, not a new rendering engine:

```
Host/Copper writes C64-style registers
         ↓
    C64Adapter translates to Mode0 register-bus writes
         ↓
    VdpTop consumes effAddr/effData/effWrite exactly as before
```

This means the adapter does not duplicate sprite evaluation, compositing, or fetch logic. It only translates register semantics.

---

## 3. Proposed Design

### 3.1 C64Adapter component

```scala
case class C64Adapter() extends Component {
  val io = new Bundle {
    // C64-style register write port (from host or Copper)
    val regAddr = in  UInt(8 bits)   // $D000..$D02F emulated
    val regData = in  Bits(8 bits)
    val regWr   = in  Bool()

    // Mode0 register bus output (merged into VdpTop's ext/copper path)
    val busAddr = out UInt(15 bits)
    val busData = out Bits(16 bits)
    val busWr   = out Bool()

    // Direct outputs that bypass the bus (fast paths)
    val rasterTriggerLine   = out UInt(10 bits)
    val rasterTriggerEnable = out Bool()
    val rasterTriggerClear  = out Bool()
  }
  ...
}
```

### 3.2 Register translation table

| C64 Addr | Name | Mode0 Target | Translation Rule |
|---|---|---|---|
| `$00` | SPRITE0_X | `0x0800` word 4 (X) | `descX(0) := regData` |
| `$01` | SPRITE0_Y | `0x0800` word 3 (Y) | `descY(0) := regData` |
| `$02` | SPRITE1_X | `0x0808` word 4 | `descX(1) := regData` |
| `$03` | SPRITE1_Y | `0x0808` word 3 | `descY(1) := regData` |
| ... | ... | ... | ... |
| `$0F` | SPRITE7_Y | `0x0838` word 3 | `descY(7) := regData` |
| `$10` | SPRITE_X_MSB | sprite descriptor bit 8 | `descX(s)(8) := regData(s)` |
| `$11` | CONTROL_1 | `0x0300`/`0x0301`/`scrollY` | `layerEnable(0) := den`, `scrollY := yscroll`, etc. |
| `$12` | RASTER | `RasterTriggerUnit` | `triggerLine := regData` |
| `$15` | SPRITE_ENABLE | `0x0800..0x083F` word 0 bit 0 | `descEnabled(s) := regData(s)` |
| `$16` | CONTROL_2 | `scrollX`/`mcm` | `scrollX := xscroll`, tile mode flags |
| `$19` | IRQ_STATUS | `STATUS_STICKY` read | passthrough / shadow |
| `$1A` | IRQ_MASK | `STATUS_ENABLE` | passthrough / shadow |
| `$20` | BORDER_COLOR | palette[0] or dedicated | `palette(0) := regData(3 downto 0)` |
| `$21` | BG_COLOR_0 | palette[1] | `palette(1) := regData(3 downto 0)` |

**Note on sprite descriptor mapping:** The current `SpriteEvaluator` uses 8 words per descriptor at `0x0800 + slot*8`. The adapter maps C64 X/Y/enable to the correct word offsets.

### 3.3 Two-bar raster split demo

The hardware proof is a **Scenario N** (`TopTang20kHdmiScenarioNVerilog`) that:

1. Programs `RasterTriggerUnit` at line 240 (mid-screen).
2. Uses the trigger pulse to swap:
   - `layer1ScrollX` (horizontal offset for lower bar)
   - `layer1ScrollY` (vertical offset for lower bar)
   - `paletteBank` for L0 (different tile colors in lower bar)
3. Keeps 4 sprites bouncing in both bars.

This is achieved by wiring the `RasterTriggerUnit.io.pending` signal into a small scenario-side state machine that writes the scroll/palette registers via the adapter on the line after the trigger fires.

### 3.4 Integration in VdpTop

Two integration options were considered:

**Option A (preferred):** `C64Adapter` is instantiated **outside** `VdpTop`, in `TopTang20kHdmi.scala`. It receives C64 register writes from a scenario-specific animator block and emits Mode0 bus writes that are fed into the existing `regBusArbiter`. This keeps `VdpTop` completely adapter-agnostic.

**Option B (rejected):** `C64Adapter` lives inside `VdpTop`. This would couple the substrate to the first adapter and violate the "Mode0 is generic" architectural rule.

**Selected: Option A.** The adapter is a peer of the QSPI decoder and Copper — another bus master feeding into `RegBusArbiter`. `VdpTop` needs no changes.

### 3.5 Register-bus address space for adapter

The adapter does not need new address space in the Mode0 map. It consumes C64 addresses internally and emits existing Mode0 addresses. For host visibility, a small **adapter shadow RAM** (256 bytes, `0x0E00..0x0EFF`) can mirror the last written C64 register values so the host can read back what it wrote.

---

## 4. Validation Plan

### 4.1 Unit simulation (`C64AdapterSim`)

| Case | What it proves | Expected |
|---|---|---|
| 1 | Write `$D012 = 100` → `RasterTriggerUnit.triggerLine = 100` | PASS |
| 2 | Write `$D015 = 0x0F` → sprite enable bits 0..3 set in descriptor words | PASS |
| 3 | Write `$D000 = 200`, `$D001 = 150` → sprite 0 X=200, Y=150 | PASS |
| 4 | Write `$D011` with DEN=0 → `LAYER_ENABLE` bit 0 cleared | PASS |
| 5 | Two-bar split: trigger at line 120, adapter swaps scrollY on next line | PASS |

### 4.2 Regression simulation

- `VdpTopSim`: unchanged (adapter is outside VdpTop).
- `FourLayerCompositorSim`: unchanged.
- `BlitterEngineSim`: unchanged.

### 4.3 Tang Nano 20K build

- Build a new Scenario N that includes the adapter + two-bar split + sprites.
- Target resource delta: minimal (adapter is combinational/register translation only; no new rendering logic).
  - LUTs: +~50–100 (address decode + register shadow)
  - FFs: +~200 (8-bit shadow registers for 32 C64 registers)
  - BSRAM: 0 (shadow RAM is 256 bytes → 2048 bits → LUT-RAM)

### 4.4 Hardware proof

- **Visible two-bar split:** upper half shows one tile pattern/scroll, lower half shows another.
- **Sprites visible in both bars:** 4 sprites bouncing, not clipped by the split.
- **30s capture stability:** `analyze.py` reports `freeze=0`, no visual corruption.

---

## 5. Files to Touch

| File | Change |
|---|---|
| `hw/spinal/spinalhdlvdp/C64Adapter.scala` | **New** — C64 register translation + shadow RAM |
| `hw/spinal/spinalhdlvdp/C64AdapterSim.scala` | **New** — 5-case unit proof |
| `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala` | Add Scenario N with adapter + two-bar split demo |
| `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` | Document adapter shadow RAM `0x0E00..0x0EFF` if used |

**Files NOT touched (scope compliance):**
- `VdpTop.scala` — adapter is outside the substrate
- `SpriteEvaluator.scala`, `RasterTriggerUnit.scala`, `BlitterEngine.scala`, `DmaEngine.scala`
- `RegBusArbiter.scala`, `Mode0RegBus.scala`

---

## 6. Risk Assessment

| Risk | Mitigation |
|---|---|
| Adapter grows into a full VIC-II emulator | Hard scope boundary: only 2-bar split + 8 sprites. No badline, no open border, no full $D018 banking. |
| `TopTang20kHdmi.scala` scenario bloat | Scenario N is a bounded demo. If it grows beyond ~200 lines, split the animator FSM into a separate `C64DemoAnimator.scala`. |
| C64 register write timing vs. Mode0 safe boundary | Adapter writes go through the same `RegBusArbiter` as QSPI and Copper, so they are subject to the same `hCounter===0` safe-boundary commit in VdpTop. No new timing risk. |
| Host reads C64 registers back | Shadow RAM provides read-back without adding read ports to Mode0 structures. |

---

## 7. Honest Gap Analysis

The following C64 features are **deliberately not emulated** in Task 40. They are documented here so future adapter hardening tasks can reference them:

| Feature | Why omitted | Future task candidate |
|---|---|---|
| Sprite-sprite collision IRQ | Status bit exists (Task 29) but adapter does not route it to C64 $D019 format | Task 40b |
| Sprite-background collision IRQ | Same as above | Task 40b |
| Full $D018 bank switching | Requires dynamic tileMap/pattern base pointers in L0 | Task 40b or substrate task |
| Badline / DMA steal emulation | Requires cycle-accurate bus arbitration not in Mode0 | Out of scope for all adapters |
| Light pen | No hardware input for it on Tang Nano 20K | Out of scope |
| Open borders / vertical blank tricks | Requires exact VIC-II timing | Out of scope |
| Interlace mode | Mode0 does not support interlace | Out of scope |

---

## 8. Open Questions

1. **Scenario number:** What Scenario ID should the two-bar split demo use? Suggested: Scenario 20 (first free ID above existing 0–18 range).
2. **Adapter shadow RAM:** Is `0x0E00..0x0EFF` acceptable, or should the adapter use a different address? It is above the blitter range (`0x0C00..0x0D0F`) and below the reserved expansion block (`0x1000..0x7FFF`).
3. **Sprite color mapping:** C64 sprite colors are 4-bit (16 colors). Mode0 uses 4-bit index + 3-bit palette bank. The adapter can map C64 sprite color to palette bank 0, index = regData(3 downto 0). Is this sufficient for the demo?
