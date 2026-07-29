# ESP32-P4 i80 Basic Read

Bounded ESP-IDF bring-up for the approved ESP32-P4-WIFI6 to Tang Nano 20K
i80 map. The first proof is intentionally one write/read/restore transaction
before any sweep.

## Pin Map

| Signal | P4 GPIO | Tang pin |
|---|---:|---:|
| D0 | 32 | 25 |
| D1 | 33 | 26 |
| D2 | 22 | 27 |
| D3 | 23 | 28 |
| D4 | 46 | 29 |
| D5 | 47 | 30 |
| D6 | 48 | 31 |
| D7 | 29 | 41 |
| DC | 20 | 85 |
| CS# | 31 | 76 |
| WR# | 21 | 77 |
| RD# | 30 | 80 |

Avoided pins: GPIO7/8, GPIO24/25, GPIO26/27, GPIO34-38.

## Build

```bash
source ~/esp/esp-idf-v6.0.2/export.sh
idf.py set-target esp32p4
idf.py build
```

## First Hardware Gate

```bash
idf.py -p /dev/ttyACM0 flash monitor
```

Expected first proof line:

```text
P4_I80_SINGLE result=PASS ...
```

If the single transaction passes, the firmware then runs a 512-transaction
2 MHz burst and prints a summary line.
