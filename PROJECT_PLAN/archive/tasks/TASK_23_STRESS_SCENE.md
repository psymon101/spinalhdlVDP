# TASK_23_STRESS_SCENE.md

**Status:** DONE — Stress scene validation completed (Scenario 17)  
**Classification:** Maximum-load validation (no new primitives)  
**Created:** 2026-04-17  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef

---

## 1. Task Name

Stress-Scene Validation

---

## 2. Purpose

Prove that the Mode0 integrated baseline remains stable when all closed primitives operate simultaneously at their documented ceiling. This is the final validation gate before the project baseline is declared complete.

**Why now:**
- Tasks 21 and 22 are closed.
- Integration correctness and long-run stability are proven.
- The remaining question is whether the hardware can sustain maximum concurrent load without corruption or performance collapse.

---

## 3. Scope Boundary

### In Scope

- **Scenario 17** bootstrap in `TopTang20kHdmi.scala` that activates **all** closed primitives at maximum configured load.
- **30-second hardware capture** with OpenCV stability analysis.
- Explicit documentation of the **load ceiling** (what the hardware is proven to handle).

### Explicitly Out of Scope

- **NO new primitives, registers, or HDL modules.**
- **NO increasing hardware limits** (e.g., do not change `descCount` or `visiblePerLine` in `SpriteEvaluator`).
- **NO soak duration extension** — 30 s is sufficient for stress validation.

---

## 4. Dependencies

- **Task 21 — Mixed-Scene Integration** (closed)
- **Task 22 — Long Soak Validation** (closed)

Repository baseline: `79b9cf6`.

---

## 5. Proof Scene — Scenario 17

See `PROJECT_PLAN/scenarios/SCENARIO_17.md` for the full criteria.

Summary:
- `scenarioId=17` configures the **maximum concurrent load** the closed substrate supports:
  - **L0**: copper-driven mixed fetch modes (packed → planar → shuffled) + scroll
  - **L1**: packed tiles + fast scroll (parallax under L0)
  - **Sprites**: all 4 descriptors active and bouncing (hardware limit: `descCount=4`, `visiblePerLine=2`)
  - **Compositor**: L0 + L1 + sprites all enabled (`LAYER_ENABLE = 0x0007`)
- 30 s YUYV capture + OpenCV analysis.

---

## 6. Register / Bootstrap Contract

No new registers. The scenario reuses the existing register set at maximum load:
- `VDP_TILE_MODE @ 0x0311` (switched by copper)
- `VDP_LAYER_ENABLE @ 0x0300` (L0 + L1 + sprites = `0x0007`)
- `VDP_COPPER_CTRL @ 0x0310` (copper enabled)
- Linestate / scroll registers (both L0 and L1 scrolling)
- Sprite position registers (all 4 descriptors active)

---

## 7. Validation

### Hardware Proof (Checkpoint C)

- **Build target:** `TopTang20kHdmiScenario15Verilog` (shared top)
- **Flash + capture:** 30 s YUYV 720×480 @ 50 fps via `/dev/video2`
- **Analysis:** `captures/sc17/analyze_sc17.py`

Pass criteria:
- C1: No visual corruption (tearing, speckles, color glitches, band collapse) for ≥ 95 % of frames
- C2: All 4 sprites detected and moving (detection ≥ 95 %, x-range ≥ 100 px per sprite class)
- C3: Both L0 and L1 scroll motion detectable (mean frame delta ≥ 5.0)
- C4: No lock-up, drift, or SDRAM instability observed during the 30 s window

Task 23 passes when C1, C2, C3, and C4 all PASS.

### Load Ceiling Documentation

The artifact must explicitly record:
- **Sprite ceiling:** 4 descriptors, 2 visible/line (hardware limit)
- **Layer ceiling:** L0 + L1 + sprites concurrently (3-layer compositor)
- **Fetch ceiling:** Mixed-mode L0 + packed L1 + 4 sprite patterns/line
- **Scroll ceiling:** Dual-layer independent scroll with copper mode-switching

---

## 8. What Counts as Failure

| Symptom | Interpretation |
|---|---|
| **Corruption** | C1 outlier rate > 5 %; visible tearing, speckles, or color banding not present at T=0. |
| **Sprite drop** | Any sprite fails detection for > 5 % of frames, or x-range collapses (< 100 px). |
| **Scroll stall** | Mean frame delta < 5.0 on either layer. |
| **Lock-up / drift** | HDMI freezes, band boundaries wander, or SDRAM artifacts appear. |

---

## 9. Checkpoint Structure

- **Checkpoint A:** artifact audit (CyanPeak)
- **Checkpoint B:** bootstrap implementation + sim regression
- **Checkpoint C:** hardware capture + OpenCV proof
