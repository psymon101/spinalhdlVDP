# TASK_R4_2_L0_FETCH_SCROLL_JITTER.md

**Status:** DONE — Scroll jitter resolved during Fetch Envelope Hardening. L0 scroll is stable in all hardware-proven scenarios (Sc15–Sc17).
**Created:** 2026-04-14
**Coding Owner:** BrightForge
**Audit Owner:** CyanPeak
**PM/Coordination:** CoralReef (covering BronzeGate)

---

## 1. Task Name

R4.2 L0 Fetch Scroll-Jitter Investigation

---

## 2. Purpose

Eliminate the residual scroll-jitter observed on the R4.1 SDRAM-backed
Layer-0 path. User reported visible motion stutter; 60 fps / 30 s OpenCV
capture at capture-card native framerate (`1280×720 @ 60 fps` against
Tang's 60 fps output) shows **4 offset-jumps > 2 cap-px per 30 s** on
both mid and bot L0 bands, while the top L1 band is clean.

**Why now:**
- R5 (Host Interface + Copper) closed as of commit `0d4331c`.
- R5.2 OpenCV analysis pinpointed the residual to the L0 fetch path
  (packet #7086). R5 paths are proven clean.
- Persistent visual stutter is a user-blocking quality regression that
  should not carry into R4.3/R4.2-tile-effects/other Mode0 primitives.

---

## 3. Primitive Boundary

### In Scope

- **Investigate** the ~4 offset-jumps / 30 s (max 2 src-px each) on the
  L0 fetch path (`SdramTileAttributeFetch` + R4.1 scheduler coupling).
- **Reproduce** the jitter in sim if possible. Characterize:
  - Frequency (per-frame probability, not just aggregate)
  - Root cause (refresh preemption? line-buffer swap race? CDC jitter on
    `fetchLine` multi-bit BufferCC?)
- **Fix** the root cause with a minimal, localized change. Preserve the
  R4/R4.1 proven architecture.
- **Re-validate** with the same 60 fps OpenCV bar: target **0 big-jumps
  > 2 cap-px in 30 s on all three bands**.

### Explicitly Out of Scope

- NO new primitive capability (no new fetch engines, no new scheduler
  clients, no compositor changes)
- NO changes to R5 paths (copper, host interface, register bus)
- NO palette / asset / proof scene changes
- NO speed-ups or micro-optimizations unrelated to the jitter root cause

---

## 4. Dependencies

- R4 / R4.1 SDRAM tile+attribute fetch (proven, `9dfeb9f`)
- R5 / R5.2 Host + Copper (proven, `0d4331c`)
- `/tmp/r5_60fps.py` 60 fps OpenCV diagnostic script (lives outside repo)

---

## 5. Interfaces

No interface changes expected. The investigation operates inside
`SdramTileAttributeFetch` and/or `VdpTop`'s fetch-control latch path.

If a fix requires a new observability port (e.g., a jitter-count LED),
that is in scope; new control ports are out of scope.

---

## 6. Data Model

No new persistent state expected.

Investigation may add transient debug counters (cycles between refresh
pulses, underrun events, ping-pong flip count) that stay inside the
fetch engine or get routed to unused LEDs for bring-up.

---

## 7. Timing Model

The symptoms are:
- **Mid + bot L0 bands**: 4 offset-jumps > 2 cap-px in 30 s (60 fps capture)
- **Same count in both bands** → single root cause
- Top L1 path is clean → not a generic VdpTop issue

Hypotheses (priority-ordered):

1. **SDRAM refresh preemption extends fetch past line boundary.**
   - Refresh cadence: ~950 SDRAM cycles (~15 µs)
   - Fetch per line: ~41 tiles × 4 reads × ~5 cycles ≈ 820 SDRAM cycles
   - If a refresh lands mid-fetch, total extends to ~1000 cycles
   - At 64.8 MHz SDRAM / 25.2 MHz pixel, 1000 SDRAM cycles ≈ 389 pixel cycles
   - 800 pixel cycles per line → fetch can complete. But the MARGIN is
     thin and occasional stretch could miss the ping-pong swap.

2. **Multi-bit CDC on `fetchLine`/`fetchScrollX` tears.**
   - `BufferCC` on each 10-bit scroll/line signal separately
   - If one bit flips early and another late, fetch reads one line of
     data with mismatched scroll for a single fetch cycle

3. **Ping-pong swap races with reader when fetch completes late.**
   - `writeBuf := !writeBuf` on `fetchStartRise` (pixel domain)
   - If SDRAM-side is still pushing into the buffer that just became the
     read side, first few tiles of the reader's line are corrupted

---

## 8. Memory / Bandwidth Impact

No change expected.

---

## 9. Platform Reuse

Fix benefits every platform using the tile+attribute fetch primitive
(NES, Genesis, SNES, generic 2D tile-scroll).

---

## 10. Failure Modes / Risks

| Risk | Mitigation |
|------|------------|
| Root cause is timing-only, not reproducible in sim | Proceed with hardware-driven iteration, document findings even if partial |
| Fix widens fetch-to-line-boundary margin but doesn't eliminate all jumps | Accept partial fix; audit against user-visible smoothness, not just OpenCV counts |
| Fix introduces new regression in R4/R4.1 proof scenes | Full OpenCV re-run at each iteration |

---

## 11. Validation Plan

### Primary

- **60 fps / 30 s OpenCV** on `/dev/video2` at `1280×720`:
  - Target: **0 big-jumps > 2 cap-px on all three bands**
  - Acceptance threshold: ≤1 big-jump as a "within noise" pass

### Regression

- `VdpTopSim`, `TileAttributeFetchSim`, `SpriteEvaluatorSim`,
  `RasterTriggerUnitSim`, `FetchSlotSchedulerSim`, `HostInterfaceSim`,
  `CopperSim`, `UnifiedRegMapSim` — all must PASS

### Sim attempt

Add a case to `TileAttributeFetchSim` that:
- Drives refresh pressure by running 10 consecutive lines
- Checks that each line-buffer writes complete before the swap strobe
- Checks `underrun` stays deasserted across all 10 lines

If the jitter reproduces in sim, fix and re-verify. If not, note in
closeout that this is a silicon-only artifact and document the hardware
bar it satisfies.

---

## 12. Hardware Proof

### Proof

`/tmp/r5_60fps.py` output with 0 big-jumps on all three bands, captured
from `/dev/video2` at `1280×720 @ 60 fps`, 30 s window.

### Regression scene

Unchanged R5.2 scene (palette-bank checkerboard + copper horizontal
split). Must render identically.

---

## 13. Audit Questions

CyanPeak to verify:

1. **Root cause identification**: did the investigation produce a
   concrete hypothesis backed by either sim reproduction or silicon
   instrumentation (debug counters / LEDs)?
2. **Fix locality**: is the fix scoped to the fetch engine / VdpTop
   fetch-latch path, with no impact on R5 register-write paths?
3. **No regression**: do all 8 regression sims still PASS?
4. **Hardware bar**: does the 60 fps OpenCV run show ≤1 big-jump on all
   three bands?
5. **Documentation**: is the root cause (or inability to reproduce)
   recorded for future reference if the issue resurfaces?

---

## 14. Constraints / Gotcha Check

- [ ] **No hardware before sim attempt**: at minimum run
  `TileAttributeFetchSim` refresh-pressure case before each rebuild
- [ ] **No R5 changes**: all fixes must stay out of HostInterface,
  Copper, RegisterMap decode, layerEnableReg
- [ ] **GT-022 preserved**: no new memories; if debug counters are
  added, they are flip-flops only
- [ ] **Preserve R4.1 bandwidth widening**: slot 1 stays at
  `[0, hTotal-1]`

---

## 15. Exit Condition

This task is done when the 60 fps / 30 s OpenCV capture shows ≤1
big-jump > 2 cap-px on all three scroll-tracked bands (top L1, mid L0,
bot L0), all 8 regression sims pass, and the root cause (or silicon-
only classification) is documented in the closeout packet.

---

## Short-Form Summary

```markdown
## Task
R4.2 L0 Fetch Scroll-Jitter Investigation

## Purpose
Eliminate ~4 scroll jumps / 30 s on the L0 SDRAM fetch path that R5.2
OpenCV analysis localized after R5 safe-boundary fixes cleared the
register path.

## Scope
- in scope: investigate + fix fetch-side jitter (refresh preemption,
  CDC tearing, ping-pong race candidates)
- in scope: 60 fps OpenCV pass criterion ≤1 big-jump on all bands
- out of scope: R5 paths, palette/assets, proof scene, new primitives

## Dependencies
- R4.1 (`9dfeb9f`) and R5.2 (`0d4331c`) baselines

## Interfaces
Unchanged (optional debug counters / LEDs only)

## Timing
Hypotheses: refresh preemption, multi-bit CDC tearing, ping-pong race.
Budget per line: ~820 SDRAM cycles vs 800 pixel cycles — thin margin.

## Risks
Silicon-only behavior may resist sim reproduction; accept partial fix
plus documentation.

## Validation
- sim: extend TileAttributeFetchSim with refresh-pressure case
- regression: 8 existing sims
- hardware: 60 fps / 30 s OpenCV ≤1 big-jump on all bands

## Audit Focus
Root cause clarity, fix locality, regression absence, hardware bar,
documentation

## Exit Condition
60 fps OpenCV bar met, 8 sims pass, root cause documented.
```
