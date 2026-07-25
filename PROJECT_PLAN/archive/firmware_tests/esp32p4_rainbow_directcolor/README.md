# ESP32-P4 rainbow direct-color sketch

This is an independent ESP-IDF application written from scratch for the
Tang Nano 20K VDP. It does not include source files from the other firmware
sketches.

It generates a smooth RGB565 rainbow in a centered 160x120 source viewport.
The active VDP raster displays that viewport at 2x in both axes, producing a
320x240 image surrounded by black inside the fixed 640x480 direct-color
timing.

The two RGB565 byte planes are uploaded independently:

- low byte plane: SDRAM `0x100000`
- high byte plane: SDRAM `0x200000`
- both row strides: 512 bytes
- source rows: 240

Only the first 320 bytes of each 512-byte row contain pixels; the remaining
row padding is transmitted as zeroes so each bulk transaction is contiguous.
Bulk transfers use the documented 4 MHz QSPI ceiling. The direct-color mode
is enabled with `BITMAP_CTRL = 0x0005` after the upload and readback checks.

## Build

With ESP-IDF v6.0.2 exported:

```sh
idf.py set-target esp32p4
idf.py build
```

## Flash

```sh
idf.py -p /dev/ttyACM0 flash monitor
```
