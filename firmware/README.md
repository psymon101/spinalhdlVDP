# spinalhdlVDP Host Firmware

Host-control firmware for the Tang Nano 20K VDP. The canonical Tang Nano
20K host path is **i80/ESP32-S3**. The legacy QSPI path (Pico 2, ESP32,
ESP8266) remains present in the tree but is deprecated as the primary
development target.

## Supported Platforms

| Platform | MCU | Toolchain | Target Board | Status |
|----------|-----|-----------|--------------|--------|
| **ESP32-S3** | ESP32-S3 | ESP-IDF / Arduino CLI | ESP32-S3-DevKitC-1 | **Canonical / authoritative** |
| Pico 2 | RP2350 | Pico SDK 2.2.0 | Raspberry Pi Pico 2 | Legacy QSPI |
| ESP32 | ESP32 | Arduino CLI | ESP32 Dev1 | Legacy QSPI |
| ESP8266 | ESP8266 | Arduino CLI | NodeMCU 1.0 (ESP-12E) | Legacy QSPI |

## Tree

- `libvdp/` — reusable host driver library (Task 39/55).
  - `vdp_host.{h,c}` — Active i80 transport for ESP32-S3 (canonical)
  - `vdp_qspi.h` — Deprecated QSPI compatibility shim (legacy only)
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
- `esp_scaler_runtime_bezel/` — ESP32/ESP8266 runtime scaler exercise (Priority 1 proof)

## Build

### ESP32-S3 (Arduino CLI) — canonical
```sh
arduino-cli compile --fqbn esp32:esp32:esp32s3 --library libvdp <sketch_dir>
```

### ESP32 / ESP8266 (Arduino CLI) — legacy QSPI
```sh
# ESP32
arduino-cli compile --fqbn esp32:esp32:esp32 --library libvdp <sketch_dir>

# ESP8266
arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 --library libvdp <sketch_dir>
```

### Pico 2 (CMake) — legacy QSPI
```sh
export PICO_SDK_PATH=/home/itadmin/.pico-sdk/sdk/2.2.0
mkdir -p build && cd build
cmake .. -DPICO_PLATFORM=rp2350-arm-s -DPICO_BOARD=pico2
make -j$(nproc)
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

1. **Authoritative Host:** ESP32-S3 (i80). Pico 2 / ESP32 / ESP8266 QSPI proofs are legacy and functional only.
2. **I80_ERROR:** Only trust visual output if `vdp_last_error() == 0` and the upload bridge sticky bits are clear. Legacy QSPI builds use `QSPI_ERROR` / `sel=4` semantics instead.
3. **Freshness:** Record bitstream and firmware commits in every proof packet.

## Pitfalls

See `firmware/GOTCHAS.md` for the proven firmware pitfalls (i80
timing/ground-bounce, SpinalHDL literal-cache bug, upload-status
handling, authoritative-host distinction). Read it before hand-rolling
a custom i80 transaction.
