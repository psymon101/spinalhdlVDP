# TASKS.md

**Updated:** 2026-05-19 (Mode2optimized Compile-Time Feature Strip DONE — bitstream produced `22afb90`; Task 10026 Barebones Simple Sprite DONE audit PASS #10117; 3b Copper Double-Buffer lane DONE closed at `01f2e91`; libvdp Mode0 helper-surface DONE `9f6b86f`, `29be453`; CoralReef audit checklist landed `4ba550e`; ESP8266 QSPI Transport Fix DONE #9876 audit PASS #9875; Host Platform Fidelity opened #9801; Reference Localization DONE #9827 audit PASS #9839; Standards Compression DONE #9828 audit PASS #9839; ZX Spectrum Firmware Host Flow DONE #9797; Atari ST paused #9783; 320-pixel planar clipping mask DONE #9768.)
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
| **Task** | **No active critical-path lane** |
| **Status** | **DONE / AWAITING NEXT PM AUTHORIZATION** |
| **Phase** | Mode2optimized Compile-Time Feature Strip closed: Gate #1 DFF blocker diagnosed and fixed via activeListMem readport-trim (\`40c0384\`). Gates 2-4 stacked with Mem-inference hardening (\`bc0e493\`, \`f0a09e2\`, \`49c3a5f\`, \`22afb90\`). Tang Nano PnR passes with massive headroom: 5874 LUT (28%), 3791 Register (24%), 6888 CLS (67%). Bitstream \`project.fs\` produced on branch \`mode2optimized-gate2-enableL2L3\` @ \`22afb90\`. Task 10026 Barebones Simple Sprite closed audit PASS #10117. |
| **Latest Commit** | \`29be453\` (libvdp standalone bitmap base/stride helpers, TopazCliff) |
| **Commits in lane** | \`5020344\` (Gate #1), \`40c0384\` (readport-trim), \`f0a09e2\` (Gate #2), \`bc0e493\` (planeRows trim), \`49c3a5f\` (LinestateStore BSRAM), \`22afb90\` (slbA/slbB BSRAM), \`d32616d\` (Copper double-buffer RTL), \`ec474c9\` (Copper bounce demo), \`01f2e91\` (Copper doc CP-G), \`6830b55\` (Mode0 standalone helpers), \`b9eb4a9\` (sprite API + asset pipeline), \`9f6b86f\` (pattern-RAM / VSCROLL / HDMA helpers), \`29be453\` (bitmap base/stride helpers), \`4ba550e\` (CoralReef audit checklist) |
| **Latest Auth Mail** | BronzeGate #10295 (ledger-sync ruling, no new lane); BronzeGate #10270 (3b CP-G closeout accepted); BronzeGate #10273 (libvdp helper authorization) |
| **Artifact** | Branch \`mode2optimized-gate2-enableL2L3\` @ \`22afb90\` produces \`project.fs\` with 51% logic headroom. GT-023..GT-028 documented in \`kb/gowin/GOTCHAS.md\`. MemReport tool landed (\`47f0a87\`, \`d32f446\`). 3b bitstream with double-buffer at \`d32616d\` + \`22afb90\` merge. \`kb/libvdp/README.md\` fully covers Mode0 register surface. \`PROJECT_PLAN/CORALREEF_AUDIT_CHECKLIST.md\` landed. |
| **Next Deliverable** | BronzeGate chooses one of: reopen Atari ST, authorize optional mode2optimized Gate #3/#4 follow-up, or keep engineering idle. |

**Context:** The Mode2optimized feature-strip lane is complete. BrightForge #10142 produced a working Tang Nano bitstream with full resource headroom. The barebones simple-sprite lane (Task 10026) is closed audit PASS. The 3b Copper double-buffer lane is closed at CP-G (\`01f2e91\`) per BronzeGate #10270. libvdp Mode0 helper-surface is complete (\`6830b55\`, \`9f6b86f\`, \`29be453\`) per BronzeGate #10273. CoralReef audit checklist (\`4ba550e\`) is landed. No active critical-path engineering lanes remain. Awaiting PM authorization for next lane.

---

## Next Up / Open Queue

The following tasks are **OPEN** and await PM authorization to become active lanes.

### Mode2optimized Compile-Time Feature Strip

| Field | Value |
|---|---|
| **Status** | **DONE** — bitstream produced 2026-05-17 per BrightForge #10142. Gate #1 blocked then fixed via readport-trim (\`40c0384\`). Gates 2-4 stacked with hardening; PnR passes on Tang Nano with 51% logic headroom. |
| **Gap** | `mode2optimized` rich top now fits GW2AR-LV18 with `project.fs` produced. Optional future work: re-enable Gate #3 (affine) and Gate #4 (planeCount=5) per-scenario. |
| **Platforms helped** | Tang Nano 20K rich-top default build; all downstream adapters that depend on the full product branch |
| **Impact** | **High** — directly unblocked the main implementation lane |
| **Risk/Complexity** | Closed. Core compile-time gating + Mem-inference hardening proven. Remaining risk is optional per-scenario feature expansion. |
| **Proof shape** | Per-gate Spinal compile + resource delta ✅; synth budgets PASS ✅; PnR placement produces \`project.fs\` ✅ (22afb90); regression build with all gates ON still elaborates/places 🔄 |
| **Source assessment** | CoralReef #10070; BrightForge #10076, #10125, #10127, #10128, #10130, #10134, #10137, #10139, #10142; BronzeGate #10126, #10135, #10138, #10140, #10141; `PROJECT_PLAN/MODE0_T20_STRIP_ANALYSIS_CORALREEF.md`; `MODE0_PLANNING.md` |
| **Depends on** | Barebones Stage 2-4 landing DONE audit PASS #10063 |
| **Scope Boundary** | Compile-time gates for L2/L3, extra raster triggers, second window, affine, and parameterized `planeCount`. Plus Mem-inference hardening (readSync conversions, BSRAM pinning) to satisfy GT-023. No runtime register-map expansion. No adapter rewrites. No hardware proof in this lane. |
| **Checkpoints** | A: lane-open implementation plan / gate order ✅; B: gated default-build compile + synth deltas ✅; C: final default-build PnR PASS under logic limit ✅ (22afb90); D: regression build with all gates ON elaborates/places 🔄; E: audit + ledger sync ✅ |
| **Coding authorized** | YES — BronzeGate 2026-05-16 following CoralReef #10070; paired experiment #10135; broader autonomy #10141 |

### Mode0-T20 Barebones Rebuild (Stages 2-4 landing)

| Field | Value |
|---|---|
| **Status** | **DONE** — CyanPeak audit PASS #10063 on commit \`1e316a4\` |
| **Gap** | Minimal QSPI-controlled Tang barebones substrate needed proof-sized landing and closeout. |
| **Platforms helped** | Tang Nano 20K barebones substrate; ESP8266 and ESP32 host proof path |
| **Impact** | **High** — established a minimal proven hardware control/rendering substrate separate from the rich-top fit pressure |
| **Risk/Complexity** | Closed |
| **Proof shape** | FPGA: PnR success for stage-4 barebones top; Host: independent L0/L1 motion on hardware; Sim: `QspiBarebonesSim` PASS and `TopTang20kBarebonesSim` PASS |
| **Source assessment** | BrightForge #10037, #10044, #10052, #10059; FoggyWolf #10048, #10050, #10054; CyanPeak #10063 |
| **Depends on** | Task 57 DONE; Host Platform Fidelity DONE (#9891) |
| **Scope Boundary** | Landed at \`1e316a4\`; closeout ledger synced at \`1f87820\` |
| **Checkpoints** | A: minimal QSPI + 2 scroll regs ✅ #10037; B: host-driven L0 motion ✅ #10048 / #10050; C: L1 + dual-layer independent host motion ✅ #10052 / #10054; D: focused landing commit ✅ \`1e316a4\`; E: audit PASS ✅ #10063 |
| **Coding authorized** | YES — closed |

### Host Platform Fidelity Requirements (ESP8266 / ESP32 / Pico 2)

| Field | Value |
|---|---|
| **Status** | **DONE** — CyanPeak audit PASS #9891 |
| **Gap** | No canonical documentation of host-platform constraints affecting visual fidelity and proof trustworthiness. |
| **Platforms helped** | All (firmware/host-transport) |
| **Impact** | **High** — establishes which host platform to trust for visual proof and why |
| **Risk/Complexity** | Low. Documentation-only lane; no RTL or firmware feature implementation. |
| **Proof shape** | Doc packet: per-platform fidelity notes, preferred authoritative host, acceptable functional hosts, transport/debug expectations, stale artifact disposition |
| **Source assessment** | `firmware/README.md`, `firmware/GOTCHAS.md`, `kb/<Adapter>/README.md` |
| **Depends on** | ZX Spectrum Firmware Host Flow DONE (#9797) |
| **Scope Boundary** | ESP8266, ESP32, Pico 2 host-side constraints only. No new RTL. No new firmware features. No reopening closed ZX work. |
| **Checkpoints** | A: planning/proof packet ✅; B: documentation updates ✅ (`8afc432`); C: CyanPeak audit ruling ✅ PASS #9891 |
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
| 3b Copper Double-Buffer Live-Update — atomic bank-swap at vSyncStart, `COPPER_SWAP_REQUEST` bit, back-compat preserved | **DONE** | BronzeGate #10270 (CP-G closeout accepted) | Commits `d32616d` (RTL+sim), `ec474c9` (firmware helper + demo), `01f2e91` (doc CP-G), `94f401f` (A1/A4 helpers), `b68e102` (README sync); bitstream produced at `d32616d` + `22afb90` merge |
| Mode2optimized Compile-Time Feature Strip — compile-time gates + Mem-inference hardening for GW2AR-LV18 fit recovery | **DONE** | BrightForge #10142 / CoralReef audit closeout | Branch `mode2optimized-gate2-enableL2L3` @ `22afb90`; commits `5020344`, `40c0384`, `f0a09e2`, `bc0e493`, `49c3a5f`, `22afb90`; `project.fs` produced |
| Task 10026 — Barebones Simple Sprite over Background (sprite > L1 > L0) | **DONE** | #10108 / audit PASS | Commit `eda89d7`, `6119360`, `40f1424` |
| Host Platform Fidelity Requirements — authoritative vs functional host, QSPI_ERROR trust, artifact stewardship | **DONE** | #9883 / audit PASS #9891 | Commits `8afc432`, `4814dc2` |
| ESP8266 QSPI Transport Fix — pinMode restore + HALF_PERIOD_US | **DONE** | #9876 / audit PASS #9875 | Commit \`878e862\` |
| Reference Localization — platform technical references | **DONE** | #9827 / audit PASS #9839 | Commit \`304bac0\` |
| Standards Compression — facts-first doc templates | **DONE** | #9828 / audit PASS #9839 | Commits \`cc099a8\`, \`805d5eb\` |
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
| libvdp Mode0 Helper-Surface Completion — pattern-RAM, VSCROLL, HDMA, bitmap base/stride, standalone control helpers | **DONE** | TopazCliff | CoralReef verified | BronzeGate #10273 | `6830b55`, `9f6b86f`, `29be453` |
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
