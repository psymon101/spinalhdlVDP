**DEPRECATED -- merged into ASSESSMENT.md. This file is now archive reference only.**


# Mode0 Sprite Envelope Assessment Report

**Assessment version:** 1.1-updated  
**Author:** CoralReef  
**Date:** 2026-04-25 (updated 2026-05-06)  
**Commit:** `31e3de0` (assessment base); recommendations implemented in Tasks 2a/2c/2b  
**Audit:** PASS #8577  
**Scope:** Assessment / analysis only; no substrate implementation changes authorized

**Implementation note:** The recommendations in this assessment have been implemented and closed via Tasks 2a, 2c, and 2b (Sprite Capacity Expansion epic). Current Mode0 defaults: `descCount=64`, `visiblePerLine=32`. See `TASKS.md` and `MODE0_GAP_TASKLIST.md` for closure evidence.

---

## 1. Executive Summary

This assessment answers the four acceptance questions defined in `TASK_MODE0_SPRITE_ENVELOPE_HARDENING.md` §9, using empirical evidence from the current codebase, external platform research, and `MODE0_STOPLINES.md` budget framing.

| Question | Ruling |
|---|---|
| Q1 — Is `visiblePerLine=8` sufficient? | **NO — increase to 32 recommended** (covers SNES; Neo Geo deferred) — **IMPLEMENTED** via Tasks 2a/2c/2b. Current default: `visiblePerLine=32`, `descCount=64`. |
| Q2 — Which descriptor fields belong in shared substrate? | **flip H/V, palette bank (3b), priority bit, size select (2b)** — all shared |
| Q3 — Can current evaluator absorb extensions without a second engine? | **YES — two-pass architecture is fundamentally sound; no second engine needed** |
| Q4 — Exact stop-line-aware cost? | **+~900 LUT, +~1,200 FF, +0 DSP, +0–1 BSRAM** — stays green zone |

**Top-line recommendation:** ✅ **IMPLEMENTED** via Tasks 2a/2c/2b (Sprite Capacity Expansion epic). The substrate now supports `visiblePerLine=32`, `descCount=64` with zero unplaced REGs on Tang Nano 20K. For remaining gaps (Genesis 80 desc, SNES 128 desc, Neo Geo 96/line), see `MODE0_GAP_TASKLIST.md` §Task 4.

---

## 2. Evidence Base

### 2.1 Current Codebase State

Reviewed files:
- `hw/spinal/spinalhdlvdp/SpriteDescriptor.scala`
- `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala`
- `hw/spinal/spinalhdlvdp/VdpTop.scala` (sprite instantiation + compositor integration)
- `hw/spinal/spinalhdlvdp/SpriteAttributes.scala`
- `hw/spinal/spinalhdlvdp/SpriteCollisionSim.scala`
- `hw/spinal/spinalhdlvdp/SpriteCapacitySim.scala`

### 2.2 External Research

- Sega Genesis VDP sprite architecture (segaretro.org, md.squee.co)
- SNES OAM/sprite architecture (snesdevwiki.net, fullsnes.htm)
- Neo Geo sprite system (wiki.neogeodev.org, mame driver docs)
- Amiga OCS/ECS sprite hardware (amiga-hardware.com, copper/sprite DMA docs)
- NES PPU sprite architecture (nesdev.com)

### 2.3 Planning Stack References

- `MODE0_MAX_CAPABILITIES.md` §5 (sprite system max envelope)
- `MODE0_COVERAGE_MATRIX.md` (current sprite classification = `Usable`)
- `MODE0_STOPLINES.md` (resource baseline and green/yellow/red zones)
- `TASK_MODE0_SPRITE_ENVELOPE_HARDENING.md` (this lane's artifact)

---

## 3. Q1 — Is `visiblePerLine=8` Sufficient?

### 3.1 Platform Requirements

| Platform | Max Sprites / Line | Current `visiblePerLine=8` | Gap |
|---|---|---|---|
| NES / Famicom | 8 | 8 | **none** ✓ |
| C64 | 8 | 8 | **none** ✓ |
| Amiga OCS/ECS | 8 (hardware) | 8 | **none** ✓ |
| Sega Genesis | 20 (hscan) / 16 (vscan) | 8 | **−12** ✗ |
| SNES / Super Famicom | 32 | 8 | **−24** ✗ |
| Neo Geo | 96 | 8 | **−88** ✗ |

### 3.2 Analysis

**NES, C64, Amiga:** Current `visiblePerLine=8` is honestly sufficient. No substrate change needed for these adapters.

**Genesis:** `visiblePerLine=8` is a real gap. Genesis games routinely hit 16–20 sprites/line. An honest Genesis adapter would need at least 20 visible slots.

**SNES:** `visiblePerLine=8` is a severe gap. SNES hardware supports 32 sprites/line and many games use 16–24. An honest SNES adapter needs at least 32 visible slots.

**Neo Geo:** `visiblePerLine=8` is completely inadequate. Neo Geo's 96 sprites/line is an architectural outlier. The current two-pass evaluator (sequential scan of 32 descriptors) cannot realistically scale to 96 visible slots without either:
- a fundamentally different scan architecture (parallel tile-sorter instead of sequential scan), or
- accepting that Neo Geo adaptation requires a platform-specific sprite engine

### 3.3 Recommended `visiblePerLine` Target

| Target | Value | Rationale |
|---|---|---|
| **Minimum shared increase** | **16** | Covers Genesis (20) with some headroom; 2× current |
| **Recommended shared increase** | **32** | Covers SNES (32) honestly; 4× current; still within `descCount=32` limit |
| **Neo Geo** | **deferred / adapter-local** | 96 sprites/line requires architectural rethinking; not a simple parameter bump |

**Ruling:** `visiblePerLine=8` is **not sufficient** for Genesis or SNES. Increase to **32** as the shared substrate target. Neo Geo remains deferred.

---

## 4. Q2 — Which Descriptor Fields Belong in Shared Substrate?

### 4.1 Current Descriptor Fields

| Field | Width | Status |
|---|---|---|
| `enabled` | 1 bit | exists |
| `x` | 10 bits | exists |
| `y` | 10 bits | exists |
| `patternIndex` | 4 bits (parametric) | exists |
| `affineEnable` | 1 bit | exists (Task 37) |
| `matrixA/B/C/D` | 16 bits each | exists (Task 37) |
| `transX/transY` | 16 bits each | exists (Task 37) |

### 4.2 Missing Fields — Platform Demand

| Missing Field | Needed By | Shared or Adapter-Local? | Rationale |
|---|---|---|---|
| **flip H** | Genesis, SNES, Neo Geo, NES | **Shared** | Fundamental sprite attribute; trivial 1-bit + pattern-fetch flip |
| **flip V** | Genesis, SNES, Neo Geo, NES | **Shared** | Fundamental sprite attribute; trivial 1-bit + pattern-fetch flip |
| **palette bank** | Genesis (4), SNES (8), Neo Geo (16), NES (4) | **Shared** | 3–4 bits; essential for color richness; no adapter can work around missing palette select |
| **priority bit** | Genesis, SNES | **Shared** | 1 bit; determines sprite vs. background priority; generic concept |
| **size select** | SNES (4 sizes), Neo Geo (variable) | **Shared** | 2 bits for 8×8/16×16/32×32/64×64; evaluator Y-range logic needs it |
| **collision mask** | Genesis, SNES | **Adapter-local** | Platform-specific collision categories; current `spriteBgHitPulse` is sufficient substrate |
| **X-position MSB** | Genesis (9-bit X), SNES (9-bit X) | **Adapter-local** | Can be handled by adapter clipping or scroll offset; not a descriptor field gap |

### 4.3 Recommended Shared Descriptor Extension

Add to `SpriteDescriptor`:
- `flipH: Bool()` — 1 bit
- `flipV: Bool()` — 1 bit
- `paletteBank: UInt(3 bits)` — 3 bits (covers 8 palettes, sufficient for Genesis/SNES; Neo Geo's 16 can use adapter-local bank splitting)
- `priority: Bool()` — 1 bit
- `sizeSel: UInt(2 bits)` — 2 bits (00=8×8, 01=16×16, 10=32×32, 11=64×64)

**Total new descriptor bits:** 8 bits per slot.

**Bus layout impact:** Current bus layout uses 8 words × 16 bits = 128 bits per descriptor. The new fields fit in the existing word-0/word-1 packing with minor re-layout:
- word 0: `{enabled[15], patIdx[14:11], affineEnable[10], sizeSel[9:8], paletteBank[7:5], priority[4], flipH[3], flipV[2], y[9:0]}` — 16 bits
- word 1: `{_[15:10], x[9:0]}` — 16 bits (unchanged)

### 4.4 Q2 Ruling

**Five new fields belong in shared substrate:** flipH, flipV, paletteBank, priority, sizeSel. Collision mask and X-MSB remain adapter-local.

---

## 5. Q3 — Can Current Evaluator Architecture Absorb Extensions?

### 5.1 Current Architecture

The `SpriteEvaluator` uses a proven two-pass design:
- **Pass 1:** Sequential scan of `descCount` descriptors during H-blank, selecting up to `visiblePerLine` whose Y-range covers the upcoming line.
- **Pass 2:** Line-stable active slot outputs exposed combinationally for the pixel-fill path.

### 5.2 Extension Analysis

| Extension | Architectural Impact | Second Engine Needed? |
|---|---|---|
| `visiblePerLine` 8→32 | Widen Pass 2 output Vecs from 8 to 32 elements; widen active slot registers | **No** — parameter change only |
| flip H/V | Add 2 bits to descriptor; pattern-fetch address flip in pixel-fill path | **No** — fetch-layer change only |
| paletteBank | Add 3 bits to descriptor; plumb through to compositor fillBank | **No** — metadata plumbing only |
| priority | Add 1 bit to descriptor; modify compositor priority mux | **No** — compositor logic change only |
| sizeSel | Add 2 bits to descriptor; modify evaluator Y-range check `[y, y+size)` | **No** — evaluator range check only |

### 5.3 Compositor Impact

Current compositor logic (`VdpTop.scala` §1010–1050):
- Background: four-layer priority-aware composition (L3 > L2 > L1 > L0)
- Sprite: slots iterate low→high with last-hit-wins; `fillBank` hardcoded to 0; `fillPrio` = False when any sprite visible

Required compositor changes:
1. **Per-sprite palette bank:** `fillBank := slotPaletteBank(s)` instead of hardcoded 0
2. **Per-sprite priority:** Modify `fillPrio` logic so sprite-with-priority=True wins over background layers, while sprite-with-priority=False loses to higher background layers
3. **Size-aware row fetch:** Pattern fetch needs `sizeSel`-aware address generation (8×8, 16×16, 32×32, 64×64 tile sizes)

All of these are incremental changes to existing combinational logic. No new engine.

### 5.4 Q3 Ruling

**YES.** The current two-pass evaluator architecture can absorb all identified extensions without a second engine. The changes are: parameter widening, descriptor field additions, evaluator range-check modification, and compositor priority/palette plumbing.

---

## 6. Q4 — Exact Stop-Line-Aware Cost

### 6.1 Current Baseline

From the planar proof bitstream (`dcb5b2f`):

| Resource | Current | Green Ceiling | Available |
|---|---|---|---|
| LUT/ALU/ROM16 | ~10,000 | ~13,478 (65%) | ~3,400 |
| FF | ~6,300 | ~10,109 (65%) | ~3,800 |
| BSRAM | ~6 / 46 (13%) | 23 (50%) | ~17 |
| DSP | 18 / 24 (75%) | ~17 (70%) | Already yellow |

### 6.2 Estimated Extension Cost

| Change | LUT | FF | BSRAM | DSP | Notes |
|---|---|---|---|---|---|
| `visiblePerLine` 8→32 | +200 | +400 | 0 | 0 | 24 extra slot output registers × ~16 bits each |
| flip H/V (pattern fetch) | +50 | 0 | 0 | 0 | Address XOR/mux in pattern fetch |
| paletteBank (3b) | +100 | +150 | 0 | 0 | Descriptor regs + compositor plumbing |
| priority (1b) | +150 | +100 | 0 | 0 | Compositor priority mux restructuring |
| sizeSel (2b) | +200 | +150 | 0 | 0 | Evaluator Y-range check + pattern address gen |
| Evaluator slot widening | +200 | +400 | 0 | 0 | Active slot registers from 8→32 |
| **Total estimated** | **+~900** | **+~1,200** | **+0** | **+0** | |

### 6.3 Zone After Extension

| Resource | Current + Growth | Green Ceiling | Zone |
|---|---|---|---|
| LUT/ALU/ROM16 | ~10,900 | ~13,478 | **Green** |
| FF | ~7,500 | ~10,109 | **Green** |
| BSRAM | ~6 / 46 | 23 | **Green** |
| DSP | 18 / 24 | ~17 | **Yellow** (unchanged) |

**Assessment:** All sprite extensions stay in the **green zone** for LUT/FF/BSRAM and do not worsen the already-yellow DSP position.

### 6.4 Timing Risk

The sprite evaluator's Pass 1 is a sequential scan during H-blank. Increasing `visiblePerLine` does not affect scan time because Pass 1 scans `descCount` descriptors (already 32), not `visiblePerLine` slots.

The compositor's pixel-fill path adds combinational logic for palette bank and priority mux. Current `clk_pixel` timing closure (25.2 MHz, ~39.6 ns period) has ample margin. The additional mux depth is estimated at <2 ns — well within existing positive slack.

---

## 7. Comparison Against Coverage Matrix

| Category | Matrix Status | Assessment Finding | Disposition |
|---|---|---|---|
| Sprite system | `Usable` | Honest for NES/C64/Amiga (8/line). Needs 32/line + 5 descriptor fields for Genesis/SNES. Neo Geo 96/line deferred. | **Harden** |

---

## 8. Honest Residual Gaps

1. **Neo Geo 96 sprites/line:** The current two-pass sequential evaluator cannot scale to 96 visible slots without architectural redesign. This remains a deferred/platform-specific concern, not a shared substrate gap.

2. **Genesis cell-composite sprites:** Genesis allows 1×1 to 4×4 cell composition per sprite. This is a pattern-memory layout concern, not an evaluator concern. Adapter-local.

3. **Sprite pattern memory expansion:** Current `patternSelBits=4` allows 16 patterns. SNES uses up to 256 patterns. Expanding pattern storage is a memory/ROM concern, not an evaluator concern.

4. **DSP yellow zone:** DSP remains at 75% (yellow). Any future feature adding DSP usage needs explicit justification. Sprite hardening adds zero DSP.

---

## 9. Bounded Next-Step Recommendation

### 9.1 Recommended Task: Sprite Descriptor Extension

**Scope — IN:**
1. Extend `SpriteDescriptor` with `flipH`, `flipV`, `paletteBank(3b)`, `priority`, `sizeSel(2b)`
2. Increase `visiblePerLine` from 8 to 32 in `VdpTop.scala` instantiation
3. Update evaluator Y-range check to respect `sizeSel`
4. Update pattern fetch to respect `flipH`, `flipV`, `sizeSel`
5. Update compositor to use per-sprite `paletteBank` and `priority`
6. Update bus word packing to include new fields
7. Prove with sim: all 5 new fields function correctly
8. Prove with sim: 32 visible slots function correctly
9. Resource report against `MODE0_STOPLINES.md`

**Scope — OUT:**
- No Neo Geo 96-sprite architecture
- no pattern memory expansion
- no new compositor layers
- no collision masking categories

### 9.2 Resource Budget

| Resource | Estimated Cost | Zone After |
|---|---|---|
| LUT/ALU/ROM16 | +~900 | Green |
| FF | +~1,200 | Green |
| BSRAM | +0 | Green |
| DSP | +0 | Yellow (unchanged) |

### 9.3 Follow-On Task Recommendation

If Sprite Descriptor Extension passes audit:
- **Then** Genesis and SNES adapters can claim honest sprite substrate support
- **Then** coverage matrix sprite category moves from `Usable` to `Strong`
- **Else** if timing closure fails, consider `visiblePerLine=16` as a fallback (covers Genesis, partial SNES)

---

## 10. Conclusion

The Mode0 sprite envelope is:
- **Strong for NES, C64, and Amiga hardware sprites** (all ≤8/line, no missing fields)
- **Usable but insufficient for Genesis and SNES** (need 32/line + flip + palette + priority + size)
- **Not claimable for Neo Geo** (96/line deferred)

The project should:
1. **Accept** that NES/C64/Amiga sprite needs are already met
2. **Open a bounded Sprite Descriptor Extension task** (5 fields + 32 visible slots) before serious Genesis/SNES adapter claims
3. **Defer** Neo Geo sprite architecture to a separate platform-specific task

This preserves the architectural rule that `Mode0` owns the reusable superset while adapters own platform-specific semantics.
