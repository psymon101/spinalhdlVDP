# spinalhdlVDP Pico 2 firmware

Minimal firmware tree for the QSPI host-control lane (Task 26). Built with
the Pico SDK 2.2.0 targeting RP2350.

## Tree

- `test_qspi_smoke/` — smoke test that toggles `VDP_LAYER_ENABLE` between
  `0x0005` and `0x0007` every 500 ms. Primary Checkpoint C proof: the
  visible layer change on HDMI capture validates the end-to-end QSPI
  transport + decoder + safe-boundary commit chain.

## Build

```sh
export PICO_SDK_PATH=/home/itadmin/.pico-sdk/sdk/2.2.0
export PICO_TOOLCHAIN_PATH=/home/itadmin/.pico-sdk/toolchain/14_2_Rel1
export PATH="/home/itadmin/.pico-sdk/cmake/v3.31.5/bin:$PATH"

cd firmware/test_qspi_smoke
mkdir -p build && cd build
cmake .. -G "Unix Makefiles" -DPICO_PLATFORM=rp2350-arm-s -DPICO_BOARD=pico2
make -j$(nproc)
```

Output: `test_qspi_smoke.uf2` ready to drag-drop onto a Pico 2 in
BOOTSEL mode.

## Pin map (Pico 2 ↔ Tang Nano 20K)

| Pico 2 GPIO | Signal    | Tang Nano 20K pin |
|-------------|-----------|-------------------|
| GP8         | SCK       | 10                |
| GP9         | CS_N      |  9                |
| GP10        | IO0       | 11                |
| GP11        | IO1       |  8                |
| GP12        | IO2       | (unconnected)     |
| GP13        | IO3       | (unconnected)     |
| GND         | GND       | GND               |

Tang IO2/IO3 are tied low inside the slave (lane 1 — input-only, tristate
driver deferred to lane 2). Pico drives them for PIO consistency but Tang
ignores those bits.
