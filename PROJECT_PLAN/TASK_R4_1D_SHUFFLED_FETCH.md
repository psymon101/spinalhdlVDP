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

- **`shuffledMode` select**: A dedicated mode value in the **existing `VDP_TILE_MODE @ 0x0311` register**. The field expands from 1 bit to 2 bits with the following encoding:
  - `0x00` = packed 4bpp (R4 default)
  - `0x01` = planar 2bpp (R4.1b)
  - `0x02` = **shuffled (Amiga-style bitplane) 2bpp (R4.1d)**
  - `0x03` = reserved
- **Interleaved Fetch Logic**:
  - Amiga-style bitplane shuffle: two separately-based plane buffers in SDRAM. For each tile row, the FSM issues one read from Plane 0 base, then one read from Plane 1 base. Pixel reconstruction is `{plane1[bit] ## plane0[bit]}` producing a 2bpp index.
- **Coordinate Transformation**:
  - No non-linear `tileX/tileY` remap. Address math remains identical to R4; only the **SDRAM base address** changes per plane. Plane 0 base = existing planar base (`0xA000`). Plane 1 base = new offset (`0xB000`).
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
| `0x0311` | `VDP_TILE_MODE` | 16 | bit[1:0] tile decode mode. `0`=packed 4bpp, `1`=planar 2bpp, `2`=shuffled/bitplane 2bpp, `3`=reserved. |

---

## 6. Data Model

- **No configurable remap register** for this lane. The shuffle pattern is fixed to the Amiga-style separate-base bitplane layout described above. Future generalization (e.g., `shuffleRemapReg`) is explicitly out of scope.

---

## 7. Timing Model

- **Burst alignment**: Ensuring the FSM handles non-contiguous bursts if required by the shuffle pattern.

---

## 8. Memory / Bandwidth Impact

- Two reads per tile row (Plane 0 + Plane 1) from distinct SDRAM regions. Bandwidth remains comparable to planar mode because each read is still a single contiguous burst.

---

## 9. Platform Reuse

- Amiga bitplane DMA (Primary)
- Arcade hardware (Secondary — explicitly out of scope for this first delivery)

---

## 10. Failure Modes / Risks

- **Burst fragmentation**: Avoided by design — each plane is a single contiguous burst per tile row.
- **Logic complexity**: Low; the only new logic is a base-address mux (Plane 0 vs Plane 1) and a 2-bit pixel reconstruction `{plane1, plane0}`.

---

## 11. Validation Plan

- **`TileAttributeFetchSim`**:
  - **Case 9**: Seed Plane 0 and Plane 1 SDRAM buffers with known patterns, verify the reconstructed 2bpp pixel stream matches `{plane1, plane0}` for every pixel in the tile row.
  - Cross-check that switching `0x0311 = 2` at `hCounter === 0` activates the new base-address path.
- **`UnifiedRegMapSim`**: Verify `0x0311 = 2` propagates through the safe-boundary shadow+commit path.
- **Full regression**: All 11 project sims pass.

---

## 12. Hardware Proof

- **Diagnostic scene**: "Bitplane Checkerboard". A synthetic 2×2 tile pattern where adjacent tiles map to the four possible 2bpp values (`00`, `01`, `10`, `11`), each routed to a distinct palette bank. This makes the two-plane reconstruction bit-observable on HDMI with zero visual ambiguity.
- The scene uses the same `PlanarTileAssets` ROM source, but plane 0 is copied to SDRAM `0xA000` and plane 1 to `0xB000` during bootstrap.

---

## 13. Audit Questions

- Is the separate base-address fetch logic gate-efficient (no barrel shifter / no remap table)?
- Does `VDP_TILE_MODE` correctly expand to 2 bits with the same safe-boundary shadow+commit pattern?
- Is the bitplane-checkerboard hardware proof unambiguous and backed by 30s OpenCV analysis?

---

## 14. Constraints / Gotcha Check

- **GT-022**: Ensure shuffle tables are power-of-two.

---

## 15. Exit Condition

- This task is done when shuffled-mode fetch is verified in simulation and shown on hardware with a stable bitmap render.

---

## 16. Implementation Checkpoints

To avoid an open-ended implementation stall, BrightForge will deliver R4.1d through three bounded checkpoints. Each checkpoint ends with a commit and a brief evidence packet.

### Checkpoint A — Control-path widening only
**Deliver:**
- `VDP_TILE_MODE @ 0x0311` widened from 1 bit to 2 bits in the safe-boundary shadow+commit path (`VdpTop`).
- `UnifiedRegMapSim` proves `0x0311 = 2` latches and propagates correctly at the safe boundary.
- No fetch-path behavior change required yet.

**Exit packet:**
- Commit hash
- Changed files list
- `UnifiedRegMapSim` result for the new mode value

### Checkpoint B — Fetch-path reconstruction in simulation
**Deliver:**
- Shuffled-mode fetch path using Plane 0 @ `0xA000`, Plane 1 @ `0xB000`.
- Pixel reconstruction `{plane1[bit], plane0[bit]}`.
- `TileAttributeFetchSim` case 9 proving bit-accurate reconstruction.
- Full 11-sim regression green.

**Exit packet:**
- Commit hash
- Case 9 evidence (assertion pass log)
- Full sim summary

### Checkpoint C — Hardware diagnostic proof
**Deliver:**
- Bootstrap path loads the bitplane-checkerboard diagnostic scene (Plane 0 + Plane 1 copied to SDRAM).
- Tang Nano 20K HDMI proof capture.
- Mandatory 30s OpenCV analysis.
- Final closeout packet addressing CyanPeak’s three audit points.

**Exit packet:**
- Hardware capture path/reference
- OpenCV summary
- Final closeout note

**Next step now:** BrightForge proceeds to **Checkpoint A** and reports back upon completion.
