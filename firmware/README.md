# spinalhdlVDP Pico 2 firmware

Host-control firmware for the Tang Nano 20K VDP, targeting the Raspberry
Pi Pico 2 (RP2350) over a full 6-wire quad QSPI transport. Built with
the Pico SDK 2.2.0.

## Tree

- `libvdp/` — reusable host driver library (Task 39).
  - `vdp_qspi.{h,c}` — REG_WRITE / READ_STATUS / SDRAM_WRITE transport
  - `vdp_status.{h,c}` — sticky status polling + vblank wait helpers
  - `vdp_upload.{h,c}` — vblank-paced asset upload
  - `vdp_platform.h` — Pico 2 pin map + SCK frequency constant
  - `qspi_quad.pio` — PIO program for quad-nibble TX
- `test_qspi_smoke/` — smoke test exercising the full libvdp surface.
  Boot-time reg writes, one SDRAM_WRITE asset upload, and a rapid-fire
  stress loop (Task 36 CP-C). Primary cross-task validation target.
- `test_mode0_bad_apple/` — fun-demo app that uploads one preprocessed
  monochrome Bad Apple frame into the Scenario 45 SDRAM-backed bitmap
  path, then enables bitmap mode.
- `test_qspi_wire/`, `test_qspi_wire_read/` — low-level wire-level
  probes used during the Task 26/27/38 hardening cycles. Useful when
  the `libvdp`-layer smoke test fails and you need to localise the
  break to the PIO or the bit-bang read path.

## Build (libvdp-based apps)

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
BOOTSEL mode, or flash via SWD through a Raspberry Pi Debug Probe:

```sh
~/.pico-sdk/openocd/0.12.0+dev/openocd \
  -s ~/.pico-sdk/openocd/0.12.0+dev/scripts \
  -f interface/cmsis-dap.cfg -f target/rp2350.cfg \
  -c "program firmware/test_qspi_smoke/build/test_qspi_smoke.elf verify reset exit"
```

The SWD path is required when manual BOOTSEL is inconvenient; the Debug
Probe is enumerated as `2e8a:000c` on a CMSIS-DAP interface.

## Pin map (Pico 2 ↔ Tang Nano 20K)

Six-wire full-quad QSPI per `fpga/tang20k/tang20k_hdmi.cst` and
`firmware/libvdp/vdp_platform.h`:

| Signal         | Pico 2 GPIO | Tang pin | Direction (Tang-side)       | Notes                         |
|----------------|-------------|----------|-----------------------------|-------------------------------|
| `I_qspi_sck`   | GP8         | 41       | input                       | 2 MHz, LVCMOS33, pulldown     |
| `I_qspi_cs`    | GP9         | 42       | input                       | active low, pull-up           |
| `IO_qspi_io0`  | GP10        | 48       | bidirectional (Task 38a)    | TX nibble bit 0, RX response  |
| `IO_qspi_io1`  | GP11        | 49       | bidirectional               | TX nibble bit 1, RX response  |
| `IO_qspi_io2`  | GP12        | 51       | bidirectional               | TX nibble bit 2, RX response  |
| `IO_qspi_io3`  | GP13        | 54       | bidirectional               | TX nibble bit 3, RX response  |
| GND            | GND         | GND      | —                           | common ground                 |

Task 38a landed bidirectional IOBUF on all four IO lines, so the
host-side read direction reuses the same wires as the TX direction —
no separate MISO/MOSI pair required.

## Pitfalls

See `firmware/GOTCHAS.md` for the four proven firmware pitfalls (PIO
pin restore after bit-bang read, SpinalHDL literal-cache bug, CS hold
time, OSR drain margin). Read it before hand-rolling a custom PIO
transaction.
