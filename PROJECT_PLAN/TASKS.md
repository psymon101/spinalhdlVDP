# TASKS.md

**Updated:** 2026-04-11
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

## Current Baseline

The repository has already completed the initial output bring-up slice:

- SBT generation is working
- Gowin build is working
- HDMI output is working
- Hardware flash and verify are working
- Direct capture confirms the intended current test pattern

That means Tasks 1 through 5 are already complete in this repo state.

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

**Status:** TODO
**depends_on:** [6, 14]
**scope_boundary:** Tile fetch from SDRAM only. No planar. No shuffled. No sprite SDRAM fetch yet.
**delivers:**

- SDRAM controller in Tang20K wrapper
- SDRAM arbiter accepting fetch requests
- Tile fetch engine requesting and receiving data from SDRAM
- Rendered output driven from SDRAM-backed tile data

**validation:** Tile data fetched from SDRAM renders correctly on hardware. Fetch timing is stable under continuous operation.

---

### Task 16 — Planar Fetch Path

**Status:** TODO
**depends_on:** [15]
**scope_boundary:** Planar background fetch only. No shuffled. No sprite path changes.
**delivers:**

- Planar fetch engine reading multi-bitplane data from SDRAM
- Pixel index reconstructed from bitplane data
- Planar scene rendered on hardware

**validation:** Planar bitmap scene (Scenario 9) passes on hardware.

---

### Task 17 — Shuffled Fetch Path

**Status:** TODO
**depends_on:** [15]
**scope_boundary:** Shuffled background fetch only. No planar changes.
**delivers:**

- Shuffled fetch engine reading non-linear pixel/attribute layout from SDRAM
- Pixel and attribute combined correctly
- Shuffled scene rendered on hardware

**validation:** Shuffled bitmap scene (Scenario 10) passes on hardware.

---

### Task 18 — Per-Line Raster Control

**Status:** TODO
**depends_on:** [14]
**scope_boundary:** Raster effects driven by linestate only. No affine. No color math yet.
**delivers:**

- Per-line scroll variation (different scroll offset per scanline)
- Per-line layer enable/disable
- Visible raster effect on hardware

**validation:** Raster effects scenario (Scenario 11) passes on hardware. No stepping or scheduling artifacts.

---

## Phase 6 — Advanced Mode0 Functions

---

### Task 19 — Affine Layer

**Status:** TODO
**depends_on:** [15, 18]
**scope_boundary:** Affine background only. No affine sprites. No scaling beyond what the affine matrix provides.
**delivers:**

- Affine coordinate stepping logic
- Affine-fetched background rendered on hardware
- Correct visual transform output

**validation:** Affine background with sprites scenario (Scenario 12) passes on hardware.

---

### Task 20 — Color Math / Window Effects

**Status:** TODO
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

**Status:** TODO
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

**Status:** TODO
**depends_on:** [21]
**scope_boundary:** No new features. Runtime stability testing only.
**delivers:**

- Soak test running for minimum 1 hour with continuous motion
- No drift, corruption, or instability observed

**validation:** Long soak scene (Scenario 16) passes. Zero corruption events in observation window.

---

### Task 23 — Stress-Scene Validation

**Status:** TODO
**depends_on:** [21]
**scope_boundary:** No new features. Maximum-load scenario only.
**delivers:**

- Worst-case scene: maximum active sprites, maximum layer activity, maximum fetch demand
- Stable output documented at load ceiling

**validation:** Stress scene (Scenario 17) runs stably. Load ceiling documented.

---

### Task 24 — QSPI Control Surface

**Status:** TODO
**depends_on:** [14, 15]
**scope_boundary:** Control path only. No new rendering features. QSPI interface to existing registers and linestate only.
**delivers:**

- QSPI interface in Tang20K wrapper
- Register map accessible from external host
- Linestate writable from external host via QSPI
- Breadboard host can drive a scene without FPGA-internal stimulus

**validation:** External host programs and updates a running scene via QSPI. No corruption from concurrent QSPI access and active rendering.

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
| 26 | Scenario 1 — Static background fill | 5, 6, 10 | TODO |
| 27 | Scenario 2 — Single-axis scroll | 7, 26 | TODO |
| 28 | Scenario 3 — Scroll wraparound / seam test | 8, 27 | TODO |
| 29 | Scenario 4 — Single sprite over static background | 11, 26 | TODO |
| 30 | Scenario 5 — Ten bouncing sprites over static background | 11, 29 | TODO |
| 31 | Scenario 6 — Sprites over scrolling background | 12, 13, 27, 30 | TODO |
| 32 | Scenario 7 — Sprite priority / overlap test | 12, 31 | TODO |
| 33 | Scenario 8 — Multi-layer parallax scroll | 13, 27 | TODO |
| 34 | Scenario 9 — Planar bitmap scene | 16 | TODO |
| 35 | Scenario 10 — Shuffled bitmap scene | 17 | TODO |
| 36 | Scenario 11 — Per-line raster effects | 18 | TODO |
| 37 | Scenario 12 — Affine background with sprites | 19, 12 | TODO |
| 38 | Scenario 13 — Palette animation during motion | 10, 14, 27 | TODO |
| 39 | Scenario 14 — Color math / window scene | 20 | TODO |
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

**Status:** OPEN  
**Task doc:** `PROJECT_PLAN/TASK_R4_TILE_ATTRIBUTE_FETCH.md`  
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
