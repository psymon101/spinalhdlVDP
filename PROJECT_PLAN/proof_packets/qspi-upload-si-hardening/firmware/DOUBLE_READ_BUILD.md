# Double-read and display-indirect firmware build

Date: 2026-08-01

The proof-only firmware changes are in commits `619f76b8`, `3b246fc7`, and
`2d066b5e`. `2d066b5e` corrects Mode 6 to call the complete
`readback_word()` routine twice, re-arming the SDRAM read on each call, and
adds the requested dummy-neighbor pattern check.
`619f76b8` changes the internal frame copy in `vdp_host_p4.c` from `memcpy`
to `memmove`, because `write_frame()` commonly receives the same buffer it
copies into. `3b246fc7` adds proof modes 6 and 7; it does not add a host
command, register, or production API.

Build environment: ESP-IDF v6.0.2, target ESP32-P4 v1.3. The mode selector
was supplied to every `idf.py reconfigure`, `build`, and `flash` invocation
so the flashed image is unambiguously tagged in its serial banner.

Corrected mode-6 artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `esp32p4_scaler_proof.elf` | `43e02289df7218f86ac236959d3202117a8e4ec710b971b367aa19290634a35c` |
| `esp32p4_scaler_proof.bin` | `dbd26957392fef6cb04668f02e776f17eaecb5928d5995046de204c69d1050d5` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |

Accepted mode-7 artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `esp32p4_scaler_proof.elf` | `dd169667e4b50bfbbbb455db837b1973179bc0c2d57cc36d3197c10e040a4565` |
| `esp32p4_scaler_proof.bin` | `ad5331cfb8cd7b3a9914f8cf8ddf8d5b9ca7c05a4aa486e5dd818660c8e9e269` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |

The approved FPGA image was already active and was not changed:
`38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`.

The first corrected hardware run is not an accepted data proof: the bitmap
upload returned `VDP_HOST_ERR_TX` (`err=5`) at offset 1518. The application
continued only to collect diagnostic context. A controlled rerun after the
failure completed the full bitmap and attribute uploads cleanly. The accepted
rerun artifact hashes were:

| Artifact | SHA-256 |
|---|---|
| `esp32p4_scaler_proof.elf` | `b66b1747c3f0aa19ea318b59faeca251636a4246a85c0499688e13c7e7b709de` |
| `esp32p4_scaler_proof.bin` | `ed4fb5d01dc2b339069f7ebad076771a7cd9bba6af019bb7d144e6ff79633944` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |

The rerun serial transcript hash is
`0fa2965cbc2a220293ba2e63ae31bbc1d1472d9eee1a1b70d7532b9a57e35954`.
