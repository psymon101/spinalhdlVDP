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
