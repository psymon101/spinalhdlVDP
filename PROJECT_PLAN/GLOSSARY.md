# GLOSSARY.md

**Purpose:** Defines the precise meaning of terms used throughout this project. Agents must use these definitions and must not reinterpret these terms based on general knowledge. Where this glossary conflicts with general usage, this glossary wins.

---

## Core Architecture Terms

### Mode0

The foundational rendering substrate of this VDP. Mode0 is not a clone of any specific historical machine. It provides the raw hardware-level rendering capabilities — sprites, tiled backgrounds, planar bitmaps, scrolling, per-line state, palette lookup, priority, affine transforms, color math — that are common across many classic video systems. All platform adapter modes are built on top of Mode0, not beside it. Mode0 is always the execution layer.

Mode0 is intended to be the superset hardware substrate that exposes the timing, state, fetch, composition, and control primitives that target platforms need. It should grow until it can supply the building blocks required by supported platform adapters. Platform-specific semantics do not belong inside Mode0 unless they are truly generic primitives.

### Platform Adapter Mode (or: higher mode)

A control surface built on top of Mode0 that presents a programming model oriented toward a specific historical platform (e.g. ZX Spectrum, Commodore 64, Amiga, SNES). A platform adapter mode translates the expected behavior of its target platform into Mode0 operations. It does not bypass Mode0. Adapter modes are not implemented until the relevant Mode0 primitive is proven on hardware.

A platform adapter owns platform-specific registers, command semantics, and behavioral quirks. For example, an Amiga-oriented adapter may model Copper-visible registers and Copper-style line effects, but it should do so by driving Mode0 primitives such as raster position, linestate commit, fetch scheduling, layer enable changes, palette updates, and composition control. The adapter defines the platform semantics; Mode0 supplies the machinery.

### Host Controller

The external CPU or MCU that programs the VDP. The host controller is responsible for application logic, user input handling, register writes, status reads, interrupt/event response, and asset upload into host-visible memory surfaces. It is not responsible for per-pixel rendering, composition, beam timing, or display processing.

The host controller may decide **what** the VDP should display and may upload the assets or descriptors needed to do so, but the VDP decides **how** pixels are fetched, composed, timed, and emitted. If a proposed feature requires the host controller to perform display processing that should instead be expressed as VDP-side rendering behavior, the proposal is architecturally suspect and should be challenged.

### Linestate

A per-scanline state record. The linestate model defines the set of rendering parameters that are valid for a single scanline: scroll positions, layer enables, palette bank, fetch addresses, per-line raster effect parameters, etc. Linestate records are prepared ahead of the scanline they govern, committed atomically at the line boundary, and consumed by the render pipeline during that line's active period. The linestate model is the primary mechanism for per-line raster effects.

### Commit (linestate commit)

The atomic application of a prepared linestate record to the active rendering pipeline at a scanline boundary. A commit makes the prepared state live for the upcoming line. Nothing in a linestate record takes effect mid-line — it applies only at the next commit point. This is a hard architectural rule.

### Fetch Engine

A hardware unit responsible for reading pixel or tile data from external memory (SDRAM) and delivering it to the render pipeline on schedule. Multiple fetch engine types exist for different data layouts: tile fetch, planar fetch, shuffled fetch, sprite fetch. Fetch engines operate under the SDRAM arbiter and must complete their work within the memory bandwidth budget for the current scanline.

### Sprite Engine

The hardware pipeline responsible for evaluating which sprites are active on a given scanline, fetching their pixel data, and delivering them to the compositor with correct priority and transparency flags.

### Compositor

The final stage of the render pipeline. Receives pixel contributions from all active layers (backgrounds, sprites) and resolves them into a single output pixel per clock using priority rules, transparency flags, and color math configuration.

### Line Buffer

A scanline-width pixel store used to decouple rendering (which may be done one line ahead) from output (which is strictly timed to the video signal). Typically implemented as a double-buffered pair: one buffer fills while the other is being read out.

### Palette

A lookup table that maps an index value (produced by the render pipeline) to an RGB color value for output. The palette may be banked or dynamically updated. Palette animation is implemented by modifying palette entries between frames or between lines under linestate control.

### Planar Fetch

A fetch mode where pixel data is stored as multiple separate bitplanes, each contributing one bit per pixel to the final color index. Used for Amiga/Atari ST-class compatibility modes.

### Shuffled Fetch

A fetch mode where pixel and attribute data are stored in a non-linear memory layout. Used for ZX Spectrum-style compatibility modes where character attributes and pixel data are stored in separate non-contiguous regions.

### SDRAM Arbiter

The hardware unit in the Tang20K wrapper that manages access to external SDRAM. Multiple fetch engines may request access concurrently; the arbiter serializes these requests according to priority and timing constraints. The arbiter is board-specific and lives in the Tang20K wrapper only.

### Scanline Sequencer

The hardware controller that drives the render pipeline through the phases of each scanline: fetch window, sprite evaluation, pixel output, horizontal blank, linestate commit. The sequencer is the timing backbone of the render pipeline.

### QSPI

Quad SPI — the external host interface used to program and control the VDP from a host microcontroller (currently ESP32-P4 on Tang Nano 20K). The active RTL front-end consists of `QspiSlave` (SPI-clock-synchronous or oversampled slave), `QspiDecoder` (command decode and `READ_STATUS` response FSM), and `QspiSdramBridge` (SDRAM write command buffering) in `hw/spinal/spinalhdlvdp/`. QSPI is the current canonical host path; i80 and legacy SPI are retired to historical reference.

### Raster Effect

A visual effect produced by changing rendering parameters mid-frame, at scanline boundaries, under linestate control. Examples: horizontal scroll variation per line (wavy effect), palette cycling, mid-screen layer enable/disable. All raster effects are driven by the linestate model.

### Affine Layer

A background layer rendered using affine (linear) transformation — rotation, scaling, shearing. Implemented as a matrix-stepped coordinate generator that maps screen positions to texture coordinates. Affine support is a Mode0 primitive added in Phase 6, after baseline fetch and composition stability is proven.

### Color Math

Post-compositor operations applied to the output pixel before palette lookup or after RGB generation. Examples: additive blending, alpha blending, subtractive blending, windowing (applying effects only within a screen region). Color math is a Mode0 primitive added in Phase 6.

### Vertical Slice

The development practice of implementing only the minimal set of logic required to prove the current validation scenario, then stopping. No future-phase logic is built during a vertical slice. Each slice must pass simulation and hardware validation before the next begins.

### Validation Scenario

A defined hardware test with explicit pass/fail criteria used to prove that a specific subsystem or feature is correct. No subsystem is considered complete until its validation scenario passes on hardware. Scenarios are documented in `doc/scenarios/`.

---

## Video Timing Terms

### H-Active / V-Active

The visible portion of the video frame. H-Active is the number of visible pixels per line. V-Active is the number of visible lines per frame.

### HBlank / VBlank

The horizontal and vertical blanking intervals. No pixel data is output during blanking. Fetch, sprite evaluation, linestate commit, and SDRAM refresh activity are scheduled to occur during blanking intervals.

### HSync / VSync

Synchronization pulses embedded in the video signal (or carried as separate signals in DVI/HDMI) that tell the display where lines and frames begin.

### TMDS

Transition-Minimized Differential Signaling — the physical encoding used by DVI and HDMI. TMDS encoding is performed in the Tang20K wrapper using board-specific primitives. Shared RTL has no knowledge of TMDS — it produces parallel RGB pixels only.

---

## Terms Agents Must Not Redefine

The following terms have precise meanings in this project. Do not generalize or reinterpret them:

| Term | Do not confuse with |
|------|-------------------|
| Mode0 | Any specific historical machine's video mode (e.g. "Mode 0" on ZX Spectrum or CGA) |
| Linestate | A generic configuration register |
| Commit | A version control commit |
| Fetch engine | A generic memory controller |
| Compositor | A software compositing system |
| Platform adapter mode | A port or emulator |
