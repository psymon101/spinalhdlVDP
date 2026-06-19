# CONVENTIONS.md

**Purpose:** Coding and design rules for the current `spinalhdlVDP` repository. These conventions are anchored to the implementation that exists today, not to a future package or directory refactor.

---

## Architectural Principles

### RTL Agnosticism (Mandatory 2026-05-24)

- **Rule:** The VDP RTL must remain purely generic graphics IP.
- **No Adapters:** Do not implement platform-specific register shims (C64, ZX, etc.) in the RTL tree.
- **No Hardcoding:** Do not hardcode platform-specific palettes, Copper programs, or scenario branches in production bitstreams.
- **Personality Location:** All platform "personality" (register translation, initialization sequences, asset uploads) belongs in `libvdp` or host-side firmware.
- **Exception:** Test-only scenarios may exist in archived commits but must not pollute the main generic bitstream.

### Transport Canary Mandate (#10670 / #10681)

- **Rule:** The v1 transport canary must remain in production. It is a 16×16 bright-cyan block at FPGA-active coordinates `x ∈ [624, 639]`, `y ∈ [464, 479]`, gated only on `video.io.de`, muxed at the final RGB stage in `TopTang20kHdmi.scala` immediately before `hdmiCleanStart`.
- **Independence:** The canary must not depend on `scenarioId`, `BITMAP_CTRL`, palette, SDRAM, or any scene-specific path.
- **Removal or modification:** Any RTL change that gates, removes, or alters the canary path must be flagged explicitly in the PM packet.
- **Hardware proof evidence (mandatory tuple):**
  - bitstream sha1
  - capture artifact path (PNG / MP4 under `fpga/tang20k/captures/`)
  - OpenCV-derived numeric (`cyan_fraction` at probe coords `[1872, 1044, 1920, 1080]` for 1920×1080 captures, threshold `> 0.50`)
- **Single-build claims:** A single-build capture is a status update, not proof. Any RTL change that touches BSRAM utilization, `readAsync` Mems, or the compositor critical path requires a 3-build determinism panel per the #10671 protocol (clean `impl/gwsynthesis` + `impl/pnr` between builds, physical power-cycle between flashes, 3/3 PASS criterion).
- **Reference:** Full capture-path hardening guide at `PROJECT_PLAN/CAPTURE.md`; transport-gate classifier at `scripts/regression/check_transport.py`.

---

## Language and Toolchain

- **HDL authoring:** SpinalHDL / Scala
- **Generated output:** Verilog in `hw/gen/`
- **Target board:** Sipeed Tang Nano 20K (`GW2AR-18`)
- **Synthesis / P&R:** Gowin IDE CLI
- **Programming:** `openFPGALoader`
- **Simulation:** SpinalHDL simulation with waveform output

Do not write hand-authored Verilog or VHDL for shared rendering logic. Hand-written Verilog is only acceptable for board transport and vendor primitive boundaries already isolated in `fpga/tang20k/` or clearly board-specific black-box wrappers.

---

## Current Package and Layout Rule

The current package prefix is:

```scala
package spinalhdlvdp
```

Do not rename the package to `vdp` or move the source tree into a new hierarchy unless an explicit refactor task is opened. The current source of truth is:

- SpinalHDL source: `hw/spinal/spinalhdlvdp/`
- generated Verilog: `hw/gen/`
- board flow: `fpga/tang20k/`

---

## SpinalHDL Style

### Component vs BlackBox

- Use `Component` for shared RTL blocks.
- Use `BlackBox` only for vendor primitives or board-specific transport boundaries.
- Do not use Chisel `Module` idioms.

### IO Bundle

- Put all ports in `val io = new Bundle { ... }`.
- Do not define ports outside `io` on `Component` types.
- Keep the bundle name as `io`.

### Combinational vs Sequential

- Use `val` for combinational expressions.
- Use `Reg`, `RegNext`, or explicit init values for state.
- Avoid implicit latches or partially-assigned sequential logic.

### Conditional Logic

- Use `when / elsewhen / otherwise` for conditional behavior.
- Use `switch / is` when the logic is genuinely decode- or state-oriented.

---

## Naming Conventions

### Files and Classes

| Item | Convention | Example |
|------|------------|---------|
| source file | `PascalCase.scala` | `VdpTop.scala` |
| component / black box | `PascalCase` | `TopTang20kHdmi` |
| simulation entry | `PascalCaseSim.scala` | `VdpTopSim.scala` |

### Signals

| Signal type | Convention | Example |
|-------------|------------|---------|
| general signal | `camelCase` | `activeVideo` |
| clock | `camelCase` with `clk` prefix or meaningfully named clock signal | `clk_pixel`, `clk_pixel_x5` |
| reset | `camelCase` with reset meaning | `pixelReset`, `reset` |
| boolean enable / valid | `camelCase` | `activeVideo`, `markerPixel` |
| counters / positions | `camelCase` | `hCounter`, `vCounter` |

### Constants

Use `val` with `camelCase` or `UPPER_SNAKE_CASE` consistently within a file. Do not mix styles arbitrarily in the same block. Existing code currently uses `hActive`, `vActive`, and similar names; follow the local style of the file you are editing unless a broader cleanup task exists.

---

## Clock and Reset Rules

### Current Validated Slice

The current hardware-proven design has:

- a board input clock at 27 MHz
- a PLL-derived serializer clock
- a divided pixel clock used for raster logic
- a pixel-domain render path

There is **not yet** a validated separate `clkSys` control domain in active use in this repository state.

### Rules

- Define board clocks and resets only at the board-facing top level.
- Do not instantiate PLLs inside reusable shared logic.
- Do not add silent clock-domain crossings.
- If a future task introduces a second live domain, document the crossing explicitly in code and in the relevant task evidence.

### Reset

- Shared RTL should prefer explicit reset values on stateful registers.
- Board-level primitive interaction may require wrapper-local reset handling.
- Do not introduce asynchronous resets in shared logic unless the board primitive forces that boundary.

---

## Simulation Conventions

- Every non-trivial shared RTL component should have a corresponding simulation entry point.
- Simulation objects should be named `<ComponentName>Sim`.
- In the current repo, simulation files may live next to their source until a dedicated structure-refactor task says otherwise.
- Simulations must emit a waveform (`FST` is already enabled through `Config.sim`).
- Simulation does not replace hardware validation.

---

## Board-Specific Boundary Rule

The following belong in board-specific files, not in shared rendering logic:

- PLL details
- serializer primitives
- LVDS / TMDS physical transport
- pin names and constraints
- programmer-specific commands

The current board-specific boundary is split between:

- `TopTang20kHdmi.scala`
- `GowinPrimitives.scala`
- `Tang20kHdmiTx.scala`
- `fpga/tang20k/*.sv`
- `fpga/tang20k/*.cst`
- `fpga/tang20k/*.sdc`

---

## Platform Adapter Fidelity Standards

Per-platform fidelity rules moved to `kb/<Adapter>/README.md` (2026-05-10).

### Visual Fidelity Rule

- **Primary Goal:** Visible output equivalence.
- **Substrate Use:** Shared `Mode0` capabilities may exceed original platform limits.
- **Fidelity Focus:** Palette/DAC behavior, borders, raster splits, and layering.
- **Guidance:** See `PROJECT_PLAN/archive/design_notes/PLATFORM_ADAPTERS.md` §Visual Fidelity Policy.

---

## What Not To Do

- Do not invent a new package tree.
- Do not move source files into a hypothetical future structure unless the task explicitly requires it.
- Do not add future-phase features while working a current task.
- Do not hardcode board facts inside shared logic when the value belongs in `PLATFORM.md` or the Tang20k wrapper.
- Do not bypass the current known-good output path without keeping a comparison baseline.
- Do not use generic RGB approximations or fallback fonts in platform adapters.
