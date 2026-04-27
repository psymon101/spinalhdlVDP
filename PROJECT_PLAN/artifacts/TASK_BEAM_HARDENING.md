# Task — Beam-Driven Automation Hardening

**Artifact version:** 1.0
**Author:** CoralReef
**Date:** 2026-04-27
**Audit:** PASS #8656 (CyanPeak)
**Status:** IN-PROGRESS (Implementation)
**Owner:** BrightForge

---

## 1. Why This Task Exists

Current Mode0 beam-driven primitives are rated `Usable` but have gaps that block honest Amiga/SNES/Genesis adapter claims:
- Copper WAIT is line-only (hCounter==0), not pixel-precise
- No conditional SKIP instruction in Copper
- HDMA line compare is 8-bit (256 lines), not 9-bit (480 lines)
- HDMA is direct-mode only, no indirect table lookup
- Only one raster trigger exists; platforms need multiple
- Linestate commit path has no proven robustness under edge-case timing

From MODE0_COLOR_WINDOW_BEAM_ASSESSMENT.md (#8580) and current repo state at `0f5dc65`.

---

## 2. Scope

### In Scope

1. **Pixel-precision Copper WAIT**
   - Extend WAIT instruction from `WAIT Y` (line-only) to `WAIT X,Y` (pixel-precision)
   - New encoding: `00 | X[9:0] | Y[9:0]` (20 bits) — may need 2-word instruction
   - Backward compatible: existing 1-word WAIT Y programs still work

2. **Copper conditional SKIP**
   - New instruction: `SKIP` — skips next instruction if a condition is true
   - Conditions: raster position compare, flag register bit test
   - Enables Amiga-style Copper conditional effects

3. **HDMA 9-bit line compare**
   - Current HDMA entry table: `{valid[24], line[7:0], data[15:0]}` (8-bit line)
   - Extend to 9-bit line compare to cover full 480-line visible region
   - Entry format: `{valid, line[8:0], data[15:0]}` = 26-bit words
   - Mem depth stays 4ch × 8ent = 32 entries; width grows 25→26 bits

4. **HDMA indirect mode**
   - Current HDMA: direct data from table entry → register write
   - Indirect mode: table entry holds pointer to data array; data array holds actual values
   - Enables SNES-style HDMA with per-line pointer tables

5. **Multiple raster triggers (4×)**
   - Current: one `RasterTriggerUnit` with single (line, pixel) compare
   - Extend to 4 independent trigger units
   - Each has: triggerLine, triggerPixel, pixelCmpEnable, enable, clear
   - Outputs: 4× triggerPulse, 4× pending, 4× irq (OR'd to single IRQ line)
   - Resource: ~4× small combinational logic

6. **Linestate robustness**
   - Current linestate commit happens at end-of-line
   - Add explicit synchronization against hCounter wrap
   - Ensure linestate is not corrupted by Copper/HDMA writes during commit window
   - Sim proof under worst-case contention

### Explicitly Out of Scope

- Copper BLITTER integration (separate lane)
- DMA-style bulk transfer primitive (Task 47)
- Beam-driven sprite manipulation ( beyond existing sprite evaluator)
- Inter-palette or inter-layer blending (Color/Window lane already closed)

---

## 3. Technical Approach

### 3.1 Pixel-Precision Copper WAIT

Current WAIT:
```
WAIT Y: stalls until vCounter==Y && hCounter==0
```

Target WAIT:
```
WAIT X,Y: stalls until vCounter==Y && hCounter==X
```

Encoding options:
- **Option A**: 2-word instruction — word 0 = `00 | X[9:0]` (opcode + X), word 1 = `Y[9:0]` (Y only)
- **Option B**: Expand opcode space — use currently reserved opcodes for extended WAIT

**Recommendation**: Option A. Use the existing 2-word pattern (like WRITE). First word carries opcode + X, second word carries Y. PC advances by 2 on match.

Backward compatibility: existing 1-word WAIT Y programs use X=0 implicitly (hCounter==0), so they continue to work if the decoder treats single-word WAIT as X=0.

### 3.2 Copper SKIP

New instruction encoding:
```
SKIP cond: `11 | 1 | cond[2:0] | offset[4:0]`
```

Conditions:
- `000`: raster line < triggerLine0
- `001`: raster line > triggerLine0
- `010`: raster line == triggerLine0
- `011`: flag register bit 0 set
- `100`: flag register bit 1 set
- etc.

The SKIP instruction reads the condition, and if true, adds `offset` to PC (skipping `offset` instructions). If false, PC advances by 1 (falls through).

### 3.3 HDMA 9-bit Line Compare

Current entry format (25 bits):
```
{valid[24], line[7:0], data[15:0]}
```

New entry format (26 bits):
```
{valid[25], line[8:0], data[15:0]}
```

Changes:
- `tbl` Mem width: 25 → 26 bits
- `entLine` extraction: 8 → 9 bits
- Compare: `entLine === io.vCounter(8 downto 0)` instead of `vCounter(7 downto 0)`
- No depth change (still 32 entries)

### 3.4 HDMA Indirect Mode

New HDMA control register bit:
- `hdmaCtrlAddr=0x00` bit[5] = indirect mode enable

In indirect mode:
- Table entry: `{valid, line[8:0], ptr[15:0]}` (ptr = address in data array)
- Data array: separate Mem(Bits(16), 256) for indirect data
- On hit: read `ptr` from table, use `ptr` to index data array, write data array value to register

This matches SNES HDMA indirect mode behavior.

### 3.5 Multiple Raster Triggers

Current: one `RasterTriggerUnit`

New: array of 4 `RasterTriggerUnit` instances:
```scala
val triggers = Vec.fill(4)(RasterTriggerUnit())
```

Each connected to same `hCounter`/`vCounter` inputs but independent:
- `triggerLine0..3`, `triggerPixel0..3`, etc.
- IRQ outputs OR'd together: `irqOut := triggers.map(_.irq).reduce(_ || _)`

Resource: ~4× the single unit (~100 LUT total, negligible).

### 3.6 Linestate Robustness

Current linestate commit in `VdpTop.scala` happens at end-of-line (hCounter wrap). Risk: Copper/HDMA writes to linestate registers during the commit window could race.

Fix: Add a 1-cycle guard band before commit where linestate-related register writes are stalled. Or: use the existing `shadow+commit` pattern already used for window/color-math registers.

---

## 4. Validation

### Sim Proof
- `CopperPixelWaitSim`: prove WAIT X,Y fires at exact pixel position
- `CopperSkipSim`: prove SKIP conditions and offset behavior
- `Hdma9BitSim`: prove 9-bit line compare covers lines 0..479
- `HdmaIndirectSim`: prove indirect pointer resolution
- `RasterTrigger4xSim`: prove 4 independent triggers with OR'd IRQ
- `LinestateRobustnessSim`: prove no corruption under worst-case Copper contention
- `VdpTopSim` regression

### Hardware Proof
- Scenario with pixel-precise Copper WAIT (e.g., color change at exact X position)
- Scenario with HDMA 9-bit line compare (effect at line > 255)
- Scenario with multiple raster triggers (e.g., 4 distinct IRQ events)

### Resource Report
- LUT/FF/BSRAM before vs. after
- Timing closure check

---

## 5. Stop-Line

| Resource | Current (0f5dc65) | Add | Ceiling | Zone After |
|---|---|---|---|---|
| LUT/ALU/ROM16 | 9,593 | +~500 | 13,478 | Green |
| Register | 6,166 | +~300 | 10,109 | Green |
| BSRAM | 16 | +0 | 23 | Green |
| DSP | 18 | +0 | 24 | Yellow (unchanged) |

Total estimated: +~500 LUT / +~300 FF / +0 BSRAM. Green zone.

---

## 6. Exit Condition

This task is successful when:
1. Pixel-precision Copper WAIT works (X,Y compare)
2. Copper conditional SKIP works
3. HDMA 9-bit line compare covers full 480-line range
4. HDMA indirect mode resolves pointers correctly
5. 4× raster triggers work independently with OR'd IRQ
6. Linestate commit is robust under Copper/HDMA contention
7. Sim proof + hardware proof pass
8. Resource report confirms green zone
9. All existing regressions still pass

---

## 7. Next Owner

- **BrightForge** for implementation (if authorized)
- **CyanPeak** to audit artifact and implementation
- **CoralReef** for ledger sync and preflight research
