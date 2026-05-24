# TASKS.md

**Updated:** 2026-05-24 (RTL Platform-Agnosticism Purge active #10567; ESP32-S3 host bring-up #10539 and scanline-start transient fix #10550 closed; baseline locked at `9e3c252`.)
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

This section tracks the active lane.

| Field | Value |
|-------|-------|
| **Task** | **RTL Platform-Agnosticism Purge** |
| **Status** | **DONE** |
| **Latest Commit** | `26ae196` (BACKDROP_INDEX + boot defaults + purge) |
| **Latest Auth Mail** | BrightForge #10589 (BACKDROP_INDEX closed), CyanPeak #10587 (Audit PASS) |
| **Summary** | Strip per-scenario / per-platform RTL. 10 files deleted. TopTang20kHdmi.scala scrubbed (-848 lines). Boot defaults fixed (layerEnableReg + LinestateStore all-off). BACKDROP_INDEX @ 0x0348 added for deterministic backdrop. Gowin PnR PASS (Setup 0, Hold 0). Bench: solid black on Tang. CyanPeak audit PASS. |
| **Next Step** | Merge `brightforge/rtl-agnosticism-purge` → `main`. |

---

## Next Up / Open Queue

### Deterministic Backdrop (BACKDROP_INDEX Register)
- **Status:** **TODO** (#10584 follow-up).
- **Scope:** Add a new host-writable register to set the backdrop color directly, bypassing SDRAM-bank transients.
- **Next Step:** Open lane after #10567 closeout.

### RGB565 Hardware Bench Framing Investigation
- **Status:** **DEFERRED** (#10503 follow-up). 
- **Scope:** Superseded by ESP32-S3 host bring-up and 1-pixel fix.

### Atari ST Adapter Lane
- **Status:** **PAUSED** (#9783).
- **Scope:** Relocating to libvdp.

---

## Closed Summary

Recently closed lanes. Full detail in `TASKS_HISTORY.md`.

| Task | Status | Reference |
|---|---|---|
| Scanline-start 1-pixel Transient Fix | **DONE** | #10550 |
| ESP32-S3 Host Bring-up | **DONE** | #10539 |
| CLS Optimization — gate2 readAsync→readSync | **DONE** | #10497 |
| R5.4 Copper Integration (gate2 descCount=32) | **DONE** | #10398 |
| 3b Copper Double-Buffer | **DONE** | #10270 |
| Mode2optimized Feature Strip | **DONE** | #10142 |
| Task 10026 — Simple Sprite | **DONE** | #10117 |
| Host Platform Fidelity | **DONE** | #9891 |
| QSPI Transport Fix | **DONE** | #9875 |
| Reference Localization | **DONE** | #9839 |
| ZX Spectrum Host Flow | **DONE** | #9797 |
| 320-pixel planar clipping | **DONE** | #9768 |
| Task 56 — SDRAM Multi-Layer | **DONE** | #9709 |
| Task 54 — Sprite-Sprite Collision | **DONE** | #9672 |
| Task 57 — DFF Optimization | **DONE** | #9605 |
| RGB565 Directcolor (CP-1a..1c + BitmapCtrlCommitSim) | **DONE** | #10503 |

Older closed tasks (Phase 1–8, R-Roadmap, sidecar lanes) are catalogued in `TASKS_HISTORY.md`.

---

## Closed Side-Lanes

Parallel work completed outside the FPGA critical path.

| Task | Status | Owner | Audit | Closeout Mail | Commit |
|---|---|---|---|---|---|
| libvdp Mode0 Helper-Surface Completion — pattern-RAM, VSCROLL, HDMA, bitmap base/stride, standalone control helpers | **DONE** | TopazCliff | CoralReef verified | BronzeGate #10273 | `6830b55`, `9f6b86f`, `29be453` |
| libvdp All-in-One Sprite Upload Helper | **DONE** | TopazCliff | CoralReef verified | BronzeGate #10296 | `c9e6702` |
| libvdp Per-Platform Palette LUT Helpers — TMS9918, SMS/GG, Atari ST/STE | **DONE** | TopazCliff | CoralReef verified | BronzeGate #10305/#10306 | `45f0d88` |
| Docs Cleanup — concision, consistency, visual-fidelity policy sync | **DONE** | CyanPeak | CoralReef verified | BronzeGate #10303 | `b10ab71`, `1b7449c`, `3f108fd` |
| Firmware Platform Parity — ESP32 Scenario Coverage | **DONE** | FoggyWolf | CyanPeak PASS #9727 | #9727 | \`e7c8a06\` |

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

| Rule | Requirement |
|------|-------------|
| Dependency gate | Do not begin if any `depends_on` is not `DONE` |
| Scope boundary | Do not implement anything in `scope_boundary` as excluded |
| DONE definition | `DONE` only when `validation` criteria met on hardware (or sim) |
| Status sync | Update status field when marking `IN-PROGRESS` or `DONE` |
| Scope immutability | Do not modify `depends_on` or `scope_boundary` without instruction |

### Deduplication Rule
Before opening a new lane, landing code, or sending a proof packet, verify the task ID does not already appear in `TASKS.md` Live Lane State or project mail from the last 48 hours. If already in flight, halt and request a BronzeGate ruling.

### Audit HOLD Iteration Limit
If CyanPeak issues `HOLD`, BrightForge may correct and resubmit once. A second `HOLD` on the same scope must escalate to BronzeGate. No third HOLD cycle without PM intervention.

---

## Lane-Closeout Team Check (Mandatory)

At every large-task closeout, `TopazCliff` must send a PM team check to all active agents before opening the next large lane.

Purpose: surface blockers, process friction, and capacity constraints while they are fresh, rather than letting them accumulate across lanes.

Required reply content from each agent:
1. **Blockers** — tooling, docs, coordination, handoffs, stale dependencies, unclear scope
2. **Process friction** — mail patterns, lane handoffs, proof requirements, review cycles
3. **Suggestions** — start/stop/change at large-task boundaries
4. **Status** — current lane/focus and capacity for next PM-assigned task

No new large lane may be opened until the team check has been sent and replies have been reviewed. Small side lanes (doc fixes, sketch tweaks, sim-only cleanup) are exempt.

---

## Lane-Open Packet Template

Every new implementation lane must open with one authoritative packet. Copy this template into the kick-off mail or doc update.

For full pre-execution planning (primitive boundary, interfaces, data model, timing, risks, exit condition), use `TASK_TEMPLATE.md`.

```markdown
## Lane Open: [Task Name]

### Prior Art Search
- [ ] Searched `TASKS_HISTORY.md` for same symptom / module / signal
- [ ] Searched `PROJECT_PLAN/archive/artifacts/` for related closed investigations
- [ ] Searched `firmware/GOTCHAS.md` for known pitfalls matching the symptom
- [ ] Searched `memory` MCP for reusable findings
- [ ] Prior art found (reference): ... / No prior art found

### Scope Boundary
- in scope: ...
- in scope: ...
- out of scope: ...
- out of scope: ...

### Required Proof
- sim: ...
- hardware: ...
- synthesis/PnR: ... (mandatory per `CONVENTIONS.md` §Synthesis and PnR Proof Rule if ≥500 lines or top-level connectivity changed)

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
