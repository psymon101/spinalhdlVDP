# BronzeGate Step C firmware build proof

Date: 2026-08-03

Environment:

- ESP-IDF `v6.0.2`
- Project: `firmware/esp32p4_scaler_proof`
- Command: `source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh`
- Command: `SCALER_PROOF_MODE=<mode> idf.py build`

Results:

| `SCALER_PROOF_MODE` | Result |
|---:|:---|
| 0 | PASS |
| 2 | PASS |
| 3 | PASS |

All builds completed the ESP32-P4 image generation and partition-size checks.
The build reported 94% free application-partition space and 6% free
bootloader space. The activated ESP-IDF profile emitted a pre-existing stale
cargo-path warning; compilation also emitted only the existing unused proof
diagnostic helper warnings in `main.c`. Neither affected the successful build.

This is a compile/build proof only; no board flash or hardware result is
claimed by this packet.
