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
- **Controller:** `SdramTileFetch.scala` (tile/attr); `BitmapRowFetch.scala` (RGB565 direct-color burst).
- **Use:** L0/L1 tile/attr fetch, bitmap/attribute planes, asset uploads.
- **Clock:** 40.5 MHz SDRAM domain (`TopTang20kI80` / `TopTang20kHdmi`).
- **Proof:** Tasks 15, 34, 36, RGB565-FULLFRAME-132 (stability under concurrent host/HDMA load).

## i80 Host Control (Canonical)

8-bit parallel Intel-8080-style bus. This is the **current canonical host path** for the Tang Nano 20K deployment, driven by an ESP32-S3.

| Host Platform | Status | Implementation | Notes |
|---|---|---|---|
| **ESP32-S3** | Canonical | GPIO bit-bang / LCD_CAM i80 | Primary development and proof target |
| **Pi Pico 2** | Legacy/Retired path | PIO QSPI | Still supported by `vdp_qspi.h`; no longer the canonical path |
| **ESP8266** | Legacy/Retired path | Bit-bang QSPI | Still supported by `vdp_qspi.h`; very low throughput |

- **Protocol:** single-byte opcode, two address bytes, two data bytes. DC# distinguishes opcode/address/data phases.
  - `0x00` — register write
  - `0x01` — register read (loopback for most addresses; debug data for `0x0328`/`0x0329`)
  - `0x02` — SDRAM block write
- **Proof:** WHOLE-VDP-134 i80 smoke, RGB565-FULLFRAME-132 HW proof.
- **Library facade:** `firmware/libvdp/vdp_i80.h` (`vdp_host_init`, `vdp_reg_write`, `vdp_reg_read`, `vdp_sdram_write`, etc.).

### i80 Pin Assignments (ESP32-S3)

Defined in `firmware/libvdp/vdp_platform.h` and wired in `fpga/tang20k/tang20k_i80.cst`:

| Signal | Tang Pin | ESP32-S3 GPIO | Function |
|--------|----------|---------------|----------|
| D0     | 25       | 4             | Data bit 0 |
| D1     | 26       | 5             | Data bit 1 |
| D2     | 27       | 6             | Data bit 2 |
| D3     | 28       | 7             | Data bit 3 |
| D4     | 29       | 8             | Data bit 4 |
| D5     | 30       | 9             | Data bit 5 |
| D6     | 31       | 10            | Data bit 6 |
| D7     | 41       | 11            | Data bit 7 |
| DC#    | 85       | 15            | 0 = opcode/address, 1 = data |
| CS#    | 76       | 16            | Active-low chip select |
| WR#    | 77       | 17            | Active-low write strobe |
| RD#    | 80       | 18            | Active-low read strobe |

## Retired QSPI Host Control

4-wire quad-mode lane. Retired from the canonical ESP32-S3 path; retained for historical Pico 2 / ESP8266 bench setups. Detailed history moved to [`archive/QSPI_HOST_CONTROL_PLAN.md`](archive/QSPI_HOST_CONTROL_PLAN.md).

| Host Platform | Status | Implementation | Production SCK |
|---|---|---|---|
| **Pi Pico 2** | Historical | PIO | 2 MHz |
| **ESP32-S3** | Historical | Hardware SPI2 + DMA | 60 MHz (Write) / 3 MHz (Read) |
| **ESP8266** | Historical | Bit-bang | ~500 kHz |

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
- SDRAM and the i80 host-control interface are validated and in active use — see the respective sections above for controller sources and the task list that proved each one.
- Palette RAM and line buffers remain in the `VdpTop` pipeline and are considered established; bus-master concurrency across them is proven under stress (Task 36 and RGB565-FULLFRAME-132).

