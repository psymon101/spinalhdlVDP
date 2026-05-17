# Gowin / Tang Nano 20K Gotchas

Purpose: keep a repo-local record of Gowin, GW2AR-18, and Tang Nano 20K implementation hazards that are relevant to this codebase.

Status rule:
- Entries imported from other projects are advisory until reproduced or otherwise validated in `spinalhdlVDP`.
- Once reproduced here, add a short repo-specific note under the entry with the affected file or task.
- Do not delete entries. If an issue is later disproven for this repo, mark it as `Not observed in spinalhdlVDP` instead of removing it.

Current focus:
- Task 15 SDRAM-backed fetch path
- Gowin synthesis behavior around wrappers, CDC, inferred RAM/FIFO, and clocking

## How To Use This File

Read this file before changing:
- `hw/spinal/spinalhdlvdp/SdramPrimitives.scala`
- `hw/spinal/spinalhdlvdp/SdramTileFetch.scala`
- `hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala`
- any handwritten Verilog or SystemVerilog under `fpga/tang20k/`

Use this file together with:
- [README.md](/home/itadmin/github/spinalhdlVDP/kb/gowin/README.md)
- [PLATFORM.md](/home/itadmin/github/spinalhdlVDP/PROJECT_PLAN/PLATFORM.md)
- local vendor docs under `kb/gowin/` and `kb/fpga/`

## Task 15 Relevant Advisory Entries

### GT-006: PLL / VCO Parameter Sensitivity

Risk:
- invalid divider combinations can produce an unlocked or unstable PLL while looking superficially reasonable

Why it matters here:
- Task 15 is introducing a separate SDRAM clock and likely a phase-shifted companion output
- `66 MHz` and `100 MHz` are both plausible bring-up targets, but only if the derived VCO stays within the documented operating window

Working rule:
- verify the full PLL math before changing frequency or phase assumptions
- record the exact chosen frequency, divider values, and rationale in the completion packet

Primary docs:
- `kb/gowin/UG286-2.0.2E_Gowin Clock User Guide.pdf`
- `kb/gowin/DS226-2.6E_GW2AR series of FPGA Products Data Sheet.pdf`

### GT-012: SDRAM Read Response Registration Race

Risk:
- a controller or wrapper can assert a read-valid pulse on the same cycle the final data word is still entering a register with non-blocking assignments
- simulation may pass while hardware captures stale data

Why it matters here:
- Task 15 is planning burst-style reads and a line-prefetch path
- any `data_ready`, `rsp_valid`, or equivalent handoff from the reused SDRAM controller into new fetch logic needs a deliberate registration boundary

Working rule:
- do not assume the final payload word is stable on the same cycle a done/valid pulse appears
- if the interface is ambiguous, add a one-cycle done state or a registered handoff stage

### GT-017: Combinational Single-Cycle Pulses Across Module Boundaries

Risk:
- a pulse visible inside one module may be missed after routing delay when sampled in a parent or sibling module

Why it matters here:
- Task 15 will likely create new `done`, `valid`, `memtest_pass`, `buffer_ready`, or CDC handshake pulses

Working rule:
- register single-cycle pulse outputs before they cross module boundaries
- prefer level-based ready/ack protocols or registered valid pulses over raw combinational expressions

### GT-021: `syn_preserve` Can Break BSRAM Output Register Inference

Risk:
- preserving a register that should infer into BSRAM output logic can force extra LUT/register implementation and damage timing or inference

Why it matters here:
- Task 15 may need a line buffer, tile-row burst buffer, or async-FIFO-adjacent storage

Working rule:
- do not add preservation attributes to RAM output registers unless there is a proven need
- if preservation is needed, place it on a downstream pipeline stage instead of the inferred RAM output itself

Primary doc:
- `kb/gowin/UG285-1.4E_Gowin BSRAM & SSRAM User Guide.pdf`

### GT-004 / GT-009: Module Sweep and Net Optimization Through Submodule Boundaries

Risk:
- Gowin can optimize away modules or nets that appear unused after other simplifications

Why it matters here:
- Task 15 is introducing new wrappers, PLL/control blackboxes, and status nets
- debug-only or proof-only signals can disappear if they do not reach a kept sink

Working rule:
- inspect synthesis logs for swept logic after first integration
- if a critical status net vanishes, use documented keep/preserve attributes sparingly and only on the specific crossing net or instance that needs it

Primary doc:
- `kb/gowin/SUG550_Gowin_Synthesis_User_Guide.pdf`

### GT-010: Source-List Sensitivity

Risk:
- adding or removing source files can change unrelated synthesis behavior

Why it matters here:
- Task 15 depends on third-party SDRAM HDL already checked into `fpga/tang20k/third_party/sdram/`

Working rule:
- do not treat source-list edits as harmless housekeeping
- after any build-list change, rerun generation, synthesis, and the proof flow rather than assuming unrelated logic is unaffected

### GT-008 / GT-011: Handwritten Verilog Declaration Hazards

Risk:
- late declarations can create implicit undriven nets
- undeclared multi-bit connections can silently collapse to 1-bit wires

Why it matters here:
- most shared logic here is Spinal-generated, but Task 15 still touches handwritten wrappers and third-party HDL interfaces

Working rule:
- if editing handwritten Verilog or SystemVerilog, explicitly declare every wire before first use and declare port-connection widths explicitly

### GT-020: Gowin Misoptimization of Certain Cascaded Comparisons

Risk:
- a legal RTL priority/comparison pattern can synthesize incorrectly in Gowin

Why it matters here:
- probably not central to the first Task 15 slice, but relevant if arbitration or priority selection grows into handwritten combinational selection logic

Working rule:
- prefer straightforward encoded comparisons and avoid clever cascaded blocking-assignment priority trees in handwritten Verilog

### GT-022: Gowin rPLL Docs Must Yield to Tool-Level Parameter Validation

Risk:
- PLL divider and phase settings that look correct from doc interpretation can still be wrong for the actual Gowin tool/device flow
- this can block synthesis entirely or, worse, produce a different output clock than intended

Why it matters here:
- Task 15 depends on a new SDRAM PLL in `fpga/tang20k/tang20k_sdram_pll.v`
- `spinalhdlVDP` already hit this directly during first hardware bring-up prep: an earlier reconciliation used the wrong interpretation of `*_SEL` fields, and Gowin synthesis rejected the chosen values with an `Invalid VCO frequency` error

Repo-local note:
- Reproduced in `spinalhdlVDP` during Task 15 pre-hardware prep on 2026-04-12
- Affected file: `fpga/tang20k/tang20k_sdram_pll.v`
- Symptom: Gowin rejected the PLL config and reported the effective VCO formula using `(FBDIV_SEL+1)` and `(IDIV_SEL+1)` semantics; `DUTYDA_SEL="0000"` was also rejected and replaced by the tool default
- Fix: trust the direct Gowin synthesis diagnostics over ambiguous doc/research interpretation, then correct the wrapper to the tool-accepted values and re-run synthesis before any flash attempt

Working rule:
- if UG286, inherited code, and team research disagree, the actual Gowin synth/PnR messages are the source of truth
- treat PLL parameter changes as unproven until a clean build confirms the tool accepts them
- record the exact accepted divider values, phase settings, and any tool warnings in the proof packet
- do not authorize hardware flashing on PLL changes until synthesis accepts the wrapper cleanly

## Current Task 15 Design Heuristics

Until contradicted by repo-local proof:
- prefer reusing the existing third-party `sdram.v` controller over rewriting a fresh controller
- prefer a dedicated SDRAM clock domain over pretending the fetch path can stay fully in the pixel domain
- prefer burst-oriented reads using the controller's wide read path over byte-at-a-time fetch sequencing
- prefer a proper async FIFO or a bounded double-buffer handoff over a cross-domain shared `Mem`
- prefer external refresh scheduling for the reused `sdram.v` controller, because Task 15 research and implementation evidence showed the controller expects refresh requests from outside
- prefer tool-validated PLL settings over doc-only PLL math when Gowin gives direct parameter diagnostics

These are heuristics, not proof. Simulation and hardware evidence win.

## Repo-Local Validation Notes

### GT-022: Non-power-of-two `Mem` depth → BSRAM address-decode corruption

- **Status**: REPRODUCED in `spinalhdlVDP` (Task 15 Sequence 2 red-band failure, mail thread `6632` → `6645`, 2026-04-12).
- **Affected files**: `hw/spinal/spinalhdlvdp/BasicPatternSource.scala` (`tileMap`: `MapTilesX=40 × MapTilesY=30 = 1200` × 3-bit), and by extension `SdramTileFetch.scala` `tileMapRom` (same 1200-byte init).
- **Symptom**: Stable horizontal band at y ≈ 160-319 on real silicon; reads in that band returned a stuck tile index (tile 0) across all 40 screen columns. Pattern-dependent — visible only when the Mem content varied by address. Sim passed cleanly (behavioural Mem), so the bug had no pre-hardware signal.
- **Root cause**: Gowin's BSRAM inference for a 1200-deep `readAsync` Mem mis-decodes addresses that cross the 1024-entry internal primitive boundary. Entries 400..799 returned 0 regardless of init data.
- **Diagnostic that isolated the fault**: constant-tile probe (`tile = 7`) produced uniform output across the whole display → init-load is fine; address-decode is the problem. Earlier probes (SDRAM address shift, ping-pong buffering, fetchStart CDC hardening) did nothing because they targeted the wrong subsystem.
- **Fix**: pad `tileMapInit` / `tileMapBytesInit` to a power-of-two depth (2048). Address math unchanged. Unused 848 entries zero-filled; never addressed at runtime. With 2048 entries the Mem maps cleanly into a single Gowin BSRAM primitive.
- **Working rule**: if adding a `Mem(T, initialContent = seq)` in this repo with `seq.length` not a power of two, pad to the next power of two (or explicitly split into power-of-two Mems). Treat any non-power-of-two `readAsync` Mem as suspect until proven clean on hardware.
- **Primary doc**: `kb/gowin/UG285-1.4E_Gowin BSRAM & SSRAM User Guide.pdf`.

### GT-023: Mem→FF promotion is a cascading fragility class on GW2AR-18

- **Status**: REPRODUCED in `spinalhdlVDP` (Mode2optimized session, 2026-05-17).
- **Affected files**: `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala`, `hw/spinal/spinalhdlvdp/VdpTop.scala`, `hw/spinal/spinalhdlvdp/Copper.scala`, `hw/spinal/spinalhdlvdp/LinestateStore.scala`.
- **Symptom**: a small structural edit near a Mem read path can flip Gowin from BSRAM/SSRAM inference to distributed-DFF replication and add thousands of DFFs quickly.
- **Repo-local note**: Gate #1 on `mode2optimized-gate1-rastertrigs` first hit `activeListMem` with `+5485 DFFs`; Gate #2 then retriggered the same fragility class on a different set of Mems.
- **Fix / working rule**: treat Mem inference as a global sensitivity, not a local one. When a read-port topology changes, re-check the entire data path for new DFF promotion before assuming the edit is harmless.
- **Reverse-cascade observation** (2026-05-17, `mode2optimized-gate2-enableL2L3 @ 22afb90`): the cascade also works in the FAVORABLE direction. Converting a small number of large `readAsync` Mems to `readSync`+BSRAM frees enough Gowin SSRAM allocator slots that other previously-DFF-promoted Mems get re-inferred BACK into SSRAM. The session measured a single 2-Mem BSRAM conversion (`SpriteRasterizer.slbA`/`slbB`, 640×16 each via the qa.md A-001 lookahead pattern) that produced **-11518 DFFs net** against the ~80 SSRAM cells directly freed — because SSRAM utilization went UP (408→561 cells) as previously-stranded Mems settled into their correct primitive class. **Practical implication:** when stuck on Mem→FF cascade, target the largest single readAsync Mem first; the indirect savings often dwarf the direct ones.

### GT-024: `setTechnology(ramBlock)` is metadata-only

- **Status**: REPRODUCED in `spinalhdlVDP` (Mode2optimized probe, 2026-05-17).
- **Affected files**: `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala`, and any other Mem that expects Gowin BSRAM inference.
- **Symptom**: `mem.setTechnology(ramBlock)` does not produce a Gowin Verilog attribute and does not move the Mem into block RAM by itself.
- **Repo-local note**: the working fix was to use `mem.addAttribute("ram_style", "block")` on the generated Verilog side, not `setTechnology(...)` alone.
- **Fix / working rule**: do not rely on `setTechnology(ramBlock)` as a Gowin hint; it is a Spinal-internal preference only.

### GT-025: `ram_style="block"` is ignored on `readAsync` Mems

- **Status**: REPRODUCED in `spinalhdlVDP` (Mode2optimized probe, 2026-05-17).
- **Affected files**: `hw/spinal/spinalhdlvdp/LinestateStore.scala`, `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala`, any render-path Mem with combinational consumers.
- **Symptom**: `ram_style="block"` alone does not force a `readAsync` Mem into BSRAM on Gowin.
- **Repo-local note**: the prepare-side LinestateStore conversion only worked after changing the read to `readSync`; the commit-side render-path Mem stayed `readAsync` because the extra cycle caused a visible stale-pixel artifact.
- **Fix / working rule**: BSRAM conversion on Gowin needs both `readSync` and `ram_style="block"`. If the consumer is per-pixel or otherwise cycle-sensitive, compensate with an early-issue pipeline or leave it async.

### GT-026: Tang Nano fit is not predicted by oversize-device synth

- **Status**: REPRODUCED in `spinalhdlVDP` (Mode2optimized session, 2026-05-17).
- **Affected files**: `hw/spinal/spinalhdlvdp/MemReport.scala`, `fpga/tang20k/build_synthonly.tcl`, Mode2optimized gate branches.
- **Symptom**: GW2A-LV55 / oversize-device synth can look healthy while the Tang Nano still fails with DFF promotion or placement pressure.
- **Repo-local note**: the larger device absorbed Mems that overflowed on Tang Nano, so the same netlist did not map equivalently across targets.
- **Fix / working rule**: use oversize-device synth only as a relative diagnostic. Always validate the actual target device before treating a Mem fit as proven.

### GT-027: `simPublic()` has zero synthesis impact

- **Status**: REPRODUCED in `spinalhdlVDP` (Mode2optimized session, 2026-05-17).
- **Affected files**: `hw/spinal/spinalhdlvdp/VdpTop.scala`, `hw/spinal/spinalhdlvdp/SpriteEvaluator.scala`, `hw/spinal/spinalhdlvdp/MemReport.scala`.
- **Symptom**: marking a signal or Mem `simPublic()` helps simulation visibility but does not change synthesis resource usage.
- **Repo-local note**: several signals were made `simPublic()` during the mode2optimized work; the synthesis deltas came from topology changes, not from the visibility tags.
- **Fix / working rule**: use `simPublic()` only for test visibility. Do not expect it to help fit or timing.

### GT-028: Gowin already CSEs repeated comparisons

- **Status**: REPRODUCED in `spinalhdlVDP` (Mode2optimized session, 2026-05-17).
- **Affected files**: `hw/spinal/spinalhdlvdp/VdpTop.scala`, `hw/spinal/spinalhdlvdp/MemReport.scala`.
- **Symptom**: manually precomputing repeated equality checks such as `hCounter === 0` does not materially reduce LUT usage in the Tang Nano synth.
- **Repo-local note**: the revised architecture note flagged repeated-comparison hoists as a zero-gain optimization after direct measurement.
- **Fix / working rule**: prefer clarity over manual common-subexpression hoists unless a specific tool report shows a real gain.

### GT-029: Render-path per-pixel reads need latency compensation

- **Status**: REPRODUCED in `spinalhdlVDP` (Mode2optimized session, 2026-05-17).
- **Affected files**: `hw/spinal/spinalhdlvdp/LinestateStore.scala`, `hw/spinal/spinalhdlvdp/SpriteRasterizer.scala`, `hw/spinal/spinalhdlvdp/UnifiedRegMapSim.scala`.
- **Symptom**: converting a per-pixel render-path Mem to `readSync` without a pipeline or lookahead causes a visible stale first-pixel artifact.
- **Repo-local note**: the commit-side LinestateStore Mem could not be converted because `UnifiedRegMapSim` caught the stale-data issue immediately.
- **Fix / working rule**: only convert render-path Mems to sync read if you can absorb the 1-cycle latency with early-issue addressing or downstream pipeline staging.

When future gotchas are reproduced here, append:
- affected task
- affected file
- one-line symptom
- one-line fix
