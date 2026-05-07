# TASKS.md

**Updated:** 2026-05-07 (Task 53 DONE. Doc restructure Audit PASS #9433. Awaiting next lane authorization.)
**Purpose:** Authoritative active task ledger for `spinalhdlVDP`. Optimized for fast operational reading. Deep historical detail is in `TASKS_HISTORY.md`.

Status values: `TODO`, `IN-PROGRESS`, `DEFERRED`, `DONE`

---

## How to Use This File

- Active state lives in **Live Lane State** and **Next Up / Open Queue**.
- Closed-lane history, extended proof narratives, and phase-by-phase task detail are in `TASKS_HISTORY.md`.
- Do not begin a task if any entry in its `depends_on` list is not `DONE`.
- A task is only `DONE` when its `validation` criteria are met on hardware (or simulation, for tasks not yet at hardware stage).

---

## Live Lane State

This section tracks the single active lane.

| Field | Value |
|-------|-------|
| **Task** | **Task 55 — Sprite Masking + Tile-Fetch Budget Counter** |
| **Status** | **ACTIVE** — PM authorized #9440; BrightForge implementation owner; Checkpoint A audit PASS #9445; Checkpoint B proof delivered #9454 |
| **Phase** | implement |
| **Latest Commit** | `0f96777` (Checkpoint B — sprite mask + tile-budget sims) |
| **Commits in lane** | N/A |
| **Latest Auth Mail** | #9440 (lane OPEN), #9445 (Checkpoint A PASS), #9454 (Checkpoint B proof) |
| **Artifact** | N/A |
| **Next Deliverable** | Checkpoint B audit (CyanPeak) |

**Context:** Task 53 CLOSED. Forward roadmap expanded and audited (PASS #9441). Task 55 opened per BronzeGate #9440. Checkpoint A contract APPROVED per CyanPeak #9445. BrightForge delivered Checkpoint B proof #9454 (5 sim cases A–E PASS; 10/10 sprite regression PASS). Awaiting CyanPeak audit.

---

## Next Up / Open Queue

The following tasks are **OPEN** and await PM authorization to become active lanes.

### Task 54 — Sprite-Sprite Collision Detector

| Field | Value |
|---|---|
| **Status** | **OPEN** — awaits PM lane authorization |
| **Gap** | No pairwise sprite-sprite overlap detection. C64 `$D01E` requires detecting any pair of sprites overlapping. |
| **Platforms helped** | C64 (primary); NES/Genesis (secondary) |
| **Impact** | **Medium** — 1 primary platform; adapter-local enhancement |
| **Risk/Complexity** | Medium. Combinational overlap detector for 32 sprites = 496 pairwise comparisons. Can optimize to bounding-box first, then pixel-precision for candidates. |
| **Proof shape** | Sim: overlapping sprites set collision bits; non-overlapping sprites do not; status register readback correct |
| **Source assessment** | `ASSESSMENT.md` §5, §Gap 4 |

---

### Task 55 — Sprite Masking + Tile-Fetch Budget Counter

| Field | Value |
|---|---|
| **Status** | **ACTIVE** — authorized #9440 |
| **Gap** | Genesis sprite masking and SNES 34-tiles/line fetch budget are unimplemented. |
| **Platforms helped** | Genesis, SNES |
| **Impact** | **Medium** — 2 platforms; edge-case features |
| **Risk/Complexity** | Low. Masking = 1 bit + suppress logic. Budget counter = counter + comparator. |
| **Proof shape** | Sim: masked sprite suppresses lower slots; 35-tile scene triggers overflow flag; regression PASS |
| **Source assessment** | `ASSESSMENT.md` §5, §Gap 3, §Gap 5 |

---

### Task 56 — Multi-Layer SDRAM Fetch

| Field | Value |
|---|---|
| **Status** | **OPEN** — awaits PM lane authorization |
| **Gap** | No SDRAM-backed fetch for background layers beyond L0. |
| **Platforms helped** | Amiga, Genesis, SNES |
| **Impact** | **Medium** — 3 platforms; deferred as "future task with its own stop-line review" |
| **Risk/Complexity** | Large. New arbiter clients, fetch FSMs, slot allocation policy, per-line budget re-analysis. |
| **Proof shape** | Sim: L0+L1 both fetch from SDRAM concurrently; arbitration priority correct; no line-drop under max load; resource + bandwidth report |
| **Source assessment** | `ASSESSMENT.md` §1, §5.1, §8.1 |

---

## Deferred Items

The following items remain intentionally coarse or out of Mode0 scope.

| Item | Status | Notes |
|------|--------|-------|
| Additional output modes | DEFERRED | Not required for baseline bring-up |
| Deep-angle affine tuning | DEFERRED | Only after affine base path is proven (Task 19, Task 37) |
| Platform adapter modes | DEFERRED | Task 40 (C64) DONE; Task 50 (ZX Spectrum) DONE; Task 51 (MODE_SELECT) DONE (#9201); Task 53–56 are open gap tasks awaiting PM authorization |
| Alternate memory strategies | DEFERRED | Only if baseline memory path becomes a blocker |
| E3.45 bottom-band stripes | **DONE** | BrightForge #8976. Analyzer PASS. |
| Parallel bus implementation | DEFERRED | After QSPI path is stable (Task 25) |

---

## Closed Summary

Recently closed lanes. Full history (phase detail, extended narratives, proof records) is in `TASKS_HISTORY.md`.

| Task | Status | Closeout Mail | Archive Artifact |
|---|---|---|---|
| Task 53 — Sprite Pattern Address Width Expansion | **DONE** | #9433 | `artifacts/TASK_53_SPRITE_PATTERN_ADDRESS_WIDTH_EXPANSION.md` |
| Task 2a — Sprite Capacity Substrate Pre-Hardening | **DONE** | #9252 | `archive/tasks/TASK_2A_SPRITE_CAPACITY_SUBSTRATE_PREHARDENING.md` |
| Task 2c — Sprite Evaluator Hardening | **DONE** | #9279 | `archive/tasks/TASK_2C_SPRITE_EVALUATOR_HARDENING.md` |
| Task 2b — Sprite Capacity Bump | **DONE** | #9294 | `archive/tasks/TASK_2B_SPRITE_CAPACITY_BUMP.md` |
| Task 3 — Planar Fetch Hardening | **DONE** | #9406 | `archive/tasks/TASK_3_PLANAR_FETCH_HARDENING.md` |
| Task 50 — ZX Spectrum Adapter | **DONE** | #8976 | `archive/tasks/TASK_50_ZX_SPECTRUM_ADAPTER.md` |
| Task 51 — MODE_SELECT Runtime Adapter Selection | **DONE** | #9201 | See `TASKS_HISTORY.md` §Phase 9 |
| Task 52 — Per-Sprite X/Y Flip Primitive | **DONE** | #9127 | See `TASKS_HISTORY.md` |
| #9026 — Zero-Footprint ROM Elimination | **DONE** | #9142 | See `TASKS_HISTORY.md` |

Older closed tasks (Phase 1–8, R-Roadmap, sidecar lanes) are catalogued in `TASKS_HISTORY.md`.

---

## Historical Artifact Index

| Category | Canonical Doc | Archive Location |
|---|---|---|
| Closed task detail | `TASKS_HISTORY.md` | `archive/tasks/` (per-task artifacts) |
| Platform adapter specs | `PLATFORM_ADAPTERS.md` | `archive/adapters/` (full specs) |
| Substrate assessments | `ASSESSMENT.md` | `archive/assessments/` (source files) |
| Gap task list (v1.0) | `TASKS.md` §Next Up | `archive/tasks/MODE0_GAP_TASKLIST_v1.0.md` |

---

## Agent Rules for This File

- Do not begin a task if any entry in its `depends_on` list is not `DONE`.
- Do not implement anything described in a task's `scope_boundary` as excluded.
- A task is only `DONE` when its `validation` criteria are met on hardware (or simulation, for tasks not yet at hardware stage).
- When marking a task `IN-PROGRESS` or `DONE`, update the status field in this file.
- Do not modify `depends_on` or `scope_boundary` fields without explicit instruction.

---

## Lane-Open Packet Template

Every new implementation lane must open with one authoritative packet. Copy this template into the kick-off mail or doc update.

```markdown
## Lane Open: [Task Name]

### Scope Boundary
- in scope: ...
- in scope: ...
- out of scope: ...
- out of scope: ...

### Required Proof
- sim: ...
- hardware: ...

### Audit Focus
- ...

### Checkpoints
- A: control/register contract
- B: simulation proof
- C: hardware proof

### Expected Next Deliverable
- [checkpoint name] by [owner]

### Coding Authorized
- YES / NO — [mail id]
```

Apply this template starting with Task 19 immediately.
