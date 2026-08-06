# Lane 1 reconfiguration diagnostic readout build

Date: 2026-08-02

This is a proof-only firmware variant based on the PM-approved mode-0 proof
firmware source at `48ce715a`. Commit `f0531869` adds one `vdp_read_status(0x0D)`
read and a single retry immediately after the existing magic read. It does not
change `libvdp`, production status semantics, the runner, or the authority
bitstream.

Build:

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
SCALER_PROOF_MODE=0 idf.py build
```

Result: PASS. ESP-IDF v6.0.2, ESP32-P4 v1.3, esptool v5.3.1.

Artifacts:

| Artifact | SHA-256 |
|---|---|
| `firmware/esp32p4_scaler_proof/main/main.c` | `86e98b002b136e1008bdd5d6aaa4f8487be59bb8bd98cc65d59fb9c02cea6c4d` |
| `build/esp32p4_scaler_proof.elf` | `ac6196c4c52c40ab37aa20fc47bb61ba07ef8f0094d88a8cbc9bf1c375aac563` |
| `build/esp32p4_scaler_proof.bin` | `c967b71aa52cf1e7ce2a948c7e645088f77b95f8b672b011572dfe1d11331634` |
| `build/partition_table/partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |
| `build/bootloader/bootloader.bin` | `3929b906d7e420d7ee9465037cd172dec4f8cb865c92667dd449e9be462ffc55` |

Firmware flash verification passed for all three images. The app was reset
after flashing and captured on `/dev/ttyACM0` at 115200 baud.
