# READ_DONE mode-8 hardware proof

Date: 2026-08-01

Board: Tang Nano 20K + ESP32-P4 Function EV Board (ESP32-P4 v1.3)
Host serial: `/dev/ttyACM0`

## Authorized pair

- FPGA source: `5ef5db2a`; generated RTL SHA-256 `ff01ab71a1758b1844a60459cbfaf2f2e628bf20ed45bcb2ae77e13ede5bccb`
- FPGA SRAM artifact: `fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs`
- FPGA SHA-256: `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2`
- Firmware source: `158b9d7c`; workspace build commit: `70c43d7a`
- Firmware ELF SHA-256: `fd592e3562e8a278b200b0c95f5a0f8ec2d2709c15ed54a441b572e48018907a`
- Firmware BIN SHA-256: `cb977e17bedcfe639382c6d2f16fcd79649e1aca5f66dc252b09521e0249ca8c`
- Partition SHA-256: `fd8026bff850ca0dee41c41305160317fffe604dda30a9bd5a701ac82d96fa17`

## Procedure and recovery

The first SRAM-load attempt failed before programming with
`ftdi_usb_reset failed (-6)`. TopazCliff reviewed this as FT2232 kernel-driver
contention in #14581 and authorized unloading `ftdi_sio`/`usbserial`, checking
JTAG detection, and retrying. After `sudo -n rmmod ftdi_sio usbserial`,
`openFPGALoader --detect` identified Gowin GW2A-18 and the named SRAM load
completed at 100%.

The first host flash was inadvertently rebuilt with the default mode 0 because
`idf.py flash` runs the build dependency without the mode selector. Its serial
output was a valid mode-0 checkerboard regression, not this proof. The firmware
was then explicitly reconfigured, built, hashed, and flashed with
`SCALER_PROOF_MODE=8` for every ESP-IDF command.

## Result

| Check | Result |
|---|---|
| Magic | `0x51560002` |
| Bitmap upload | PASS, 30,720 bytes at 4 MHz |
| Attribute upload | PASS, 30,720 bytes at 4 MHz |
| READ_DONE selector | `0x0C`, bit 0 high-true, reserved bits zero |
| Target `0x100008` | 8/8 `0x55555555`, all polls completed in 1 poll |
| Target `0x101000` | 8/8 `0x55555555`, all polls completed in 1 poll |
| Health before/after upload/read | `raw=0x00000000`, `overflow=0`, `malformed=0` |
| Overall | `READ_DONE_PROOF pass=1` |

Decision fork: both targets returned `0x55555555`, so the SDRAM writes are
clean under this lag-free completion-poll proof. The residual defect pivots to
the existing `sel=8` readback/CDC path; no production host-interface change is
authorized by this result.

Serial evidence:

- Curated proof transcript: `hardware/READ_DONE_SERIAL.md`
- Curated transcript SHA-256: `f52ec1c4513a1e2a480d5dc326bc8d9e0a5f01869f1e089659116ca0310193f3`
- Full raw capture SHA-256: `b86647404db6b89d04c563879e044a22596bff147f68600d00336ad416ef3ed8`
- SRAM-load retry log SHA-256: `6088ed49dbaf9adf9ca80af913db1b079156d52a0e7e91bc89a5bcf5381d7ec1`
- Mode-8 ESP32 flash log SHA-256: `93108bc51f1ea8641928678a968bdf3b0690beae8835b3164dc0ef70fa3882c6`
