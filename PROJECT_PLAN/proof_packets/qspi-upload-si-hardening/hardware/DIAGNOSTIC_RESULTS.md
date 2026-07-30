# Focused write-vs-readback discriminator

Run date: 2026-07-30. Firmware source commit: `9e0d5efe`.
The approved bitstream was already loaded: `project_38002d5c_scaler_hwproof.fs`
(SHA-256 `38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`).
The target was an ESP32-P4 v1.3 on `/dev/ttyACM0`; the firmware was built with
ESP-IDF v6.0.2 and flashed with `idf.py -p /dev/ttyACM0 flash`.

## Upload geometry and frame map

The diagnostic used the normal `libvdp` path and did not hand-frame QSPI
packets. Both planes were uploaded at 4 MHz in 61 frames each:

| Field | Value |
|---|---:|
| Bitmap base | `0x100000` |
| Attribute base | `0x110000` |
| Image size | 15,360 words / 30,720 bytes |
| Full frame | 253 words / 506 bytes |
| Final frame | 180 words / 360 bytes |
| Bitmap frames | 61 |
| Attribute frames | 61 |
| Control/readback clock | 2 MHz |
| SDRAM row size | 1,024 bytes |

The failing sample `0x100008` is in bitmap frame 0 (`0x100000`); the failing
sample `0x101000` is in bitmap frame 8 (`0x100FD0`). The latter is a 1 KiB
SDRAM row start (`row=1028`, bank 0), while frame 8 is not itself a row-sized
transport boundary.

## CRC status observations

The diagnostic read selector `0x0B` immediately before and after every frame.
The status counter changed on six frames; no `vdp_last_error()` was reported:

| Plane/frame | Frame address | Counter before → after | Delta |
|---|---|---|---:|
| bitmap/3 | `0x1005EE` | `0x000100A7 → 0x000100A8` | 1 |
| bitmap/43 | `0x1054FE` | `0x000100A8 → 0x000100A9` | 1 |
| attr/1 | `0x1101FA` | `0x000100A9 → 0x000100AA` | 1 |
| attr/6 | `0x110BDC` | `0x000100AA → 0x000100AB` | 1 |
| attr/20 | `0x112788` | `0x000100AB → 0x000100AC` | 1 |
| attr/23 | `0x112D76` | `0x000100AC → 0x000100AD` | 1 |

All other frame deltas were zero. Upload API status remained successful and
transport health was `raw=0x00000000 overflow=0 malformed=0` before and after
the uploads.

## Neighbor re-read result

At 2 MHz, all 13 addresses were read eight times, for 104 successful reads.
Every repetition returned the same value:

```text
expected=0x00000000, got=0x00000000:
  0x100000 0x100004 0x100010 0x100014 0x100FF8 0x100FFC 0x101008

expected=0x55555555, got=0x00000000:
  0x100008 0x10000C 0x100018 0x10001C 0x101000 0x101004
```

Every read reported `read_ok=1 err=0`. The result is therefore **stable zero**
for both assigned suspect regions, not a varying `sel=8` readback artifact.

Diagnostic result: `DIAG_READ_RESULT pass=0 repeats=8 addresses=13`.
Per the PM discriminator, this selects the real SDRAM/write-path branch. It
does not by itself identify the minimal RTL delta; BrightForge/TopazCliff still
need the three-way agreement before implementation.
