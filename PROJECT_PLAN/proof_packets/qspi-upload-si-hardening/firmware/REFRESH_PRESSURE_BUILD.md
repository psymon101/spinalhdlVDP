# Refresh-pressure cross-check firmware builds

The PM-authorized diagnostic used the existing scaler-proof application without
source changes. The approved FPGA image remained loaded throughout:

```text
bitstream: fpga/tang20k/impl/pnr/project_38002d5c_scaler_hwproof.fs
bitstream_sha256: 38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09
target: ESP32-P4 revision v1.3
toolchain: ESP-IDF v6.0.2
```

Both builds completed with `Project build complete` and were flashed and
verified with `idf.py -p /dev/ttyACM0 flash`. The build used the existing
`SCALER_PROOF_MODE` preprocessor switch; no firmware source file was edited.

## Layer-disabled condition

```text
command: SCALER_PROOF_MODE=4 idf.py build
ELF:       32e643a31e070dfc1d3d03c17acbc04ac760c70c12f84c13984032cce93c5830
BIN:       abadf79b5f23a167e9597a99ebece2ee62462a81978e304420fb42075364cd6a
PARTITION: fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17
```

Mode 4 uploads 61 bitmap and 61 attribute frames at 4 MHz, leaves layer 0
disabled, and performs the 13-address neighborhood read eight times at 2 MHz.

## Layer-enabled condition

```text
command: SCALER_PROOF_MODE=0 idf.py build
ELF:       6dce108ff57c3340f4c8985ead2b16ccade9092873643e5511506efb73056649
BIN:       29c0cb7a75d770e968d39b31121db5aad9e1940f643beebfe7667769c2668a58
PARTITION: fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17
```

Mode 0 uploads the same 30,720-byte bitmap and attribute planes at 4 MHz,
performs the existing sparse readback at 2 MHz, then enables layer 0 and
leaves display fetch active.
