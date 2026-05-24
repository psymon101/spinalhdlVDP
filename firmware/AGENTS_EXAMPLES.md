# AGENTS Examples — firmware

Examples and command snippets referenced by `firmware/AGENTS.md`.

## Directory Layout

```text
firmware/
├── libvdp/              — shared host driver library (C, platform-agnostic core)
│   ├── vdp_qspi.{c,h}   — QSPI transport framing
│   ├── vdp_upload.{c,h} — SDRAM asset upload helpers
│   ├── vdp_status.{c,h} — register/status read helpers
│   └── vdp_platform.h   — platform-specific pin maps & types
├── esp8266_<scenario>/  — per-scenario Arduino sketches (NodeMCU 1.0)
├── esp32_<scenario>/    — per-scenario Arduino sketches (ESP32 dev1)
├── test_qspi_wire/      — low-level QSPI validation harness (Pico)
├── test_qspi_smoke/     — transport smoke test (Pico)
├── esp8266_asset_upload/ — generated-asset upload template (ESP8266)
└── AGENTS.md            — policy file
```

## Arduino Build / Flash

```sh
arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 --library libvdp <sketch_dir>
arduino-cli upload   --fqbn esp8266:esp8266:nodemcuv2 -p /dev/ttyUSB0 <sketch_dir>
```

ESP32 variant:

```sh
arduino-cli compile --fqbn esp32:esp32:esp32 --library libvdp <sketch_dir>
arduino-cli upload   --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 <sketch_dir>
```

ESP32-S3 variant:

```sh
arduino-cli compile --fqbn esp32:esp32:esp32s3 --library libvdp <sketch_dir>
arduino-cli upload   --fqbn esp32:esp32:esp32s3 -p /dev/ttyACM0 <sketch_dir>
```

## ESP32-S3 Production Sketches

| Sketch | Purpose |
|---|---|
| `esp32s3_qspi_smoke` | Minimal READ_STATUS magic loop; verifies basic transport integrity. |
| `esp32s3_red_screen` | Verification of bit-perfect palette writes using `palette[64]` as a canary. |
| `esp32s3_throughput` | Sweeps 1-80 MHz to characterize transport bandwidth and error floor. |
| `esp32s3_stress` | High-load mixed read/write stress test with error accounting. |
| `esp32s3_scope_fodder` | Continuous toggling on GPIO 12 for scope signal-integrity analysis. |

## PNG Asset Conversion

```sh
python3 ../scripts/assets/png_to_vdp_assets.py background \
  path/to/frame.png build/assets/frame \
  --bpp 4 --header build/assets/frame.h --sdram-base 0x6000

python3 ../scripts/assets/png_to_vdp_assets.py palette \
  path/to/palette.png build/assets/palette.bin \
  --count 16 --header build/assets/palette.h --sdram-base 0x7000
```

Use the generated `.h` file from the sketch or test harness that uploads the
matching `.bin` outputs through `libvdp`.

To avoid separate binary packaging, convert the raw `.bin` file into a C
header:

```sh
python3 ../scripts/assets/bin_to_c_array.py build/assets/frame.tiles.bin \
  build/assets/frame_tiles.h --symbol frame_tiles
```

Example sketch-side pattern:

```c
#include "frame.h"        /* generated metadata header */
#include "frame_tiles.h"  /* generated payload header */

vdp_qspi_init();
vdp_upload_asset(FRAME_SDRAM_BASE, frame_tiles, FRAME_TILES_WORD_COUNT, NULL);
```

## Platform Pin Maps

### ESP8266 NodeMCU 1.0

| QSPI | NodeMCU | GPIO |
|------|---------|------|
| SCK  | D5      | 14   |
| CS_N | D6      | 12   |
| IO0  | D7      | 13   |
| IO1  | D1      | 5    |
| IO2  | D2      | 4    |
| IO3  | D0      | 16   |

### ESP32 dev1

| QSPI | GPIO |
|------|------|
| SCK  | 18   |
| CS_N | 19   |
| IO0  | 23   |
| IO1  | 22   |
| IO2  | 25   |
| IO3  | 27   |

### ESP32-S3 (FSPI IOMUX)

| QSPI | GPIO | Tang Nano pin |
|------|------|---------------|
| SCK  | 12   | 41            |
| CS_N | 10   | 42            |
| IO0  | 11   | 48            |
| IO1  | 13   | 49            |
| IO2  | 14   | 51            |
| IO3  | 9    | 54            |

### Pico (RP2350)

Defined in `libvdp/vdp_platform.h` and `qspi_quad.pio`.
