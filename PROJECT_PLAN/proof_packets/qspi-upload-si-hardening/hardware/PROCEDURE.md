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

## Focused discriminator procedure

1. Keep the approved `project_38002d5c_scaler_hwproof.fs` loaded and connect
   the P4 on `/dev/ttyACM0`.
2. Build and flash with `SCALER_PROOF_MODE=4` using the command in
   `firmware/BUILD.md`.
3. Capture the serial output. The proof mode uploads both 30,720-byte planes
   through `libvdp` at 4 MHz, reads CRC status selector `0x0B` before and
   after each frame, then reads the two assigned neighborhoods at 2 MHz.
4. Accept the discriminator only when all 13 addresses have eight successful
   reads and the values can be classified as stable or varying. Record the
   exact frame/chunk map and counter transitions in `DIAGNOSTIC_RESULTS.md`.
