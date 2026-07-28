# spinalhdlVDP Host Firmware

This directory contains the canonical host driver library for the Tang Nano
20K VDP. Platform-specific example sketches and proof-of-concept projects
have been moved to [`PROJECT_PLAN/archive/firmware_tests/`](../PROJECT_PLAN/archive/firmware_tests/).

## Supported Platforms

| Platform | MCU | Toolchain | Target Board | Status |
|----------|-----|-----------|--------------|--------|
| **ESP32-P4** | ESP32-P4 | ESP-IDF v6.0.2 | ESP32-P4-Function-EV-Board | **Canonical / authoritative** |
| ESP32-S3 | ESP32-S3 | ESP-IDF / Arduino CLI | ESP32-S3-DevKitC-1 | historical, archived sketches |
| Pico 2 | RP2350 | Pico SDK 2.2.0 | Raspberry Pi Pico 2 | legacy SPI, archived sketches |
| ESP32 | ESP32 | Arduino CLI | ESP32 Dev1 | legacy SPI, archived sketches |
| ESP8266 | ESP8266 | Arduino CLI | NodeMCU 1.0 (ESP-12E) | archived reference sketches |

## Tree

- `libvdp/` — reusable host driver library.
  - `vdp_host.{h,c}` — portable host API and legacy/platform backends
  - `vdp_host_p4.c` — ESP32-P4 QSPI backend for the canonical host
  - `vdp_crc8.h` — CRC-8-CCITT helper for the QSPI-CRC8-185 write-frame contract
  - `vdp_legacySpi.h` — explicit legacy SPI transport entry point for archived sketches
  - `vdp_status.{h,c}` — status polling + vblank wait helpers
  - `vdp_upload.{h,c}` — vblank-paced asset upload
  - `vdp_mode0.{h,c}` — generic Mode0 register helpers (non-adapter-specific)
  - `vdp_copper.{h,c}` — Copper opcode encoding + program upload helpers
  - `vdp_platform.h` — platform-specific pin maps
  - canonical API reference: [`kb/libvdp/README.md`](../kb/libvdp/README.md)
- `tools/` — small helper scripts
- `esp32p4_scaler_proof/` — ESP-IDF checkerboard and scaler proof app; build
  with `SCALER_PROOF_MODE=0`, `2`, or `3` for the 1×, 2×, and 3× lanes.

Historical per-scenario sketches are preserved in
[`PROJECT_PLAN/archive/firmware_tests/`](../PROJECT_PLAN/archive/firmware_tests/).

## Asset Conversion

Use `scripts/assets/png_to_vdp_assets.py` to convert PNGs to VDP data and headers.

```sh
python3 ../scripts/assets/png_to_vdp_assets.py background \
  frame.png build/frame --bpp 4 --header build/frame.h --sdram-base 0x6000
```

To embed payload in a header, use `scripts/assets/bin_to_c_array.py` on the `.bin` output.

## Host Platform Fidelity

Read [`firmware/GOTCHAS.md`](GOTCHAS.md) §Host Platform Fidelity before capturing proof.

1. **Authoritative Host:** ESP32-P4 (QSPI). Archived sketches are not active proof for current lanes.
2. **QSPI health:** Only trust visual output if transport health is clear and the upload bridge sticky bits are clean.
3. **Freshness:** Record bitstream and firmware commits in every proof packet.

## Pitfalls

See `firmware/GOTCHAS.md` for the proven firmware pitfalls (QSPI timing,
SpinalHDL literal-cache bug, upload-status handling, authoritative-host
distinction, and capture-path artifacts). Read it before hand-rolling a custom
QSPI transaction.
