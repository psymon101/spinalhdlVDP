# TASK_R4_1C_PACKED_ATTRIBUTE.md

**Status:** DONE — Packed attribute mode implemented and hardware-proven
**Created:** 2026-04-14
**Coding Owner:** BrightForge
**Audit Owner:** CyanPeak
**PM/Coordination:** CoralReef (covering BronzeGate)

---

## 1. Task Name

R4.1c Packed-Attribute Decode (NES-style)

---

## 2. Purpose

Extend the R4.1 fetch engine to support **packed attribute** modes, where a single attribute byte in SDRAM covers multiple tiles (e.g., a 2x2 or 16x16 pixel area). This is the attribute model used by the NES (Famicom) and several other retro platforms to save memory. 

The current R4.1 baseline assumes a **linear 1:1 attribute map** (one byte per 8x8 tile). This task adds the flexibility to share attribute bytes across tile clusters and extract sub-byte fields (e.g., 2 bits per tile).

**Why now:**
- R4.1b proved planar decode logic.
- R4.1c completes the "NES-compatibility" fetch primitives (Planar 2bpp + Packed Attributes).
- Reduces SDRAM bandwidth requirements for attribute-dense scenes.

---

## 3. Primitive Boundary

### In Scope

- **`attributeMode` select**: A new configuration field (likely in `VDP_TILE_MODE` or `VDP_ATTR_MODE`) to select between:
  - 0: Linear 1:1 (current R4 baseline)
  - 1: NES-style 2x2 packing (1 byte per 2x2 tiles, 2 bits per tile)
- **Attribute Fetch Optimization**:
  - In packed mode, the FSM should skip redundant SDRAM reads if the current tile shares the same attribute byte as the previous tile.
  - Requires a small "last attribute byte" cache/shadow register in the fetch engine.
- **Attribute Decoding**:
  - Logic to extract the correct bits from the fetched byte based on the tile's alignment within the block (e.g., bits [1:0] for top-left, [3:2] for top-right, etc.).
- **Sim Coverage**:
  - New test cases in `TileAttributeFetchSim` verifying correct bank selection across a 2x2 block boundary.
- **Hardware Proof**:
  - A scene using packed attributes to drive different palette banks for 4 adjacent tiles from a single SDRAM byte.

### Explicitly Out of Scope

- **NO changes to Planar Decode**: This task is orthogonal to the bit-plane decode logic.
- **NO new SDRAM layout**: Uses the existing `AttributeMapBase`.
- **NO sprite changes**.

---

## 4. Dependencies

- R4.1b Planar Decode baseline (`f8aa20a`).
- R5.4 ScrollWrap primitive (`d580dcb`).

---

## 5. Interfaces

### New/Extended Register

| Addr | Name | Width | Description |
|------|------|------:|-------------|
| `0x0312` | `VDP_ATTR_MODE` | 16 | bit[0]=0 Linear (1:1), bit[0]=1 Packed (2x2) |

---

## 6. Data Model

- **`attrCacheReg`**: Stores the last fetched attribute byte.
- **`attrBlockCoord`**: Latched coordinates of the current attribute block to detect cache hits.

---

## 7. Timing Model

- **Scheduled Fetch**: Attribute fetch happens in the same scheduled slot, but the FSM may bypass the `sFetchAttrRq` state if a cache hit is detected.

---

## 8. Memory / Bandwidth Impact

- **Reduction**: In 2x2 mode, attribute bandwidth is reduced by ~75% (1 read per 4 tiles).

---

## 9. Platform Reuse

- NES (Primary)
- SMS/Genesis (Secondary - if using similar packing for priority/palette)

---

## 10. Failure Modes / Risks

- **Alignment bugs**: Incorrect bit extraction at block boundaries.
- **Stale cache**: Failure to clear the attribute cache at the start of a scanline.
- **CDC**: Ensuring the new mode bit is safely synchronized.

---

## 11. Validation Plan

- **`TileAttributeFetchSim`**: Verify that `pixelPaletteBank` changes correctly across a 2x2 grid using only 1 attribute read per block.
- **Regression**: Ensure packed 4bpp and planar 2bpp still work in linear attribute mode.

---

## 12. Hardware Proof

- A checkerboard pattern where 4 tiles share one attribute byte but render with different palette banks (using the 2-bit sub-fields).

---

## 13. Audit Questions

- Does the cache hit logic correctly handle horizontal and vertical boundaries?
- Is the bit extraction logic bit-accurate to the NES spec (or the defined generic variant)?
- Does the FSM correctly skip the SDRAM cycle on a hit?

---

## 14. Constraints / Gotcha Check

- **GT-022**: Ensure any new attribute lookup tables or small memories are power-of-two.

---

## 15. Exit Condition

- This task is done when packed-attribute decode is verified in simulation **and** proven unambiguously on hardware using a dedicated diagnostic attribute map that renders four distinct palette banks from a single 2×2 shared byte.

## 100% Verification Rule (Mandatory)

**This task must be proven 100% before closeout. No exceptions.**
- Simulator proof (`TileAttributeFetchSim` case 8) is required but not sufficient.
- An unambiguous hardware proof with a packed-friendly diagnostic attribute map is required.
- The legacy linear-mode attribute ROM does not satisfy the hardware proof because it produces an ambiguous visual pattern.
