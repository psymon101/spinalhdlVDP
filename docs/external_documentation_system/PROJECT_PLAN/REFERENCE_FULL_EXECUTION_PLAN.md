# Reference Full Execution Plan

> This file preserves the previously generated all-in-one plan. It is a
> reference source, not the current navigation authority. The modular
> documents in this package own their respective subjects.

# spinalhdlVDP Full Project Execution Plan

**Project:** Universal host-independent retro video display processor  
**FPGA platform:** Tang Nano 20K  
**FPGA implementation language:** SpinalHDL / Scala  
**Host library:** `libvdp`  
**Primary host reference:** Must be confirmed and locked during Foundation Gate 0  
**Plan date:** 2026-07-25

> **Reproducibility status:** This document defines the complete engineering and
> governance process. A release is not reproducible until the exact values required
> by **Part VI — Reproducible Product Package** are populated, committed, and
> validated by a clean-room build. Placeholders, undocumented local settings, and
> verbal knowledge are release blockers.

---

## 1. Project Goal

Build one FPGA-based video coprocessor that any MCU, CPU, SBC, or custom computer can use through `libvdp` to render graphics using either:

1. the native generic Mode0 graphics model; or
2. a platform-specific visual adapter that reproduces the visible behavior, formats, limits, and raster effects of a historical video chipset.

The FPGA emulates **video hardware only**. It does not emulate the platform CPU, operating system, storage controller, audio subsystem, or complete computer/console.

The host supplies graphics memory, registers, commands, and optional beam-timed event programs. The FPGA owns deterministic fetch, decode, composition, scaling, and HDMI scanout.

### In scope

- Generic Mode0
- ZX Spectrum
- TMS9918A family
- Sega Master System
- Game Gear
- NES/Famicom
- Commodore 64 VIC-II visual profile
- Atari ST/STE visual profile
- Amiga OCS/ECS visual profile
- Sega Mega Drive/Genesis
- SNES modes 0–3 visual subset, with optional Mode 7 later
- Atari 2600 TIA visual profile

### Explicitly out of scope for this roadmap

- Full-machine CPU emulation in the FPGA
- Amiga AGA
- Cycle-exact shared-bus contention between CPU and video hardware
- Neo Geo as a full adapter target
- Complete SNES interlace and every undocumented hardware quirk
- Complete Atari ST, Amiga, C64, NES, or other machine cores

---

## 2. Architectural Rules

### 2.1 Source-of-truth order

When two artifacts disagree, use this order until the discrepancy is formally resolved:

1. Approved platform and Mode0 specification
2. SpinalHDL source
3. SpinalSim regression behavior
4. Register schema
5. `libvdp` public headers and implementations
6. Generated Verilog
7. Firmware examples
8. Captures and screenshots

Generated Verilog is a build artifact. Team members must not make permanent behavior changes by editing generated RTL directly.

### 2.2 SpinalHDL rule

All FPGA features must be implemented in Scala using SpinalHDL components, bundles, clocking areas, and simulation tests.

Every new hardware feature requires:

- a bounded SpinalHDL component or a clearly documented modification to an existing component;
- a SpinalSim unit test;
- a VdpTop integration test;
- generated Verilog regeneration;
- Gowin synthesis and timing review;
- Tang Nano 20K hardware proof.

### 2.3 Firmware rule

Reusable host behavior belongs in `libvdp`. Platform proof applications must remain thin wrappers.

The public layering is:

```text
Application
    ↓
Platform adapter API: vdp_zx_*, vdp_atarist_*, vdp_amiga_*, etc.
    ↓
Generic API: vdp_mode0_*, vdp_copper_*, vdp_status_*, vdp_upload_*
    ↓
Transport API: vdp_host_*, vdp_reg_*, vdp_sdram_*
    ↓
Transport backend: QSPI, i80, SPI, parallel, MMIO, or future bus
    ↓
FPGA
```

### 2.4 Platform-adapter rule

A platform adapter may:

- translate native platform registers into Mode0 registers;
- translate native memory layouts into shared fetch-engine configuration;
- enforce original platform limits;
- generate Copper, LINESTATE, or HDMA programs;
- expose platform-native palette helpers;
- add a small FPGA extension when generic Mode0 primitives cannot reproduce the visual behavior.

A platform adapter must not duplicate the whole compositor, scaler, palette RAM, host bridge, or HDMI output path.

### 2.5 One active RTL lane

Only one platform or shared-substrate RTL lane may modify `VdpTop.scala`, the SDRAM arbiter, common fetch engines, compositor, Copper, HDMA, or sprite substrate at a time.

Research, documentation, firmware-only work, and test-vector preparation may proceed in parallel, but common SpinalHDL integration is serialized.

---

## 3. Project State Model

Every work lane must have exactly one state:

1. **BACKLOG** — requested but not researched
2. **RESEARCH** — source material and visual requirements being collected
3. **SPEC REVIEW** — platform contract awaiting independent review
4. **DESIGN APPROVED** — register/memory/component design approved
5. **SPINALHDL IMPLEMENTATION** — Scala RTL being written
6. **SPINALSIM PASS** — unit and integration simulations green
7. **FIRMWARE IMPLEMENTATION** — `libvdp` and proof firmware being written
8. **SYNTHESIS PASS** — Verilog generated, Gowin synthesis and timing green
9. **HARDWARE PROOF** — authoritative board/host proof underway
10. **DOC/AUDIT** — independent code/spec/doc reconciliation
11. **CLOSED** — evidence packet accepted and regression added
12. **BLOCKED** — dependency, resource, or hardware issue prevents progress

No lane may skip a state. A lane may move backward when review finds a defect.

---

## 4. Definition of Done for Every Lane

A lane is complete only when all of these are true:

### Specification

- Platform scope and non-goals are explicit.
- Native registers, memory layouts, palette rules, sprites, scrolling, priority, borders, and raster effects are documented.
- The design identifies generic Mode0 reuse versus platform-specific FPGA logic.
- Register addresses and bit encodings are assigned or explicitly declared unnecessary.

### SpinalHDL

- Scala source compiles.
- No generated-Verilog-only edits exist.
- Unit SpinalSim passes all positive, boundary, reset, and negative cases.
- VdpTop integration regression passes.
- CDC, reset, pending/active register semantics, and memory arbitration are reviewed.

### Firmware

- `libvdp` contains the public API.
- No proof application hand-frames protocol packets that belong in `libvdp`.
- At least one reference application builds for the authoritative host.
- Platform-native asset conversion is documented or automated.
- Error and timeout behavior is tested.

### Build

- Generated Verilog is reproducible.
- Gowin synthesis completes without new critical warnings.
- Timing closes at the approved clocks.
- LUT, block RAM, DSP, PLL, and SDRAM bandwidth deltas are recorded.
- The bitstream hash and source commit are recorded.

### Hardware

- Firmware and bitstream artifact hashes are matched before testing.
- Transport health and upload status are clean.
- A monitor proof is captured.
- Capture-device output is considered secondary evidence.
- A minimum 10-minute static/animated soak passes; timing-sensitive lanes require a longer platform-specific soak.
- Reset, repeated mode switching, and cold boot are tested.

### Documentation and review

- Platform specification is current.
- Mode0 register documentation is current.
- `kb/libvdp/README.md` is current.
- Firmware build and flash instructions are current.
- An independent reviewer cross-checks spec, SpinalHDL, firmware, and proof packet.
- All new regressions are included in the standard test suite.

---

# PART I — FOUNDATION PROGRAM

## 5. Foundation Gate 0: Re-baseline the Current Repository

**This is the next project step. No new platform implementation begins before this gate closes.**

### 5.1 Freeze and identify the authoritative state

1. Freeze shared RTL and `libvdp` changes.
2. Record the current branch, commit, dirty files, generated-Verilog state, firmware commit, and flashed bitstream hash.
3. Identify the authoritative Tang Nano 20K top-level generator and constraint file.
4. Identify the authoritative host and transport used for acceptance testing.
5. Move historical hosts and experiments under clearly labeled archived/reference sections.
6. Create `PROJECT_PLAN/CURRENT_BASELINE.md` containing the locked artifacts and commands.

### 5.2 Resolve current contract conflicts

The team must produce one reconciliation PR covering:

- bitmap-format encoding;
- packed 1/2/4/8bpp target support versus current two-bit mode field;
- RGB565 and HAM6 allocation;
- planar plane count and plane-base behavior;
- Copper pixel-precise writes versus top-level write-drain gating;
- status and vblank behavior across QSPI and i80;
- upload-status clear semantics;
- active host selection;
- current authoritative QSPI clock limits and read/write rates;
- `mode0_regs.json`, SpinalHDL register decode, `vdp_mode0.h`, and documentation agreement.

### 5.3 Repair the build surfaces

- Make the Scala/SpinalHDL build command canonical and reproducible.
- Add one command to run all SpinalSim tests.
- Add one command to generate the production Verilog.
- Add one command to synthesize the Tang Nano 20K bitstream.
- Make `libvdp` CMake include all public implementation files.
- Add the authoritative host build system, including an ESP-IDF component path when ESP32-P4 remains canonical.
- Narrow `architectures=*` or prove every advertised platform build.

### 5.4 Gate 0 tests

- Full existing SpinalSim regression
- Clean generated-Verilog diff from a fresh checkout
- Clean Gowin synthesis
- Generic Mode0 hardware scene
- QSPI/i80 register write/read test as applicable
- SDRAM bulk upload/readback test
- Bitmap 1bpp, 2bpp, direct color, and any currently claimed additional modes
- Copper WAIT/WRITE/JUMP/SKIP test
- Sprite and collision test
- Reset and mode-select test

### Gate 0 exit criteria

- One baseline commit is named.
- One authoritative host and transport are named.
- One bitmap-format map is named.
- One planar-plane-count contract is named.
- One standard regression command is named.
- One standard bitstream build command is named.
- All documents and public headers agree.

---

## 6. Foundation Gate 1: Stabilize the Shared Mode0 Substrate

### 6.1 Required SpinalHDL component boundaries

The shared design should expose or converge toward these bounded components:

```text
VdpTop
├── HostInterface / register bridge
├── Mode0RegisterFile
├── SdramArbiter
├── BitmapLineFetch
├── PlanarLineFetch
├── TileMapFetch L0–L3
├── SpriteEvaluator / SpriteRasterizer
├── Copper
├── HDMA / LINESTATE
├── DmaEngine
├── BlitterEngine
├── PlatformAdapterMux
├── FourLayerCompositor
├── PaletteRam
├── WindowUnit / ColorMath
├── LogicalScaler
└── VideoTiming / HDMI output
```

Platform adapters should live under a dedicated Scala namespace, for example:

```text
hw/spinal/spinalhdlvdp/adapters/
    ZXSpectrumAdapter.scala
    Tms9918Adapter.scala
    SmsAdapter.scala
    NesAdapter.scala
    C64VicIIAdapter.scala
    AtariStAdapter.scala
    AmigaOcsAdapter.scala
    GenesisAdapter.scala
    SnesAdapter.scala
    Atari2600TiaAdapter.scala
```

### 6.2 Shared format target

The final generic bitmap target is:

- packed indexed 1bpp;
- packed indexed 2bpp;
- packed indexed 4bpp;
- indexed 8bpp;
- RGB565 direct color.

HAM6 is an Amiga compatibility decoder, not a generic packed BPP alias.

The team must expand or redesign the bitmap format register because a two-bit field cannot unambiguously encode all five generic formats plus special compatibility decoders.

### 6.3 Shared planar target

- One to six planes
- Independent plane bases
- Configurable line stride/modulo
- Configurable plane ordering
- Interleaved-word mode for Atari ST
- Independent-plane mode for Amiga
- Common output as palette index plus metadata

### 6.4 Shared timing automation target

- Copper WAIT(Y)
- Copper WAIT(X,Y)
- Register WRITE and WRITE_SEQ
- JUMP and SKIP
- Atomic inactive-bank program upload and vblank swap
- HDMA/LINESTATE per-line update table
- Late-event and underrun status

### 6.5 Shared simulation suite

The foundation suite must include:

- `Mode0RegisterFileSim`
- `BitmapFetchSim` for every format
- `PlanarLineFetchSim` for 1–6 planes and both memory layouts
- `SdramArbiterSim` with refresh and worst-case concurrent clients
- `FetchSlotSchedulerSim`
- `CopperSim`
- `HdmaSim`
- `SpriteEvaluatorSim`
- `SpriteSubstrateSim`
- `SpriteCollisionSim`
- `FourLayerCompositorSim`
- `WindowColorMathSim`
- `ModeSelectSim`
- `SoftResetSim`
- `VdpTopRegressionSim`
- continuous scanout plus host-write stress simulation

### Gate 1 exit criteria

- Generic substrate features are documented and stable.
- Platform adapters can be selected without replacing the common output pipeline.
- Resource budget leaves agreed headroom for platform adapters.
- Shared regressions are green before and after every adapter lane.

---

## 7. Foundation Gate 2: Make `libvdp` Truly Host-Independent

### 7.1 Public API layers

Keep the `vdp_` prefix. Do not introduce a second `retro_vdp_*` API.

Split implementation concerns into:

```text
libvdp/core/
    vdp_mode0.c
    vdp_copper.c
    vdp_status.c
    vdp_upload.c

libvdp/transports/
    vdp_transport_qspi_p4.c
    vdp_transport_i80_s3.c
    vdp_transport_pio_pico.c
    vdp_transport_spi_legacy.c
    future vdp_transport_mmio.c

libvdp/adapters/
    vdp_zx.c
    vdp_tms9918.c
    vdp_sms.c
    vdp_nes.c
    vdp_c64.c
    vdp_atarist.c
    vdp_amiga.c
    vdp_genesis.c
    vdp_snes.c
    vdp_atari2600.c
```

A transport operations structure is recommended so the generic library does not grow one large conditional implementation file.

### 7.2 Required common API additions

- ABI and capability query
- Adapter mask query
- SDRAM size query
- Transport feature query
- Atomic configuration commit
- Backend-independent vblank wait
- Backend-independent register read
- Upload completion and error status
- Explicit transport timeout handling
- Exact active bitstream ABI check during initialization

### 7.3 Host matrix

Every release records support as one of:

- authoritative;
- tested;
- builds only;
- archived;
- unsupported.

No metadata may claim universal architecture support unless the CI/build matrix proves it.

### Gate 2 exit criteria

- The authoritative host uses `libvdp`, not a standalone raw proof implementation.
- All active transports share the same public API semantics.
- Platform adapters compile against the same headers.
- Vblank and status behavior is transport neutral.

---

# PART II — STANDARD PLATFORM LANE

## 8. Required Workflow for Every Platform

### Step 1 — Research packet

Create or update `PROJECT_PLAN/platform_specs/<PLATFORM>_VIDEO_SPEC.md`.

It must cover:

1. visible resolutions and refresh families;
2. memory layout;
3. tile/bitmap/text modes;
4. palette format;
5. sprites;
6. scrolling;
7. priority;
8. borders/windows;
9. raster effects;
10. collisions/status;
11. exact versus visual-only behaviors;
12. Mode0 reuse;
13. required new FPGA logic;
14. resource risks;
15. verification references.

### Step 2 — Design checkpoint

Produce a design packet containing:

- SpinalHDL components touched;
- new bundles and signals;
- registers and memory map;
- reset and commit timing;
- CDC crossings;
- SDRAM clients and bandwidth;
- `libvdp` API;
- proof scene;
- simulation cases;
- acceptance criteria;
- deferred behavior.

No code begins before independent design approval.

### Step 3 — SpinalHDL unit implementation

Implement the smallest standalone adapter or extension first. Do not start with VdpTop integration.

### Step 4 — SpinalSim unit proof

Test:

- reset defaults;
- legal modes;
- boundary values;
- invalid writes;
- timing transitions;
- memory layout;
- color decode;
- priority;
- status and collision behavior;
- backward compatibility.

### Step 5 — VdpTop integration

Connect the adapter through `PlatformAdapterMux` or the agreed adapter control path. Run the complete shared regression.

### Step 6 — Register and documentation sync

Update in one change:

- register schema;
- Mode0 register bus specification;
- `vdp_mode0.h` or platform header;
- `kb/libvdp/README.md`;
- platform specification.

### Step 7 — Firmware adapter

Create `vdp_<platform>.h/.c` with platform-native helpers. Add conversion tools and one minimal proof application.

### Step 8 — Synthesis gate

Generate Verilog, synthesize, record resource/timing deltas, and confirm no critical warnings.

### Step 9 — Hardware proof

Use an authoritative firmware/bitstream pair. Capture:

- console logs;
- register/readback evidence;
- transport health;
- raw test assets and hashes;
- monitor photo or direct proof;
- optional capture-device output;
- soak results.

### Step 10 — Independent audit

A reviewer who did not write the primary implementation compares:

- platform spec;
- SpinalHDL;
- generated RTL interface;
- register schema;
- `libvdp`;
- proof firmware;
- evidence.

### Step 11 — Closeout

Merge only after the closeout packet names:

- commits;
- tests;
- bitstream hash;
- firmware hash;
- known limitations;
- regression names;
- next platform lane.

---

# PART III — PLATFORM ROADMAP

## 9. Platform Sequence

The implementation order is dependency-driven:

1. Generic Mode0 closure
2. ZX Spectrum closure
3. TMS9918A
4. Sega Master System and Game Gear
5. NES/Famicom
6. Commodore 64
7. Atari ST/STE
8. Amiga OCS/ECS
9. Mega Drive/Genesis
10. SNES modes 0–3-lite
11. Atari 2600 TIA

Atari 2600 research may run earlier, but its procedural scanline engine is a separate architecture and should not interrupt shared substrate work.

---

## 10. Generic Mode0

### Goal

Provide the stable, host-independent graphics card API used by all applications and adapters.

### FPGA/SpinalHDL work

- Reconcile bitmap formats.
- Finish packed 1/2/4/8bpp and RGB565.
- Finish one-to-six-plane planar engine.
- Stabilize four background layers.
- Stabilize 32-sprite-per-line ceiling and documented total descriptors.
- Stabilize Copper, HDMA, LINESTATE, windows, color math, DMA, and Blitter.
- Add capability registers and ABI version.
- Add late-event, upload, and underrun diagnostics.

### Firmware work

- Complete `libvdp` build and transport abstraction.
- Add capability query and ABI guard.
- Add structured configuration objects.
- Add asset tools for every generic format.
- Add atomic commit helpers.

### Test proof

- One scene for every bitmap format.
- Four-layer scene.
- 32-sprite stress scene.
- Copper raster bars.
- HDMA per-line scroll.
- Blitter fill/copy/line.
- repeated mode-switch and soft-reset soak.

### Exit criteria

No platform adapter depends on undocumented raw register writes.

---

## 11. ZX Spectrum

### Current direction

Treat the existing ZX v1 implementation as a lane requiring closure and re-baselining, not a new design.

### Visual target

- 256×192 1bpp bitmap
- 32×24 attribute cells
- ink, paper, bright, and flash
- border color
- Spectrum memory addressing/shuffle
- visible attribute clash

### FPGA/SpinalHDL work

- Re-run `ZXSpectrumAdapterSim` against the reconciled baseline.
- Confirm shuffled bitmap addressing in the production path.
- Confirm flash cadence and reset.
- Confirm border changes at approved timing boundaries.
- Add a direct attribute-clash regression.

### Firmware work

- Add `vdp_zx_init`, screen upload, attribute upload, border, flash, and present helpers.
- Add a Spectrum memory-layout converter.

### Required proof scene

One scene must intentionally place conflicting colored shapes inside the same 8×8 cell so attribute clash is undeniable.

### Exit criteria

- Existing v1 behavior is preserved.
- Attribute clash, flash, border, and shuffled addressing are independently proven.

---

## 12. TMS9918A Family

### Visual target

- Graphics I
- Graphics II
- Text mode
- Multicolor mode
- fixed 16-color palette
- hardware sprites and overflow/collision flags

### FPGA/SpinalHDL work

- Create `Tms9918Adapter.scala`.
- Map name, pattern, and color tables to shared tile/attribute fetch.
- Implement TMS sprite limits and status semantics.
- Reuse generic palette RAM with fixed palette preload.

### Firmware work

- Build `vdp_tms9918_*` helpers.
- Reuse the existing fixed-palette loader.
- Add table upload helpers matching native TMS table concepts.

### Tests

- All four display modes.
- sprite overflow and collision.
- table-base changes.
- transparent color behavior.

### Exit criteria

A host can program the FPGA using TMS-style name/pattern/color/sprite tables without constructing generic Mode0 descriptors manually.

---

## 13. Sega Master System and Game Gear

### Visual target

- 4bpp tile graphics
- scrollable background
- sprite layer
- tile priority and flips
- SMS CRAM palette
- Game Gear 12-bit palette and viewport

### FPGA/SpinalHDL work

- Create a shared Sega 8-bit adapter with SMS and GG flags.
- Implement native tilemap entry decode.
- Enforce sprite-per-line and overflow behavior.
- Implement top-row/right-column scrolling locks if included in the agreed visual scope.
- Implement Game Gear crop/window behavior.

### Firmware work

- `vdp_sms_*` and `vdp_gamegear_*` APIs.
- Reuse existing SMS and GG palette conversion helpers.
- Native VRAM/CRAM upload helpers.

### Tests

- tile priority and flips;
- scrolling;
- sprite limit and overflow;
- palette conversion;
- SMS full frame and GG viewport.

### Exit criteria

SMS and GG share one verified FPGA substrate while presenting separate native firmware APIs.

---

## 14. NES/Famicom

### Visual target

- 256×240/224 presentation
- 2bpp planar pattern tables
- nametables and attribute tables
- fine scrolling
- 64 sprites, 8 per scanline
- sprite 0 hit
- sprite/background priority

### FPGA/SpinalHDL work

- `NesAdapter.scala`.
- Native 2bpp planar tile decode.
- nametable and attribute quadrant decode.
- OAM translation into the shared sprite evaluator.
- hard 8-sprites-per-line visual limit.
- sprite 0 hit and overflow status.
- mirroring/scroll mapping at the adapter level.

### Firmware work

- PPU-style memory region helpers.
- CHR, nametable, attribute, palette, and OAM uploads.
- scroll and mask/control helpers.

### Tests

- 2bpp decode;
- attribute quadrants;
- all mirroring selections in scope;
- 8/9 sprite boundary;
- sprite 0 hit positive and negative cases;
- left-edge clipping and priority.

### Exit criteria

A host can create a visually NES-like screen by supplying PPU-formatted tables and OAM.

---

## 15. Commodore 64 VIC-II

### Visual target

- standard text
- multicolor text
- standard bitmap
- multicolor bitmap
- extended background color mode if retained
- 8 sprites
- raster-controlled changes
- border
- sprite collisions

### FPGA/SpinalHDL work

- `C64VicIIAdapter.scala`.
- character, screen, color, and bitmap memory mapping.
- multicolor decode.
- C64 sprite width/double-size/multicolor behavior.
- collision/status mapping.
- raster event hooks through Copper/HDMA.
- visual bad-line behavior only where it affects output; do not model CPU cycle stealing.

### Firmware work

- C64-native VIC register helpers.
- charset, screen RAM, color RAM, bitmap, and sprite uploads.
- raster program builder.

### Tests

- each display mode;
- multicolor bit grouping;
- border and raster color bars;
- eight sprites and multiplex-style Copper updates;
- sprite/sprite and sprite/background collision.

### Exit criteria

The visual result and register-facing model reproduce VIC-II display behavior without claiming CPU-bus timing accuracy.

---

## 16. Atari ST/STE

### Visual target

Phase 1:

- ST low: 320×200, four interleaved bitplanes, 16 colors
- RGB333 palette
- border and integer scaling
- raster palette changes

Phase 2:

- ST medium: 640×200, two planes
- ST high: 640×400, monochrome
- STE RGB444 palette
- selected STE scrolling/display extensions

### FPGA/SpinalHDL work

- `AtariStAdapter.scala`.
- Interleaved-word planar fetch mode.
- one, two, and four plane selection.
- correct bit significance and word order.
- border/display window.
- palette writes through Copper at approved X/Y precision.

### Firmware work

- `vdp_atarist_*` and optional `vdp_atariste_*` helpers.
- native 32 KB screen upload.
- packed-to-ST-planar conversion tool.
- reuse existing ST and STE palette helpers.

### Tests

- known 16-pixel plane-word vectors;
- low/medium/high modes;
- palette conversion;
- border;
- raster bars;
- double-buffer swap;
- full-screen and dirty-region upload.

### Exit criteria

A generic host can upload authentic ST screen data and visually reproduce all three standard ST display modes.

---

## 17. Amiga OCS/ECS — No AGA

### Visual target

Core:

- 1–6 independent bitplanes
- lores and hires
- 32-color palette
- dual playfield
- 8 OCS-style sprite channels
- attached sprites
- Copper beam-synchronous changes
- display and fetch windows
- odd/even modulo
- basic Blitter copy/fill/line

Extended:

- EHB
- HAM6
- selected ECS display positioning

Explicit exclusions:

- AGA
- HAM8
- 8 bitplanes
- AGA palette behavior
- AGA sprites/fetch modes
- cycle-exact Agnus DMA contention

### FPGA/SpinalHDL work

- `AmigaOcsAdapter.scala`.
- independent plane pointers and odd/even modulo.
- one-to-six-plane fetch.
- dual-playfield split and priority.
- Amiga sprite channel restrictions and attached-pair mode.
- DIW/DDF-like window mapping.
- Copper register mapping.
- EHB decoder.
- HAM6 stateful line decoder with correct line reset.
- basic Blitter mapping to shared Blitter engine.

### Firmware work

- `vdp_amiga_*` API.
- plane pointer, modulo, display/fetch window, color, sprite, and Copper helpers.
- native planar asset conversion.
- Copper list builder using existing opcode helpers.

### Tests

- 1 through 6 plane decode;
- independent plane-base proof;
- odd/even modulo;
- dual playfield transparency and priority;
- 8 sprites and attached pairs;
- Copper mid-line palette and scroll changes;
- EHB half-bright result;
- HAM6 direct and modify operations plus line reset;
- Blitter-generated proof image.

### Exit criteria

The FPGA can be used as an OCS/ECS-style visual chipset from any host while making no AGA or cycle-exact DMA claim.

---

## 18. Sega Mega Drive/Genesis

### Visual target

- Plane A
- Plane B
- Window plane
- 4bpp tiles
- per-tile priority and flips
- full, per-row, and selected per-line horizontal scroll
- full and column vertical scroll
- sprite system
- 64-entry CRAM
- shadow/highlight

### FPGA/SpinalHDL work

- `GenesisAdapter.scala`.
- native name-table entry decode.
- plane/window selection.
- horizontal and vertical scroll-table fetch.
- priority resolver matching the agreed visual model.
- sprite chain/table translation.
- shadow/highlight post-compositor operation.

### Firmware work

- VRAM, CRAM, VSRAM helpers.
- plane, window, sprite, and scroll configuration.
- palette conversion.

### Tests

- A/B/window priority combinations;
- per-tile priority;
- all supported scroll modes;
- sprite boundary and overflow;
- shadow/highlight;
- 320- and 256-wide output modes if retained.

### Exit criteria

The adapter reproduces the standard Genesis visual organization without emulating its complete FIFO timing or CPU interface.

---

## 19. SNES Modes 0–3-lite

### Visual target

Required first release:

- modes 0–3 only
- up to four backgrounds
- 2bpp, 4bpp, and 8bpp tile decode as required by selected modes
- 128 sprite descriptors and up to the approved 32 sprites per line
- windows and masks
- color math
- per-line HDMA-style changes
- mode-specific priority

Optional later release:

- Mode 7

Deferred:

- interlace
- every mosaic/offset-per-tile corner case
- full cycle-accurate PPU behavior

### FPGA/SpinalHDL work

- `SnesAdapter.scala`.
- mode configuration and layer BPP mapping.
- native tilemap decode.
- priority resolver.
- OAM-to-shared-sprite mapping.
- window/mask mapping.
- color math mapping.
- HDMA table builder/consumer mapping.
- optional Mode 7 through the shared affine engine after modes 0–3 close.

### Firmware work

- mode, BG, tilemap, palette, OAM, window, color-math, and HDMA helpers.
- native planar tile conversion.

### Tests

- each supported mode;
- four-layer priority;
- all tile depths;
- 32/33 sprite boundary and tile-budget overflow;
- window combinations;
- add/sub/half color math;
- per-line HDMA changes;
- optional Mode 7 affine scene.

### Exit criteria

Modes 0–3-lite close before any Mode 7 lane opens.

---

## 20. Atari 2600 TIA

### Visual target

- 20-bit playfield reflected or repeated
- two players
- two missiles
- ball
- color registers
- horizontal positioning and motion
- size/copy controls
- priority and collisions
- beam-synchronous register changes

### Architectural note

TIA is not naturally a framebuffer or tile adapter. It is a procedural scanline generator. It should be implemented as a dedicated frontend that feeds the common compositor/output pipeline.

### FPGA/SpinalHDL work

- `Atari2600TiaAdapter.scala`.
- scanline register state.
- playfield/player/missile/ball generators.
- horizontal motion and copy/size decode.
- priority and collision logic.
- Copper/command-list integration for timed writes.

### Firmware work

- TIA-register API.
- scanline command-list builder.
- optional helper that converts a simple image into playfield/player events.

### Tests

- playfield reflection/repetition;
- player copy/size modes;
- missile and ball;
- horizontal motion;
- priority modes;
- collision latches;
- timed mid-line register changes.

### Exit criteria

A host can construct a frame by submitting TIA-style timed register activity without streaming HDMI pixels.

---

# PART IV — TEST, REVIEW, AND RELEASE SYSTEM

## 21. Continuous Integration

Every pull request that touches FPGA, registers, or `libvdp` must run:

1. Scala formatting and compile
2. All SpinalSim unit tests
3. VdpTop regression
4. Verilog generation from a clean tree
5. generated-interface consistency check
6. firmware compile matrix
7. register-schema/header consistency generator or checker
8. documentation link and command validation

Nightly or release CI should additionally run:

- Gowin synthesis;
- timing and resource report diff;
- long randomized SDRAM/host-write simulation;
- adapter regression suite;
- firmware static analysis.

## 22. Hardware Proof Standard

Every hardware proof packet must contain:

```text
FPGA source commit:
Generated Verilog hash:
Bitstream hash:
Firmware source commit:
Firmware binary hash:
Board:
Host:
Transport:
Transport frequency:
Display/capture path:
Test asset hashes:
Cold boots:
Warm resets:
Mode switches:
Soak duration:
Transport status:
Known artifacts:
Result:
```

A capture-device artifact alone cannot fail or pass an RTL lane. Serial readback, transport status, repeat-frame hashes, and a direct monitor check take precedence.

## 23. Review Roles

Every lane names these owners before work starts:

- **Lane owner:** accountable for scope and completion
- **SpinalHDL implementer:** writes Scala RTL and simulations
- **Firmware implementer:** writes `libvdp` and proof application
- **Specification reviewer:** verifies historical/platform behavior
- **RTL reviewer:** checks SpinalHDL structure, timing, CDC, reset, and synthesis implications
- **Firmware reviewer:** checks API, transport, timeout, and build behavior
- **Hardware validator:** flashes and records evidence
- **Documentation auditor:** reconciles every public document and command
- **Project manager:** accepts checkpoints and opens the next lane

The same person may fill multiple roles, but the primary implementer may not be the only reviewer.

## 24. Change-Control Rules

- Register addresses are not assigned informally in source code.
- Any register change updates schema, SpinalHDL, firmware header, API documentation, and regression in one lane.
- Any behavior change touching a shared primitive reruns all closed platform regressions.
- Any transport timing change requires a dedicated hardware validation packet.
- Any generated Verilog change without a corresponding SpinalHDL change is rejected.
- Any proof without matched firmware and bitstream hashes is invalid.
- Deferred features remain documented as deferred and are not described as supported.

---

# PART V — IMMEDIATE EXECUTION QUEUE

## 25. Exact Next Steps

The team should execute the following in order:

### Task 1 — Open Foundation Gate 0

Create one lane named:

```text
FOUNDATION-0 — Baseline and Contract Reconciliation
```

### Task 2 — Produce current-state manifest

Record source, generated RTL, bitstream, firmware, board, host, transport, toolchain, and test commands.

### Task 3 — Resolve bitmap-format contract

Decide and document the final generic format encoding for 1/2/4/8bpp and RGB565. Move HAM6 to the Amiga compatibility path.

### Task 4 — Resolve planar contract

Name the supported plane count, independent bases, interleaved mode, modulo/stride, and common output representation.

### Task 5 — Resolve Copper timing contract

Make pixel-precise WAIT/WRITE behavior agree between Copper, VdpTop drain logic, simulations, firmware, and documentation.

### Task 6 — Resolve authoritative host and transport

Make firmware documentation, `libvdp`, build files, and hardware proof process name one current authoritative path.

### Task 7 — Repair `libvdp` builds

Include all public sources and add the authoritative host build integration.

### Task 8 — Run and lock the baseline regression

Publish the command, results, synthesis report, and bitstream hash.

### Task 9 — Close Generic Mode0 gaps

Complete shared format, planar, capability, status, and commit semantics.

### Task 10 — Close ZX Spectrum

Re-verify existing v1 and add the explicit attribute-clash hardware proof.

### Task 11 onward

Open platform lanes in this order:

```text
TMS9918A
SMS/Game Gear
NES
C64
Atari ST/STE
Amiga OCS/ECS
Mega Drive/Genesis
SNES modes 0–3-lite
Atari 2600 TIA
```

The next lane is always the first item in this list whose shared dependencies are CLOSED. A later platform may not bypass an earlier platform merely because its proof scene is easier.

---

## 26. Release Milestones

### Release 0.9 — Stable generic VDP

- Gate 0–2 complete
- Mode0 stable
- host-independent `libvdp`
- ZX closed

### Release 1.0 — 8-bit visual systems

- TMS9918A
- SMS/Game Gear
- NES
- C64

### Release 1.5 — 16-bit computer visuals

- Atari ST/STE
- Amiga OCS/ECS, no AGA

### Release 2.0 — 16-bit console visuals

- Mega Drive/Genesis
- SNES modes 0–3-lite

### Release 2.5 — Procedural raster profile

- Atari 2600 TIA

---

## 27. Final Project Rule

At every point, the team should be able to answer these questions from the repository without asking another person:

1. What is the active lane?
2. What state is it in?
3. What commit is authoritative?
4. What test must run next?
5. What evidence is required to move forward?
6. Who reviews it?
7. Which document must be updated?
8. What platform opens after it closes?

If any answer is missing, the lane is not ready to advance.

---

# PART VI — REPRODUCIBLE PRODUCT PACKAGE

## 28. Reproducibility Standard

The project is considered reproducible only when an independent team, starting
with a clean supported workstation and the released repository, can produce the
same functional product without contacting the original implementers.

There are two levels of reproducibility:

1. **Functional reproducibility**
   - The independently built FPGA bitstream and firmware pass the same acceptance
     tests and produce the same defined visual behavior.
   - Tool-generated binary hashes may differ only when the vendor toolchain is
     nondeterministic and the difference is documented and reviewed.

2. **Artifact reproducibility**
   - The independently built generated RTL, bitstream, firmware binary, generated
     headers, asset binaries, and test vectors match the published hashes.
   - This is the preferred release standard.

A release must explicitly state which level it achieves. It must not claim
artifact reproducibility when only functional equivalence has been demonstrated.

## 29. Mandatory Repository Layout

The release repository must contain, or clearly map to, the following logical
structure. Existing names may be retained, but the documentation must identify
the exact equivalents.

```text
/
├── README.md
├── LICENSE
├── CHANGELOG.md
├── RELEASE_MANIFEST.yaml
├── REPRODUCIBILITY.md
├── AGENTS.md
├── PROJECT_PLAN/
│   ├── CURRENT_BASELINE.md
│   ├── ACTIVE_LANE.md
│   ├── DECISIONS/
│   ├── platform_specs/
│   ├── test_plans/
│   ├── proof_packets/
│   └── release_checklists/
├── hw/
│   ├── spinal/
│   │   ├── build.sbt
│   │   ├── project/
│   │   ├── src/main/scala/
│   │   └── src/test/scala/
│   ├── generated/
│   ├── gowin/
│   │   ├── constraints/
│   │   ├── project/
│   │   └── scripts/
│   └── reports/
├── firmware/
│   ├── libvdp/
│   ├── reference_apps/
│   ├── transports/
│   └── platform_examples/
├── tools/
│   ├── asset_converters/
│   ├── register_generator/
│   ├── test_vector_generator/
│   └── capture_validation/
├── tests/
│   ├── golden/
│   ├── assets/
│   ├── expected/
│   ├── hardware/
│   └── clean_room/
├── docs/
│   ├── hardware/
│   ├── protocol/
│   ├── mode0/
│   ├── libvdp/
│   ├── platforms/
│   ├── build/
│   ├── test/
│   └── troubleshooting/
└── ci/
    ├── scripts/
    ├── containers/
    └── workflows/
```

Every top-level directory must have a short README explaining its ownership,
inputs, generated outputs, and whether files are hand-maintained or generated.

## 30. Exact Hardware Definition

A repeatable product requires an exact hardware package. The release must include:

### 30.1 Bill of materials

For every supported hardware configuration:

- FPGA board manufacturer and exact model;
- FPGA device and package;
- board revision;
- onboard SDRAM manufacturer/part when known;
- host board manufacturer, model, and revision;
- level shifters, resistors, connectors, cables, and adapters;
- power-supply voltage and minimum current;
- HDMI adapter or connector details;
- optional capture device used for secondary validation.

Substitutions must be classified as:

- equivalent and validated;
- expected compatible but unvalidated;
- unsupported.

### 30.2 Wiring definition

The hardware documentation must include:

- one canonical connection table;
- a schematic or wiring diagram;
- FPGA package pin;
- board header pin;
- host GPIO number;
- signal direction;
- idle level;
- voltage domain;
- pull-up/pull-down requirements;
- maximum validated clock;
- wire-length and grounding limits;
- signals that must not float;
- reset and boot sequencing.

The pinout must be machine-readable in a checked-in file such as:

```yaml
signals:
  - name: HOST_D0
    fpga_pin: "<LOCKED>"
    fpga_header: "<LOCKED>"
    host_gpio: "<LOCKED>"
    direction: bidirectional
    voltage: 3.3
    idle: 0
```

### 30.3 Board-specific electrical setup

Document all board-specific requirements, including:

- I/O bank voltages;
- required LDO or power-domain initialization;
- drive strength;
- slew rate;
- pull configuration;
- clock source and PLL input;
- reset polarity;
- JTAG/programming interface;
- safe power-on and power-off order.

No electrical prerequisite may exist only in a team message or engineer notebook.

## 31. Locked Toolchain and Build Environment

The release must lock every tool that can affect generated output.

### 31.1 Required version record

Record exact versions and acquisition method for:

- operating system and architecture;
- Java/JDK;
- Scala;
- sbt;
- SpinalHDL;
- SpinalSim;
- simulator backend and version;
- Verilator or other simulator;
- Gowin EDA edition and version;
- device database;
- Python;
- C/C++ compiler;
- CMake;
- Ninja or Make;
- Pico SDK, Arduino core, ESP-IDF, or other host SDK;
- host flashing tools;
- serial tools;
- hashing utilities;
- asset-conversion dependencies.

Use a lock file, container image, Nix/Devbox definition, or equivalent. A prose
version list alone is not enough when dependencies can drift.

### 31.2 Canonical environment

Provide at least one supported clean environment:

- pinned container image; or
- reproducible VM image with documented checksum; or
- scripted host setup with locked package versions.

The environment must not depend on undeclared files from a developer home
directory.

### 31.3 Vendor-tool exception

If Gowin EDA cannot legally or technically be redistributed:

- record the exact installer filename and checksum;
- record the official acquisition location;
- document installation options and license requirements;
- provide a script that verifies the installed version;
- archive project scripts, constraints, device selection, and synthesis options.

## 32. Canonical Commands

`REPRODUCIBILITY.md` must provide copy-and-paste commands for a clean build.

At minimum:

```text
bootstrap environment
verify tool versions
clean repository outputs
format/check Scala
compile SpinalHDL
run all SpinalSim tests
generate Verilog
verify generated interface
run register/schema generator
build every supported libvdp target
build every reference firmware
run software unit tests
synthesize Tang Nano 20K bitstream
extract timing/resource reports
program FPGA
flash authoritative host
run hardware acceptance suite
collect proof packet
build release archive
verify release hashes
```

Each command must state:

- working directory;
- required environment variables;
- expected exit code;
- expected output files;
- expected important console markers;
- approximate resource requirements, not as a promise but for planning;
- whether network access is required.

A command that exists only in CI is insufficient; CI must call the same checked-in
script that developers run locally.

## 33. SpinalHDL Reproducibility Contract

### 33.1 Source ownership

- Scala/SpinalHDL is the only editable FPGA behavioral source.
- Generated Verilog is created in a clean output directory.
- Generated files contain a generator version header.
- Permanent edits to generated RTL are forbidden and checked by CI.

### 33.2 Generator entry points

Document the exact Scala main classes or sbt tasks for:

- production top generation;
- simulation-only tops;
- diagnostic bitstreams;
- register/header generation;
- optional platform-specific debug variants.

Each generated top must name:

- target board;
- clock frequencies;
- reset assumptions;
- enabled adapters;
- feature flags;
- output directory.

### 33.3 Clock and reset specification

The release must contain a clock/reset table:

| Domain | Source | Frequency | Reset | Crossing rules |
|---|---|---:|---|---|
| Host | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |
| Pixel | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |
| SDRAM | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |
| HDMI/TMDS | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` | `<LOCKED>` |

For every crossing, document the synchronizer, FIFO, handshake, or ownership rule.

### 33.4 Memory and arbitration specification

Document:

- SDRAM geometry and addressing;
- byte/word endianness;
- burst rules;
- refresh interval;
- client list;
- arbitration order;
- maximum service latency;
- line-buffer depth;
- prefetch deadlines;
- underrun behavior;
- host-write behavior during active scanout;
- bank ownership and completion protocol.

A platform lane may not rely on an undocumented memory timing assumption.

### 33.5 Resource budget

Maintain a checked-in budget containing:

- current LUT usage;
- block RAM usage;
- DSP usage;
- PLL usage;
- I/O usage;
- worst negative slack;
- maximum supported clock;
- reserved headroom per future lane.

Every synthesis report is compared against the approved budget. Threshold
violations block merging unless a design decision explicitly changes the budget.

## 34. Host Protocol and `libvdp` ABI Contract

The host-facing interface must be documented independently of any transport.

### 34.1 Protocol definition

Specify:

- command opcode table;
- command and response framing;
- address width;
- length encoding;
- byte order;
- CRC/parity behavior;
- command atomicity;
- burst auto-increment behavior;
- timeout behavior;
- invalid-command response;
- reset recovery;
- read turnaround;
- transport-specific idle and chip-select rules.

Every packet example must include both logical fields and exact wire bytes.

### 34.2 Register map

The register map must be generated from one authoritative schema.

Generation must produce:

- SpinalHDL constants or decode data;
- C headers;
- human-readable documentation;
- optional Rust or other language bindings;
- reset-value test vectors.

CI must fail when generated outputs are stale.

### 34.3 ABI and capability discovery

A host must be able to read:

- magic value;
- ABI major/minor;
- feature bitmap;
- adapter bitmap;
- SDRAM size;
- maximum logical resolution;
- maximum sprite count;
- supported bitmap formats;
- supported planar layouts;
- supported transport features.

`vdp_host_init()` or its successor must reject an incompatible major ABI.

### 34.4 `libvdp` portability

For each supported host, record:

- SDK and version;
- compiler flags;
- transport backend;
- pin map;
- validated clock;
- read support;
- interrupt support;
- DMA support;
- maximum validated transaction;
- known limitations.

The release support table must use only:

- authoritative;
- tested;
- build-only;
- experimental;
- archived;
- unsupported.

## 35. Mode0 Technical Product Specification

The release must include one normative Mode0 specification that defines:

- output timing and HDMI mode;
- logical coordinate system;
- scaling and centering;
- backdrop and border behavior;
- layer count and ordering;
- tilemap formats;
- tile pattern formats;
- bitmap formats;
- planar formats;
- palette format;
- sprite descriptor layout;
- sprite limits;
- windows and masks;
- color math;
- affine behavior;
- Copper instruction set;
- HDMA and LINESTATE tables;
- DMA and Blitter behavior;
- status, collision, overflow, late-event, and underrun flags;
- active/pending register commit boundaries;
- reset values;
- unsupported and reserved encodings.

Every field must state whether it takes effect:

- immediately;
- at an H boundary;
- at a scanline boundary;
- at vblank;
- on explicit commit;
- after engine completion.

## 36. Per-Platform Reproducibility Package

Each platform lane must produce a complete package, not only code.

Required path:

```text
docs/platforms/<platform>/
├── VIDEO_SPEC.md
├── REGISTER_MAPPING.md
├── MEMORY_LAYOUT.md
├── TIMING_MODEL.md
├── LIMITATIONS.md
├── BUILD_AND_RUN.md
├── TEST_PLAN.md
├── GOLDEN_VECTORS.md
└── REFERENCES.md
```

### 36.1 Normative video specification

For the supported visual subset, document:

- native terminology;
- supported modes;
- exact dimensions;
- pixel aspect and display scaling policy;
- palette encoding;
- memory organization;
- tile/bitmap/character decode;
- sprite rules;
- priority;
- scrolling;
- border/window behavior;
- raster effects;
- collision/status behavior;
- reset state;
- undefined or intentionally simplified behavior.

Clearly distinguish:

- exact behavior;
- visually equivalent behavior;
- approximated behavior;
- deferred behavior;
- unsupported behavior.

### 36.2 Platform-to-Mode0 mapping

Provide a table for every native feature:

| Native feature | Mode0/shared implementation | New FPGA logic | Firmware translation | Accuracy |
|---|---|---|---|---|
| `<feature>` | `<component/register>` | `<component or none>` | `<API/helper>` | exact/visual/approx |

### 36.3 Exact memory examples

Include worked binary examples showing:

- source platform bytes;
- FPGA SDRAM placement;
- register configuration;
- decoded pixel indices;
- final RGB result.

At least one example must be hand-checkable.

### 36.4 Public firmware API

Document every `vdp_<platform>_*` function:

- parameters;
- valid ranges;
- required call order;
- memory ownership;
- synchronous/asynchronous behavior;
- timeouts;
- errors;
- thread/interrupt safety;
- example use.

### 36.5 Golden vectors

Every platform must publish:

- smallest legal scene;
- normal representative scene;
- boundary/stress scene;
- intentional invalid-input scene;
- raster-effect scene when applicable;
- expected line/frame hashes or pixel dumps;
- expected status flags;
- source asset hashes.

## 37. Platform-Specific Mandatory Coverage

The following are minimum technical deliverables for each planned platform.

### 37.1 ZX Spectrum

- canonical 6144-byte bitmap addressing;
- 768-byte attribute addressing;
- ink/paper/bright/flash truth table;
- flash period definition;
- border timing boundary;
- golden attribute-clash scene;
- exact host upload layout.

### 37.2 TMS9918A

- supported screen modes;
- pattern, color, name, and sprite table layouts;
- fixed palette values and provenance;
- fifth-sprite and collision behavior selected for the visual model;
- sprite size/magnification rules;
- backdrop and transparency behavior.

### 37.3 Sega Master System and Game Gear

- 4bpp tile bitplane order;
- tilemap entry format;
- scroll modes;
- column-0 blanking decision;
- sprite table layout and limits;
- SMS CRAM and Game Gear CRAM conversion;
- priority and transparency truth tables.

### 37.4 NES/Famicom

- pattern-table format;
- nametable and attribute decoding;
- fine/coarse scroll mapping;
- mirroring policy;
- sprite OAM layout;
- eight-sprites-per-line behavior;
- sprite-zero-hit visual model;
- clipping and palette mirroring rules;
- documented exclusions from cycle-exact PPU behavior.

### 37.5 Commodore 64 VIC-II

- text, multicolor text, bitmap, multicolor bitmap, and extended-color modes selected;
- character/bitmap/color RAM layouts;
- bad-line behavior included or explicitly excluded;
- sprite expansion, multicolor, priority, and collision;
- border opening approximation policy;
- raster-register timing model;
- PAL/NTSC visual timing policy.

### 37.6 Atari ST/STE

- low, medium, and high-resolution layouts;
- interleaved 16-pixel planar word examples;
- screen-base and stride alignment;
- ST RGB333 and STE palette mapping;
- border and overscan policy;
- raster palette-change timing;
- STE fine-scroll and line-width scope;
- explicit statement that CPU/GLUE/MMU timing is not emulated.

### 37.7 Amiga OCS/ECS

- one-to-six independent bitplanes;
- bitplane pointers and odd/even modulo;
- fetch and display window model;
- lores and hires policy;
- dual-playfield plane assignment and priority;
- EHB truth table;
- HAM6 direct/modify truth table and line reset;
- eight sprite channels and attached-pair behavior;
- Copper-supported register set and timing;
- Blitter subset mapping;
- explicit no-AGA statement;
- explicit exclusions from cycle-exact chip-bus contention.

### 37.8 Mega Drive/Genesis

- Plane A, Plane B, and Window layouts;
- 4bpp tile order;
- CRAM and VSRAM layout;
- horizontal and vertical scroll modes;
- sprite-link table and limits;
- complete supported priority truth table;
- shadow/highlight behavior;
- 256/320-width policy.

### 37.9 SNES Modes 0–3-lite

- per-mode background count and BPP;
- native planar tile layout;
- tilemap entry format;
- OAM mapping and approved per-line limits;
- priority tables;
- window/mask combination;
- add/sub/half color-math truth tables;
- HDMA mapping;
- explicit deferred interlace, edge cases, and Mode 7 status.

### 37.10 Atari 2600 TIA

- procedural scanline command format;
- playfield reflection/repetition;
- player copy/size behavior;
- missile and ball behavior;
- horizontal motion;
- priority truth table;
- collision latch matrix;
- beam-coordinate write scheduling;
- maximum command rate and late-command behavior.

## 38. Test Oracle and Acceptance Thresholds

Tests must define expected results, not only actions.

### 38.1 Simulation oracle

Each simulation records:

- deterministic seed;
- input vector hash;
- expected transaction trace;
- expected pixel/line/frame hash;
- expected status flags;
- maximum permitted latency;
- expected assertion count of zero.

Randomized tests publish failing seeds and retain them as fixed regressions.

### 38.2 Synthesis oracle

A synthesis pass requires:

- correct device and package;
- no unconstrained primary clock;
- no failed timing domain;
- no critical warnings unless explicitly waived;
- resource use below the approved budget;
- generated report archived with hash.

### 38.3 Hardware oracle

Each hardware test defines:

- exact firmware and bitstream hashes;
- exact asset hashes;
- expected boot log markers;
- expected register values;
- expected status counters;
- expected frame or scanline hashes when available;
- allowed visual tolerance;
- duration;
- reset count;
- pass/fail rule.

Words such as “looks right,” “seems stable,” or “mostly works” are not acceptance criteria.

### 38.4 Visual comparison

When exact frame capture is possible:

- use lossless capture;
- document RGB/YUV conversion;
- compare active area only unless borders are under test;
- publish exact or tolerance-based pixel comparison;
- record the tolerance and rationale.

When capture hardware is not trustworthy:

- use internal pixel-stream hashing or test-port readback;
- supplement with direct monitor confirmation;
- classify photographs as supporting evidence only.

## 39. Clean-Room Reproduction Procedure

Before a milestone release, a person or team not involved in the primary
implementation must perform this procedure:

1. Acquire the released repository and verify its source archive hash.
2. Acquire or build the documented environment.
3. Run the tool-version verification script.
4. Assemble the documented hardware from the BOM and wiring package.
5. Run the repository clean check.
6. Run the complete SpinalHDL/SpinalSim suite.
7. Generate Verilog.
8. Compare generated interface and expected generated-file hashes.
9. Run register/header generation and stale-file checks.
10. Build the complete firmware matrix.
11. Synthesize the production bitstream.
12. Compare timing and resource results with allowed ranges.
13. Flash the FPGA and authoritative host.
14. Run generic Mode0 hardware acceptance.
15. Run every closed platform acceptance suite.
16. Collect a new proof packet.
17. Compare expected hashes, counters, images, and logs.
18. Record every deviation.
19. Sign the clean-room report.
20. Block release until deviations are resolved or formally accepted.

The clean-room report becomes part of the release archive.

## 40. Procedural Work Package Template

Every task opened from this plan must contain:

```text
Task ID:
Lane:
State:
Owner:
Reviewers:
Dependency commits:
Goal:
Non-goals:
Files allowed to change:
SpinalHDL components:
Firmware components:
Registers/memory affected:
Documentation affected:
Test vectors:
Simulation commands:
Expected simulation results:
Synthesis command:
Resource/timing thresholds:
Hardware setup:
Firmware/bitstream pair:
Hardware test commands:
Expected hardware results:
Evidence path:
Rollback plan:
Known risks:
Definition of done:
Next task after closure:
```

A task without this information remains in BACKLOG or RESEARCH.

## 41. Decision and Deviation Management

### 41.1 Architecture decisions

Every material decision receives a checked-in ADR containing:

- context;
- options considered;
- decision;
- technical rationale;
- consequences;
- affected specifications;
- migration plan;
- reviewers;
- date and commit.

### 41.2 Deviations

Any mismatch from the reproducibility package must be recorded as:

- expected nondeterminism;
- supported alternative;
- temporary waiver;
- defect.

A waiver must include an owner and expiration milestone. Permanent undocumented
deviations are forbidden.

## 42. Failure Recovery and Troubleshooting

The documentation must include decision trees for:

- Scala or sbt dependency failure;
- SpinalHDL generation failure;
- SpinalSim mismatch;
- generated RTL drift;
- Gowin synthesis failure;
- timing regression;
- FPGA programming failure;
- no HDMI output;
- unstable HDMI output;
- host initialization failure;
- register write/read mismatch;
- SDRAM upload error;
- CRC/parity error;
- vblank wait timeout;
- Copper late event;
- line-buffer underrun;
- sprite overflow;
- transport signal-integrity failure;
- incorrect platform visual result.

Each entry must include:

- observable symptoms;
- diagnostic commands;
- known-good expected values;
- likely causes;
- safe corrective actions;
- evidence to collect before escalation.

## 43. Release Archive Contents

Every milestone release archive must contain:

```text
source archive + hash
release manifest
toolchain/version manifest
BOM and wiring revision
SpinalHDL sources
generated Verilog
register schema and generated bindings
Gowin project/scripts/constraints
synthesis, timing, and resource reports
production bitstream + hash
libvdp sources and built libraries
reference firmware sources and binaries + hashes
asset converters
test assets and golden results
simulation reports
hardware proof packets
clean-room reproduction report
known limitations
migration notes
license and third-party notices
```

## 44. Release Sign-Off

A release is approved only when the following signatures are recorded:

- architecture/specification;
- SpinalHDL/RTL;
- firmware/`libvdp`;
- hardware validation;
- documentation;
- clean-room reproduction;
- release manager.

No individual may provide every signature.

## 45. Reproducibility Exit Questions

Before marking any lane or release CLOSED, the reviewer must answer:

1. Can a new engineer identify the exact source commit?
2. Can they install or acquire the exact toolchain?
3. Can they assemble the exact supported hardware?
4. Can they run one canonical command per build stage?
5. Can they regenerate RTL without editing generated files?
6. Can they synthesize with the same device, constraints, and settings?
7. Can they build and flash the same host firmware?
8. Can they identify the exact protocol and register ABI?
9. Can they reproduce every claimed visual platform behavior?
10. Can they compare their result against objective expected outputs?
11. Can they diagnose a failure without private team knowledge?
12. Can they produce a release proof packet with matched hashes?

Any “no” blocks closure.
