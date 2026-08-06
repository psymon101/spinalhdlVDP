# CS#-high QSPI reset diagnostic firmware

Date: 2026-08-01  
Assignment: TopazCliff #14600  
Source commit: `08ee736ae35b62cb3e9257487110ddc73394ac92`  
Proof mode: `SCALER_PROOF_MODE=9` in `firmware/esp32p4_scaler_proof/main/main.c`

This is proof-only firmware. It drives ESP32-P4 GPIO20 (CS_N) as a GPIO
output high immediately on application start, holds it high for 1200 ms, then
initializes the normal SPI transport and reads the existing magic and health
selectors. No production `libvdp` behavior, register, command, or RTL was
changed.

Build environment: ESP-IDF v6.0.2, ESP32-P4 v1.3, esptool v5.3.1.

Build command:

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
SCALER_PROOF_MODE=9 idf.py reconfigure
SCALER_PROOF_MODE=9 idf.py build
```

Build result: PASS (partition-size check PASS). The shell emitted the known
missing `/home/itadmin/.agent-homes/bronzegate/home/.cargo/env` profile
warning; ESP-IDF activation and compilation completed successfully.

Artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `main.c` | `dc4a06edb0294acf62eef428b7f628f3ce9d4d6048a84a7946a7e06c2ca0cbb6` |
| `esp32p4_scaler_proof.elf` | `4ffa999762818835bbc3043b54aeeb039cbe2e3bbc133519e248ccbfd226cfea4` |
| `esp32p4_scaler_proof.bin` | `9f7c9645e9eea548414cabbe9351cd2aa123db2c4d34ca3f59a8087dacd61c0f` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |
| `bootloader.bin` | `3929b906d7e420d7ee9465037cd172dec4f8cb865c92667dd449e9be462ffc55` |

Flash command:

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
SCALER_PROOF_MODE=9 idf.py -p /dev/ttyACM0 flash
```

Flash result: PASS. Bootloader, partition table, and application writes each
reported `Hash of data verified`; hard reset completed.

— BronzeGate
