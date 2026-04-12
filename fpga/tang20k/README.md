# Tang Nano 20K HDMI Flow

This directory owns the board-facing HDMI flow for `spinalhdlVDP`.

Inputs:

- `../../hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala`
- generated HDL in `../../hw/gen/`
- local hardware docs in `../../kb/`

Outputs:

- Gowin build products under `impl/`
- Tang Nano 20K SRAM / flash images

## Flow

Generate fresh HDL:

```sh
make gen
```

Build the bitstream:

```sh
make
```

Program SRAM:

```sh
make prog
```

Program flash:

```sh
make flash
```

If multiple FTDI-based probes are attached, pass extra loader arguments to bind
the intended debugger explicitly:

```sh
make flash LOADER_ARGS="--ftdi-serial 2025030317"
```
