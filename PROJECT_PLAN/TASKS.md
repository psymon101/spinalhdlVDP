# TASKS.md

**Updated:** 2026-05-29 (P3 closed, P4 queued. BrightForge replied to team check #10853. Three active sub-lanes/action items in flight.)
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
| **Task** | *(none — last closed: Priority 3 Planar Hardening)* |
| **Status** | **NO ACTIVE LANE** |
| **Task ID** | — |
| **Owner** | BrightForge |
| **Baseline Commit** | `1efa9c1` (main, post-P3 merge) |
| **Latest Auth Mail** | TopazCliff #10815 (lane-closeout team check sent) |
| **Summary** | Defensive checks on planar read path. 6 risks identified, runtime asserts landed, sim discriminators in flight. |
| **Checkpoints** | A: DONE (#10790). B(1): DONE (`4ff92d5`). B(2): DONE (`50fcced`, `aa4b1d0`, `89ec7e9`). B(3): DONE (`4123604`). C: DONE (CyanPeak doc audit PASS #10814, fixes merged `60a6f03`). |
| **Next Step** | Await team check replies (#10815) → review → open P4 (Reset-pin) lane. BrightForge standing by for PM priority assignment between active sub-lanes. |

**Security note:** Messages #10794 and #10795 were sent via the `overseer/send` HTTP endpoint, which stamps `from: HumanOverseer` and injects a `HUMAN OVERSEER` header. BrightForge correctly flagged these as non-canonical (#10796). CyanPeak correctly retracted acknowledgement (#10797). Corrected authorizations sent as #10799 (BrightForge) and #10800 (CyanPeak) via proper agent `send_message` MCP tool. **Rule:** Agent-to-agent mail must use `send_message` MCP tool only. The `overseer/send` endpoint is for human operator injection only and must not be used for PM authorizations.

---

## Next Up / Open Queue

### Priority 1 — libvdp Scaler Register Exposure
- **Status:** **DONE**
- **Owner:** BronzeGate (firmware), BrightForge (hardware sanity test)
- **Scope:** Wire `SCALE_CTRL`, `LOGIC_WIDTH`, `LOGIC_HEIGHT` into libvdp register map + host API. `BORDER_CTRL` already exposed.
- **Depends on:** #10590 (scaler RTL proven)
- **Validation:** Host can successfully change scaler mode from firmware; hardware proof shows bezel response to register writes.
- **Checkpoints:**
  - **A:** DONE (`c1f87fd`) — register constants + packer/writer helpers + compile check
  - **B:** DONE (`d900182`) — ESP32/ESP32-S3/ESP8266 runtime bezel sketch, 3500ms mode cycles
  - **C:** DONE (#10762) — BrightForge hardware sanity test: runtime-write path bezel verification + canary stability gate. Runtime-write parity proven byte-identical to POR-init.
- **Closeout:** DONE — scenario cleanup merged (`fe8d6f3`), CyanPeak doc audit PASS (#10770), BronzeGate housekeeping landed (`ec838da`).

### Priority 2 — readAsync Mems Audit
- **Status:** **DONE**
- **Owner:** BrightForge
- **Scope:** Audit remaining `readAsync` memory usages across the design for safety / timing closure.
- **Depends on:** None
- **Validation:** All `readAsync` instances either justified with comment or converted to `readSync`.
- **Checkpoints:**
  - **A:** DONE (#10772) — 21 instances across 13 files, classified into 4 risk classes
  - **A-revised:** DONE (#10778) — CP-A reclassified. All targets have same-cycle FSM semantics.
  - **B(1):** DONE (`72adf70` → `dbb4c08`) — Uniform audit comments on all 21 instances. Merged to main.
  - **B(2):** DONE (`d0b645e` → `17b1c2c`) — Demonstration conversion: `SdramTileFetch tileMapRom` readAsync→readSync with lookahead-address FSM. Sim byte-identical to baseline. Pattern documented as GOTCHA-14.
  - **C:** DONE — Synthesis clean, 0 violations. Converted Mem uses `ram_style="block"`.
- **Closeout:** DONE — CyanPeak doc audit PASS (#10786).

### Priority 3 — Planar Hardening Task 3
- **Status:** **DONE**
- **Owner:** BrightForge
- **Scope:** Defensive checks on planar read path.
- **Depends on:** None
- **Validation:** Simulation proof of planar path under stress conditions.
- **Checkpoints:**
  - **A:** DONE (#10790) — 8 entry points, boundary surfaces mapped, 6 risks classified, zero runtime asserts found
  - **B(1):** DONE (`4ff92d5`) — Runtime `assert(...)` at all 6 risk sites. 80 lines, 2 files, zero new diagnostics. All planar regression sims PASS.
  - **B(2):** DONE (`50fcced`, `aa4b1d0`, `89ec7e9`) — 3 targeted sim discriminators. Boundary + Refresh PASS (asserts silent). WriteBufRace ASSERT TRIPS ~30× under pathological stress → CP-B(3) GO.
  - **B(3):** DONE (`4123604`) — Option α latch-and-flip implemented, scheduler gap analysis (858 cycles min, ~30 cycles emit-clear, 28.6× safety ratio), full regression PASS. Merged to main @ `1efa9c1`.
  - **C:** DONE — CyanPeak doc audit PASS #10814, fixes merged `60a6f03`.
- **Closeout:** DONE — lane-closeout team check sent #10815, awaiting replies before P4.

### Priority 4 — Soft Reset via VDP_CTRL[2]
- **Status:** **IN-PROGRESS**
- **Owner:** BrightForge (RTL), BronzeGate (libvdp wrapper)
- **Scope:** Host-triggered soft reset using spare bit 2 of `VDP_CTRL @ 0x0310`. Writing `1` to bit 2 triggers the same global reset/clear engine as a physical POR: all registers return to their `init` values, all BSRAM memories are zeroed, and an SDRAM zero-fill engine clears SDRAM. The request bit auto-clears when reset completes. No physical pin or wiring change.
- **Depends on:** None
- **Validation:**
  - Simulation: after writing `VDP_CTRL = 0x0004`, observable registers and memories read back as reset values.
  - Hardware: host can call `vdp_mode0_soft_reset()` and verify the VDP returns to a known baseline state without power-cycling.
- **Checkpoints:**
  - A: RTL global reset + BSRAM clear engine + SDRAM zero-fill engine.
  - B: Simulation proof.
  - C: Hardware proof + libvdp `vdp_mode0_soft_reset()` wrapper.
  - D: Doc update (`VDP_CTRL` detail table, `VDP_PROGRAMMING_GUIDE.md` usage note) and CyanPeak audit.

### Priority 5 — Fixed/Hardcoded Asset Audit
- **Status:** **QUEUED**
- **Owner:** CyanPeak
- **Scope:** Audit all SpinalHDL RTL (`hw/spinal/spinalhdlvdp/**/*.scala`) for fixed values, hardcoded constants, and ROM-like behavior that should be host-programmable. Classify each finding as acceptable (transient internal state / legitimate POR default / demo-only) or a violation (user-facing fixed asset). Produce a report with file/line references and remediation recommendations.
- **Depends on:** None
- **Validation:** Deliverable is `PROJECT_PLAN/VDP_FIXED_ASSET_AUDIT_136.md` with:
  - Complete list of `.init` / `initialContent` memories and their host-write status.
  - List of hardcoded asset objects (`AffineAssets`, `BasicPatternSource`, `TileAttributeAssets`, `PlanarTileAssets`, etc.) and whether each should be host-loadable.
  - Recommendations for which fixed assets to convert, remove, or document as demo-only.
- **Checkpoints:**
  - A: Inventory scan complete.
  - B: Classification and remediation plan.
  - C: CoralReef doc updates for accepted findings.

### Sub-lane: 2bpp Planar FPGA Hardware Proof
- **Status:** **IN-PROGRESS**
- **Owner:** BrightForge
- **Opened by:** TopazCliff #10851 (ACK sent, plan replied #10853-sub)
- **Scope:** Generate 2bpp planar test asset, flash to Tang Nano 20K, capture visual proof.
- **Validation:** Visual output matches source pattern; any mirroring/color-swap/stride-corruption = FAIL.

### Action: Tile-Row Stride Verification (non-4bpp)
- **Status:** **IN-PROGRESS**
- **Owner:** BrightForge
- **Opened by:** TopazCliff #10826 (ACK sent, analysis replied #10853-sub)
- **Scope:** Confirm hardware fetch behavior for 1bpp and 2bpp tiles with fixed 8-byte row stride.
- **Validation:** RTL analysis complete; hardware proof pending PM decision on priority.

### Integer Pixel-Repetition Scaler + Auto-Center Borders
- **Status:** **DONE** (#10590).
- **Scope:** Add integer pixel-repetition scaling to VdpTop output path + libvdp resolution APIs.
- **Closeout:** Merged to main @ `d69d404`. Hardware proof v3 validated by BrightForge #10731, merged by TopazCliff #10732.

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
| P23 Timing-Margin Recovery — burst-refresh re-enabled + place/route effort=2 (clk_pixel +5.709 ns, TNS 0) | **DONE** | #12107/#12114/#12115 |
| P22 i80 Block-Write + SDRAM Upload — byte-exact HW proof through libvdp | **DONE** | #12072/#12084 |
| P21 i80 Parallel Host Interface — full HW proof (continuity + transport + visible BORDER_CTRL) | **DONE** | #12010/#12071 |
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
| RTL Platform-Agnosticism Purge | **DONE** | #10567 |
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

### Doc-Audit Rule (PM-activated)
CyanPeak is activated for documentation review after each priority completion. See `AGENTS/CyanPeak.md` §Doc-Audit Protocol. CyanPeak must review and sign off on docs before the next priority opens.

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

**Override:** Project owner may direct immediate lane opening via direct instruction. TopazCliff records the override and proceeds.

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
