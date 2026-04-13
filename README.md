# spinalhdlVDP

Fresh SpinalHDL-based Tang Nano 20K HDMI VDP development repository.

## Repository layout

- `hw/spinal/spinalhdlvdp/` Scala / SpinalHDL sources
- `hw/gen/` generated HDL output
- `fpga/tang20k/` Tang Nano 20K HDMI build files
- `kb/` local hardware and Gowin documentation
- `project/` SBT project metadata

The Scala package for this repository is `spinalhdlvdp`.

## Toolchain

- Java 11+
- `sbt`
- Gowin IDE CLI `gw_sh`
- `openFPGALoader`

This repo currently targets:

- Sipeed Tang Nano 20K
- 27 MHz board clock
- 640x480@60 blue/black checker output
- HDMI / TMDS output path

## Common commands

Generate the checker core:

```sh
sbt "runMain spinalhdlvdp.VdpTopVerilog"
```

Generate the Tang Nano 20K HDMI top-level:

```sh
sbt "runMain spinalhdlvdp.TopTang20kHdmiVerilog"
```

Run the checker-core simulation:

```sh
sbt "runMain spinalhdlvdp.VdpTopSim"
```

Build the Tang Nano 20K bitstream:

```sh
cd fpga/tang20k
make gen
make
```

## Current architecture

`VdpTop.scala` is the fresh pattern generator. `TopTang20kHdmi.scala` is the
fresh board-facing HDMI top that adds:

- TMDS encoding
- Gowin primitive wrappers for clocking and serialization
- Tang Nano 20K HDMI pin and build integration

## Mode0 direction

`Mode0` in this repo is the foundational rendering substrate, not a clone of a
single historical machine. The intended architecture is:

- `Mode0` grows the generic primitives that platforms need: raster timing,
  linestate, fetch, composition, palette, sprites, scrolling, and related
  control hooks
- platform-specific modes sit on top as semantic adapters
- platform-specific registers and quirks belong in those adapters, not inside
  `Mode0` itself

So, for example, an Amiga-oriented adapter would model Copper-visible behavior
using `Mode0` scanline/raster and state-commit primitives, rather than requiring
a separate Amiga-only rendering engine beside `Mode0`.

Candidate adapter targets mentioned in the repo now include platforms such as
ZX Spectrum, Commodore 64, Amiga, SNES, and other systems whose video behavior
can be expressed through `Mode0` primitives.

The strategic build order for those primitives now lives in
`PROJECT_PLAN/MODE0_ROADMAP.md`. That file describes the capability progression
needed for `Mode0` to support the intended adapter platforms without baking
platform-specific semantics into the substrate itself.
