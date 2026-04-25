# Task — Mode0 Sprite Envelope Hardening

**Artifact version:** 1.0-draft  
**Author:** CoralReef  
**Date:** 2026-04-25  
**Status:** implementation landed #8587 — partial deferrals; awaiting CyanPeak audit  
**Coding authorized:** YES — CyanPeak #8577

---

## Sub-Slice Tracker

| Sub-Slice | Description | Commit | Audit | Evidence |
|-----------|-------------|--------|-------|----------|
| S-1 | Artifact creation — sprite envelope hardening scope & gap analysis plan | `863f6dc` | PASS #8566 | Artifact v1.0-draft approved by CyanPeak |
| S-2 | Descriptor field extension assessment (size, flip, priority, palette) | `31e3de0` | PASS #8577 | 5 shared fields identified; 2 adapter-local |
| S-3 | Evaluator capacity / visible-per-line scaling analysis | `31e3de0` | PASS #8577 | 8→32 recommended; architecture sound |
| S-4 | Stop-line-aware recommendation for any growth | `31e3de0` | PASS #8577 | +~900 LUT / +~1,200 FF; stays green zone |
| S-5a | Sprite Descriptor Extension — Stage A (evaluator + 5 new fields) | `119d61c` | PASS #8583 | 12/12 sim PASS; VdpTopSim regression PASS; back-compat verified |
| S-5b | Sprite Descriptor Extension — Stage B (visiblePerLine 8→32 + compositor priority/palette + pattern fetch flip/size) | `d44a9c0` | **pending audit** | flipH/V ✓, sizeSel ✓, priority ✓ landed; paletteBank compositor wiring reverted (timing); visiblePerLine 32→8 reverted (resource); 0 setup/0 hold build clean |

---

## 1. Executive Summary

The project has a real two-pass sprite evaluator (`SpriteEvaluator.scala`, Task 28) with:
- 32 descriptor slots (Task 45 capacity hardening restored full scale)
- 8 visible-per-line slots
- Affine transformation support (Task 37)
- Bus-mapped descriptor storage
- Collision detection hooks

The coverage matrix marks the sprite system as `Usable` — real and functional for the C64 adapter proof, but not yet proven strong enough for higher-pressure adapters.

This task assesses whether the current sprite envelope can honestly serve:
- Amiga / Atari ST (hardware sprites + blitter-object pressure)
- Genesis / Mega Drive (80 sprites, 16×16/8×8, flip, priority)
- Neo Geo (96 sprites/line, variable size, scaling)
- SNES / Super Famicom (128 sprites, 8×8/16×16, flip, priority, palette)

**Current PM recommendation:** this is Priority B in `MODE0_HARDENING_BACKLOG.md`, to follow Fetch Envelope Hardening.

---

## 2. Why This Task Exists

The coverage matrix currently says:

| Category | Status |
|---|---|
| Sprite system | `Usable` |

That means the sprite system is real and functional, but likely needs bounded hardening or expansion for higher-pressure adapters.

This task answers:
- is the current sprite envelope strong enough to serve higher-pressure adapters without splitting into multiple platform-specific sprite engines?
- if not, what is the smallest bounded extension that closes the gap?

---

## 3. Scope

### In Scope

1. **Gap analysis:** measure current descriptor/visibility/priority/metadata envelope against Amiga/Genesis/Neo Geo/SNES pressure
2. **Field audit:** identify which missing descriptor fields or evaluator rules belong in shared sprite machinery
3. **Platform quirk classification:** identify which platform-specific sprite behavior should remain adapter-local
4. **Capacity audit:** assess whether `descCount=32` / `visiblePerLine=8` is sufficient or needs expansion
5. **Stop-line framing:** quantify any proposed growth against `MODE0_STOPLINES.md`
6. **Recommendation:** produce a clear, bounded next step (or "no action needed" if current envelope is already strong enough)

### Explicitly Out of Scope

- no new platform adapter implementation
- no whole-new sprite engine invented "just in case"
- no changes to the compositor's pixel-mixing rules beyond what sprite metadata requires
- no pattern-memory expansion (ROM/SDRAM sprite tile storage is a separate concern)

---

## 4. Current Sprite System — Evidence Base

### 4.1 Descriptor Fields (`SpriteDescriptor.scala`)

| Field | Width | Purpose |
|---|---|---|
| `enabled` | 1 bit | visibility on/off |
| `x` | 10 bits | horizontal position (0..1023) |
| `y` | 10 bits | vertical position (0..1023) |
| `patternIndex` | 4 bits (parametric) | pattern ROM/SDRAM selector |
| `affineEnable` | 1 bit | affine transform on/off (Task 37) |
| `matrixA/B/C/D` | 16 bits each | Q8.8 signed affine matrix |
| `transX/transY` | 16 bits each | Q10.6 signed translation |

**Fields added in Stage A/B** (now present in descriptor + evaluator):
- `flipH`, `flipV` — consumed in pattern fetch
- `paletteBank` — stored, exposed via `io.activePaletteBank`, but **compositor uses bank 0** (timing revert in `d44a9c0`)
- `sizeSel` — consumed in evaluator Y-range and pattern hitbox
- `priority` — consumed in compositor merge logic

**Remaining absences**:
- sprite-to-sprite collision masking / category bits
- visiblePerLine remains at 8 (32 deferred for resource reasons)

### 4.2 Evaluator Parameters (`SpriteEvaluator.scala`)

| Parameter | Current Value | Configurable? |
|---|---|---|
| `descCount` | 32 | Parameter (constructor arg) |
| `visiblePerLine` | 8 (32 attempted, reverted) | Parameter (constructor arg) |
| `patternSelBits` | 4 | Parameter (constructor arg) |
| `legacyIoCount` | 4 | Parameter (constructor arg) |

Pass 1: sequential scan across `descCount` descriptors during H-blank, selecting up to `visiblePerLine` whose Y-range covers the upcoming line.

Pass 2: line-stable active slot outputs (`activeValid`, `activeX`, `activeY`, `activeRow`, `activePatternIdx`, affine matrix fields).

### 4.3 Existing Proofs

| Task | What | Status |
|---|---|---|
| R2 / Task 28 | Two-pass evaluator core | DONE |
| Task 37 | Affine sprite path | DONE |
| Task 45 | Capacity hardening (8→32 desc, 4→8 visible/line) | DONE |
| SpriteCollisionSim | Collision detection hooks | DONE (sim-proven) |
| C64 adapter | Sprite proof under real adapter load | DONE |

---

## 5. Platform Pressure Cases

### 5.1 Sega Genesis / Mega Drive

- **Sprite limit:** 80 sprites total, 20 sprites per line (hscan) / 16 (vscan)
- **Size:** 8×8, 16×16, or composite (1×1 to 4×4 cells)
- **Attributes:** flip H/V, 4 palette banks, priority bit, pattern index
- **Pressure on current envelope:**
  - `visiblePerLine=8` < 20 (genesis max). **Gap:** per-line capacity.
  - No flip H/V fields. **Gap:** descriptor fields.
  - No per-sprite palette bank. **Gap:** descriptor fields.
  - No priority bit. **Gap:** compositor metadata.
  - Size is cell-composite, not a single fixed 16×16. **Gap:** pattern addressing / size metadata.

### 5.2 SNES / Super Famicom

- **Sprite limit:** 128 sprites total, 32 sprites per line
- **Size:** 8×8, 16×16, 32×32, 64×64 (selectable per sprite)
- **Attributes:** flip H/V, 8 palette banks, 2 priority levels, pattern index
- **Pressure on current envelope:**
  - `visiblePerLine=8` < 32 (SNES max). **Gap:** per-line capacity.
  - No flip H/V fields. **Gap:** descriptor fields.
  - No per-sprite palette bank. **Gap:** descriptor fields.
  - No size selection. **Gap:** descriptor fields + evaluator Y-range logic.

### 5.3 Neo Geo

- **Sprite limit:** 96 sprites per line (384 total in sprite RAM)
- **Size:** 16×16 to 16×512 (variable height in 16-pixel increments)
- **Attributes:** flip H/V, 16 palette banks, auto-animation, scaling
- **Pressure on current envelope:**
  - `visiblePerLine=8` << 96 (Neo Geo max). **Large gap:** per-line capacity.
  - No scaling beyond affine. **Gap:** evaluator/compositor.
  - No auto-animation. **Gap:** adapter-local or substrate.

### 5.4 Amiga (OCS/ECS)

- **Sprite limit:** 8 hardware sprites (16×16, 4 colors), but also "bobs" (blitter objects)
- **Attributes:** sprite attach (16→15 color), X-position (9-bit), DMA fetch
- **Pressure on current envelope:**
  - Amiga hardware sprites are simpler than current system (only 8, fixed 16×16).
  - Amiga "bobs" are blitter-driven, not hardware-sprite-driven — this is a blitter/transfer-engine concern, not a sprite-evaluator concern.
  - Current system already exceeds Amiga hardware-sprite capability.
  - **Conclusion:** sprite envelope pressure from Amiga is lower than Genesis/SNES/Neo Geo.

### 5.5 NES / Famicom

- **Sprite limit:** 64 sprites total, 8 sprites per line
- **Size:** 8×8 or 8×16 (global toggle)
- **Attributes:** flip H/V, 4 palette banks, priority bit
- **Pressure on current envelope:**
  - `visiblePerLine=8` matches NES max. ✓
  - No flip H/V fields. **Gap:** descriptor fields.
  - No per-sprite palette bank. **Gap:** descriptor fields.
  - Size is global toggle, not per-sprite. **Gap:** minor.

---

## 6. Initial Gap Assessment

### 6.1 Confirmed Substrate Gaps (shared, not adapter-local)

| Gap | Impact | Estimated Fix Complexity |
|---|---|---|
| `visiblePerLine` capped at 8 | Blocks Genesis (20/line), SNES (32/line), Neo Geo (96/line) | Medium — evaluator slot width + compositor input width |
| No flip H/V per sprite | Blocks Genesis, SNES, Neo Geo, NES | Low — 2 descriptor bits + pattern fetch flip |
| No per-sprite palette bank | Blocks Genesis, SNES, Neo Geo, NES | Low — 3–4 descriptor bits + compositor plumbing |
| No per-sprite priority level | Blocks Genesis, SNES | Medium — descriptor bit + compositor priority logic |
| Fixed 16×16 sprite size | Blocks SNES (variable size), Neo Geo (tall sprites) | Medium — descriptor size field + evaluator Y-range logic |

### 6.2 Likely Adapter-Local (not substrate)

| Quirk | Platform | Why adapter-local |
|---|---|---|
| Genesis cell-composite sizes | Genesis | Pattern-memory layout + cell assembly is platform-specific |
| Neo Geo auto-animation | Neo Geo | Frame sequencing is a playback rule, not a sprite hardware rule |
| Neo Geo 16-pixel-height increments | Neo Geo | Pattern addressing math is platform-specific |
| Amiga sprite attach | Amiga | 2-sprite pairing for 15-color is an Amiga-specific trick |
| NES 8×16 global toggle | NES | Global mode bit, not per-sprite descriptor |

---

## 7. Resource Budget Estimate

Based on `MODE0_STOPLINES.md` baseline (current usage from planar proof bitstream):

| Resource | Current | Green Ceiling | Available | Estimated Max Sprite Growth | Zone After |
|---|---|---|---|---|---|
| LUT/ALU/ROM16 | ~10,000 | ~13,478 (65%) | ~3,400 | +500–1,000 (flip + palette + priority + size) | Still green |
| FF | ~6,300 | ~10,109 (65%) | ~3,800 | +200–400 (descriptor regs + slot regs) | Still green |
| BSRAM | ~6 / 46 (13%) | 23 (50%) | ~17 | +0–2 (if descriptor store grows) | Still green |
| DSP | 18 / 24 (75%) | ~17 (70%) | Already yellow | +0 (sprite logic is not DSP) | Still yellow |

**Assessment:** Even with all identified substrate gaps closed, sprite hardening stays in the green zone for LUT/FF/BSRAM and does not worsen the already-yellow DSP position.

---

## 8. Required Outputs

This lane should end with:

1. **Audited gap list:** which gaps are confirmed substrate vs. adapter-local
2. **Bounded recommendation:** the smallest next step that closes the highest-leverage gaps
3. **Explicit budget:** LUT/FF/BSRAM/DSP estimate for any proposed extension
4. **Coverage matrix update:** whether sprite system moves from `Usable` to `Strong`

---

## 9. Acceptance Criteria

This task is successful only if it answers clearly:

1. Is `visiblePerLine=8` sufficient, or what is the smallest safe increase?
2. Which descriptor fields (flip, palette, priority, size) belong in shared substrate?
3. Can the current evaluator architecture absorb these extensions without a second engine?
4. What is the exact stop-line-aware cost of the recommended extension?

---

## 10. Exit Condition

This lane is done when the team has an audited answer to:
- whether the current shared sprite envelope is strong enough for serious future adapter work
- which bounded shared hardening step, if any, should come next

If the result is "hardening still needed," the next task should be opened immediately with a bounded scope and explicit stop-line-aware budget expectations.

---

## 11. Files Expected In Follow-On Work

If the lane turns into implementation work, likely touch points:

- `hw/spinal/spinalhdlvdp/SpriteDescriptor.scala`
- `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala`
- `hw/spinal/spinalhdlvdp/Compositor.scala` (priority/metadata plumbing)
- `hw/spinal/spinalhdlvdp/VdpTop.scala` (evaluator instantiation)
- sprite sims and mixed-scene proofs

But this artifact itself authorizes no such changes yet.
