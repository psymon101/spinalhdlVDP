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

The current validated repository slice does **not** actively use SDRAM yet.

Known facts:

| Property | Value |
|---------|-------|
| Bus width | 16-bit |
| Intended use | future fetch / render data path |
| Validation status | not yet part of the proven hardware slice |

Do not invent SDRAM timing or part values here until they are brought into active implementation and validated.

---

## QSPI

The current validated repository slice does **not** actively use QSPI yet.

Known facts:

| Property | Value |
|---------|-------|
| Intended role | external host control path |
| Validation status | not yet implemented in the proven slice |

Do not invent QSPI mode, frequency, or pin assignments here until the wrapper and validation evidence exist.

---

## Pin Assignments

Current validated assignments from `fpga/tang20k/tang20k_hdmi.cst`:

| Signal | Pin / pins | Notes |
|--------|------------|-------|
| `I_clk` | `4` | 27 MHz board clock input |
| `O_led[0]` | `15` | debug LED |
| `O_led[1]` | `16` | debug LED |
| `O_led[2]` | `17` | debug LED |
| `O_led[3]` | `18` | debug LED |
| `O_led[4]` | `19` | debug LED |
| `O_led[5]` | `20` | debug LED |
| `O_tmds_clk_p` | `33,34` | differential clock pair |
| `O_tmds_data_p[0]` | `35,36` | TMDS lane 0 differential pair |
| `O_tmds_data_p[1]` | `37,38` | TMDS lane 1 differential pair |
| `O_tmds_data_p[2]` | `39,40` | TMDS lane 2 differential pair |

Shared RTL must remain unaware of pin locations.

---

## Toolchain

| Tool | Purpose |
|------|---------|
| `sbt` | SpinalHDL generation |
| Gowin `gw_sh` | synthesis / place-and-route |
| `openFPGALoader` | board programming |
| local V4L2 capture device | direct hardware image inspection |

Validated programming flow:

```sh
cd /home/itadmin/github/spinalhdlVDP/fpga/tang20k
make flash LOADER_ARGS="--ftdi-serial 2025030317"
```

The explicit FTDI serial is required on this workstation when multiple FT2232 probes are attached.

---

## Known Constraints

- The current known-good slice depends on board-specific TMDS transport in `fpga/tang20k/`.
- Headless Gowin builds on this workstation require a software/minimal Qt path; the repo Makefile already encodes that workaround.
- The current hardware-proof baseline is the visible test pattern. Keep it available as a comparison reference when adding later stages.
- SDRAM, QSPI, palette RAM, and line buffers are future work and must not be treated as already established.
