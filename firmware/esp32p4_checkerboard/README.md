# esp32p4_checkerboard

Minimal ESP32-P4 firmware for the spinalhdlVDP Tang Nano 20K reference board.

## Purpose

Display a static 320×240 2bpp indexed checkerboard scaled to 640×480 HDMI.
This is a clean-room test image intended to isolate upload/transport corruption
from rendering-path bugs. It contains no HAM6 code, no stress tests, and no
campaign modes.

## Expected output

A checkerboard of 32×32 source-pixel squares alternating black and white.

## Build

Requires ESP-IDF v6.0.2 (or compatible) with `IDF_PATH` set:

```sh
idf.py set-target esp32p4
idf.py build
```

## Flash

```sh
idf.py -p /dev/ttyACM0 flash monitor
```

## Register configuration

- `MODE_SELECT` = `0x0000` (native Mode0)
- `BITMAP_BASE` = `0x100000`
- `ATTR_BASE`   = `0x110000`
- `BITMAP_STRIDE` / `ATTR_STRIDE` = `128`
- `BITMAP_HEIGHT` = `240`
- `BITMAP_CTRL` = `0x0003` (enable + 2bpp indexed)
- `LAYER_ENABLE` = `0x0001`
- Linestate `0x0000..0x01DF` = `0x0800` (L0 on, L1 off, scrollX = 0)
- Palette entries 0..1 = black / white

## Notes

- Attribute plane is filled with `0xE4` for identity pixel → palette mapping.
- Bulk SDRAM upload runs at 4 MHz QSPI; register traffic runs at 40 MHz.
  The 4 MHz setting is the canonical safe rate for 30,720-byte bitmap and
  attribute planes on the current ESP32-P4-to-Tang-Nano-20K wiring. An 8 MHz
  upload passed some runs but corrupted bitmap words intermittently.
- A small SDRAM readback sanity check runs after upload.
