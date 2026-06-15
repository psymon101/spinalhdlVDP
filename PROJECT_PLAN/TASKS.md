# TASKS.md

**Updated:** 2026-06-11 (BSRAM at ~92%; WHOLE-VDP-134 closed at scenario #5; VDP-SOFT-RESET-135 is live lane.)
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
| **Task** | Host-triggered soft reset via `VDP_CTRL[2]` |
| **Status** | **IN-PROGRESS** |
| **Task ID** | VDP-SOFT-RESET-135 |
| **Owner** | BrightForge (RTL + SDRAM fill engine) / BronzeGate (libvdp wrapper) / CyanPeak (ClockDomain partitioning review + code-to-spec) / CoralReef (docs) |
| **Baseline Commit** | `d42bac8` (main after copper-docs merge and affine-texture backlog task) |
| **Latest Auth Mail** | TopazCliff #12539 (SDRAM zero-fill rulings: 1000 ms timeout, interleaved refresh, Option C controller-direct fill) |
| **Summary** | Add a host-triggered soft reset that returns the VDP to a POR-equivalent state without a power cycle. Stage 1 (handshake + i80 `0x0310` live readback) and Stage 2 (on-chip host-writable Mem zero-sweep) are sim-proven. Stage 3 zeros only the **occupied/configured SDRAM regions** (auto-derived from active-layer geometry registers) via a controller-direct fill FSM in `sdramClockDomain`; the FSM interleaves auto-refresh (~1 per 15 µs) so the clear is size-independent and stays within the 64 ms SDRAM retention window. Stage 4 will partition the core ClockDomain so registers reset while the controller + host interface survive. `affineTexture` and immutable tile ROMs remain excluded from reset scope. |
| **Checkpoints** | A: `VDP_CTRL[2]` request/busy handshake + i80 live readback — DONE (`095a507`). B: On-chip Mem clear sweep — DONE (`a2043fc`). C: SDRAM occupied-region zero-fill engine — **IN PROGRESS** (design approved with interleaved refresh; implement + SDRAM-model cosim). D: Core register ClockDomain partition — **PENDING** (CyanPeak review before build). E: libvdp `vdp_mode0_soft_reset()` wrapper + docs — DONE (`6acb359`). |
| **Next Step** | BrightForge implements Stage 3 SDRAM fill with interleaved refresh and proves it in cosim; CyanPeak stands by for Stage 4 partitioning review. |

**Security note:** Messages #10794 and #10795 were sent via the `overseer/send` HTTP endpoint, which stamps `from: HumanOverseer` and injects a `HUMAN OVERSEER` header. BrightForge correctly flagged these as non-canonical (#10796). CyanPeak correctly retracted acknowledgement (#10797). Corrected authorizations sent as #10799 (BrightForge) and #10800 (CyanPeak) via proper agent `send_message` MCP tool. **Rule:** Agent-to-agent mail must use `send_message` MCP tool only. The `overseer/send` endpoint is for human operator injection only and must not be used for PM authorizations.

---

## Next Up / Open Queue

### Priority 0 — RGB565 Full-Frame Docs and Canonical Example
- **Status:** **DONE**
- **Owner:** CoralReef (docs) / BronzeGate (example)
- **Scope:** Finish `VDP_PROGRAMMING_GUIDE.md` RGB565 section, sweep docs/examples for stale data, CyanPeak doc audit.
- **Depends on:** RGB565-FULLFRAME-132 DONE
- **Validation:** Docs consistent with `mode0_regs.json`; canonical example compiles; CyanPeak audit PASS (#12437); branch merged to `main` @ `c98ec03`.

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

### Priority 4 — Reset-pin Lane
- **Status:** **QUEUED**
- **Owner:** BrightForge
- **Scope:** Physical reset button / pin for Tang Nano 20K.
- **Depends on:** None
- **Validation:** Hardware proof: reset pin returns system to known state without power cycle.

### Priority 5 — BSRAM Reclamation (BSRAM-RECLAIM-137)
- **Status:** **QUEUED**
- **Owner:** BrightForge (RTL) / CyanPeak (review) / CoralReef (docs if semantics change)
- **Scope:** Phase 1 only: reduce `BitmapRowFetch` `NBanks` 3→2 and `byteFifo` depth 256→32; prove with `BitmapArbiterIntegrationSim` refresh ON at 40.5 MHz. **Phase 2 sprite descriptor/FIFO reductions are paused by owner directive.** Phase 3 structural consolidation remains parked pending soft-reset closeout.
- **Depends on:** VDP-SOFT-RESET-135 Stage 3 sim PASS
- **Validation:** Sim zero mismatches; STA clean; no visible regression in existing demos.

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

### Priority 6 — Host-Loadable Affine Texture
- **Status:** **QUEUED**
- **Owner:** BrightForge (RTL), BronzeGate (libvdp + test), CoralReef (docs), CyanPeak (audit)
- **Scope:** Remove the fixed `affineTexture` ROM in `VdpTop` and replace it with a host-loadable memory path. The affine/Mode7 background texture must be uploadable by the user at runtime, not hardcoded in RTL. Reset implication: once host-loadable, `VDP_SOFT_RESET_REQUEST` must also clear this texture (the existing 16384-cycle sweep already fits it).
- **Depends on:** VDP-SOFT-RESET-135 Stage 4 complete (so the reset sweep contract is stable).
- **Validation:**
  - Simulation: host can upload a new texture and the affine layer displays it.
  - Hardware: uploaded texture visible on Tang Nano 20K output.
- **Checkpoints:**
  - A: RTL change — add write port to affine texture memory, host register interface, and reset-sweep inclusion.
  - B: Simulation proof.
  - C: libvdp helper and hardware proof.
  - D: Docs + audit.

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
| WHOLE-VDP-134 — whole-system i80/ESP32-S3 regression baseline before BSRAM optimization (scenarios #1–#5) | **DONE** | scenario #5 PASS on raw-i80 + libvdp-helper paths; copper docs merged `36adcbc`; closeout #12543 |
| RGB565-FULLFRAME-132 — full-frame RGB565 direct-color burst-read controller, sim + STA + HW proof | **DONE** | merge `c8129bd`, formal fix `d668e01`, closeout #12378 |
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
