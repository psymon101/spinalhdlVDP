# CONVENTIONS.md

**Purpose:** Coding and design rules for the current `spinalhdlVDP` repository. These conventions are anchored to the implementation that exists today, not to a future package or directory refactor.

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

The following standards apply to all platform adapters (starting with Task 40). They are not optional enhancements; they are foundational to the definition of a "platform adapter" in this project.

### 0. General Capability Rule

Platform adapters must translate platform behavior onto the shared `Mode0` substrate. They must not create separate platform-specific render engines when a general `Mode0` primitive can serve multiple adapters honestly.

Required interpretation:

- `Mode0` owns the reusable superset capability.
- adapters own platform-specific register semantics, limits, quirks, and presentation choices.
- an adapter may clamp or subset a richer `Mode0` primitive to match its target platform.
- a more demanding adapter may use more of the same primitive's range without requiring a second engine.

Examples:

- a stronger Amiga-facing sprite model and a more limited C64-facing sprite model should still sit on the same generic `Mode0` sprite machinery if the underlying primitive is shareable
- a blitter/DMA-style primitive belongs in `Mode0` if multiple platforms can use it, even if they expose different platform-facing command semantics
- Copper/HDMA/H-int style control should reuse beam-driven automation primitives rather than creating per-platform timing engines

Do not:

- add a C64-only, Amiga-only, or SNES-only hardware engine inside `Mode0` when the need is actually a shared primitive with different adapter limits
- weaken a shared primitive just to match the least demanding platform
- bypass a reusable `Mode0` primitive from an adapter merely to preserve platform naming

### 1. Circuitry-Accurate Palettes

Palettes MUST NOT use generic RGB approximations. They must be derived from hardware-level circuitry analysis, specifically considering:

- **DAC resistor values** and output network characteristics of the target system.
- System-specific voltage levels and color-space mappings (e.g., YPbPr, S-Video nuances).
- Measurement-based references (e.g., for C64, using Pepto's palette or similar circuitry-aware models).

Adapter implementations must document the palette source and any assumptions in the artifact or code comments.

### 2. Native Platform Fonts

Every adapter must include the **default system font/ROM** (e.g., C64 character ROM) as its baseline text/tile asset to ensure authentic presentation. Fallback generated fonts are not acceptable for adapter proof.

Font ROMs should be stored as `Mem` initial content or Scala `Seq[Bits]` constants in the adapter source file or a dedicated `assets/` directory.

### 3. Display Nuances

Implementations must consider and document platform-specific display nuances:

- Border/background relationships (e.g., C64 $D020/$D021 interaction).
- Aspect ratio considerations if the adapter/composition level can influence them.
- Signal-level artifacts that are visually characteristic of the target platform.

### 4. Gap Analysis

Adapters must include an explicit "honest gap analysis" section listing:

- Features that are emulated.
- Features that are deliberately omitted and why.
- Features that are architecturally impossible on the current Mode0 substrate.

This prevents scope creep while maintaining honest claims about adapter fidelity.

---

## What Not To Do

- Do not invent a new package tree.
- Do not move source files into a hypothetical future structure unless the task explicitly requires it.
- Do not add future-phase features while working a current task.
- Do not hardcode board facts inside shared logic when the value belongs in `PLATFORM.md` or the Tang20k wrapper.
- Do not bypass the current known-good output path without keeping a comparison baseline.
- Do not use generic RGB approximations or fallback fonts in platform adapters.
