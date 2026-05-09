# spinalhdlVDP Changelog

## 2026-05-09 — Task 57 Closed, Task 54 Active

- **Task 57** — Substrate DFF Optimization (GW2AR-LV18 recovery) — DONE (audit PASS #9617)
  - Path 5A: descCount=8 / visiblePerLine=8 / NUM_SLOTS=8
  - Commit `fae0585`; first sprite-enabled bitstream since Task 2b
  - Logic Register as FF: 6834 / 15552 (44%) — 56% headroom
- **Task 54** — Sprite-Sprite Collision Detector — IN-PROGRESS (Checkpoint B)
  - Checkpoint A audit PASS #9620; BrightForge proof packet #9625 landed
  - Awaiting CyanPeak Checkpoint B audit

## 2026-05-07 — Task 53 Closed, Task 55 Active

- **Task 53** — Sprite Pattern Address Width Expansion — DONE (audit PASS #9433)
  - `patIdxWidth` 4→6 (16→64 unique tiles)
  - Commit `26174a7`; artifact in `PROJECT_PLAN/artifacts/TASK_53...md`
- **Task 55** — Sprite Masking + Tile-Fetch Budget Counter — DONE (audit PASS #9479)
  - Checkpoint A audit PASS #9445; Checkpoint B audit PASS #9461; hardware proof PASS

## 2026-05-06 — Task 3 Closed

- **Task 3** — Planar Fetch Hardening (2→5+ planes) — DONE (audit PASS #9406)
  - Fix: `VdpTop.scala:888` modulo wrap + `PlanarPixelIdxBoundsSim` discriminator
  - Commit `452c3db`; 0 timing violations; SMPTE bars proven

## 2026-04 — Sprite Capacity Expansion Epic Closed

- **Task 2b** — Sprite Capacity Bump (V=32/D=64) — DONE (audit PASS #9298)
- **Task 2c** — Sprite Evaluator Hardening — DONE (audit PASS #9278)
- **Task 2a** — Sprite Capacity Substrate Pre-Hardening — DONE (audit PASS #9250)

## 2026-04 — Adapter Infrastructure Complete

- **Task 50** — ZX Spectrum Adapter — DONE (#8976)
- **Task 51** — MODE_SELECT Runtime Adapter Selection — DONE (#9201)
- **Task 52** — Per-Sprite X/Y Flip Primitive — DONE (#9127)

## Earlier Closed Lanes (Selected)

| Task | Closeout | Commit | Key Evidence |
|------|----------|--------|--------------|
| Beam-Driven Automation Hardening | #8660 | `6345fcc` | Scenario 60 HW proof |
| Color/Window Hardening | #8654 | `0f5dc65` | Scenarios 51+52 HW proof |
| Sprite Phase 2 + 2-bis | #8638 | `39a7242` | Scenario 50 HW proof |
| Sprite Pattern Memory Foundation | #8605 | `e86fe49` | BSRAM-backed pattern RAM |
| Mode0 Sprite Envelope Hardening | #8589 | `d44a9c0` | Descriptor + evaluator upgrades |
| Mode0 Fetch Envelope Hardening | — | — | Tile/planar/shuffled/bitmap assessment |
| #9026 Zero-Footprint ROM Elimination | #9142 | — | Host-init bootstrap proven |

---

## Active Lane

See `PROJECT_PLAN/TASKS.md` §Live Lane State for the single active lane.

## Open Queue

See `PROJECT_PLAN/TASKS.md` §Next Up / Open Queue.

## Full History

For phase-by-phase narratives, proof records, and debug archaeology, see `PROJECT_PLAN/TASKS_HISTORY.md`.
