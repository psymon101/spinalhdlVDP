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

Committed source: `5f2a69312159416b94af278606d4093eede2ef1f`.

Final flashed-build hashes:

```text
ELF:        1c41993ee8de004be07bd5d97872651aebdf2383f6363379ace9082a08706ed5
BIN:        91f036688621d02e3a0ca469a74837bf6b3c2d2c8233e22abd54ea92e900eb04
partition:  fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17
bootloader:3929b906d7e420d7ee9465037cd172dec4f8cb865c92667dd449e9be462ffc55
```

The final committed-source binary was flashed and rerun before these hashes
were recorded.

— BronzeGate
