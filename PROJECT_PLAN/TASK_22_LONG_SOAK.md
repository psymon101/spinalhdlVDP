# TASK_22_LONG_SOAK.md

**Status:** DONE — Long soak validation completed (Scenario 16)  
**Classification:** Runtime stability testing (no new primitives)  
**Created:** 2026-04-17  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak  
**PM/Coordination:** CoralReef

---

## 1. Task Name

Long Soak Validation

---

## 2. Purpose

Prove that the integrated Mode0 baseline (mixed fetch modes + scroll + sprites) remains stable over an extended continuous run. This is the duration gate before stress validation.

**Why now:**
- Task 21 (Mixed-Scene Integration) is closed and hardware-verified.
- The next question is long-run stability, not feature integration.

---

## 3. Scope Boundary

### In Scope

- **Scenario 16** bootstrap in `TopTang20kHdmi.scala` — identical to Scenario 15 (proven integration scene).
- **Minimum 1-hour continuous hardware run** with motion active for the full duration.
- **Three 30-second snapshot captures** at T=0 min, T=30 min, and T=60 min.
- **OpenCV stability analysis** on each snapshot using the same criteria as Scenario 15.

### Explicitly Out of Scope

- **NO new primitives, registers, or HDL modules.**
- **NO new scenarios or visual compositions.** Sc16 reuses the Sc15 bootstrap exactly.
- **NO stress-load expansion** (that belongs to Task 23).

---

## 4. Dependencies

- **Task 21 — Mixed-Scene Integration** (closed)

Repository baseline: `957e4f6`.

---

## 5. Proof Scene — Scenario 16

See `PROJECT_PLAN/scenarios/SCENARIO_16.md` for the full criteria.

Summary:
- `scenarioId=16` configures the **same copper-driven mode-switching scene as Scenario 15**:
  - **Lines 0–159:** packed 4bpp tiles
  - **Lines 160–319:** planar 2-plane 2bpp
  - **Lines 320–479:** shuffled bitplanes
- L0 scroll at 1 px/frame.
- Two sprites bouncing horizontally.
- The only difference from Sc15 is the **duration** (1 h soak vs. 30 s integration proof).

---

## 6. Register / Bootstrap Contract

No new registers. The scenario reuses the exact same register set as Scenario 15:
- `VDP_TILE_MODE @ 0x0311` (switched by copper)
- `VDP_LAYER_ENABLE @ 0x0300` (L0 + sprites)
- `VDP_COPPER_CTRL @ 0x0310` (copper enabled)
- Linestate / scroll registers (existing)
- Sprite position registers (existing)

**Bootstrap delta from Sc15 to Sc16:** none. The `TopTang20kHdmi.scala` case for `16` falls through to the `15` copper program and identical static register values.

---

## 7. Validation

### Hardware Proof (Checkpoint C)

- **Build target:** `TopTang20kHdmiScenario15Verilog` (Sc16 shares the bootstrap)
- **Flash command:** `make all SCENARIO=16`
- **Soak protocol:**
  1. Flash Sc16 to Tang Nano 20K.
  2. Start the FPGA running; leave it untouched for 60 minutes.
  3. At T=0, T=30 min, and T=60 min, record a 30-second snapshot:
     ```bash
     ffmpeg -y -f v4l2 -input_format yuyv422 -video_size 720x480 -framerate 50 \
       -i /dev/video2 -t 30 -c:v libx264 -preset ultrafast -qp 0 -pix_fmt yuv444p \
       sc16_soak_T0.mp4
     ```
  4. Run `python3 captures/sc15/analyze_sc15.py captures/sc16/sc16_soak_T<N>.mp4` on each snapshot.

### Pass Criteria

| Check | Condition |
|---|---|
| **C1a top-band presence** | Top 1/3 mean differs from bottom 2/3 mean by ≥ 15 units in at least one BGR channel for ≥ 95 % of sampled frames, **in all three snapshots**. |
| **C1b mid/bottom coherence** | Mid 1/3 and bottom 1/3 means are within ±20 units in at least one BGR channel for ≥ 95 % of sampled frames, **in all three snapshots**. |
| **C2 L0 scroll motion** | Mean absolute frame-difference ≥ 5.0 in all three snapshots. |
| **C3 sprite presence** | Sprite detection fraction ≥ 95 % and x-range ≥ 100 px in all three snapshots. |
| **C4 stability** | Combined C1a/C1b outlier rate ≤ 5 % in all three snapshots. |
| **C5 zero corruption events** | No visual lock-up, screen tearing, color corruption, or SDRAM drift observed during the 1-hour observation window. |

Task 22 passes when **C1a, C1b, C2, C3, C4, and C5** all PASS.

### Regression

- `VdpTopSim` — must still PASS (no HDL changes)
- All existing scenario sims — must still PASS

---

## 8. What Counts as Failure

| Symptom | Interpretation |
|---|---|
| **Lock loss** | HDMI output freezes on a single frame; scroll or sprite motion stops. |
| **Drift** | Band boundaries visibly wander (±>5 lines) compared to T=0 snapshot. |
| **Corruption** | C1a/C1b outlier rate rises above 5 % in any snapshot; random speckles or tearing appear. |
| **Memory instability** | Sudden color shifts, tile-map glitches, or sprite corruption that were not present at T=0. |

---

## 9. Checkpoint Structure

- **Checkpoint A:** artifact audit (CyanPeak)
- **Checkpoint B:** bootstrap implementation + sim regression
- **Checkpoint C:** 1-hour soak execution + snapshot analysis
