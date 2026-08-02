# Lane 1 prime firmware build

Date: 2026-08-02
Owner: BronzeGate

## Scope

This proof-only mode-0 firmware adds the PM-authorized post-reconfigure prime:
one discarded `vdp_read_status(SEL_MAGIC)` immediately after the mandatory
CS#-high pre-flight and `vdp_host_init()`. The following magic read is the
campaign gate. The prior diagnostic `sel=0x0D` readout is not present in this
campaign image.

## Source and toolchain

- Source commit: `9babcbeec436906271114cb4b146bc0234e1e4be`
- Parent source: `f0531869` (proof-only diagnostic image); production contract
  remains unchanged.
- FPGA authority bitstream: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`
- SDK: ESP-IDF v6.0.2
- Target: ESP32-P4
- Transport: QSPI, 2 MHz SCK; `cs_ena_pretrans=2`, `cs_ena_posttrans=8`
- Firmware flash port: `/dev/ttyACM0`

## Commands

```text
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
SCALER_PROOF_MODE=0 idf.py build
SCALER_PROOF_MODE=0 idf.py -p /dev/ttyACM0 flash
```

The build completed successfully. ESP-IDF reported only the existing
`-Wunused-function` warnings for proof-only diagnostic helpers. The flash
completed with esptool verification of bootloader, partition table, and app.

## Artifact hashes

```text
cc02848dd5171f55352cf26da4f081e374e2f02526a34db96fbf7d2af26b73f1  firmware/esp32p4_scaler_proof/main/main.c
8d7afb27b856b6f22ed82f4c21f319c965b1f32e7ac612fb51705b29de042f39  firmware/esp32p4_scaler_proof/build/esp32p4_scaler_proof.elf
5057452cf41077f17445a883088f58fb93b2e44d7bcc9d59d0f52b450af9bef2  firmware/esp32p4_scaler_proof/build/esp32p4_scaler_proof.bin
3929b906d7e420d7ee9465037cd172dec4f8cb865c92667dd449e9be462ffc55  firmware/esp32p4_scaler_proof/build/bootloader/bootloader.bin
fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17  firmware/esp32p4_scaler_proof/build/partition_table/partition-table.bin
c71522f663ef9882ced3542d4f9b43cc6cb508f9b8f20bbf9168d15bff559e5f  PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof/run_ten_cycles_prime.sh
```

— BronzeGate
