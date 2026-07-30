# Firmware build and hashes

Command:

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
idf.py build
```

Result: PASS, ESP-IDF v6.0.2, ESP32-P4 revision v1.3.

```text
ELF:       0dda4bcfc67c5b7c7ca63d6f1deebe9bccafe984d3d1afa2fe2b684e8536179d
BIN:       66d4eaa0f863fc302a551b94b0e3cfb25718fef22b71b04a7709aa61217ea901
PARTITION: fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17
```

The image was flashed and verified with `idf.py -p /dev/ttyACM0 flash`.
