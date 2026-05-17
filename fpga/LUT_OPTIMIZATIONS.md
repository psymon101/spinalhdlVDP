# LUT Optimization Recommendations for spinalhdlVDP

**Context:** SpinalHDL VDP (Video Display Processor) design targeting a Tang FPGA (Lattice/ECP5). Current LUT utilization is ~51k of ~20k available (exceeds capacity). The main file is `VdpTop.scala` (~2245 lines).

---

## Required Changes

### 1. ScrollTable Memory → BSRAM

Replace distributed LUT memory with block RAM for the scroll tables.

```scala
// BEFORE: Uses inferred Mem (likely LUT-based)
val scrollTable0 = ScrollTable(entries = 128, offsetWidth = 10)

// AFTER: Explicit BSRAM
val scrollTable0 = Mem(UInt(10 bits), 128)  // Infers to BSRAM
```

**Estimated savings:** 200-400 LUT

---

### 2. PlanarLineFetch planeWords → BSRAM

Replace the 1,600+ FFs (5×10×32) with a BSRAM row buffer.

```scala
// BEFORE:
val planeWords = Vec(Reg(Bits(32 bits)), planeCount * planePixels)

// AFTER:
val planeWords = Mem(Bits(32 bits), planeCount * planePixels)
// Read/write via FSM instead of registers
```

**Estimated savings:** 300-500 LUT

---

### 3. Compositor Priority Chain → PriorityEncoderOH

Replace sequential `when/elsewhen` chain with one-hot priority encoding.

```scala
// BEFORE:
when(layer0PrioGated && layer0Opaque) { ... }
.elsewhen(layer1Opaque) { ... }
.elsewhen(layer2Opaque) { ... }

// AFTER:
val layerValid = Vec(layer0Opaque && layer0PrioGated, layer1Opaque, layer2Opaque)
val prioEncode = PriorityEncoderOH(layerValid.asBits)
val selectedPixel = MuxOH(prioEncode, Vec(layer0Pixel, layer1Pixel, layer2Pixel))
```

**Estimated savings:** 50-150 LUT

---

### 4. Safe-Boundary Commit Logic → Precompute

Reduce repeated comparisons by computing once.

```scala
// BEFORE:
when(hCounter === 0 && layerEnablePendHit) { ... }
when(hCounter === 0 && tileDecodeModePendHit) { ... }

// AFTER:
val hCounterZero = hCounter === 0
when(hCounterZero && layerEnablePendHit) { ... }
when(hCounterZero && tileDecodeModePendHit) { ... }
```

**Estimated savings:** 30-50 LUT

---

### 5. Remove Unused `.simPublic()` Calls

Search for and remove debug simPublic() calls in synthesis builds.

```bash
# Find all: grep -n "simPublic" VdpTop.scala
# Remove if not needed for simulation
```

**Estimated savings:** 50-100 LUT

---

### 6. FetchSlotScheduler → OHMaskedFirst

Use one-hot priority encoder for slot arbitration.

```scala
import spinal.lib._
val validMask = slotValid.asBits & slotEnabled.asBits
val grant = OHMaskedFirst(validMask)
```

**Estimated savings:** 30-80 LUT

---

### 7. SpriteEvaluator Pipelining

Add a register stage between Pass 1 and Pass 2 sprite evaluation to reduce combinational depth.

**Estimated savings:** 50-100 LUT

---

### 8. Bit Width Verification

Check for oversized registers:
- `patternRamPtr`: Currently UInt(14 bits) → could be UInt(12) if 4096 entries

---

## Priority Order

| # | Change | Est. Savings | Effort |
|---|--------|-------------|--------|
| 1 | ScrollTable → BSRAM | 200-400 | Low |
| 2 | Remove simPublic | 50-100 | Low |
| 3 | Priority encoder (compositor) | 50-150 | Medium |
| 4 | hCounterZero precompute | 30-50 | Low |
| 5 | PlanarLineFetch BSRAM | 300-500 | Medium |
| 6 | FetchSlotScheduler OH | 30-80 | Medium |
| 7 | SpriteEvaluator pipeline | 50-100 | Medium |

**Total estimated savings:** 740-1,280 LUT

---

## Verification

After each change:

1. Run synthesis and check LUT utilization
2. Verify functionality (simulation/bench)
3. Ensure timing still meets target