# spinalhdlVDP Host Firmware

Host-control firmware for the Tang Nano 20K VDP. Supports multiple MCU
platforms over a full 6-wire quad QSPI transport.

## Supported Platforms

| Platform | MCU | Toolchain | Target Board |
|----------|-----|-----------|--------------|
| Pico 2 | RP2350 | Pico SDK 2.2.0 | Raspberry Pi Pico 2 |
| ESP32 | ESP32 | Arduino CLI | ESP32 Dev1 |
| ESP8266 | ESP8266 | Arduino CLI | NodeMCU 1.0 (ESP-12E) |

## Tree

- `libvdp/` — reusable host driver library (Task 39/55).
  - `vdp_qspi.{h,c}` — Multi-platform transport (PIO for Pico, bit-bang for ESP)
  - `vdp_status.{h,c}` — status polling + vblank wait helpers
  - `vdp_upload.{h,c}` — vblank-paced asset upload
  - `vdp_platform.h` — platform-specific pin maps
- `esp32_*/`, `esp8266_*/` — per-scenario Arduino sketches (thin wrappers)
- `test_qspi_smoke/` — Pico-native smoke test exercising the full libvdp surface.
- `test_mode0_bad_apple/` — Pico demo uploading a monochrome Bad Apple frame.

## Build

### Pico 2 (CMake)

```sh
export PICO_SDK_PATH=/home/itadmin/.pico-sdk/sdk/2.2.0
cd firmware/test_qspi_smoke
mkdir -p build && cd build
cmake .. -G "Unix Makefiles" -DPICO_PLATFORM=rp2350-arm-s -DPICO_BOARD=pico2
make -j$(nproc)
```

### ESP32 / ESP8266 (Arduino CLI)

```sh
# ESP32
arduino-cli compile --fqbn esp32:esp32:esp32 --library libvdp esp32_sc62_sprite_flip

# ESP8266
arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 --library libvdp esp8266_sc62_sprite_flip
```

## Pin Maps (Host ↔ Tang Nano 20K)

### Raspberry Pi Pico 2

| Signal | GPIO | Tang pin |
|--------|------|----------|
| SCK    | GP8  | 41       |
| CS_N   | GP9  | 42       |
| IO0    | GP10 | 48       |
| IO1    | GP11 | 49       |
| IO2    | GP12 | 51       |
| IO3    | GP13 | 54       |

### ESP32 Dev1

| Signal | GPIO | Tang pin |
|--------|------|----------|
| SCK    | 18   | 41       |
| CS_N   | 19   | 42       |
| IO0    | 23   | 48       |
| IO1    | 22   | 49       |
| IO2    | 25   | 51       |
| IO3    | 27   | 54       |

### ESP8266 NodeMCU 1.0

| Signal | NodeMCU | GPIO | Tang pin |
|--------|---------|------|----------|
| SCK    | D5      | 14   | 41       |
| CS_N   | D6      | 12   | 42       |
| IO0    | D7      | 13   | 48       |
| IO1    | D1      | 5    | 49       |
| IO2    | D2      | 4    | 51       |
| IO3    | D0      | 16   | 54       |

## Pitfalls

See `firmware/GOTCHAS.md` for the four proven firmware pitfalls (PIO
pin restore after bit-bang read, SpinalHDL literal-cache bug, CS hold
time, OSR drain margin). Read it before hand-rolling a custom PIO
transaction.
