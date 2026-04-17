# TASKS.md

**Updated:** 2026-04-16
**Purpose:** Authoritative task list for the current `spinalhdlVDP` repository state. Agents must read the `depends_on` and `scope_boundary` fields before beginning any task.

Status values: `TODO`, `IN-PROGRESS`, `DEFERRED`, `DONE`

---

## How to Use This File

Each task entry includes:

- **depends_on** — tasks that must already be `DONE`
- **scope_boundary** — work that is explicitly out of scope
- **delivers** — concrete outputs required from the task
- **validation** — the evidence required before the task may be marked `DONE`

If a dependency is not `DONE`, do not start the task.

---

## Live Lane State

This section tracks the single active lane so the team does not infer state from scattered mail threads.

| Field | Value |
|-------|-------|
| **Task** | Task 23 — Stress-Scene Validation |
| **Status** | IN_REVIEW |
| **Phase** | artifact — awaiting audit |
| **Owner** | BrightForge (coding), CyanPeak (audit) |
| **Latest Commit** | *pending* |
| **Latest Auth Mail** | #7433 (BronzeGate: open Task 23) |
| **Next Deliverable** | CyanPeak audit of Task 23 / Sc17 artifact |
| **Coding Authorized** | **NO** — await audit GO before HDL or build changes |

Rules:
- Only **one** lane may be live at a time.
- When the lane changes, update this block in the **same commit** as the artifact/state change.
- Phase values: `artifact`, `audit`, `implement`, `capture`, `closeout`.

---

## Current Baseline

The repository has closed the Mode0 substrate backlog through baseline **`32a87ff`**:

- **R1** — Raster Trigger Unit (`df7af63`)
- **R2** — Two-Pass Sprite Evaluator
- **R3** — Static Fetch-Slot Scheduler
- **R4** — Tile + Attribute Fetch Primitive (`df7af63`)
- **R4.1** — Multi-Slot Scheduler Coupling (`9dfeb9f`)
- **R4.1b** — Planar Fetch Path
- **R4.1c** — Packed-Attribute Decode (`0e4d9dc`)
- **R5** — Host Interface + Copper Coprocessor
- **R5.3** — Copper Control Unification (`32a87ff`)
- **R5.4** — Scroll-Wrap Component Primitive (`d580dcb`)

That means Tasks 1 through 5, Task 15, Task 16, and Task 18 are already complete in this repo state.

---

## Phase 1 — Output Bring-Up

### Task 1 — Tang20K Project Skeleton

**Status:** DONE
**depends_on:** none
**scope_boundary:** No claim beyond a buildable repository skeleton and board flow.
**delivers:**

- `build.sbt` and SBT project configuration
- initial SpinalHDL package under `hw/spinal/spinalhdlvdp/`
- Tang Nano 20K build flow under `fpga/tang20k/`
- Gowin project Tcl and constraints that accept generated Verilog

**validation:** SBT generation succeeds and Gowin accepts the generated top-level.

### Task 2 — Clocking and Reset Bring-Up

**Status:** DONE
**depends_on:** [1]
**scope_boundary:** No feature work beyond stable board clocks, reset, and basic board visibility.
**delivers:**

- PLL instantiation for board clocking
- pixel clock divider path
- reset behavior tied to PLL lock
- LED observability in the top-level wrapper

**validation:** Hardware shows stable board activity and the synthesized design is free of clock/reset build errors.

### Task 3 — Video Timing Generator

**Status:** DONE
**depends_on:** [2]
**scope_boundary:** No memory-backed rendering. Timing generation only.
**delivers:**

- visible-raster timing in `VdpTop.scala`
- active video, sync, and coordinate outputs
- simulation coverage in `VdpTopSim.scala`

**validation:** Simulation confirms timing-driven coordinates and active pixel behavior for the current 640x480 pattern generator.

### Task 4 — TMDS / Video Output Path

**Status:** DONE
**depends_on:** [2, 3]
**scope_boundary:** No external-memory rendering. Output transport only.
**delivers:**

- TMDS transport wrapper
- vendor serializer / LVDS output path
- HDMI output driven from the SpinalHDL pixel source

**validation:** HDMI capture locks to the signal and remains stable without sync loss.

### Task 5 — Solid Color / Test Pattern Output

**Status:** DONE
**depends_on:** [3, 4]
**scope_boundary:** No tile/sprite/palette/memory-backed rendering.
**delivers:**

- direct coordinate-driven visible pattern
- stable hardware output suitable as a bring-up baseline
- simulation and hardware evidence for the same pattern

**validation:** Current hardware output shows the intended red / green / blue / yellow quadrant pattern with white grid, border, and cross, and remains stable under direct capture.

---

## Phase 2 — Core Mode0 Pixel Control

### Task 6 — Basic Pattern Source

**Status:** DONE
**depends_on:** [5]
**scope_boundary:** No SDRAM. No scrolling. No sprites. Single deterministic on-chip pattern source only.
**delivers:**

- on-chip pattern or tile source stored in local memory
- pixel fetch path that no longer depends purely on combinational quadrant logic
- visible deterministic pattern on hardware

**validation:** Hardware output is stable and matches simulation for the same pattern source.

### Task 7 — Scroll Path

**Status:** DONE
**depends_on:** [6]
**scope_boundary:** Single layer only. No seam validation beyond local movement. No sprites. No multi-layer composition.
**delivers:**

- X and Y scroll offsets
- address generation applying scroll offsets to the pattern source
- visible motion under scroll updates

**validation:** Motion is stable, deterministic, and free of obvious addressing artifacts.

### Task 8 — Wraparound / Seam Correctness

**Status:** DONE
**depends_on:** [7]
**scope_boundary:** Single layer only. Boundary-correctness work only.
**delivers:**

- correct wraparound behavior at tilemap or pattern boundaries
- no visible seam at the wrap point

**validation:** Boundary crossing shows no visible discontinuity on hardware.

### Task 9 — Line Buffer Implementation

**Status:** DONE
**depends_on:** [5]
**scope_boundary:** No sprites. No palette lookup yet. No feature expansion beyond replacing the direct pixel path with a buffered path.
**delivers:**

- line buffer storage
- fill/drain handoff logic
- output path preserved through buffered readout

**validation:** Buffered output is visually equivalent to the current direct test-pattern baseline.

### Task 10 — Palette Path

**Status:** DONE
**depends_on:** [9]
**scope_boundary:** Single palette only. No palette animation. No sprite-specific palette rules.
**delivers:**

- palette memory
- indexed pixel to RGB lookup
- test palette contents with visible proof on hardware

**validation:** Hardware colors match the programmed palette entries with no new corruption.

---

## Phase 3 — Core Mode0 Object Rendering

### Task 11 — Sprite Pipeline

**Status:** DONE
**depends_on:** [9, 10]
**scope_boundary:** No SDRAM. Single-sprite proof only. No affine. No scaling.
**delivers:**

- sprite attribute storage
- per-line active-sprite evaluation
- sprite pixel fetch and injection into the current render path

**validation:** A single sprite renders correctly over a static background and moves correctly.

### Task 12 — Sprite Priority / Transparency

**Status:** DONE
**depends_on:** [11, 13]
**scope_boundary:** Priority and transparency only. No affine. No scaling.
**delivers:**

- transparent pixel handling
- sprite/background priority resolution
- multi-sprite ordering behavior

**validation:** Transparent regions and overlap ordering behave correctly on hardware.

---

## Phase 4 — Core Mode0 Composition

### Task 13 — Multi-Layer Composition

**Status:** DONE
**depends_on:** [9, 10]
**scope_boundary:** Background layers only. No sprites in this task. No linestate. No SDRAM.
**delivers:**

- multi-input compositor for background layers
- deterministic priority handling
- combined output path compatible with the current timing/output baseline

**validation:** Multiple visible layers compose correctly with stable hardware output.

---

## Phase 5 — Core Mode0 Memory-Driven Rendering

---

### Task 14 — Linestate Model

**Status:** DONE
**depends_on:** [9, 13]
**scope_boundary:** No raster effects yet. Linestate commit at line boundary only. No SDRAM. Linestate records stored on-chip.
**delivers:**

- Linestate record definition (per `GLOSSARY.md`)
- Double-buffered linestate store: prepare side writable by host, commit side readable by render pipeline
- Atomic commit at line boundary
- Render pipeline reads linestate from commit side only

**validation:** Linestate values apply correctly at line boundaries in simulation. No mid-line state change visible.

---

### Task 15 — Memory-Backed Fetch Path

**Status:** DONE
**depends_on:** [6, 14]
**scope_boundary:** Tile fetch from SDRAM only. No planar. No shuffled. No sprite SDRAM fetch yet.
**note:** Delivered by **R4 — Tile + Attribute Fetch Primitive** (`df7af63`).
**delivers:**

- SDRAM controller in Tang20K wrapper
- SDRAM arbiter accepting fetch requests
- Tile fetch engine requesting and receiving data from SDRAM
- Rendered output driven from SDRAM-backed tile data

**validation:** Tile data fetched from SDRAM renders correctly on hardware. Fetch timing is stable under continuous operation.

---

### Task 16 — Planar Fetch Path

**Status:** DONE
**depends_on:** [15]
**scope_boundary:** Planar background fetch only. No shuffled. No sprite path changes.
**note:** Delivered by **R4.1b — Planar Fetch** (closed).
**delivers:**

- Planar fetch engine reading multi-bitplane data from SDRAM
- Pixel index reconstructed from bitplane data
- Planar scene rendered on hardware

**validation:** Planar bitmap scene (Scenario 9) passes on hardware.

---

### Task 17 — Shuffled Fetch Path

**Status:** DONE
**depends_on:** [15]
**scope_boundary:** Shuffled background fetch only. No planar changes.
**note:** Planned as **R4.1d — Shuffled Fetch Path**.
**delivers:**

- Amiga-style dual-base 2bpp bitplane fetch path (Plane 0 @ `0xA000`, Plane 1 @ `0xB000`)
- Pixel reconstruction `{plane1[bit], plane0[bit]}` from separate SDRAM bases
- Bitplane-checkerboard diagnostic scene rendered on hardware

**validation:** Shuffled bitmap scene (Scenario 10) passes on hardware with 30s OpenCV stability analysis.

---

### Task 18 — Per-Line Raster Control

**Status:** DONE
**depends_on:** [14]
**scope_boundary:** Raster effects driven by linestate only. No affine. No color math yet.
**note:** Delivered by **R1 — Raster Trigger Unit** (`df7af63`).
**delivers:**

- Per-line scroll variation (different scroll offset per scanline)
- Per-line layer enable/disable
- Visible raster effect on hardware

**validation:** Raster effects scenario (Scenario 11) passes on hardware. No stepping or scheduling artifacts.

---

## Phase 6 — Advanced Mode0 Functions

---

### Task 19 — Affine Layer

**Status:** DONE
**depends_on:** [15, 18]
**scope_boundary:** Affine background only. No affine sprites. No scaling beyond what the affine matrix provides.
**delivers:**

- Affine coordinate stepping logic
- Affine-fetched background rendered on hardware
- Correct visual transform output

**validation:** Affine background with sprites scenario (Scenario 12) passes on hardware.

---

### Task 20 — Color Math / Window Effects

**Status:** DONE
**depends_on:** [13, 14]
**scope_boundary:** Post-compositor effects only. No changes to upstream pipeline.
**delivers:**

- Blending operation (additive or alpha, per configuration)
- Window region definition and masking
- Effect visible on hardware

**validation:** Color math / window scene (Scenario 14) passes on hardware.

---

## Phase 7 — Platform Adapter Modes

---

### Task 21 — Mixed-Scene Integration

**Status:** DONE
**depends_on:** [15, 16, 17, 18, 19, 20]
**scope_boundary:** Integration of existing Mode0 primitives only. No new primitives.
**delivers:**

- Scene combining tile, planar, shuffled, sprite, and raster effect paths simultaneously
- Stable output with no corruption

**validation:** Mixed fetch-mode integration scene (Scenario 15) passes on hardware.

---

## Phase 8 — Integration and Stress

---

### Task 22 — Long Soak Validation

**Status:** DONE
**depends_on:** [21]
**scope_boundary:** No new features. Runtime stability testing only.
**delivers:**

- Soak test running for minimum 1 hour with continuous motion
- No drift, corruption, or instability observed

**validation:** Long soak scene (Scenario 16) passes. Zero corruption events in observation window.

---

### Task 23 — Stress-Scene Validation

**Status:** OPEN
**depends_on:** [21]
**scope_boundary:** No new features. Maximum-load scenario only.
**delivers:**

- Worst-case scene: maximum active sprites, maximum layer activity, maximum fetch demand
- Stable output documented at load ceiling

**validation:** Stress scene (Scenario 17) runs stably. Load ceiling documented.

---

### Task 24 — QSPI Control Surface

**Status:** RETIRED — superseded by R5 Host Interface + Copper
**depends_on:** [14, 15]
**scope_boundary:** Control path only. No new rendering features. QSPI interface to existing registers and linestate only.
**delivers:**

- Redirected to R5. The indirect register model and command FIFO in R5 satisfy the original QSPI control-surface goal.

**validation:** See R5 task doc.

---

### Task 25 — Future Address/Data Bus Interface Definition

**Status:** DEFERRED
**depends_on:** [24]
**scope_boundary:** Definition and planning only. No implementation until QSPI path is stable.
**delivers:** Design document describing parallel bus attachment strategy.

**validation:** Document reviewed and approved before implementation begins.

---

## Scenario Validation Tasks

These are not implementation tasks. Each scenario must be run against hardware and marked `DONE` only when it passes.

| # | Scenario | Depends on Tasks | Status |
|---|----------|-----------------|--------|
| 26 | Scenario 1 — Static background fill | 5, 6, 10 | DONE |
| 27 | Scenario 2 — Single-axis scroll | 7, 26 | DONE |
| 28 | Scenario 3 — Scroll wraparound / seam test | 8, 27 | DONE |
| 29 | Scenario 4 — Single sprite over static background | 11, 26 | DONE |
| 30 | Scenario 5 — Four bouncing sprites over static background | 11, 29 | DONE |
| 31 | Scenario 6 — Sprites over scrolling background | 12, 13, 27, 30 | DONE |
| 32 | Scenario 7 — Sprite priority / overlap test | 12, 31 | DONE |
| 33 | Scenario 8 — Multi-layer parallax scroll | 13, 27 | DONE |
| 34 | Scenario 9 — Planar bitmap scene | 16 | DONE |
| 35 | Scenario 10 — Shuffled bitmap scene | 17 | DONE |
| 36 | Scenario 11 — Per-line raster effects | 18 | DONE |
| 37 | Scenario 12 — Affine background with sprites | 19, 12 | DONE |
| 38 | Scenario 13 — Palette animation during motion | 10, 14, 27 | DONE |
| 39 | Scenario 14 — Color math / window scene | 20 | DONE |
| 40 | Scenario 15 — Mixed fetch-mode integration scene | 21 | TODO |
| 41 | Scenario 16 — Long soak scene | 22 | TODO |
| 42 | Scenario 17 — Stress scene | 23 | TODO |

---

## R-Roadmap Execution Tasks

These tasks track the post-roadmap primitive build order defined in `MODE0_ROADMAP.md`.

### R1 — Raster Trigger Unit

**Status:** DONE  
**Task doc:** `PROJECT_PLAN/TASK_R1_RASTER_TRIGGER_UNIT.md`

### R2 — Two-Pass Sprite Evaluator

**Status:** DONE  
**Task doc:** `PROJECT_PLAN/TASK_R2_TWO_PASS_SPRITE_EVALUATOR.md`

### R3 — Static Fetch-Slot Scheduler

**Status:** DONE  
**Task doc:** `PROJECT_PLAN/TASK_R3_FETCH_SLOT_SCHEDULER.md`

### R4 — Tile + Attribute Fetch Primitive

**Status:** CLOSED (`df7af63`)  
**Task doc:** `PROJECT_PLAN/TASK_R4_TILE_ATTRIBUTE_FETCH.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

### R4.1 — Multi-Slot Scheduler Coupling

**Status:** CLOSED (`9dfeb9f`)  
**Task doc:** `PROJECT_PLAN/TASK_R4_1_MULTI_SLOT_SCHEDULER_COUPLING.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

### R4.1c — Packed-Attribute Decode (NES-style)

**Status:** CLOSED (`0e4d9dc`)  
**Task doc:** `PROJECT_PLAN/TASK_R4_1C_PACKED_ATTRIBUTE.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

**Note:** Stages 1-4 audited and passed (`4290b86`). Stage 5 re-delivered with a packed-friendly diagnostic attribute map (`0xE4`/`0xEC`) producing an unambiguous 2×2 bank-checkerboard (banks 0/1/2/3 from a single shared SDRAM byte) on Tang Nano 20K HDMI capture. All 11 sims pass. 100% verification rule satisfied.

### R4.1d — Shuffled Fetch Path (Amiga-style)

**Status:** CLOSED (`0087920`)  
**Task doc:** `PROJECT_PLAN/TASK_R4_1D_SHUFFLED_FETCH.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

**Checkpoint A:** COMPLETE / AUDIT-PASSED (`8a86f31`) — `VDP_TILE_MODE @ 0x0311` widened 1→2 bits, `UnifiedRegMapSim` case 4c/4d proves safe-boundary commit.  
**Checkpoint B:** COMPLETE / AUDIT-PASSED (`db6b933`) — dual-base fetch path implemented, `TileAttributeFetchSim` case 9 proves bit-accurate reconstruction, all 11 sims PASS.  
**Checkpoint C:** COMPLETE (`0087920`) — bitplane-checkerboard diagnostic scene rendered on Tang Nano 20K; 30s OpenCV analysis shows four distinct intensity bands (~25% each) with 0 spikes / 0 freezes; all 11 sims PASS.

### R5 — Host Interface + Copper Coprocessor

**Status:** CLOSED (`32a87ff`)  
**Task doc:** `PROJECT_PLAN/TASK_R5_HOST_INTERFACE_AND_COPPER.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

### R5.3 — Copper Control Unification (`VDP_CTRL` register)

**Status:** CLOSED (`32a87ff`)  
**Task doc:** `PROJECT_PLAN/TASK_R5_3_COPPER_CTRL_UNIFICATION.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

### R5.4 — Scroll-Wrap Component Primitive

**Status:** CLOSED (`d580dcb`)  
**Task doc:** `PROJECT_PLAN/TASK_R5_4_SCROLL_WRAP.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

### R6 — Color Math / Window Effects

**Status:** CLOSED (`dd119ec`)  
**Task doc:** `PROJECT_PLAN/TASK_20_COLOR_MATH_WINDOW.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

---

## Deferred Items

| Item | Status | Notes |
|------|--------|-------|
| Additional output modes | DEFERRED | Not required for baseline bring-up |
| Deep-angle affine tuning | DEFERRED | Only after affine base path is proven |
| Platform adapter modes | DEFERRED | Only after stable Mode0 completion |
| Alternate memory strategies | DEFERRED | Only if baseline memory path becomes a blocker |
| Parallel bus implementation | DEFERRED | After QSPI path is stable (Task 25) |

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
