# READ_DONE completion-poll proof firmware build

Date: 2026-08-01

Interface-checkpoint approval: TopazCliff #14571, BronzeGate #14572.
The proof-only firmware uses `READ_STATUS` selector `0x0C`, bit 0 high-true,
with bits [31:1] required zero. The status clears on the `0x0327` HI arm write
and sets after the settled pixel-domain result latch. The 32-bit data remains
on the existing `sel=8` path.

Source commit: `158b9d7c`.
Proof mode: `SCALER_PROOF_MODE=8` in
`firmware/esp32p4_scaler_proof/main/main.c`.
The reproducible build was performed from workspace commit `70c43d7a`;
the mode-8 source file is unchanged from `158b9d7c` (SHA-256
`e674c1cc76940a656bc8530bb9736bf0a509d06c67763aaafa659847274c000a`).

Build environment:

- ESP-IDF v6.0.2
- ESP32-P4 v1.3
- Build command:
  `source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh && SCALER_PROOF_MODE=8 idf.py reconfigure && SCALER_PROOF_MODE=8 idf.py build`
- Result: PASS; partition size check PASS.
- The shell emitted the known missing `/home/itadmin/.agent-homes/bronzegate/home/.cargo/env` profile warning; ESP-IDF activation and build completed successfully.
- Warnings are compile-time unused-function warnings for proof modes 4–7, which are excluded by `SCALER_PROOF_MODE=8`.

Mode 8 uploads bitmap and attribute planes at 4 MHz, arms each target with
`0x0326`/`0x0327`, polls `sel=0x0C` until bit 0 is high, and reads the word
through `sel=8`. It tests `0x100008` and `0x101000` for eight repeats and logs
poll counts, reserved-bit validity, upload result, and transport health.

Artifact hashes:

| Artifact | SHA-256 |
|---|---|
| `esp32p4_scaler_proof.elf` | `fd592e3562e8a278b200b0c95f5a0f8ec2d2709c15ed54a441b572e48018907a` |
| `esp32p4_scaler_proof.bin` | `cb977e17bedcfe639382c6d2f16fcd79649e1aca5f66dc252b09521e0249ca8c` |
| `partition-table.bin` | `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17` |

The PM-authorized hardware proof completed after unloading the FTDI serial
drivers and retrying the preserved bitstream. See
`hardware/READ_DONE_RESULTS.md`; no production firmware or host-driver
behavior was changed.
