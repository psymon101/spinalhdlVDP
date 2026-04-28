# Task 37 — Affine Sprite Path

**Status:** DONE (`e4e53bc`) — Affine sprite path implemented and hardware-proven (Scenario 37)
**depends_on:** [19, 28]
**scope_boundary:** Affine-transformed sprites only. No new background affine features.
**delivers:**

- Per-sprite affine matrix coefficients (A, B, C, D, X, Y) wired through descriptor storage
- Matrix-stepped texture address generation for sprite source data
- Rotation/scaling support for individual sprites
- Integration with existing `SpriteEvaluator` and compositor

**validation:**

- Sim: affine sprite renders with correct transformed pixels
- Hardware: visible rotated/scaled sprite on Tang Nano 20K

---

## 1. Goal

Extend the Task 28 sprite evaluator so that individual sprites can be rotated and scaled using the same affine matrix primitive proven in Task 19. A sprite with affine enabled no longer reads its pattern as a flat 16×16 tile; instead, each output pixel samples the pattern through an affine-transformed coordinate.

---

## 2. Scope

### 2.1 In scope

1. **Affine descriptor extension** — add matrix coefficients A, B, C, D, X, Y and an `affineEnable` flag to the sprite descriptor format.
2. **Per-sprite affine stepper** — instantiate one `AffineStepper` (Task 19 primitive) per active sprite slot, or share one stepper multiplexed across slots during fill.
3. **Transformed pattern fetch** — compute `(u, v)` texture coordinates per output pixel and read the sprite pattern memory at the transformed address.
4. **Integration with evaluator outputs** — `activeAffineEnable`, `activeMatrix*`, `activeTrans*` exposed alongside existing `activeX/Row/PatternIdx`.
5. **Deterministic bounding** — define the visible rectangle of an affine sprite (e.g., clamp to source 16×16 bounds; out-of-bounds = transparent).
6. **Sim + no-regression proof** — `AffineSpriteSim` verifying correct transformed pixels; all Sc0–Sc33 regression scenes unchanged.
7. **Hardware proof scene** — one scenario showing a visibly rotated and/or scaled sprite on Tang Nano 20K.

### 2.2 Out of scope (deferred)

- Background affine changes (Task 19 already delivered this)
- Sprite collision / flag logic (Task 29)
- Sprite attribute extension beyond affine (palette bank, flip, priority — future task)
- Compositor metadata pipe changes (Task 41 pipe already carries `layerSource`)
- Arbitrary sprite size changes (still 16×16 source patterns)
- Bi-linear filtering or sub-pixel sampling (nearest-neighbour only)
- Deep-angle quality tuning (noted as DEFERRED in `TASKS.md`)

---

## 3. Architecture

### 3.1 Current state (Task 28)

```
Sprite pixel fill (flat 16×16):
  col = fillX - sprite.x
  row = scanline - sprite.y
  addr = (row ## col).asUInt
  pixel = patternMem.readAsync(addr)
```

### 3.2 Target state (Task 37)

```
Sprite descriptor (extended):
  {x, y, enabled, patternIdx, affineEnable, matrixA, matrixB, matrixC, matrixD, transX, transY}

Sprite pixel fill (affine path):
  if sprite.affineEnable:
    // (sx, sy) = screen coordinate relative to sprite hotspot
    sx = fillX
    sy = vCounter
    // AffineStepper computes texture (u, v)
    u = A*sx + B*sy + X
    v = C*sx + D*sy + Y
    // Clamp/wrap to 0..15 source bounds (power-of-two wrap is free)
    addr = (v(3:0) ## u(3:0)).asUInt
    pixel = patternMem.readAsync(addr)
  else:
    // Legacy flat path unchanged
    pixel = flatPixel(sprite)
```

### 3.3 Reuse of Task 19 primitive

`AffineStepper` (Task 19) already implements:
- Fixed-point matrix multiply: `u = A*x + B*y + X`, `v = C*x + D*y + Y`
- Q8.8 matrix / Q10.6 translation contract
- 7-bit unsigned integer output with implicit power-of-two wrap

Task 37 wires one `AffineStepper` per active sprite slot (resource: ~8 steppers × small combinational logic) OR time-multiplexes a single stepper across slots if timing allows.

### 3.4 Interface boundaries

- **Descriptor storage** — extended via Mode0RegBus block `0x0800..0x08FF` (same block as Task 28; additional words per descriptor for matrix coefficients).
- **Active-line buffer** — carries `affineEnable` + matrix fields alongside existing x/y/row/pattern.
- **Pixel output** — feeds existing compositor pixel path; transparent pixels (`pixel == 0`) still drop through to background.

---

## 4. Implementation Plan

### 4.1 HDL changes

1. **`SpriteDescriptor.scala`** (extend) — add `affineEnable: Bool`, `matrixA/B/C/D: Bits(16)`, `transX/Y: Bits(16)`.
2. **`SpriteEvaluator.scala`** (extend) —
   - Add Reg-backed matrix fields to descriptor storage.
   - Pass 1 FSM: copy `affineEnable` + matrix into active-slot registers.
   - Pass 2 outputs: add `activeAffineEnable`, `activeMatrixA/B/C/D`, `activeTransX/Y`.
3. **`AffineSpriteStepper.scala`** (new, or reuse `AffineStepper`) — per-pixel coordinate generator. If resource permits, one per slot; else one shared stepper with slot-pipelining.
4. **`VdpTop.scala`** (diff) —
   - Wire `activeAffineEnable` to select affine vs flat pixel path.
   - For each active slot, drive `AffineStepper` with screen `(x, y)` and slot matrix.
   - Select transformed `addr` when `affineEnable`, else legacy flat `addr`.
5. **Bus decode extension** — extend `0x0800..0x08FF` decode to cover additional descriptor words for matrix coefficients.

### 4.2 Data model

| Structure | Size | Notes |
|-----------|------|-------|
| Descriptor table | 8–32 entries | Power-of-two depth per GT-022; each entry grows by ~96 bits |
| Active-line buffer | ≤ 8 entries | Now carries matrix + enable flag |
| Affine stepper | 1–8 instances | Combinational; no state. Reuse Task 19 logic |

### 4.3 Register / bus impact

Extend the Task 28 descriptor bus block. If a descriptor is 4 words:
- Word 0: `{enabled, patternIdx[3:0], y[9:0]}`
- Word 1: `x[9:0]`
- Word 2: `{affineEnable, matrixA[15:0]}` (or packed A/B)
- Word 3: `matrixC[15:0]` (or packed C/D)
- Word 4: `transX[15:0]`
- Word 5: `transY[15:0]`

Exact packing TBD in implementation; bus block stays within `0x0800..0x08FF`.

### 4.4 Validation plan

**Checkpoint A — Simulation:**
- `AffineSpriteSim`: sprite with 45° rotation renders diamond as a rotated square; pixel-by-pixel reference model comparison.
- `AffineSpriteSim`: sprite with 2× scale renders stretched pattern; reference model comparison.
- `VdpTopSim` regression: all existing scenarios pass unchanged (affineEnable = 0 default).

**Checkpoint B — Hardware:**
- Build Sc37 scenario: one affine-enabled sprite at screen center with 45° rotation.
- Capture and verify: visible rotated sprite shape, no regression in background or legacy sprites.
- Optional: animate rotation angle frame-by-frame to prove live matrix updates.

---

## 5. Deliverables

| File / Path | Purpose |
|-------------|---------|
| `hw/spinal/spinalhdlvdp/SpriteDescriptor.scala` (extend) | Extended descriptor bundle with affine fields |
| `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala` (extend) | Pass-1/Pass-2 wiring for affine matrix state |
| `hw/spinal/spinalhdlvdp/AffineSpriteStepper.scala` (new or reuse) | Per-pixel texture coordinate generator |
| `hw/spinal/spinalhdlvdp/VdpTop.scala` (diff) | Affine pixel path integration |
| `sim/` test additions | `AffineSpriteSim` + regression proof |
| `PROJECT_PLAN/TASK_37_AFFINE_SPRITE_PATH.md` | This artifact |

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Per-pixel matrix math too slow for 25 MHz pixel clock | `AffineStepper` is purely combinational and already proven at this clock; pipeline if needed |
| Resource explosion from 8× steppers | Time-multiplex one stepper across slots at pixel rate (8 slots × 1 pixel each = 8 cycles; line has 640 cycles) |
| Transformed address out of source bounds | Power-of-two wrap (implicit in truncation) or explicit clamp; define in artifact before coding |
| Bus address block overflow | Keep descriptor words ≤ 6; verify block fits in `0x0800..0x08FF` |
| Regression in flat-sprite path | `affineEnable` default = False; all existing Sc0–Sc33 must pass unchanged |
| Fixed-point precision artifacts | Reuse Task 19 Q8.8 / Q10.6 contract; do not invent new precision |

---

## 7. Dependencies

- **Task 19 (Affine Matrix Primitive)** — DONE. `AffineStepper` proven on hardware; matrix math contract established.
- **Task 28 (Two-Pass Sprite Evaluator)** — DONE. Descriptor storage, active-line buffer, and pixel-fill path in place.
- **Task 32b (Mode0 Register Bus: Master Refactor)** — DONE. Bus available for descriptor programming.

---

## 8. Open Questions

1. **Stepper replication vs multiplexing**: One stepper per slot (simple, more LUTs) or one shared stepper (complex mux, fewer LUTs)? Decision deferred to implementation read-ahead.
2. **Bounding behaviour**: Should out-of-bounds transformed coordinates wrap (power-of-two free) or clamp to transparent? Clamping is safer for visual quality; wrapping is cheaper.
3. **Hotspot origin**: Is the affine transform origin the sprite's top-left corner (x, y) or its centre? Centre is more intuitive for rotation; corner is simpler.
4. **Matrix update atomicity**: If host writes matrix coefficients across multiple bus transactions, is a partially-written matrix visible? May require a double-buffer or atomic-commit flag.

---

## 9. Audit Focus

- Scope compliance: no background affine changes, no compositor refactor beyond pixel-path mux
- Matrix math correctness: does the sprite affine path reuse Task 19's proven `AffineStepper` contract exactly?
- Integration: does `affineEnable = False` produce bit-identical output to Task 28 baseline?
- Proof quality: is the hardware scene unambiguously showing rotation/scaling vs a flat sprite?

---

## 10. Exit Condition

This task is done when an affine-enabled sprite renders correctly transformed pixels in simulation and a visibly rotated or scaled sprite is proven on Tang Nano 20K hardware with zero regression in existing scenarios.
