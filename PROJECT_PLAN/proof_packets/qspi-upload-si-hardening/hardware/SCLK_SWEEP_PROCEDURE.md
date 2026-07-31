# `sel=8` SCLK sweep procedure

Date: 2026-07-31  
Board: Tang Nano 20K + ESP32-P4 v1.3  
FPGA: `project_38002d5c_scaler_hwproof.fs` (SHA-256 recorded in `manifest.yaml`)  
Host: `/dev/ttyACM0`  
Assignment: TopazCliff #14547

1. Leave the approved FPGA bitstream loaded; do not reflash the FPGA.
2. Flash the proof-only `SCALER_PROOF_MODE=5` ESP32-P4 application.
3. On boot, verify magic `0x51560002`, configure explicit 1x display state,
   and verify health before upload.
4. Upload 30,720-byte bitmap and 30,720-byte attribute planes at 4 MHz using
   the existing CRC8/retry-enabled `vdp_sdram_write()` path.
5. Verify health after upload.
6. For each requested read rate `2,000,000`, `1,000,000`, `500,000`, and
   `250,000` Hz, perform 30 cycles over these byte-aligned words:
   `0x100004`, `0x100008`, `0x10000C`, `0x100FFC`, `0x101000`, `0x101004`.
7. For each word, log expected value, returned `sel=8` value, and
   `vdp_last_error()`. Once per cycle, log `READ_STATUS sel=0x0A` health.
8. Treat a slower rate returning `0x55555555` where 2 MHz returns zero as
   readback timing/SI evidence. Treat stable zeros at all rates as failure of
   this readback discriminator to explain the residual.

The P4 backend's SPI configuration is unchanged for CS timing:
`cs_ena_pretrans=2`, `cs_ena_posttrans=8`, `dummy_bits=2` for status reads.

— BronzeGate
