# Double-read and display-indirect firmware build

Date: 2026-07-31

The proof-only firmware changes are in commits `619f76b8` and `3b246fc7`.
`619f76b8` changes the internal frame copy in `vdp_host_p4.c` from `memcpy`
to `memmove`, because `write_frame()` commonly receives the same buffer it
copies into. `3b246fc7` adds proof modes 6 and 7; it does not add a host
command, register, or production API.

Build environment: ESP-IDF v6.0.2, target ESP32-P4 v1.3. The mode selector
was supplied to every `idf.py reconfigure`, `build`, and `flash` invocation
so the flashed image is unambiguously tagged in its serial banner.

Accepted mode-6 artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `esp32p4_scaler_proof.elf` | `e690ce1a05f93fbf6bf476df034b97f3d05be7be27b02f77993c9e0eea3ac7e0` |
| `esp32p4_scaler_proof.bin` | `c94547a1fb087830bf6ed2cd1c862e9259bed73545eb8f5942f07a333731c72e` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |

Accepted mode-7 artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `esp32p4_scaler_proof.elf` | `dd169667e4b50bfbbbb455db837b1973179bc0c2d57cc36d3197c10e040a4565` |
| `esp32p4_scaler_proof.bin` | `ad5331cfb8cd7b3a9914f8cf8ddf8d5b9ca7c05a4aa486e5dd818660c8e9e269` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |

The approved FPGA image was already active and was not changed:
`38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`.
