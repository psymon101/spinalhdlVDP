# Task 48 — Four-Layer Compositor Expansion

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-23  
**Status:** Audit PASS #8221; implementation authorized  
**Coding authorized:** YES — bounded to this artifact and CyanPeak audit notes #8221  

---

## 1. Executive Summary

The current compositor supports two background layers (L0, L1) plus sprites. Task 48 expands this to **four background layers** (L0–L3) while preserving the existing 2-layer + sprite behavior when L2/L3 are disabled. The priority model extends the current fixed-index ordering, and the line-buffer metadata pipe widens from 4 to 5 bits to carry the additional layer-source encoding.

**Scope boundary:** Layer-count expansion only. No platform-specific mode tables, no new color-math semantics beyond existing contracts.

---

## 2. Current State Analysis

### 2.1 Existing compositor (2-layer + sprites)

```scala
// VdpTop.scala ~794-818
val effectiveL0Enable = linestate.io.layer0Enable && layerEnableReg(0)
val effectiveL1Enable = linestate.io.layer1Enable && layerEnableReg(1)

// Priority-aware composition:
//   1. If L0 has forced-priority AND is opaque -> L0 wins
//   2. Else if L1 is opaque -> L1 wins
//   3. Else -> L0 (background / transparent)
```

Sprites render on top of the composed background via back-to-front slot iteration.

### 2.2 Layer 0 vs Layer 1 capability asymmetry

- **L0** is the "rich" layer: BasicPatternSource + SDRAM overlay + test pattern + affine path + per-tile palette bank + priority bit.
- **L1** is "simple": BasicPatternSource only, fixed bank 0, no priority bit.

### 2.3 Register and control surface

- `LAYER_ENABLE` (0x0300): 3 bits `{sprite, L1, L0}`. Safe-boundary committed at `hCounter===0`.
- `LinestateStore`: per-line 12-bit record `{l1en, l0en, l0scrollX[9:0]}`.
- `PixelMetadata`: 4-bit `{layerSource[1:0], forcedPriority, mathEnable}`. `layerSource` encodes `00=BG0, 01=BG1, 10=SPRITE, 11=reserved`.
- Line buffer: 12 bits = `{metadata[3:0], priority, bank[2:0], idx[3:0]}`.

---

## 3. Architecture

### 3.1 Target state

Add **Layer 2** and **Layer 3** as simple `BasicPatternSource` instances (same capability level as L1). The compositor becomes a 4-layer priority chain.

```scala
VdpTop:
  val layer2 = BasicPatternSource()
  val layer3 = BasicPatternSource()
  layer2.io.scrollX := io.layer2ScrollX
  layer2.io.scrollY := io.layer2ScrollY
  layer3.io.scrollX := io.layer3ScrollX
  layer3.io.scrollY := io.layer3ScrollY
```

### 3.2 Priority model

The MVP preserves **exact backward compatibility** with the 2-layer case:
- L0 can assert `forcedPriority` to win over all other layers (same as today).
- In the absence of L0 priority, the **highest-index opaque layer wins** (L3 > L2 > L1 > L0).
- Sprites render on top of the composed background with the existing back-to-front slot iteration.

This is deterministic, cheap to implement (sequential `when` chain), and requires no new per-layer priority bits.

> **Future enhancement note:** A follow-up task can add per-layer priority bits to any or all of L1/L2/L3. Task 48 deliberately defers this to keep scope bounded.

### 3.3 PixelMetadata and line-buffer width

`layerSource` must distinguish 4 background layers + sprite. Current 2-bit encoding is insufficient.

**Option A (recommended):** Expand `PixelMetadata` to 5 bits:
```scala
case class PixelMetadata() extends Bundle {
  val mathEnable     = Bool()
  val forcedPriority = Bool()
  val layerSource    = UInt(3 bits)  // 0=BG0, 1=BG1, 2=BG2, 3=BG3, 4=SPRITE
}
```
Line buffer becomes `8 + 5 = 13` bits. Gowin BSRAM natively supports 16-bit width modes, so 13 bits fits without waste.

**Option B:** Keep 4-bit metadata and coarsen encoding (`11` = "BG2 or BG3"). Not recommended — loses layer provenance.

Artifact recommends **Option A**.

### 3.4 LAYER_ENABLE register expansion

Current (3 bits): `{sprite[2], L1[1], L0[0]}`
New (5 bits): `{L3[4], L2[3], sprite[2], L1[1], L0[0]}`

Safe-boundary commit semantics unchanged. Existing scenarios write 3-bit values to 0x0300; the upper bits default to 0 (L2/L3 disabled), preserving bit-identical behavior.

### 3.5 Per-line state (LinestateStore)

Task 48 **does not widen** `LinestateStore`. L2 and L3 use global enable only (`layerEnableReg` bits 3 and 4). Per-line enable for L2/L3 is a future enhancement that can be added without changing the compositor math.

### 3.6 Scroll for L2/L3

L2 and L3 use **global scroll only** (`layer2ScrollX/Y`, `layer3ScrollX/Y` top-level inputs). Per-column scroll tables for L2/L3 are deferred to a future task. This keeps the scope strictly to layer-count expansion.

---

## 4. Exact Changes Required

### 4.1 `VdpTop.scala`

**Change A:** Add L2/L3 `BasicPatternSource` instantiations and scroll wiring.

```scala
val layer2 = BasicPatternSource()
layer2.io.x := hCounter.resize(10)
layer2.io.y := fillLine
layer2.io.scrollX := io.layer2ScrollX
layer2.io.scrollY := io.layer2ScrollY

val layer3 = BasicPatternSource()
layer3.io.x := hCounter.resize(10)
layer3.io.y := fillLine
layer3.io.scrollX := io.layer3ScrollX
layer3.io.scrollY := io.layer3ScrollY
```

**Change B:** Expand compositor to 4 layers.

```scala
val layerPixels  = Vec(layer0Pixel, layer1Pixel, layer2Pixel, layer3Pixel)
val layerOpaques = Vec(layer0Opaque, layer1Opaque, layer2Opaque, layer3Opaque)
val layerBanks   = Vec(layer0Bank, U(0,3 bits), U(0,3 bits), U(0,3 bits))

composedBgIdx  := layerPixels(0)
composedBgBank := layerBanks(0)
for (i <- 1 to 3) {
  when(layerOpaques(i)) {
    composedBgIdx  := layerPixels(i)
    composedBgBank := layerBanks(i)
  }
}
when(layer0PrioGated && layer0Opaque) {
  composedBgIdx  := layerPixels(0)
  composedBgBank := layerBanks(0)
}
```

**Change C:** Expand `layerEnableReg` to 5 bits.

```scala
val layerEnableReg  = (Reg(Bits(5 bits)) init B"11111").simPublic()
```

Update decode at 0x0300 to capture bits 4..0 instead of 2..0. Update `effectiveL0Enable`/`effectiveL1Enable` and add `effectiveL2Enable`/`effectiveL3Enable`.

**Change D:** Expand `PixelMetadata` and line buffer.

```scala
case class PixelMetadata() extends Bundle {
  val mathEnable     = Bool()
  val forcedPriority = Bool()
  val layerSource    = UInt(3 bits)
}
```
Update `toBits`/`fromBits` and `PixelMetadata.Width`. Update `LineBuffer` instantiation to `pixelWidth = 8 + PixelMetadata.Width` (13 bits).

Update `fillMeta` assignment to reflect layer source for L2/L3:
```scala
val fillMeta = PixelMetadata.default()
// Layer source tracking per compositor winner — see implementation notes
```

**Change E:** Add top-level IOs for L2/L3 scroll.

```scala
val layer2ScrollX = in UInt(10 bits)
val layer2ScrollY = in UInt(10 bits)
val layer3ScrollX = in UInt(10 bits)
val layer3ScrollY = in UInt(10 bits)
```

Wired in `TopTang20kHdmi.scala` to default 0 (or scenario-driven values).

### 4.2 `TopTang20kHdmi.scala`

Wire new scroll inputs:
```scala
video.io.layer2ScrollX := U(0, 10 bits)
video.io.layer2ScrollY := U(0, 10 bits)
video.io.layer3ScrollX := U(0, 10 bits)
video.io.layer3ScrollY := U(0, 10 bits)
```

Scenario-specific overrides can be added later; defaults keep existing scenarios bit-identical.

### 4.3 `MODE0_REGISTER_BUS_SPEC.md`

Update 0x0300 row:

| Range | Purpose |
|---|---|
| `0x0300` | `LAYER_ENABLE` — `{L3[4], L2[3], sprite[2], L1[1], L0[0]}` |

### 4.4 `PixelMetadata.scala`

Expand `layerSource` to `UInt(3 bits)` and update `Width` to 5. Update `toBits`/`fromBits` helpers.

### 4.5 Files that do NOT change

- `ScrollTable.scala`, `ScrollWrap.scala`, `SpriteEvaluator.scala` — untouched.
- `LinestateStore.scala` — not widened; L2/L3 use global enable only.
- `ColorMath` / windowing logic — no new color-math semantics.

---

## 5. Resource Impact

| Item | Current (Task 47) | After Task 48 | Delta |
|---|---|---|---|
| BasicPatternSource instances | 2 (L0, L1) | 4 (+L2, L3) | +2 |
| Register (FF) | ~6057 (39%) | ~6100 | +~40 FFs (enables + metadata) |
| Logic (LUT) | ~8499 | ~8700 | +~200 LUTs (2x pattern source + wider mux) |
| BSRAM | 7 / 46 (16%) | 9–11 / 46 | +2–4 BSRAMs (two more tileMap + tileRows; Gowin may infer some to LUT-RAM) |
| Line buffer width | 12 bits | 13 bits | +1 bit (negligible — BSRAM width mode) |

**Headroom check:** Even at the high end (+4 BSRAMs), total would be ~11/46 (24%). CLS and LUT headroom remain comfortable. Timing closure risk is low because the compositor is combinational logic on the pixel clock (25 MHz) with ample slack.

---

## 6. Validation Plan

### 6.1 Simulation validation

**6.1.1 `FourLayerCompositorSim.scala` (new)**

Unit/integration sim that:
- Enables all 4 layers with different scroll offsets and tile patterns.
- Verifies compositor selects highest-index opaque layer at each pixel.
- Verifies L0 priority override still wins over L3.
- Verifies sprites render on top of all 4 layers.

**6.1.2 Regression: `VdpTopSim`**

Existing scenarios with L2/L3 disabled (default `layerEnableReg = 0x07`, upper bits 0) must be bit-identical to pre-Task-48.

### 6.2 Hardware validation

**6.2.1 Build**

`make -C fpga/tang20k SCENARIO=8 all` (or any scroll scenario).
- 0 errors, timing closed.

**6.2.2 Scenario: 4-layer parallax**

A new or modified scenario that:
- Sets L0 scroll = 0, L1 scroll = +1 px/frame, L2 scroll = +2 px/frame, L3 scroll = +3 px/frame.
- Each layer shows a visually distinct tile pattern (e.g., checkerboard, stripes, dots, solid).
- Result: visible parallax with 4 independently scrolling layers.

**6.2.3 Hardware proof evidence**

- Direct capture or monitor screenshot showing 4-layer parallax.
- 30 s capture + `analyze.py` showing motion.
- Bitstream md5 and HEAD commit hash.

---

## 7. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| BSRAM growth from 2 more tileMap/tileRows | Medium | Medium | Gowin may infer to LUT-RAM (has done so for scroll tables). If BSRAM runs short, force LUT-RAM inference via attribute or reduce pattern count. |
| Line buffer width change (12->13) causes BSRAM inference issue | Low | Medium | 13 bits fits in Gowin 16-bit BSRAM mode. If not, pad to 16 bits explicitly. |
| Combinational path through 4-layer mux becomes critical | Low | Medium | Current timing slack at 25 MHz is very large. 4:1 mux adds minimal delay. If violated, pipeline the compositor into 2 cycles. |
| Existing scenario regression due to `LAYER_ENABLE` width change | Very low | High | Upper bits default to 1 via `init B"11111"`; but existing writes only touch bits 2..0. Verified by sim regression. |

---

## 8. Out-of-Scope / Deferred

Per TASKS.md boundary:
- **Per-layer priority bits for L1/L2/L3** — L0 priority override is sufficient for MVP.
- **Per-column scroll tables for L2/L3** — global scroll only; scroll tables deferred.
- **Per-line enable for L2/L3 via LinestateStore** — global enable only; LinestateStore expansion deferred.
- **SDRAM / affine / test-pattern paths for L2/L3** — L2/L3 are simple BasicPatternSource like L1.
- **New color-math semantics** — existing color-math contracts unchanged.
- **Platform-specific mode tables** — no SNES Mode-1/2/3/etc. semantics.

---

## 9. Audit Checklist for CyanPeak

- [ ] 4-layer compositor preserves backward compatibility when L2/L3 disabled.
- [ ] Priority model is deterministic and matches existing L0-priority + L1-wins semantics.
- [ ] `PixelMetadata` expansion to 5 bits is justified and line-buffer width is handled.
- [ ] `LAYER_ENABLE` expansion to 5 bits does not regress existing scenarios.
- [ ] Resource impact (+2 BasicPatternSource) is bounded and within headroom.
- [ ] Validation plan includes 4-layer sim proof and hardware parallax proof.
- [ ] Scope boundary excludes per-layer priority, scroll tables, and LinestateStore expansion.

---

## 10. Next Steps (Post-Audit)

1. **CyanPeak audit:** Rule PASS / HOLD / FAIL on this artifact.
2. **BronzeGate PM authorization:** If audit PASS, authorize BrightForge to implement.
3. **BrightForge implementation:** Apply changes, run sims, synthesize, capture hardware evidence.
4. **CyanPeak implementation audit:** Audit implementation evidence.
5. **CoralReef ledger sync:** Update `TASKS.md` to mark Task 48 DONE at implementation commit.
