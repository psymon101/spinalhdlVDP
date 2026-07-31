# Refresh-pressure hardware cross-check procedure

Date: 2026-07-31

1. Verify the approved FPGA image hash:

   ```text
   fpga/tang20k/impl/pnr/project_38002d5c_scaler_hwproof.fs
   38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09
   ```

2. Do not reconfigure the FPGA. Flash the mode-4 ESP32-P4 image and run 30
   cold-start-equivalent monitor resets. For each cycle, record the final
   `DIAG_READ_RESULT`, `DIAG_RESULT`, and health fields. The mode-4 monitor
   reset was performed with the `idf_monitor` control sequence Ctrl-T, Ctrl-R.

3. Flash the mode-0 ESP32-P4 image without changing the FPGA image and run 30
   monitor resets using the same control sequence. Record `SCALER_PROOF`, both
   target readback lines, and health.

4. Compare failure rates for the two display-workload conditions. The target
   words are `0x100008` and `0x101000`, both expected to contain
   `0x55555555`.

The test changes only display-layer enable/fetch workload. It does not change
the QSPI clock, register contract, upload framing, retry behavior, FPGA image,
or production RTL.
