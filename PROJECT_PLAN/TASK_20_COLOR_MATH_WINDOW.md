# TASK_20_COLOR_MATH_WINDOW.md

**Status:** DONE — Color math and window effects implemented and hardware-proven  
**Classification:** Post-compositor Mode0 primitive  
**Created:** 2026-04-15  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef

---

## 1. Task Name

R6 Color Math / Window Effects

---

## 2. Purpose

Add a programmable **window mask** and a simple **post-palette color-math stage** to the output path. This unlocks Genesis-style shadow/highlight and SNES-style window + color-math effects without changing any upstream fetch or compositor logic.

**Why now:**
- R4.1d (fetch-path) is closed. This is a self-contained post-compositor effect that keeps velocity high while preserving fetch-path stability.
- It is the next unblocked primitive in the R6 roadmap phase.
- It proves the output path can be modified for region-aware effects before_affine_ introduces larger architectural change.

---

## 3. Primitive Boundary

### In Scope

- **Single programmable window rectangle** (`x0`, `x1`, `y0`, `y1`) compared against the current raster position.
- **Window-invert option** (force effect inside the rectangle or outside it).
- **Post-palette color-math mux** with three modes:
  - `0` = passthrough (existing behavior)
  - `1` = shadow (halve each RGB channel: `>> 1`)
  - `2` = add constant (clamp to 255)
  - `3` = reserved
- **Safe-boundary register commit** for all control registers at `hCounter === 0`.
- **Top-level wiring** in `VdpTop.scala` only; no new external ports.

### Explicitly Out of Scope

- **NO changes to Sprite Fetch, Tile Fetch, or SDRAM scheduling.**
- **NO per-sprite or per-tile math-enable flags.** Color math applies globally based on the window mask only.
- **NO multiple overlapping windows or boolean combine logic.** One rectangle only.
- **NO subtraction, alpha blend, or fixed-point math.** Only the three modes listed above.
- **NO perspective-correct or affine coordinate changes.** This task is purely post-palette RGB manipulation.

---

## 4. Dependencies

- R1–R5.4 and R4.1b/c/d all proven and closed.
- `VdpTop` compositor and palette path stable (`c709176` baseline).

---

## 5. Interfaces

### New Register-Space Addresses (safe-boundary commit)

| Addr | Name | Width | Description |
|------|------|------:|-------------|
| `0x0330` | `VDP_WIN_X0` | 10 | Window left edge (inclusive) |
| `0x0331` | `VDP_WIN_X1` | 10 | Window right edge (exclusive) |
| `0x0332` | `VDP_WIN_Y0` | 10 | Window top edge (inclusive) |
| `0x0333` | `VDP_WIN_Y1` | 10 | Window bottom edge (exclusive) |
| `0x0334` | `VDP_COLOR_MATH` | 16 | `{op[1:0], invert_window, 5'b0, constant_color[7:0]}` |

`op` encoding:
- `00` = passthrough
- `01` = shadow (`RGB >> 1`)
- `10` = add constant (`RGB + constant_color` per channel, clamp 255)
- `11` = reserved

`invert_window`:
- `0` = apply math when pixel is **inside** the rectangle
- `1` = apply math when pixel is **outside** the rectangle

### Internal `VdpTop` Additions

- `winInside = (hCounter >= winX0 && hCounter < winX1 && vCounter >= winY0 && vCounter < winY1)`
- `winEffect = winInside ^ invertWindow`
- Post-palette mux on `paletteRgb` selected by `colorMathOp` when `winEffect` is true.

---

## 6. Data Model

### Persistent Registers (safe-boundary shadow + commit)

- `winX0Reg`, `winX1Reg`, `winY0Reg`, `winY1Reg` — 10 bits each.
- `colorMathReg` — 16 bits.

All use the same `pend` + commit-at-`hCounter===0` pattern already proven for `VDP_TILE_MODE` and `LAYER_ENABLE`.

---

## 7. Timing Model

- Window comparison runs **combinatorially** on `hCounter`/`vCounter` during active video.
- Color-math mux sits **after palette lookup** and before final RGB drive.
- Register updates commit at `hCounter === 0`; the window is stable for the entire frame unless changed.

---

## 8. Memory / Bandwidth Impact

- **Zero SDRAM impact.**
- **Zero on-chip RAM impact.**
- Pure combinational post-palette stage.

---

## 9. Platform Reuse

| Platform | Benefit |
|----------|---------|
| Genesis | Shadow / highlight approximation (mode 1 halving) |
| SNES | Windowing + additive color-math foundation |
| Custom | General-purpose screen-region dimming / tinting |

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| Off-by-one window edge | Sim asserts on exact boundary pixels |
| Safe-boundary commit forgotten | Reuse the existing `pend`/`commit` template literally |
| Add-constant overflow | Explicit `min(255, channel + constant)` clamp in hardware |
| Regression in existing output path | Default register values must produce passthrough (`op=00`) |

---

## 11. Validation Plan

### Dedicated Sims

1. **WindowUnitSim** (new)
   - Program `winX0=100`, `winX1=540`, `winY0=100`, `winY1=380`.
   - Drive raster coordinates and verify `winInside` is true only in the rectangle.
   - Test `invert_window=1` and verify inversion.

2. **ColorMathSim** (new)
   - Shadow mode: input `0xFF0000` → expect `0x7F0000`.
   - Add-constant mode: input `0x102030` with constant `0x20` → expect `0x304050`.
   - Clamp mode: input `0xE0E0E0` with constant `0x40` → expect `0xFFFFFF`.

3. **VdpTopSim extension**
   - Verify that writes to `0x0330–0x0334` commit at `hCounter===0` and affect the output path.

### Regression

- All 11 existing sims must still PASS.

---

## 12. Hardware Proof

**Scene:** Pattern 6 (Grid) with a scrolling horizontal offset, plus a centered window rectangle that applies shadow mode (`op=01`).

**Expected visual result:**
- Outside the window: bright grid lines on black (normal palette).
- Inside the window: the same grid, but all colors are visibly dimmed (halved intensity).

**Capture protocol:** 720×480 YUYV capture, 5-second clip. OpenCV mean intensity inside the window region must be measurably lower than outside (target ~50% ratio).

---

## 13. Audit Questions

- Does the window rectangle use inclusive/exclusive edges consistently?
- Is the default power-on state passthrough so there is no output regression?
- Are all new registers committed at `hCounter === 0` using the proven shadow pattern?
- Does the hardware proof show an unambiguous intensity difference inside vs outside the window?

---

## 14. Constraints / Gotcha Check

- [x] **GT-022:** No new `Mem` instances introduced.
- [x] **Safe boundary:** Register writes use `pend` + `hCounter===0` commit.
- [x] **No hardware before sim:** `WindowUnitSim` and `ColorMathSim` must pass first.
- [x] **Backward compatibility:** Default register state = passthrough.

---

## 15. Exit Condition

This task is done when the programmable window and post-palette color-math stage are integrated, all regression sims pass, and a hardware capture proves an unambiguous intensity difference across the window boundary.
