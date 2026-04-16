# TASK_21_MIXED_SCENE_INTEGRATION.md

**Status:** OPEN  
**Classification:** Integration validation (no new primitives)  
**Created:** 2026-04-16  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef

---

## 1. Task Name

Mixed-Scene Integration

---

## 2. Purpose

Prove that the closed Mode0 primitives can operate coherently in a single integrated scene. This is the gating task for the final validation lanes (soak + stress).

**Why now:**
- Tasks 15, 16, 17, 18, 19, and 20 are all closed.
- Every major primitive (tile fetch, planar fetch, shuffled fetch, sprites, raster effects, color math, affine) is individually proven.
- The next question is integration coherence, not primitive correctness.

---

## 3. Scope Boundary

### In Scope

- **Scenario 15 bootstrap** in `TopTang20kHdmi.scala` that activates multiple closed primitives simultaneously.
- **Copper-driven L0 mode switching** per horizontal band (tile → planar → shuffled).
- **L1 scroll** running concurrently with the L0 band switching.
- **Sprites** moving across the mode boundaries.
- **30-second YUYV hardware capture** with OpenCV proof of stability and distinct band presence.

### Explicitly Out of Scope

- **NO new primitives.** No new HDL modules, no new register maps, no new fetch paths.
- **NO new color-math or window features.** If used, only existing Task 20 semantics.
- **NO per-line affine updates.** The affine path is proven in Task 19; this task focuses on fetch-mode integration.
- **NO soak or stress testing.** That belongs to Tasks 22 and 23.

---

## 4. Dependencies

- **Task 15 — Memory-Backed Fetch Path** (closed)
- **Task 16 — Planar Bitmap Fetch** (closed)
- **Task 17 — Shuffled Bitmap Fetch** (closed)
- **Task 18 — Per-Line Raster Control** (closed)
- **Task 19 — Affine Layer** (closed)
- **Task 20 — Color Math / Window Effects** (closed)

Repository baseline verified at `0c90773`.

---

## 5. Proof Scene — Scenario 15

See `PROJECT_PLAN/scenarios/SCENARIO_15.md` for the full criteria.

Summary:
- `scenarioId=15` configures a copper program that switches `VDP_TILE_MODE` at `y=160` and `y=320`, creating three horizontal bands:
  - **Lines 0–159:** tile mode (packed 4bpp)
  - **Lines 160–319:** planar mode (2-plane 2bpp)
  - **Lines 320–479:** shuffled mode (Amiga-style bitplane)
- L1 scroll at 1 px/frame.
- Two sprites bouncing horizontally.
- 30 s YUYV capture + OpenCV analysis.

---

## 6. Register / Bootstrap Contract

No new registers. The scenario reuses:
- `VDP_TILE_MODE @ 0x0311` (switched by copper)
- `VDP_LAYER_ENABLE @ 0x0300` (L0 + L1 + sprites)
- `VDP_COPPER_CTRL @ 0x0310` (copper enable)
- Linestate / scroll registers (existing)
- Sprite position registers (existing)

---

## 7. Validation

### Hardware Proof (Checkpoint C)

- **Build target:** `TopTang20kHdmiScenario15Verilog`
- **Flash + capture:** 30 s YUYV 720×480 @ 50 fps via `/dev/video2`
- **Analysis:** `captures/sc15/analyze_sc15.py` (to be created)

Pass criteria:
- C1: three distinct horizontal bands are present and stable
- C2: L1 scroll motion is detectable
- C3: sprites are detected and moving
- C4: no corruption frames (band structure stable across ≥ 95 % of sampled frames)

### Regression

- `VdpTopSim` — must still PASS (no HDL changes)
- All existing scenario sims — must still PASS

---

## 8. Checkpoint Structure

Because this is an integration lane with no new HDL, the checkpoint structure is compressed:

- **Checkpoint A:** artifact audit (CyanPeak)
- **Checkpoint B:** bootstrap implementation + sim regression
- **Checkpoint C:** hardware capture + OpenCV proof

---

## 9. Audit Questions

1. **Is the scope strictly integration?** YES — no new primitives, no new registers.
2. **Does the proof scene exercise all three closed L0 fetch modes?** YES — tile, planar, shuffled via copper switching.
3. **Are sprites and scroll active concurrently?** YES — required by the bootstrap.
4. **Is the capture duration the project standard 30 s?** YES.
5. **Does any implementation cross the scope boundary?** HOLD if yes.
