# TASK_R4_1D_SHUFFLED_FETCH.md

**Status:** OPEN (PM-approved post-backlog extension)
**Classification:** New post-backlog extension — not part of the previously closed Mode0 substrate backlog.
**Created:** 2026-04-15
**Coding Owner:** BrightForge
**Audit Owner:** CyanPeak
**PM/Coordination:** CoralReef (covering BronzeGate)

---

## 1. Task Name

R4.1d Shuffled Fetch Path (Amiga-style / Interleaved)

---

## 2. Purpose

Extend the R4.1 fetch engine to support **shuffled (interleaved) layouts**, where pixel data and attribute data for a tile are stored in a non-linear sequence or a different memory arrangement compared to the standard packed/planar modes. This is common in platforms like the Amiga or certain arcade hardware to optimize bandwidth or hardware layout.

**Classification note:** This lane is a deliberate **post-backlog extension**. The Mode0 substrate backlog (R1–R5.4, R4.1b/c, R5.3) remains closed at baseline `32a87ff` / `86934d0`. R4.1d is an optional next-phase expansion that completes the legacy fetch primitive superset.

**Why now:**
- R4.1b (Planar) and R4.1c (Packed-Attribute) are closed.
- R4.1d completes the "Legacy Fetch Superset" for background layers.
- Foundational for Task 17 in the main roadmap.

---

## 3. Primitive Boundary

### In Scope

- **`shuffledMode` select**: A new mode bit in `VDP_TILE_MODE` or a new register.
- **Interleaved Fetch Logic**:
  - The FSM must be able to fetch "shuffled" words where bitplanes or attribute fields are interleaved within the SDRAM burst.
- **Coordinate Transformation**:
  - Logic to remap `tileX/tileY` to a shuffled SDRAM address space if the layout is non-linear.
- **Sim Coverage**:
  - New test cases in `TileAttributeFetchSim` verifying data reconstruction from a shuffled SDRAM buffer.
- **Hardware Proof**:
  - A scene rendered correctly from a shuffled SDRAM source.

### Explicitly Out of Scope

- **NO changes to Sprite Fetch**.
- **NO changes to Copper opcodes**.

---

## 4. Dependencies

- R4.1c verified baseline (`0e4d9dc`).
- R5.4 ScrollWrap primitive (`d580dcb`).

---

## 5. Interfaces

### New Mode Bit

| Addr | Name | Width | Description |
|------|------|------:|-------------|
| `0x0311` | `VDP_TILE_MODE` | 16 | bit[1]=1 Shuffled Mode (R4.1d) |

---

## 6. Data Model

- **`shuffleRemapReg`**: Optional register if the shuffle pattern is configurable.

---

## 7. Timing Model

- **Burst alignment**: Ensuring the FSM handles non-contiguous bursts if required by the shuffle pattern.

---

## 8. Memory / Bandwidth Impact

- Dependent on the specific shuffle pattern; goal is to maintain R4.1 bandwidth efficiency.

---

## 9. Platform Reuse

- Amiga (Primary)
- Arcade hardware (Secondary)

---

## 10. Failure Modes / Risks

- **Burst fragmentation**: If the shuffle requires many small reads, bandwidth will collapse.
- **Logic complexity**: High gate count for the barrel shifters/shufflers.

---

## 11. Validation Plan

- **`TileAttributeFetchSim`**: Verify bit-accurate reconstruction from a shuffled memory seed.
- **Full regression**: All 11 project sims pass.

---

## 12. Hardware Proof

- A bitmap scene stored in shuffled format in SDRAM, rendered without artifacts.

---

## 13. Audit Questions

- Is the shuffle logic efficient (low gate count)?
- Does it correctly handle the safe-boundary commit pattern?
- Is there an unambiguous hardware proof?

---

## 14. Constraints / Gotcha Check

- **GT-022**: Ensure shuffle tables are power-of-two.

---

## 15. Exit Condition

- This task is done when shuffled-mode fetch is verified in simulation and shown on hardware with a stable bitmap render.
