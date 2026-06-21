# AGENTS Examples — firmware

Examples and command snippets referenced by `firmware/AGENTS.md`.

## Directory Layout

```text
firmware/
├── libvdp/              — shared host driver library (C, platform-agnostic core)
│   ├── vdp_host.{c,h}   — canonical host transport facade
│   ├── vdp_legacySpi.h       — explicit legacy SPI transport entry point
│   ├── vdp_upload.{c,h} — SDRAM asset upload helpers
│   ├── vdp_status.{c,h} — register/status read helpers
│   └── vdp_platform.h   — platform-specific pin maps & types
├── esp8266_<scenario>/  — per-scenario Arduino sketches (NodeMCU 1.0)
├── esp32_<scenario>/    — per-scenario Arduino sketches (ESP32 dev1)
├── esp8266_*/esp32_* legacy SPI sketches — archived legacy SPI sketches/tests; not active i80 proof
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

## ESP32-S3 Production Sketches (i80 — canonical)

| Sketch | Purpose |
|---|---|
| `esp32s3_i80_smoke` | Minimal register loopback; verifies basic i80 transport integrity. |
| `esp32s3_i80_copper_bars` | Copper-driven color-bar demo. |
| `esp32s3_i80_copper_diag` | Copper diagnostic / instruction-packing test. |
| `esp32s3_i80_copper_raster_bands_probe` | Raster-band / copper timing probe. |
| `esp32s3_i80_rgb565_fullframe` | RGB565 full-frame direct-color upload proof. |
| `esp32s3_i80_scaler_bezel` | Runtime scaler + bezel exercise. |
| `esp32s3_i80_sprite_mask` | Sprite mask / transparency proof. |
| `esp32s3_i80_border_palette_probe` | Border + palette probe. |

## Deprecated legacy SPI Sketch Archive

The legacy SPI-era sketches are retained as `firmware/esp8266_*` and
`firmware/esp32_*` (non-i80) sketches for historical transport reference. Do
not use them for active ESP32-S3/i80 proof unless PM explicitly reopens a
legacy SPI lane.

| Archived path | Purpose |
|---|---|
| `esp8266_*` / `esp32_*` (legacy SPI builds) | legacy SPI-era palette/register/upload canaries and stress tests. |

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

vdp_host_init();
vdp_upload_asset(FRAME_SDRAM_BASE, frame_tiles, FRAME_TILES_WORD_COUNT, NULL);
```

## Platform Pin Maps

### ESP32-S3-DevKitC-1 (i80 — canonical)

| i80 signal | ESP32-S3 GPIO | Tang Nano 20K pin |
|------------|---------------|-------------------|
| D0         | 4             | 25                |
| D1         | 5             | 26                |
| D2         | 6             | 27                |
| D3         | 7             | 28                |
| D4         | 8             | 29                |
| D5         | 9             | 30                |
| D6         | 10            | 31                |
| D7         | 11            | 41                |
| DC         | 15            | 85                |
| CS#        | 16            | 76                |
| WR#        | 17            | 77                |
| RD#        | 18            | 80                |

### ESP32-S3 (FSPI IOMUX) — archived legacy SPI

| legacy SPI | GPIO | Tang Nano pin |
|------|------|---------------|
| SCK  | 12   | 41            |
| CS_N | 10   | 42            |
| IO0  | 11   | 48            |
| IO1  | 13   | 49            |
| IO2  | 14   | 51            |
| IO3  | 9    | 54            |

### ESP32 dev1 — archived legacy SPI

| legacy SPI | GPIO |
|------|------|
| SCK  | 18   |
| CS_N | 19   |
| IO0  | 23   |
| IO1  | 22   |
| IO2  | 25   |
| IO3  | 27   |

### ESP8266 NodeMCU 1.0 — reference legacy SPI

| legacy SPI | NodeMCU | GPIO |
|------|---------|------|
| SCK  | D5      | 14   |
| CS_N | D6      | 12   |
| IO0  | D7      | 13   |
| IO1  | D1      | 5    |
| IO2  | D2      | 4    |
| IO3  | D0      | 16   |

### Pico (RP2350) — archived legacy SPI

Defined in `libvdp/vdp_platform.h`; legacy SPI PIO code is archived in the
legacy SPI-era sketches.
