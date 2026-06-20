# External AI Review — Doc/Spec Items

Log of code-review findings from the external AI review that have a documentation or register-spec implication. Hardware implementation tracking for these items belongs in the relevant RTL lane; this file only captures the user-visible contract that should eventually be documented.

## Open Items

### 1. HDMA 9-bit line field wraps at 512 (Bug 8)
- **Severity:** Medium
- **Finding:** The HDMA line field is 9 bits, so it wraps at 512 and cannot target vblank lines above that boundary.
- **Doc/spec action:** Document the limitation that HDMA cannot trigger on lines ≥ 512 or target vblank regions beyond the 9-bit field. If a future revision widens the field, update the register spec.
- **Related file:** `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` (HDMA/line trigger registers).

### 2. `layerMaskReg` is 16 bits but only bits 0–7 are used (Bug 9)
- **Severity:** Low
- **Finding:** The `layerMaskReg` register is implemented as 16 bits, but the hardware only uses bits 0–7.
- **Doc/spec action:** Document bits 8–15 as reserved/ignored in `LAYER_ENABLE` / `layerMaskReg` so host code does not rely on them.
- **Related file:** `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` § LAYER_ENABLE / layer mask registers.

### 3. `AffineStepper` 32-bit silent truncation (Bug 10)
- **Severity:** Low
- **Finding:** `AffineStepper` silently truncates 32-bit inputs.
- **Doc/spec action:** Document the valid input range for affine parameters, or note that out-of-range values wrap/saturate silently. If saturation is added later, update the note.
- **Related file:** `VDP_PROGRAMMING_GUIDE.md` affine/rotation section (when added) or `MODE0_REGISTER_BUS_SPEC.md` affine registers.

### 4. `spriteCollMask` descriptor aliasing (8–31 → 0–7)
- **Severity:** Medium (known / intentional)
- **Finding:** Sprite collision mask indices 8–31 alias to 0–7. BronzeGate previously logged this as intentional "parking" behavior.
- **Doc/spec action:** Document that only collision mask indices 0–7 are distinct and that indices 8–31 alias modulo 8 (or are intentionally parked). Clarify whether this is a permanent hardware limit or a temporary parking state.
- **Related file:** `PROJECT_PLAN/MODE0_REGISTER_BUS_SPEC.md` sprite/collision registers, and `VDP_PROGRAMMING_GUIDE.md` sprite section.

## Status

Logged by CoralReef on 2026-06-20 from TopazCliff's external AI review digest (#13034). These items are **documentation-only** and do not block HAM-DECODER-171 CP-D or LEFT-EDGE-ALIGN-172 / RGB565-HW-DIVERGE-173.
