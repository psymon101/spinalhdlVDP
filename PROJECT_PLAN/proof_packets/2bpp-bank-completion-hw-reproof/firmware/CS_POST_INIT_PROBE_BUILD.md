# Post-`vdp_host_init()` CS# probe

Date: 2026-08-01  
Assignment: TopazCliff #14610  
Source commit: `48ce715a`  
Proof mode: `SCALER_PROOF_MODE=0`

The proof image samples GPIO20 after `vdp_host_init()` returns and immediately
before the first `READ_STATUS` transaction. It also logs the configured P4 SPI
CS parameters. This is proof-only application instrumentation; no production
transport framing, register, command, or RTL was changed.

Build environment: ESP-IDF v6.0.2, ESP32-P4 v1.3, esptool v5.3.1.

Build and flash commands:

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
SCALER_PROOF_MODE=0 idf.py build
idf.py -p /dev/ttyACM0 flash
```

Build result: PASS; partition-size check PASS. Flash result: PASS; bootloader,
partition table, and application writes each reported `Hash of data verified`.

Artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `main.c` | `5d94cc3d24d7cba9427f30f55b4416efe5fccd54e63fb55177882536d4de66a4` |
| `esp32p4_scaler_proof.elf` | `2eadbe69dccca5325ca8499c71ca433a17f8c0a3b17f7c605f2525a712d0338c` |
| `esp32p4_scaler_proof.bin` | `ceaeed136ecba097e2fe1acedec903fa8c8804d2e33cd4dd78c22515ba3bf440` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |
| `bootloader.bin` | `3929b906d7e420d7ee9465037cd172dec4f8cb865c92667dd449e9be462ffc55` |
| `cs_post_init_probe_serial.log` | `854fdd503c4359e0a9f89ce9dc5d251b0974c82398a2e2a10635461a7e13b32b` |

The current FPGA was not reconfigured for this firmware-only discriminator;
campaign cycle 2 was not started.

— BronzeGate
