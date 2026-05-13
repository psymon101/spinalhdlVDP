# TASKS.md

**Updated:** 2026-05-13 (Host Platform Fidelity opened #9801; ZX Spectrum Firmware Host Flow DONE #9797; Atari ST paused #9783; 320-pixel planar clipping mask DONE #9768.)
**Purpose:** Authoritative active task ledger for \`spinalhdlVDP\`. Optimized for fast operational reading. Deep historical detail is in \`TASKS_HISTORY.md\`.

Status values: \`TODO\`, \`IN-PROGRESS\`, \`DEFERRED\`, \`DONE\`

---

## How to Use This File

- Active state lives in **Live Lane State** and **Next Up / Open Queue**.
- Closed-lane history, extended proof narratives, and phase-by-phase task detail are in \`TASKS_HISTORY.md\`.
- Do not begin a task if any entry in its \`depends_on\` list is not \`DONE\`.
- A task is only \`DONE\` when its \`validation\` criteria are met on hardware (or simulation, for tasks not yet at hardware stage).

---

## Live Lane State

This section tracks the single active lane.

| Field | Value |
|-------|-------|
| **Task** | **Host Platform Fidelity Requirements** — ESP8266 / ESP32 / Pico 2 |
| **Status** | **IN-PROGRESS** — opened #9801 (BronzeGate), owner FoggyWolf, audit CyanPeak |
| **Phase** | implement (doc-only) |
| **Latest Commit** | — |
| **Commits in lane** | — |
| **Latest Auth Mail** | #9801 (BronzeGate lane open) |
| **Artifact** | — |
| **Next Deliverable** | FoggyWolf planning/proof packet for documentation updates |

**Context:** Task 54 CLOSED per CyanPeak #9672. Task 56 DONE per CyanPeak #9709. **320-pixel planar clipping mask** DONE #9768 (\`755fd10\`). **ZX Spectrum Firmware Host Flow (v1)** DONE #9797 (\`13989c1\`). **Atari ST Adapter Lane** opened #9776, Checkpoint A accepted #9782, then **PAUSED #9783** per user priority shift. **Host Platform Fidelity Requirements** opened #9801: doc-only lane for per-platform fidelity notes, preferred authoritative proof host, transport limits, status/debug expectations.

---

## Next Up / Open Queue

The following tasks are **OPEN** and await PM authorization to become active lanes.

### Host Platform Fidelity Requirements (ESP8266 / ESP32 / Pico 2)

| Field | Value |
|---|---|
| **Status** | **IN-PROGRESS** — opened #9801; owner FoggyWolf, audit CyanPeak |
| **Gap** | No canonical documentation of host-platform constraints affecting visual fidelity and proof trustworthiness. |
| **Platforms helped** | All (firmware/host-transport) |
| **Impact** | **High** — establishes which host platform to trust for visual proof and why |
| **Risk/Complexity** | Low. Documentation-only lane; no RTL or firmware feature implementation. |
| **Proof shape** | Doc packet: per-platform fidelity notes, preferred authoritative host, acceptable functional hosts, transport/debug expectations, stale artifact disposition |
| **Source assessment** | `firmware/README.md`, `firmware/GOTCHAS.md`, `kb/<Adapter>/README.md` |
| **Depends on** | ZX Spectrum Firmware Host Flow DONE (#9797) |
| **Scope Boundary** | ESP8266, ESP32, Pico 2 host-side constraints only. No new RTL. No new firmware features. No reopening closed ZX work. |
| **Checkpoints** | A: planning/proof packet; B: documentation updates; C: CyanPeak audit ruling |
| **Coding authorized** | YES — #9801 |

---

### Atari ST Adapter Lane

| Field | Value |
|---|---|
| **Status** | **PAUSED** — Checkpoint A accepted #9782; paused #9783 per user priority shift |
| **Gap** | No Atari ST adapter. Bounded v1: 320×200 4-plane planar output only. |
| **Platforms helped** | Atari ST (primary) |
| **Impact** | **Low-Medium** — 1 platform; lowest-risk Tier 1 adapter per \`MODE0_PLANNING.md\` §6 |
| **Risk/Complexity** | Low-Medium. Planar + raster only; no sprites needed for v1. |
| **Proof shape** | Sim: adapter-local coherence proof; HW: static test pattern renders correctly, palette swap via raster trigger if used, 30s capture freeze=0 |
| **Source assessment** | \`MODE0_PLANNING.md\` §6 rank 5; \`ASSESSMENT.md\` |
| **Depends on** | Task 3 DONE |
| **Scope Boundary** | v1: 320×200 4-plane planar only. STE blitter out of scope. No sprite expansion. No substrate rewrite. |
| **Checkpoints** | A: adapter plan / register-mode mapping / proof shape ✅ ACCEPTED #9782; B: implementation + sim + hardware capture (PAUSED); C: CyanPeak audit ruling (pending reopen) |
| **Coding authorized** | YES — #9776; PAUSED — #9783 |

---

### Task 54 — Sprite-Sprite Collision Detector

| Field | Value |
|---|---|
| **Status** | **DONE** — CyanPeak audit PASS #9672; sim-only proof per #9620 |
| **Gap** | No pairwise sprite-sprite overlap detection. C64 \`\$D01E\` requires detecting any pair of sprites overlapping. |
| **Platforms helped** | C64 (primary); NES/Genesis (secondary) |
| **Impact** | **Medium** — 1 primary platform; adapter-local enhancement |
| **Risk/Complexity** | Medium. Per-pixel collision in sequential rasterizer via "check buffer before write" — honest for descCount=8 substrate. |
| **Proof shape** | Sim: overlapping sprites set collision bits; non-overlapping sprites do not; W1C clears mask and sticky bit; regression PASS |
| **Source assessment** | \`ASSESSMENT.md\` §5, §Gap 4 |
| **Checkpoints** | A: implementation shape + status surface ✅ AUDIT PASS #9620; B: implement + sim + regression ✅ PASS #9625; C: audit + ledger sync ✅ PASS #9672 |
| **Coding authorized** | YES — #9616 |
| **Depends on** | Task 53 DONE, Task 57 DONE |

---

### Task 55 — Sprite Masking + Tile-Fetch Budget Counter

| Field | Value |
|---|---|
| **Status** | **DONE** — authorized #9440 |
| **Gap** | Genesis sprite masking and SNES 34-tiles/line fetch budget are unimplemented. |
| **Platforms helped** | Genesis, SNES |
| **Impact** | **Medium** — 2 platforms; edge-case features |
| **Risk/Complexity** | Low. Masking = 1 bit + suppress logic. Budget counter = counter + comparator. |
| **Proof shape** | Sim: masked sprite suppresses lower slots; 35-tile scene triggers overflow flag; regression PASS |
| **Source assessment** | \`ASSESSMENT.md\` §5, §Gap 3, §Gap 5 |

---

### Task 56 — Multi-Layer SDRAM Fetch

| Field | Value |
|---|---|
| **Status** | **DONE** — CyanPeak audit PASS #9709 on commit \`834c71e\`. Sim-only contract fulfilled. |
| **Gap** | No SDRAM-backed fetch for background layers beyond L0. |
| **Platforms helped** | Amiga, Genesis, SNES |
| **Impact** | **Medium** — 3 platforms; deferred as "future task with its own stop-line review" |
| **Risk/Complexity** | Large. New arbiter clients, fetch FSMs, slot allocation policy, per-line budget re-analysis. |
| **Proof shape** | Sim: L0+L1 both fetch from SDRAM concurrently; arbitration priority correct; no line-drop under max load; resource + bandwidth report |
| **Source assessment** | \`ASSESSMENT.md\` §1, §5.1, §8.1 |

---

### Task 57 — Substrate DFF Optimization (GW2AR-LV18 recovery)

| Field | Value |
|---|---|
| **Status** | **DONE** — Path 5A PnR PASS #9605 |
| **Gap** | Sprite substrate overran 18K DFF budget (111% load). **Resolved** by descCount=8 floor + cumulative Mem-refactor work (Slice 2/3). |
| **Platforms helped** | All (sprite-dependent) |
| **Impact** | **High** — restores hardware-readiness for sprite-enabled scenarios |
| **Risk/Complexity** | Low (Slice 1 parametric); High (Slice 2/3 structural). Actual resolution was parametric (descCount=8) after structural work proved insufficient alone. |
| **Proof shape** | PnR: zero \`PR0003\` errors, \`project.fs\` produced, DFF ≤ 44%. Sim: 11/11 sprite regression PASS bit-identical. |
| **Source assessment** | #9474, #9478, #9479, #9488, #9493, #9547, #9549, #9601, #9605, #9604 |

> Deep findings, slice narratives, and toolchain gotchas moved to \`ASSESSMENT.md\` §6 (Resource and Toolchain Gotchas) to keep the task ledger concise.


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

Recently closed lanes. Full history (phase detail, extended narratives, proof records) is in \`TASKS_HISTORY.md\`.

| Task | Status | Closeout Mail | Archive Artifact |
|---|---|---|---|
| ZX Spectrum Firmware Host Flow (v1) | **DONE** | #9797 | Commit \`13989c1\`, \`zx_final_proof_v4.png\` |
| 320-pixel planar clipping mask | **DONE** | #9768 | Commit \`77bedae\` |
| Task 56 — Multi-Layer SDRAM Fetch | **DONE** | #9709 | Commits \`93773d7\`, \`ee5820c\`, \`834c71e\` |
| Task 54 — Sprite-Sprite Collision Detector | **DONE** | #9672 | Commit \`e556ff5\` |
| Task 57 — Substrate DFF Optimization | **DONE** | #9605 | Commit \`fae0585\`, \`impl/pnr/project.fs\` |
| Task 53 — Sprite Pattern Address Width Expansion | **DONE** | #9433 | \`archive/artifacts/TASK_53_SPRITE_PATTERN_ADDRESS_WIDTH_EXPANSION.md\` |
| Task 2a — Sprite Capacity Substrate Pre-Hardening | **DONE** | #9252 | \`archive/tasks/TASK_2A_SPRITE_CAPACITY_SUBSTRATE_PREHARDENING.md\` |
| Task 2c — Sprite Evaluator Hardening | **DONE** | #9279 | \`archive/tasks/TASK_2C_SPRITE_EVALUATOR_HARDENING.md\` |
| Task 2b — Sprite Capacity Bump | **DONE** | #9294 | \`archive/tasks/TASK_2B_SPRITE_CAPACITY_BUMP.md\` |
| Task 3 — Planar Fetch Hardening | **DONE** | #9406 | \`archive/tasks/TASK_3_PLANAR_FETCH_HARDENING.md\` |
| Task 50 — ZX Spectrum Adapter | **DONE** | #8976 | \`archive/tasks/TASK_50_ZX_SPECTRUM_ADAPTER.md\` |
| Task 51 — MODE_SELECT Runtime Adapter Selection | **DONE** | #9201 | See \`TASKS_HISTORY.md\` §Phase 9 |
| Task 52 — Per-Sprite X/Y Flip Primitive | **DONE** | #9127 | See \`TASKS_HISTORY.md\` |
| #9026 — Zero-Footprint ROM Elimination | **DONE** | #9142 | See \`TASKS_HISTORY.md\` |

Older closed tasks (Phase 1–8, R-Roadmap, sidecar lanes) are catalogued in \`TASKS_HISTORY.md\`.

---

## Closed Side-Lanes

Parallel work completed outside the FPGA critical path.

| Task | Status | Owner | Audit | Closeout Mail | Commit |
|---|---|---|---|---|---|
| Firmware Platform Parity — ESP32 Scenario Coverage | **DONE** | FoggyWolf | CyanPeak PASS #9727 | #9727 | \`e7c8a06\` |

---

## Historical Artifact Index

| Category | Canonical Doc | Archive Location |
|---|---|---|
| Closed task detail | \`TASKS_HISTORY.md\` | \`archive/tasks/\` (per-task artifacts) |
| Platform adapter specs | \`PLATFORM_ADAPTERS.md\` | \`archive/adapters/\` (full specs) |
| Substrate assessments | \`ASSESSMENT.md\` | \`archive/assessments/\` (source files) |
| Gap task list (v1.0) | \`TASKS.md\` §Next Up | \`archive/tasks/MODE0_GAP_TASKLIST_v1.0.md\` |

---

## Agent Rules for This File

- Do not begin a task if any entry in its \`depends_on\` list is not \`DONE\`.
- Do not implement anything described in a task's \`scope_boundary\` as excluded.
- A task is only \`DONE\` when its \`validation\` criteria are met on hardware (or simulation, for tasks not yet at hardware stage).
- When marking a task \`IN-PROGRESS\` or \`DONE\`, update the status field in this file.
- Do not modify \`depends_on\` or \`scope_boundary\` fields without explicit instruction.

### Deduplication Rule
Before opening a new implementation lane, landing code, or sending a proof packet, verify the task ID does not already appear in:
- \`TASKS.md\` Live Lane State, or
- project mail from the last 48 hours.
If the task is already in flight, halt and request a BronzeGate ruling.

### Audit HOLD Iteration Limit
If CyanPeak issues \`HOLD\` on a checkpoint, BrightForge may correct and resubmit once. A second \`HOLD\` on the same checkpoint scope must escalate to BronzeGate for re-scoping or closure. No third HOLD cycle without PM intervention.

---

## Lane-Open Packet Template

Every new implementation lane must open with one authoritative packet. Copy this template into the kick-off mail or doc update.

For full pre-execution planning (primitive boundary, interfaces, data model, timing, risks, exit condition), use \`TASK_TEMPLATE.md\`.

\`\`\`markdown
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
\`\`\`
