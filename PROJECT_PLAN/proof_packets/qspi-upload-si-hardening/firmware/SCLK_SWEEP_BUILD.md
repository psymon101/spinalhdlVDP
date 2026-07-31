# `sel=8` SCLK sweep firmware build

Date: 2026-07-31  
Lane: `qspi-upload-si-hardening`  
Assignment: TopazCliff #14547, acknowledgement/reply #14549  
Proof app: `firmware/esp32p4_scaler_proof`, `SCALER_PROOF_MODE=5`

## Scope

This is a proof-only diagnostic path. It does not change `libvdp` production
semantics, QSPI framing, registers, commands, CS timing, or the FPGA
bitstream. The existing P4 backend recreates the SPI device for each requested
rate; `cs_ena_posttrans=8` remains unchanged in `vdp_host_p4.c`.

The app uploads the checkerboard bitmap and attribute planes once at the
canonical 4 MHz write rate, then reads existing `READ_STATUS sel=8` SDRAM
debug data at requested rates 2 MHz, 1 MHz, 0.5 MHz, and 0.25 MHz. Each rate
performs 30 cycles over `0x100008`, `0x101000`, and their immediate word
neighbors.

## Build and flash

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
SCALER_PROOF_MODE=5 idf.py build
idf.py -p /dev/ttyACM0 flash
```

Toolchain: ESP-IDF v6.0.2, ESP32-P4 v1.3, esptool v5.3.1. Build completed
successfully (exit 0); the project flags permit the existing mode-specific
unused-function warnings (`-Wno-error=unused-function`). Flash write and
verification completed successfully. No FPGA reflash was performed.

Artifact hashes from the flashed build are recorded in the lane manifest and
`hashes.sha256` after the source commit.

— BronzeGate
