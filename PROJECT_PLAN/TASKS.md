# TASKS.md

**Updated:** 2026-04-18 (convergence applied per BronzeGate #7580)
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
| **Task** | *(no active lane — cleared)* |
| **Status** | — |
| **Phase** | — |
| **Owner** | — |
| **Latest Commit** | `1294614` (Task 38b DONE: status surface expansion) |
| **Latest Auth Mail** | #7617 (CyanPeak: Task 38b PASSED and CLOSED) |
| **Next Deliverable** | BronzeGate PM direction for next active lane (expected: Task 38c) |
| **Coding Authorized** | **NO** — wait for next lane artifact + audit |

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

That means Tasks 1 through 5, Task 15, Task 16, Task 18, Task 26, Task 27, Task 38a, and Task 38b are already complete in this repo state.

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

## Phase 7 — Integration Proofs

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

**Status:** DONE
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
| 40 | Scenario 15 — Mixed fetch-mode integration scene | 21 | DONE |
| 41 | Scenario 16 — Long soak scene | 22 | DONE |
| 42 | Scenario 17 — Stress scene | 23 | DONE |

---

## R-Roadmap Execution Tasks

These tasks track the post-roadmap primitive build order defined in `MODE0_ROADMAP.md`.

### R1 — Raster Trigger Unit

**Status:** DONE  
**Task doc:** `PROJECT_PLAN/TASK_R1_RASTER_TRIGGER_UNIT.md`

### R2 — Two-Pass Sprite Evaluator

**Status:** Initial primitive closed; stronger variant pending via **Task 28**  
**Task doc:** `PROJECT_PLAN/TASK_R2_TWO_PASS_SPRITE_EVALUATOR.md`

### R3 — Static Fetch-Slot Scheduler

**Status:** Initial primitive closed; stronger variant pending via **Task 30**  
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

**Status:** CLOSED (`32a87ff`) — initial host interface + Copper primitive  
**Task doc:** `PROJECT_PLAN/TASK_R5_HOST_INTERFACE_AND_COPPER.md`  
**Coding Owner:** BrightForge  
**Audit Owner:** CyanPeak

**Note:** Register bus spec (R5.1) and Copper-lite automation (R5.2) remain open via **Tasks 32a** and **33**.

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

## Phase 9 — Host Control

---

### Task 26 — QSPI Host-Control Frontend

**Status:** DONE
**depends_on:** [23]
**scope_boundary:** Transport shim and decoder only. No bulk asset streaming, no protocol expansion, no new rendering primitives, no FIFO model redesign.
**delivers:**

- QSPI slave transport shim (SpinalHDL port of proven `m0_qspi_slave.v`)
- QSPI command decoder mapping register writes into existing `VdpTop.io.regWrite*` contract
- READ_STATUS response mux for host bring-up diagnostics
- Pico 2 smoke firmware proving the path
- Hardware proof: QSPI-driven register write visibly toggles output

**validation:**

- Checkpoint A: control contract + clean Verilog + pin mapping (PASSED at `df0b75b`)
- Checkpoint B: simulation proofs + READ_STATUS mux (PASSED at `8c4f165`)
- Checkpoint C: Pico 2 smoke test + `LAYER_ENABLE` live-update on hardware

**debug history:**

- `2138a27`: Amended firmware toggle `0x0000 <-> 0x0005`; 30 s capture FAIL — no visible change.
- `04d488b`: Forward wire-test (Pico drives, FPGA samples via LEDs) — committed, awaiting user observation.
- `1541615`: Reverse wire-test (FPGA drives, Pico reads via serial) — **all 4 wires PASS**. Physical wiring proven correct.
- `1541615`: 2 MHz retest — FAIL (same flat signature). SCK rate ruled out.
- `7512`: Architectural gap identified — top-level QSPI IO pins are input-only; `IOBUF` bidirectional work deferred.
- `7513`: BronzeGate direction — execute **Option B** (LED write-direction probe) first. Bounded scope, no `IOBUF`.
- `7516`–`7518`: Option B/B2 executed — `active` and `cmd_valid` pulse, but `regWriteEnable` NEVER pulses.
- `7517`–`7519`: BronzeGate authorized iterations B2, B3.
- `7520`: BronzeGate direction — HDMI debug HUD preferred over LED-only.
- **`7527` (BrightForge PRIMARY root cause):** HUD-v2 shows `payload_cnt / cmd_cnt = 1.000` — only 1 payload byte arrives per command instead of 2. Pico firmware `qspi_tx_bytes` polls `tx_fifo_empty` then `sleep_us(2)`, but CS is deasserted before the SM finishes shifting the last word from OSR. **Fix 1:** `sleep_us(10)` in `befcd17`.
- **`7531` (Checkpoint C PASS):** Fix 1 applied. Post-fix evidence: `payload_cnt/cmd_cnt = 2.000`, `regWriteEnable` duty = 20.1%, luma bimodal gap = 144.01. Hardware proof complete.
- **`7534` (CyanPeak audit):** Checkpoint C formally PASSED. Fix 2 (IO2/IO3 wiring) DEFERRED to separate hardening lane. BrightForge authorized to revert debug artifacts (HUD/LED probes).
- **`7526` (CoralReef SECONDARY issue):** spinalhdlVDP CST only wires IO0+IO1. PIO quad-mode sends 4 bits/nibble; FPGA ties IO2/IO3 to 0. Data bytes with bits 2/3 set in any nibble are corrupted (e.g. `0x05` → `0x01`). Verified by corrected `Qspi2WireSimV2`. Does NOT block Checkpoint C (opcode `0x01` correct, 2 payload bytes arrive with Fix 1, `regWriteEnable` fires). Fix deferred.

**Task doc:** `PROJECT_PLAN/QSPI_HOST_CONTROL_PLAN.md`

---

### Task 27 — Full-QSPI Hardening (IO2/IO3)

**Status:** DONE
**depends_on:** [26]
**scope_boundary:** IO2/IO3 wiring + CST + hardware proof of 4-bit payload fidelity only. No protocol redesign, no bulk asset streaming, no readback/bidirectional work unless explicitly added.
**delivers:**

- Tang CST updated with IO2 (pin 51) and IO3 (pin 54) per proven VDP project pinout
- `TopTang20kHdmi` updated to connect all 4 QSPI data lines (remove `B"00" ## ...` tie-off)
- Simulation proof that 4-bit payload bytes (nibbles with bits 2/3 set) are received correctly
- Hardware proof: QSPI-driven register write with payload value exercising bit 2 (`0x0007`) visibly toggles on Tang Nano 20K

**validation:**

- Checkpoint A: HDL + CST update, clean Verilog generation, P&R passes — **LANDED `1794c9c`, regression verified**
- Checkpoint B: simulation proof with payload bytes containing bits 2/3 set — **LANDED `494e037`, 7 cases PASS (0xFFFF, 0xAAAA, 0x5555, 0xBEEF, 0x00F3)**
- Checkpoint C: hardware proof on Tang Nano 20K — **LANDED `92ecb41`, LAYER_ENABLE toggle `0x0007 ↔ 0x0000` visible, bimodal gap = 153.07**

**Post-completion notes (BrightForge #7566):**
- Bit 3 observability gap: no current Mode0 register maps bit 3 to visible pixel feature. Bit 3 is sim-proven (rides same IO3 wire as bit 2) but hardware eyeball confirmation requires readback path or diagnostic probe.
- Physical jumper map (GP8–GP13 ↔ Tang 41/42/48/49/51/54) should be captured in `PLATFORM.md`.
- PIO TX drain gotcha (`sleep_us(2)` insufficient) should be hardened to true SM-idle poll in future firmware.
- `rx_cmd_cnt` / `last_addr` / `last_data` / `last_error` should be promoted to READ_STATUS surface in Task 38.

**Task doc:** `PROJECT_PLAN/TASK27_FULL_QSPI_HARDENING.md` (artifact doc opened in `cc7ec82`, CyanPeak audit PASS in #7548)

---

### Task 28 — Two-Pass Sprite Evaluator

**Status:** TODO
**depends_on:** [12, 15]
**scope_boundary:** Sprite evaluation pipeline only. No new compositor changes, no collision logic (Task 29), no attribute extension (Task 37).
**delivers:**

- Per-line active-sprite scan with bounded visible-sprite selection
- Sprite-per-scanline limit enforcement
- Secondary sprite buffer for fetched attributes
- Stronger overlap/priority behavior

**validation:**

- Sim: mixed scene with >8 sprites on a line proves correct selection and limit enforcement
- Hardware: visual proof on Tang Nano 20K that sprite drop behavior matches expected limits

**Task doc:** `PROJECT_PLAN/TASK_R2_TWO_PASS_SPRITE_EVALUATOR.md`

---

### Task 29 — Sprite Flags and Collision Hooks

**Status:** TODO
**depends_on:** [28]
**scope_boundary:** Sprite-side status flags only. No new compositor, no new fetch formats.
**delivers:**

- Sprite-0-hit style flag (first non-transparent sprite pixel vs background)
- Per-line sprite overflow/limit status register
- Sprite/background collision latches
- Clearer priority-vs-layer hooks

**validation:**

- Sim: collision scenarios produce correct latch values
- Hardware: raster-IRQ or status-read path proves flags are visible to host

---

### Task 30 — Pre-Announced Arbiter Grant

**Status:** TODO
**depends_on:** [15]
**scope_boundary:** SDRAM arbitration lookahead only. No new fetch engines, no new memory types.
**delivers:**

- BA-style lookahead so fetch clients can prepare before the exact memory-use slot
- More deterministic multi-fetch scenes
- Latency-tolerant SDRAM arbitration

**validation:**

- Sim: mixed scene with tile + sprite + Copper fetch proves no arbitration glitches under lookahead
- Hardware: long-soak validation (Task 22 class) with arbiter active

---

### Task 31 — Scroll Table Primitive

**Status:** TODO
**depends_on:** [7, 15]
**scope_boundary:** Scroll tables only. No new tile fetch formats, no new compositor math.
**delivers:**

- Separate small dual-port RAM/table primitive for per-column or per-band scroll
- Explicit distinction between line state and scroll lookup state
- Interface for Genesis VSRAM-style patterns and SNES offset-per-tile

**validation:**

- Sim: scene with per-column scroll offsets proves correct addressing
- Hardware: visible parallax effect on Tang Nano 20K

---

### Task 32a — Mode0 Register Bus: Spec & Naming Lock

**Status:** TODO
**depends_on:** [18, 26]
**scope_boundary:** Register bus specification and naming lock only. No master refactor, no new primitives, no adapter-specific registers.
**delivers:**

- Written register bus specification (address map, semantics, naming convention)
- Uniform naming and ownership pattern for all existing control/status surfaces
- Interface contract that bootstrap, QSPI, Copper, and Animator masters can target
- Semantics sketch that earlier primitives (R1-R4) can absorb without breakage

**validation:**

- Doc review: bus spec covers all existing primitives plus planned R5-R8
- Sim: register writes via QSPI correctly propagate through the bus to all targets

---

### Task 32b — Mode0 Register Bus: Master Refactor

**Status:** TODO
**depends_on:** [32a]
**scope_boundary:** Refactor existing masters to the named bus. No new primitives.
**delivers:**

- Bootstrap write path, QSPI `regWriteEnable` mux, copper RAM writes, animator writes, linestate prepare/commit, affine register set, and all existing control surfaces refactored onto the named bus
- All existing simulations pass after refactor

**validation:**

- Sim: all existing scenario simulations pass with zero behavioral change
- Hardware: at least one existing scenario re-proven on Tang Nano 20K

---

### Task 33 — Copper-lite / HDMA Automator

**Status:** TODO
**depends_on:** [32a]
**scope_boundary:** Beam-synchronous micro-engine only. No new fetch engines, no new output stages.
**delivers:**

- Wait-for-beam-position + write-selected-register engine
- Optional table-driven value reload
- Palette-bank or palette-entry reload actions
- Amiga Copper-style wait/move and SNES HDMA-style per-line updates

**validation:**

- Sim: copper script produces expected raster splits and color bars
- Hardware: visible raster effects on Tang Nano 20K

---

### Task 34 — QSPI Host-Driven Asset Upload

**Status:** TODO
**depends_on:** [27, 38c]
**scope_boundary:** Bulk SDRAM write via QSPI only. No new rendering primitives, no protocol redesign.
**delivers:**

- QSPI command path for writing SDRAM directly (textures, tilemaps, tile rows, palette entries/banks)
- Addressed burst write protocol with progress/status
- Hardware proof: upload a small texture/tileset and palette entry/bank via QSPI and render it

**validation:**

- Sim: QSPI burst write lands in SDRAM model, fetched data matches
- Hardware: uploaded asset renders correctly on Tang Nano 20K

---

### Task 35 — Host-Facing IRQ and Status Registers

**Status:** TODO
**depends_on:** [18, 32a]
**scope_boundary:** IRQ line + readable status register surface only. No new automation engines.
**delivers:**

- Host-visible raster match / IRQ line
- Sticky status registers (sprite overflow, raster match, QSPI ready, etc.)
- Clear-on-read or write-to-clear semantics
- Status readable under maximum fetch load (not just idle)

**validation:**

- Sim: raster trigger asserts IRQ, host readback sees correct status
- Sim: status read is stable under concurrent SDRAM fetch + sprite evaluation load
- Hardware: Pico reads status register over QSPI and prints expected values

---

### Task 36 — Register Write Concurrency Stress Test

**Status:** TODO
**depends_on:** [26, 33]
**scope_boundary:** Validation-only task. No new HDL, no new firmware features.
**delivers:**

- Sim scenario with QSPI + Copper + Animator all writing registers on the same frame
- Proof that safe-boundary commit absorbs concurrent writes without glitches
- Explicit bandwidth analysis under maximum write traffic
- Multi-master bus stress under maximum SDRAM fetch + sprite evaluation load

**validation:**

- Sim: 10k-frame randomized stress with all three masters → zero commit glitches
- Sim: status readback is correct while masters are actively writing
- Hardware: rapid alternating writes from QSPI and Copper → visual stability

---

### Task 37 — Affine Sprite Path

**Status:** TODO
**depends_on:** [19, 28]
**scope_boundary:** Affine-transformed sprites only. No new background affine features.
**delivers:**

- Matrix-stepped texture address generation for sprite source data
- Rotation/scaling support for individual sprites
- Integration with existing sprite evaluator and compositor

**validation:**

- Sim: affine sprite renders with correct transformed pixels
- Hardware: visible rotated/scaled sprite on Tang Nano 20K

---

### Task 38a — Bidirectional QSPI: HDL IOBUF + CST

**Status:** DONE (`f49880f`)
**depends_on:** [27]
**scope_boundary:** Top-level bidirectional wiring only. No decoder changes, no firmware.
**delivers:**

- Gowin `IOBUF` primitive on IO0/IO1/IO2/IO3 in `TopTang20kHdmi`
- CST updated: pins 48/49/51/54 become bidirectional (pull-modes revisited)
- `QspiSlave` Respond state drives valid data back to host
- Clean Verilog generation, P&R passes

**validation:**

- Sim: `READ_STATUS` command drives valid response data onto IO pins
- Hardware: synthesis and place-and-route succeed without errors

---

### Task 38b — Bidirectional QSPI: Status Surface Expansion

**Status:** DONE (`1294614`)
**depends_on:** [38a]
**scope_boundary:** Decoder status surface only. No firmware, no IOBUF changes.
**delivers:**

- READ_STATUS response surface expanded beyond magic `sel=0`
- `sel=1`: `rx_cmd_cnt`
- `sel=2`: `last_addr`
- `sel=3`: `last_data`
- `sel=4`: `last_error`
- Sim proof that all sel values return expected fields

**validation:**

- Sim: `READ_STATUS` with sel=0..4 returns expected values
- Hardware: high-nibble bits (e.g. `0x51` in response bytes) confirm IO3 path is electrically alive

---

### Task 38c — Bidirectional QSPI: Firmware Read Helper + Bit-3 Proof

**Status:** TODO
**depends_on:** [38b]
**scope_boundary:** Firmware read path only. No HDL changes.
**delivers:**

- Pico firmware QSPI read helper (proven reference available in `/home/itadmin/github/VDP/src/mode0/firmware/src/qspi_bus.c`)
- Firmware reads magic `0x51560002` and status registers (`rx_cmd_cnt`, `last_addr`, `last_data`, `last_error`)
- `pio_wait_sm_idle()` drain helper replacing ad-hoc `sleep_us(10)` margin
- Bit-3 hardware observability: high-nibble status bytes confirm IO3 path

**validation:**

- Hardware: Pico receives `0x51560002` on Tang Nano 20K over QSPI readback
- Hardware: high-nibble bits are not silently zeroed (bit-3 alive proof)

---

### Task 39 — Host Driver Library

**Status:** TODO
**depends_on:** [34, 35, 38c]
**scope_boundary:** Host-side library only. No HDL changes, no new rendering primitives.
**delivers:**

- `libvdp_mode0_host.{c,h}` — firmware-agnostic driver above QSPI transport
- Packet framing, register map abstraction, status polling helpers
- Asset upload protocol with burst + progress callbacks
- IRQ handling hooks
- Reusable `pio_wait_sm_idle()` drain helper

**validation:**

- Firmware builds and links against the library with zero warnings
- Hardware: library-driven upload + register write + status read cycle proves end-to-end host control

---

### Task 40 — First Platform Adapter (C64 Raster+Sprite Smoke)

**Status:** TODO
**depends_on:** [28, 30, 39]
**scope_boundary:** Single bounded adapter proof only. No cycle-accurate emulation claim. No additional platforms.
**delivers:**

- C64-class raster IRQ driving a two-bar split with sprites
- Register semantics and control model matching C64-style behavior
- Honest "first real adapter on top of Mode0" milestone
- Proof that the substrate contract works in real use

**validation:**

- Sim: adapter register writes produce expected C64-class raster split
- Hardware: visible two-bar split + sprite behavior on Tang Nano 20K
- 30s capture stability analysis passes

**Task doc:** To be created when lane opens.

---

### Task 41 — Compositor Metadata Pipe

**Status:** TODO
**depends_on:** [13]
**scope_boundary:** Metadata pipe definition and wiring only. No new compositor math, no new fetch formats.
**delivers:**

- Per-pixel metadata flag definition (math-enable, forced-priority, layer-source)
- Metadata carried through line-buffer boundary
- Compositor consumes metadata flags for color-math and priority decisions
- Explicit contract between fetch engines and post-compositor stages

**validation:**

- Sim: metadata flags generated by fetch engine arrive at compositor intact
- Hardware: visible proof that metadata-driven effects (e.g. forced-priority sprite) behave correctly

---

### Task 42 — Firmware + Platform Docs Hardening

**Status:** TODO
**depends_on:** [27]
**scope_boundary:** Documentation and small firmware helpers only. No HDL changes.
**delivers:**

- Six-wire Pico↔Tang jumper map captured in `PLATFORM.md`
- Reusable `pio_wait_sm_idle()` PIO drain helper (if not delivered by Task 39)
- Documented QSPI firmware gotchas and workarounds
- PIO timing constraints and verified SCK rates documented

**validation:**

- Doc review: PLATFORM.md is complete and accurate for current hardware setup
- Firmware: drain helper tested in isolation and integrated into smoke test

---

### Task 43 — Scenario Regression Harness

**Status:** TODO
**depends_on:** [21]
**scope_boundary:** Test infrastructure only. No new HDL, no new features.
**delivers:**

- Regression script/Makefile target that rebuilds and tags scenario bitstreams 1–17
- Capture + analysis artifact preservation policy
- Automated stability check (OpenCV/FFT-based) for each scenario
- CI-friendly entry point for post-substrate-change validation

**validation:**

- All 17 scenarios rebuild successfully from clean state
- At least 3 representative scenarios pass automated stability analysis

---

## Phase 10 — Platform Adapters

---

## Deferred Items

The following items remain intentionally coarse or out of Mode0 scope. Where possible they have been decomposed into numbered tasks above.

| Item | Status | Notes |
|------|--------|-------|
| Additional output modes | DEFERRED | Not required for baseline bring-up |
| Deep-angle affine tuning | DEFERRED | Only after affine base path is proven (Task 19, Task 37) |
| Platform adapter modes | DEFERRED | Now tracked as **Task 40** (First Platform Adapter) in Phase 10 |
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
