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
  - `vdp_mode0.{h,c}` — generic Mode0 register helpers (non-adapter-specific)
  - `vdp_platform.h` — platform-specific pin maps
  - canonical API reference: [`kb/libvdp/README.md`](../kb/libvdp/README.md)
- `esp32_*/`, `esp8266_*/` — per-scenario Arduino sketches (thin wrappers)
- `esp8266_barebones_scroll/`, `esp32_barebones_scroll/` — barebones stage-4 scroll proofs (inline bit-bang, 40-bit protocol)
- `esp8266_barebones_sprite/`, `esp32_barebones_sprite/` — barebones Checkpoint C sprite-over-background proofs
- `esp8266_mode2_rich_top_exercise/` — rich-top register-surface exercise via `libvdp`
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

## Host Platform Fidelity

Before claiming any visual output as proof, read `firmware/GOTCHAS.md`
§Host Platform Fidelity. Key requirements:

- **Authoritative host:** Pico 2 (RP2350 PIO at 2 MHz). ESP32/ESP8266 are
  functional but not authoritative for audit-signoff proofs.
- **QSPI_ERROR == 0:** Poll `last_error` (sel=4) after every write burst;
  only trust visual output when `last_error == 0` and sticky `QSPI_ERROR`
  (bit 3) is clear.
- **Artifact stewardship:** Record commit hashes of both bitstream and
  firmware in every proof packet. Rebuild/reflash if freshness cannot be
  proven.

## Pitfalls

See `firmware/GOTCHAS.md` for the proven firmware pitfalls (PIO
pin restore after bit-bang read, SpinalHDL literal-cache bug, CS hold
time, OSR drain margin, UF2 family-ID mismatch, authoritative-host
distinction). Read it before hand-rolling a custom PIO transaction.
