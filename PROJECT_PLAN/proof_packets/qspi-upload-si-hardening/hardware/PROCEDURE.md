# 4 MHz CRC-enabled byte-readback stress

1. Load the already-proven `project_38002d5c_scaler_hwproof.fs` into the Tang
   Nano 20K SRAM with `openFPGALoader -b tangnano20k`; this clears sticky FPGA
   transport state without changing RTL.
2. Flash the clean ESP32-P4 scaler proof image from this packet.
3. For each cycle, hard-reset the P4, upload the 30,720-byte bitmap and
   30,720-byte attribute plane at 4 MHz through `vdp_sdram_write()`, switch to
   2 MHz for control/readback, then verify six deterministic bitmap words and
   three transport-health samples.
4. Repeat for 30 cycles. A cycle passes only when the app prints
   `SCALER_PROOF mode=0 pass=1`; any `READBACK FAIL` is a failure even when
   transport health is zero.

Board: Tang Nano 20K with ESP32-P4 Function EV Board, QSPI wiring from the
canonical scaler proof. FPGA bitstream and firmware hashes are in `manifest.yaml`.
