# TASKS.md

**Updated:** 2026-05-19 (Mode2optimized Compile-Time Feature Strip DONE — bitstream produced `22afb90`; Task 10026 Barebones Simple Sprite DONE audit PASS #10117; 3b Copper Double-Buffer lane DONE closed at `01f2e91`; libvdp Mode0 helper-surface DONE `9f6b86f`, `29be453`; CoralReef audit checklist landed `4ba550e`; libvdp all-in-one sprite upload helper DONE `c9e6702`; libvdp per-platform palette LUT helpers DONE `45f0d88`; docs cleanup DONE `b10ab71`; ESP8266 QSPI Transport Fix DONE #9876 audit PASS #9875; Host Platform Fidelity opened #9801; Reference Localization DONE #9827 audit PASS #9839; Standards Compression DONE #9828 audit PASS #9839; ZX Spectrum Firmware Host Flow DONE #9797; Atari ST paused #9783; 320-pixel planar clipping mask DONE #9768.)
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

This section tracks the active lane.

| Field | Value |
|-------|-------|
| **Task** | **No active critical-path lane** |
| **Status** | **AWAITING PM AUTHORIZATION** |
| **Latest Commit** | `3f108fd` (CyanPeak docs cleanup) |
| **Latest Auth Mail** | BronzeGate #10295 |
| **Summary** | Feature-strip complete (22afb90, 51% headroom). 3b Copper swap proven. libvdp Mode0 surface, sprite upload, and palette LUTs complete. Docs cleanup complete. |
| **Next Step** | Wait for PM lane-open ruling (Atari ST, mode2optimized follow-up, or idle). |

---

## Next Up / Open Queue

### Atari ST Adapter Lane
- **Status:** **PAUSED** (#9783). Checkpoint A accepted (#9782).
- **Scope:** 320×200 4-plane planar only. No sprites/blitter for v1.

---

## Closed Summary

Recently closed lanes. Full detail in `TASKS_HISTORY.md`.

| Task | Status | Reference |
|---|---|---|
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

Older closed tasks (Phase 1–8, R-Roadmap, sidecar lanes) are catalogued in \`TASKS_HISTORY.md\`.

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
| Closed task detail | \`TASKS_HISTORY.md\` | \`archive/tasks/\` (per-task artifacts) |
| Platform adapter specs | \`PLATFORM_ADAPTERS.md\` | \`archive/adapters/\` (full specs) |
| Substrate assessments | \`ASSESSMENT.md\` | \`archive/assessments/\` (source files) |
| Gap task list (v1.0) | \`TASKS.md\` §Next Up | \`archive/tasks/MODE0_GAP_TASKLIST_v1.0.md\` |

---

## Agent Rules for This File

| Rule | Requirement |
|------|-------------|
| Dependency gate | Do not begin if any \`depends_on\` is not \`DONE\` |
| Scope boundary | Do not implement anything in \`scope_boundary\` as excluded |
| DONE definition | \`DONE\` only when \`validation\` criteria met on hardware (or sim) |
| Status sync | Update status field when marking \`IN-PROGRESS\` or \`DONE\` |
| Scope immutability | Do not modify \`depends_on\` or \`scope_boundary\` without instruction |

### Deduplication Rule
Before opening a new lane, landing code, or sending a proof packet, verify the task ID does not already appear in \`TASKS.md\` Live Lane State or project mail from the last 48 hours. If already in flight, halt and request a BronzeGate ruling.

### Audit HOLD Iteration Limit
If CyanPeak issues \`HOLD\`, BrightForge may correct and resubmit once. A second \`HOLD\` on the same scope must escalate to BronzeGate. No third HOLD cycle without PM intervention.

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
