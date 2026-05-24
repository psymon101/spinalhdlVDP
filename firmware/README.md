# spinalhdlVDP Host Firmware

Host-control firmware for the Tang Nano 20K VDP. Supports multiple MCU
platforms over a full 6-wire quad QSPI transport.

## Supported Platforms

| Platform | MCU | Toolchain | Target Board |
|----------|-----|-----------|--------------|
| Pico 2 | RP2350 | Pico SDK 2.2.0 | Raspberry Pi Pico 2 |
| **ESP32-S3** | ESP32-S3 | ESP-IDF / Arduino CLI | ESP32-S3-DevKitC-1 |
| ESP32 | ESP32 | Arduino CLI | ESP32 Dev1 |
| ESP8266 | ESP8266 | Arduino CLI | NodeMCU 1.0 (ESP-12E) |

## Tree

- `libvdp/` — reusable host driver library (Task 39/55).
  - `vdp_qspi.{h,c}` — Multi-platform transport (PIO for Pico, bit-bang for ESP)
  - `vdp_status.{h,c}` — status polling + vblank wait helpers
  - `vdp_upload.{h,c}` — vblank-paced asset upload
  - `vdp_mode0.{h,c}` — generic Mode0 register helpers (non-adapter-specific)
  - `vdp_copper.{h,c}` — Copper opcode encoding + program upload helpers
  - `vdp_platform.h` — platform-specific pin maps
  - canonical API reference: [`kb/libvdp/README.md`](../kb/libvdp/README.md)
- `esp32_*/`, `esp8266_*/` — per-scenario Arduino sketches (thin wrappers)
- `esp8266_barebones_scroll/`, `esp32_barebones_scroll/` — barebones stage-4 scroll proofs (inline bit-bang, 40-bit protocol)
- `esp8266_barebones_sprite/`, `esp32_barebones_sprite/` — barebones Checkpoint C sprite-over-background proofs
- `esp8266_mode2_rich_top_exercise/` — rich-top register-surface exercise via `libvdp`
- `test_qspi_smoke/` — Pico-native smoke test exercising the full libvdp surface.
- `test_mode0_bad_apple/` — Pico demo uploading a monochrome Bad Apple frame.
- `esp8266_asset_upload/` — ESP8266 template showing generated asset headers + `vdp_upload_asset()`

## Build

### Pico 2 (CMake)
```sh
export PICO_SDK_PATH=/home/itadmin/.pico-sdk/sdk/2.2.0
mkdir -p build && cd build
cmake .. -DPICO_PLATFORM=rp2350-arm-s -DPICO_BOARD=pico2
make -j$(nproc)
```

### ESP32 / ESP8266 (Arduino CLI)
```sh
# ESP32
arduino-cli compile --fqbn esp32:esp32:esp32 --library libvdp <sketch_dir>

# ESP8266
arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 --library libvdp <sketch_dir>
```

## Asset Conversion

Use `scripts/assets/png_to_vdp_assets.py` to convert PNGs to VDP data and headers.

```sh
python3 ../scripts/assets/png_to_vdp_assets.py background \
  frame.png build/frame --bpp 4 --header build/frame.h --sdram-base 0x6000
```

To embed payload in a header, use `scripts/assets/bin_to_c_array.py` on the `.bin` output.

## Host Platform Fidelity

Read [`firmware/GOTCHAS.md`](GOTCHAS.md) §Host Platform Fidelity before capturing proof.

1. **Authoritative Host:** Pico 2 (RP2350). ESP-based proofs are functional only.
2. **QSPI_ERROR:** Only trust visual output if `last_error == 0` (sel=4) and sticky bit 3 is clear.
3. **Freshness:** Record bitstream and firmware commits in every proof packet.

## Pitfalls

See `firmware/GOTCHAS.md` for the proven firmware pitfalls (PIO
pin restore after bit-bang read, SpinalHDL literal-cache bug, CS hold
time, OSR drain margin, UF2 family-ID mismatch, authoritative-host
distinction). Read it before hand-rolling a custom PIO transaction.
