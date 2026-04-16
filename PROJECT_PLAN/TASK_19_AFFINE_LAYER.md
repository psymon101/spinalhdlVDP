# TASK_19_AFFINE_LAYER.md

**Status:** OPEN  
**Classification:** Mode0 rendering primitive (R8.1 Affine Stepper)  
**Created:** 2026-04-16  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef

---

## 1. Task Name

Affine Layer

---

## 2. Purpose

Add a matrix-stepped **affine coordinate generator** that enables SNES Mode 7-class rotation, scaling, and shearing for a background layer. This is the first R8 primitive and proves the pipeline can support non-tile-aligned texture sampling before any perspective-correct extension is attempted.

**Why now:**
- R4 (tile fetch), R5 (copper/host), R6 (color math/window), and Task 18 (linestate) are all closed.
- The compositor and fetch substrate are stable enough to absorb a new background source.
- Affine is the next unblocked primitive in the `TASKS.md` execution order.

---

## 3. Primitive Boundary

### In Scope

- **`AffineStepper` component** that computes per-pixel texture `(u, v)` from screen `(x, y)` using a 2×3 affine matrix.
- **Affine matrix registers** (safe-boundary shadow + commit at `hCounter === 0`).
- **128×128 on-chip BRAM texture** (8 bpp, power-of-two dimensions, GT-022 safe) initialized from a diagnostic asset.
- **L0 source mux extension**: when affine is enabled, the compositor uses the affine texture lookup instead of the on-chip pattern source or SDRAM tile fetch.
- **Wrapping texture addressing** (modulo 128 in both axes) so rotated/scaled textures tile seamlessly.
- **Integration with existing sprite path**: sprites must composite correctly over the affine background.

### Explicitly Out of Scope

- **NO affine sprites.** Sprite evaluation and fetch remain unchanged.
- **NO perspective correction** (no divide, no 3D transform).
- **NO runtime texture upload.** The texture is ROM-initialized for this task.
- **NO bilinear/trilinear filtering.** Nearest-neighbor sampling only.
- **NO per-line affine matrix updates via linestate.** The matrix is global for this task; per-line HDMA-style updates are a future follow-up.
- **NO SDRAM texture fetch.** Keeping the texture in BRAM isolates the task to coordinate generation rather than SDRAM arbitration.

---

## 4. Dependencies

- **Task 15 — Memory-Backed Fetch Path** (closed): proven compositor + line-buffer + fetch scheduling substrate.
- **Task 18 — Per-Line Raster Control** (closed): linestate infrastructure is mature; global register commit pattern is proven.
- `VdpTop` baseline stable at `1718c5c`.

---

## 5. Interfaces

### New Register-Space Addresses (safe-boundary commit)

All registers use the same `pend` + commit-at-`hCounter===0` pattern proven for `VDP_TILE_MODE` and `VDP_WIN_X0`.

| Addr | Name | Width | Description |
|------|------|------:|-------------|
| `0x0340` | `AFFINE_A` | 16 | Matrix coefficient A (signed 8.8 fixed point) |
| `0x0341` | `AFFINE_B` | 16 | Matrix coefficient B (signed 8.8 fixed point) |
| `0x0342` | `AFFINE_C` | 16 | Matrix coefficient C (signed 8.8 fixed point) |
| `0x0343` | `AFFINE_D` | 16 | Matrix coefficient D (signed 8.8 fixed point) |
| `0x0344` | `AFFINE_X` | 16 | Translation X (signed 10.6 fixed point) |
| `0x0345` | `AFFINE_Y` | 16 | Translation Y (signed 10.6 fixed point) |
| `0x0346` | `AFFINE_CTRL` | 16 | `{15:1 reserved, bit 0 = affineEnable}` |

Coordinate generation (nearest-neighbor):
```
u(x, y) = A*x + B*y + X
v(x, y) = C*x + D*y + Y
pixel = texture[(v_int mod 128) * 128 + (u_int mod 128)]
```

### Internal `VdpTop` Additions

- `AffineStepper` instantiated inside `VdpTop`.
- `affineTexture = Mem(Bits(8 bits), 128*128, initialContent = AffineAssets.textureInit)`
- `affineAddr = (vInt(6 downto 0) ## uInt(6 downto 0)).asUInt`
- `affinePixel = affineTexture.readAsync(affineAddr)` — split into `{priority[7], bank[6:4], index[3:0]}`.
- When `affineEnableReg` is high, `layer0Index/Bank/Prio` are driven from `affinePixel` instead of the SDRAM/on-chip/test-pattern mux.

---

## 6. Data Model

### Persistent Registers (safe-boundary shadow + commit)

- `affineAReg` .. `affineYReg` — 16 bits each, `pend` + commit-at-0.
- `affineCtrlReg` — 16 bits, same shadow pattern.

### Texture Memory

- `Mem(Bits(8 bits), 128 * 128)` — initialized at elaboration from `AffineAssets.textureInit`.
- **Zero write port.** Read-only for this task.

### Coordinate Accumulators

- Per-line start: `uStart = B*y + X`, `vStart = D*y + Y`
- Per-pixel step: `u = uStart + A*x`, `v = vStart + C*x` (two accumulators, incremented each pixel).

---

## 7. Timing Model

- Matrix register updates commit at `hCounter === 0`; the transform is stable for the entire frame.
- `AffineStepper` runs **combinatorially** during the line-buffer fill stage (same timing domain as `layer0`/`layer1` pixel generation).
- Texture BRAM read is **async** (`readAsync`) so the pixel is available in the same cycle as the coordinate.
- The affine pixel is injected into the existing `composedBgIdx`/`composedBgBank` path with no extra pipeline stages.

---

## 8. Memory / Bandwidth Impact

- **Zero SDRAM impact.** The affine texture lives in BRAM.
- **BRAM addition:** 128 × 128 × 8 = 16 384 bits ≈ 2 KB. Well within GW2A-18 BSRAM budget.
- **Zero line-buffer change** — the affine layer still writes 8-bit {priority, bank, index} pixels into the existing scanline buffer.

---

## 9. Platform Reuse

| Platform | Benefit |
|----------|---------|
| SNES | Mode 7 foundation (rotation / scaling background) |
| Custom | General-purpose transformable background layer |

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| Fixed-point overflow in accumulators | Use signed accumulators wide enough for 640 px × max scale; assert in sim. |
| Texture coordinate wrap bugs | Power-of-two dimensions (128) make wrapping a simple bit mask. |
| Safe-boundary commit forgotten | Reuse the exact `pend`/`commit` template from `VDP_TILE_MODE`. |
| Affine output regresses L1 or sprite path | Default `affineEnable = 0` must produce identical behavior to baseline. |
| Visual proof is ambiguous (looks like scroll) | Diagnostic texture must have strong non-axis-aligned features; OpenCV validation must measure edge orientation. |

---

## 11. Validation Plan

### Dedicated Sims

1. **AffineStepperSim** (new)
   - Drive known matrix values (identity, 90° rotation, 2× scale, shear).
   - Verify that `(u, v)` at specific `(x, y)` matches the hand-calculated fixed-point result.
   - Verify wrapping: `u = 130` wraps to `2` for a 128-wide texture.

2. **VdpTopSim extension**
   - Write to `0x0340..0x0346`, verify commit at `hCounter===0`.
   - Verify that when `affineEnable=1`, the line buffer contains the expected affine texture indices.
   - Verify that when `affineEnable=0`, all regression scenarios still produce bit-identical output.

### Regression

- All 11+ existing sims must still PASS.

---

## 12. Hardware Proof

**Scene (Scenario 12 bootstrap):**
- **Texture:** 128×128 diagnostic grid (vertical + horizontal lines + center dot) in 8 bpp.
- **Matrix:** Slow rotation (~2°/frame) around screen center, combined with a mild scale (≈0.9×).
- **Sprites:** One sprite in descriptor slot 0 moving horizontally at constant speed.

**Expected visual result:**
- The background grid is visibly rotated and slightly shrunk compared to a normal tile map.
- The sprite moves cleanly over the rotating background without tearing or priority glitches.

**Capture protocol:** 720×480 YUYV, 5-second clip.

**OpenCV checks:**
1. **Edge orientation histogram:** dominant peaks are offset from 0°/90° by the rotation angle (proves the transform is not just scroll).
2. **Sprite presence:** the moving sprite is detected in every frame by color/blob segmentation.

---

## 13. Audit Questions

- Does the affine matrix math use the correct fixed-point interpretation (8.8 for A/B/C/D, 10.6 for X/Y)?
- Is texture wrapping implemented as a simple bit mask (modulo 128) for both axes?
- Are all new registers committed at `hCounter === 0` using the proven shadow pattern?
- Does the default power-on state (`affineEnable = 0`) preserve baseline rendering?
- Does the hardware proof show an unambiguously rotated background, not just a scrolled one?
- Are sprites still visible and correctly prioritized over the affine background?

---

## 14. Constraints / Gotcha Check

- [x] **GT-022:** Texture dimensions are power-of-two (128×128).
- [x] **Safe boundary:** Register writes use `pend` + `hCounter===0` commit.
- [x] **No hardware before sim:** `AffineStepperSim` and `VdpTopSim` must pass first.
- [x] **Backward compatibility:** Default register state = `affineEnable = 0`.
- [x] **BRAM budget:** 16 KB texture is well within GW2A-18 limits.

---

## 15. Exit Condition

This task is done when the affine coordinate generator and BRAM texture are integrated into `VdpTop`, all regression sims pass, and a hardware capture proves an unambiguously rotated/scaled background with sprites composited on top.
