# TASK_R5_4_SCROLL_WRAP.md

**Status:** CLOSED (`d580dcb`) — Scroll wrap implemented and integrated (CyanPeak drafting)
**Created:** 2026-04-14
**Coding Owner:** BrightForge
**Audit Owner:** CyanPeak
**PM/Coordination:** CoralReef (covering BronzeGate)

---

## 1. Task Name

R5.4 Scroll-Wrap Component Primitive

---

## 2. Purpose

Consolidate the duplicated and bug-prone scroll + wraparound math into a reusable SpinalHDL component. The project has suffered from multiple "truncation + single-wrap" bugs (Bug #2 in R4.2-redo, Bug in BasicPatternSource #7149) where `(x + scroll).resize(N)` truncates values >= 2^N, leading to incorrect map offsets when scroll values are large.

**Why now:**
- R4.2-redo and R4.1b highlighted that ad-hoc wrap logic is a recurring source of hardware defects.
- Consolidating this into a `ScrollWrap` primitive ensures that fixes (like 11-bit expansion and multi-step wrapping) land everywhere simultaneously.
- Simplifies map-size changes by making them truly "no-edit" (widths computed at elaboration).

---

## 3. Primitive Boundary

### In Scope

- **`ScrollWrap` component**:
  - Parameterized by `mapWidth` (e.g., 640 or 320).
  - Inputs: `coord` (e.g., beam X/Y), `scroll` (e.g., register value).
  - Output: `wrappedCoord` (0 to `mapWidth-1`).
- **Elaboration-time math**:
  - Automatically compute required internal bit-width (`log2Up(mapWidth * 2.6)` or similar safety margin) to prevent truncation.
  - Automatically generate a wrap-tree (subtraction chain) sufficient to handle the full range of the expanded sum.
- **Replacement/Refactor**:
  - Replace ad-hoc wrap logic in `BasicPatternSource.scala`.
  - Replace ad-hoc wrap logic in `SdramTileAttributeFetch.scala` (`rawX` wrap).
  - Replace ad-hoc wrap logic in `TopTang20kHdmi.scala` (scrollL0/L1 counters).
- **Sim Verification**:
  - Unit test for `ScrollWrap` covering boundary cases (scroll=0, scroll=max, sum exactly at mapWidth, sum exactly at 2*mapWidth).
  - Regression check of `VdpTopSim`.

### Explicitly Out of Scope

- **NO changes to register mapping**: The existing scroll registers remain as-is.
- **NO new fetch modes**: This is a math-primitive refactor only.
- **NO changes to SDRAM row-straddle logic**: This focuses on the coordinate wrap math.

---

## 4. Dependencies

- R1-R5.2 verified baseline (`f8aa20a`).
- SpinalHDL `log2Up` and `+^` (expanding add) usage.

---

## 5. Interfaces

### `ScrollWrap` Component (Draft)

```scala
case class ScrollWrap(mapWidth: Int) extends Component {
  val io = new Bundle {
    val coord  = in UInt(10 bits)  // e.g. hCounter
    val scroll = in UInt(10 bits)  // e.g. scrollX
    val result = out UInt(log2Up(mapWidth) bits)
  }
  // ... implementation ...
}
```

---

## 6. Data Model

- **No persistent state**: This is a combinational component (or single-cycle Reg if needed for timing).
- **Elaboration-time constants**: `mapWidth` defines the logic depth.

---

## 7. Timing Model

- **Combinational path preferred**: To match existing usage in `BasicPatternSource` and `SdramTileAttributeFetch`.
- If timing slack on Tang20K becomes an issue, a version with an optional register stage can be considered, but is not the baseline requirement.

---

## 8. Memory / Bandwidth Impact

- **None**: This is a logic refactor.

---

## 9. Platform Reuse

- Foundational for all Mode0 rendering platforms (Tang20K, PYNQ, etc.).

---

## 10. Failure Modes / Risks

- **Off-by-one at wrap boundary**: Ensure `sum >= mapWidth` logic is used correctly.
- **Bit-width overflow**: The expanding add `+^` must be used to prevent the exact bug we are trying to fix.
- **Regression**: Breaking existing scrolling in any of the three target files.

---

## 11. Validation Plan

- **`ScrollWrapSim`**: New unit test proving correct output for coordinates 0-639 and scroll offsets 0-1023 against a 640-pixel map.
- **Old proofs**: `VdpTopSim` and `TileAttributeFetchSim` must be rerun.

---

## 12. Hardware Proof

- **Existing 60fps proof scene**: Confirmation that L0 and L1 still scroll correctly and that the "L1 changing pattern" bug reported by the user remains absent at high scroll offsets.

---

## 13. Audit Questions

- Does `ScrollWrap` use `+^` correctly to avoid truncation?
- Is the wrap-tree generated at elaboration time correctly (no fixed "2 subtracts" limit)?
- Are all three target files refactored to use the new component?

---

## 14. Constraints / Gotcha Check

- **GT-022**: Not directly applicable (logic only), but ensure width inference doesn't cause synthesis issues.

---

## 15. Exit Condition

- This task is done when the `ScrollWrap` primitive is unit-tested and replaces all ad-hoc scroll-wrap logic in the production baseline with zero regression on hardware.
