# Task 32a — Mode0 Register Bus: Spec & Naming Lock

**Opened:** 2026-04-19  
**Opened by:** CoralReef (CyanPeak #7646 directive — unblock Task 35 dependency chain)  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

---

## Purpose

Mode0 already has a de-facto register bus: `video.io.regWriteAddr/Data/Enable` is used by bootstrap, QSPI decoder, animator, and Copper masters. Task 32a formalizes this into a named, documented, and stable contract so that later tasks (Task 35 status registers, Task 33 Copper-lite, Task 34 asset upload) all target the same interface without ad-hoc drift. This is a spec-and-naming lock, not a refactor.

---

## Scope

- **in scope:** Written register bus specification document
- **in scope:** Address map covering all existing control/status surfaces
- **in scope:** Naming convention for registers, fields, and masters
- **in scope:** Semantics sketch: read/write rules, safe-boundary behavior, clear-on-read semantics
- **in scope:** Interface contract that bootstrap, QSPI, Copper, and Animator masters can target
- **in scope:** Proof that existing simulations still pass with the named bus (no behavioral change)
- **out of scope:** Refactoring existing masters onto the named bus (Task 32b)
- **out of scope:** New primitives or adapter-specific registers
- **out of scope:** HDL changes beyond naming annotations if any

---

## Dependencies

- Task 18 — Per-Line Raster Control must be DONE (R1 trigger unit exists)
- Task 26 — QSPI Host-Control Frontend must be DONE (QSPI master exists)

---

## Interfaces / State

- De-facto bus: `regWriteAddr` (width TBD), `regWriteData` (16-bit), `regWriteEnable` (Bool)
- Masters: bootstrap, QSPI decoder, animator, Copper (future)
- Targets: `VdpTop` control surfaces, linestate, palette, scroll registers, status registers (Task 35)

---

## Timing / Memory Notes

- No new timing domains
- No memory additions
- This is a documentation + naming task with minimal HDL touch

---

## Risks

- Over-specifying: trying to define the bus so rigidly that it prevents needed evolution
- Under-specifying: leaving ambiguity that causes Task 35/33/34 to invent incompatible extensions
- Naming collision: choosing names that conflict with existing module-level signals
- Simulation breakage: even trivial renaming can break existing testbenches

---

## Validation

- **doc review:** Bus spec covers all existing primitives (R1–R6) plus planned R5–R8
- **doc review:** Address map has no gaps or overlaps
- **sim:** All existing scenario simulations pass after any naming annotations (zero behavioral change)
- **hardware:** At least one existing scenario re-proven on Tang Nano 20K to confirm no behavioral drift

---

## Audit Focus

- Does the spec match the actual current wiring (not an aspirational future bus)?
- Are the naming choices stable enough that Tasks 33, 34, 35 can target them without renaming later?
- Is the safe-boundary behavior explicitly defined and consistent with proven behavior?
- No hidden scope creep toward Task 32b (refactor)

---

## Exit Condition

This task is done when a written register bus specification exists, all existing simulations pass with zero behavioral change, and the spec is approved as the stable naming contract for all future register bus consumers.
