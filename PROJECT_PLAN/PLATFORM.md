# PLATFORM.md

**Purpose:** Hard facts for the currently validated Tang Nano 20K build target. Use these values unless a newer validated hardware change explicitly replaces them.

---

## FPGA

| Property | Value |
|---------|-------|
| Device family | GW2AR-18 |
| Device string used in build | `GW2AR-LV18QN88C8/I7` |
| Gowin device name | `GW2AR-18C` |
| Package code | `QN88` |
| LUT count | ~20K LUT4 |
| Block RAM | 46 x 18Kb BSRAM |
| DSP blocks | 28 |
| PLL count | 2 available on device |

Source of truth for the current build target:

- `fpga/tang20k/build.tcl`
- `fpga/tang20k/tang20k_hdmi.cst`
- `hw/spinal/spinalhdlvdp/GowinPrimitives.scala`

---

## Clock Architecture

| Signal | Source | Frequency | Notes |
|--------|--------|-----------|-------|
| `I_clk` | On-board oscillator | 27 MHz | Board input clock |
| pixel clock | `CLKDIV` output | 25.2 MHz | Derived from the PLL path; used for raster generation |
| serializer clock | PLL output | 5x pixel clock | Used by the TMDS serializers |

Validated timing evidence:

- `fpga/tang20k/tang20k_hdmi.sdc` constrains the pixel clock with a 39.6825 ns period
- `TopTang20kHdmi.scala` drives raster logic from the divided clock
- `tang20k_hdmi_tx.sv` consumes `clk_pixel` and `clk_pixel_x5`

There is **no validated separate `clkSys` domain in active use yet**. Do not document or depend on one until it exists in the implementation.

---

## Video Output

| Property | Value |
|---------|-------|
| Output connector | On-board HDMI |
| Encoding | DVI-compatible TMDS transport |
| Visible raster | 640x480 active region |
| Pixel generator | `VdpTop.scala` |
| Current proven pattern | deterministic tiled pattern from on-chip `tileMap` and `tileRows` memories |
| Transport wrapper | `fpga/tang20k/tang20k_hdmi_tx.sv` |
| Serializer primitive | Gowin `OSER10` |
| Differential output primitive | Gowin `ELVDS_OBUF` |

Important note:

- Local HDMI capture on this machine currently reports and samples the signal as `1280x720` through the capture device, but the visible pattern generator implemented in the RTL is the current 640x480 timing slice.

---

## SDRAM

Integrated SDR SDRAM SiP (64 Mbit, 32-bit bus, 4 banks).
- **Controller:** `SdramTileFetch.scala`.
- **Use:** L0 tile/attr fetch, asset uploads.
- **Proof:** Tasks 15, 34, 36 (stability under concurrent QSPI/HDMA load).

## QSPI Host Control

4-wire quad-mode lane. Host: Pi Pico 2 (Authoritative).
- **Protocol:** 6-byte header `[CMD:1][ADDR:3][LEN:2]`.
- **SCK:** 2 MHz (Proven).
- **Proof:** Tasks 26, 38, 34, 36.

## Pin Assignments

Validated in `fpga/tang20k/tang20k_hdmi.cst`:

| Signal | Tang Pin | Pico (Host) |
|--------|----------|-------------|
| SCK    | 41       | GP8         |
| CS_N   | 42       | GP9         |
| IO0    | 48       | GP10        |
| IO1    | 49       | GP11        |
| IO2    | 51       | GP12        |
| IO3    | 54       | GP13        |

## Toolchain & Programming

| Tool | Purpose |
|------|---------|
| `sbt` | RTL Generation |
| Gowin `gw_sh` | Synthesis / PnR |
| `openFPGALoader` | Programming |

**Flash Command:**
```sh
cd fpga/tang20k && make flash LOADER_ARGS="--ftdi-serial 2025030317"
```

---

## Known Constraints

- The current known-good slice depends on board-specific TMDS transport in `fpga/tang20k/`.
- Headless Gowin builds on this workstation require a software/minimal Qt path; the repo Makefile already encodes that workaround.
- SDRAM and the full-quad QSPI host-control lane are validated and in active use — see the respective sections above for controller sources and the task list that proved each one.
- Palette RAM and line buffers remain in the `VdpTop` pipeline and are considered established; bus-master concurrency across them is proven under stress (Task 36).
