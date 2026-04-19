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

The SiP SDRAM is **actively used** for L0 tile + attribute fetch and as
the target for host-driven asset uploads.

| Property | Value |
|---------|-------|
| Type | embedded SDR SDRAM (SiP) |
| Capacity | 64 Mbit (8 MB) |
| Bus width | 32-bit |
| Data width / banks | 4 banks of 512K x 32 |
| Clock target from device docs | up to 166 MHz |
| Refresh requirement | 4096 refresh cycles / 64 ms |
| Voltage requirement | SDRAM-connected banks at 3.3V |
| Controller source | `hw/spinal/spinalhdlvdp/SdramTileFetch.scala` and supporting modules |
| Validation status | **Task 15** validated custom controller integration; **Task 34** validated the host-driven SDRAM_WRITE upload path; **Task 36** validated stability under concurrent QSPI + HDMA bus load |

Source references for the hardware model:

- `kb/fpga/tang20k-datasheet.pdf`
- `kb/fpga/SDRAM-Datasheet.pdf`
- `kb/fpga/tang20kfpga-chip-data.pdf`

Important implementation note:

- This SDRAM is integrated in the Tang Nano 20K SiP and is **not** exposed as ordinary user-routed board-header pins; no board-side routing is required.
- The custom SpinalHDL controller replaces the Gowin SiP reference IP — see `feedback_gowin_bsram.md` project memory for the tristate DQ + 225° phase notes if the controller is ever retouched.

Do not invent additional timing margins, phase settings, or controller behavior that are not reflected in the current SpinalHDL sources.

---

## QSPI

The full 4-wire quad-mode QSPI host-control lane is **validated and
actively used**. The external host (Pi Pico 2) drives register writes,
status reads, and SDRAM asset uploads through this lane.

| Property | Value |
|---------|-------|
| Role | external host control path |
| Mode | quad-output (TX) + bit-bang turnaround (RX on the same 4 IO lines) |
| Header format | 6-byte header `[CMD:1][ADDR:3][LEN:2]` (little-endian) |
| Commands | `0x01` REG_WRITE, `0x02` SDRAM_WRITE, `0x04` READ_STATUS |
| SCK | 2 MHz (proven); 5 MHz is the unverified theoretical ceiling and requires re-validating the PIO OSR drain margin + SDRAM CDC margin before use |
| Controller source | `hw/spinal/spinalhdlvdp/QspiSlave.scala`, `QspiDecoder.scala` |
| Host library | `firmware/libvdp/` (Task 39) — see `firmware/README.md` for build + flash |
| Validation status | **Task 26/27** REG_WRITE; **Task 38a** bidirectional IOBUF on IO0..IO3; **Task 38c** bit-bang READ_STATUS response; **Task 34** SDRAM_WRITE upload; **Task 36** concurrent-load stability under rapid writes paired with Copper/HDMA |

See `firmware/GOTCHAS.md` for the four proven firmware pitfalls
(PIO pin-function restore, SpinalHDL literal-cache bug, CS hold time,
OSR drain margin) that a custom QSPI path must respect.

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
| `I_qspi_sck` | `41` | 2 MHz, LVCMOS33, pulldown — Pico GP8 |
| `I_qspi_cs`  | `42` | active low, pull-up — Pico GP9 |
| `IO_qspi_io0`| `48` | bidirectional (Task 38a IOBUF) — Pico GP10 |
| `IO_qspi_io1`| `49` | bidirectional — Pico GP11 |
| `IO_qspi_io2`| `51` | bidirectional — Pico GP12 |
| `IO_qspi_io3`| `54` | bidirectional — Pico GP13 |

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
- SDRAM and the full-quad QSPI host-control lane are validated and in active use — see the respective sections above for controller sources and the task list that proved each one.
- Palette RAM and line buffers remain in the `VdpTop` pipeline and are considered established; bus-master concurrency across them is proven under stress (Task 36).
