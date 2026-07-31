# ESP32-P4 scaler hardware-proof firmware

This is the PM-assigned P4 host application for `scaler-rewrite-hw-proof`.
Build the same source in three modes:

```sh
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
idf.py set-target esp32p4
SCALER_PROOF_MODE=0 idf.py build       # 1x checkerboard regression
SCALER_PROOF_MODE=2 idf.py build       # 2x centered checkerboard
SCALER_PROOF_MODE=3 idf.py build       # 3x centered checkerboard
SCALER_PROOF_MODE=4 idf.py build       # QSPI write-vs-readback discriminator
SCALER_PROOF_MODE=6 idf.py build       # sel=8 double-read lag confirmation
SCALER_PROOF_MODE=7 idf.py build       # display-indirect target color proof
```

The mode-0 image explicitly writes `LOGIC_WIDTH=640`, `LOGIC_HEIGHT=480`, and
`SCALE_CTRL=0` because the FPGA register persists across an MCU reset while
the bitstream remains loaded. Modes 2 and 3 use the existing `libvdp` helpers,
programming `LOGIC_WIDTH`/`LOGIC_HEIGHT` before `SCALE_CTRL`, and keep all bulk
SDRAM uploads at the proven 4 MHz clock.

Mode 4 is a proof-only diagnostic. It keeps the normal `libvdp` upload path,
logs the CRC-status selector before and after each 253-word (506-byte) frame,
and re-reads the two assigned bitmap neighborhoods eight times at 2 MHz. It
does not change the production transport or register contract.

Mode 6 is a proof-only diagnostic for the suspected one-read pipeline lag in
the existing `sel=8` debug readback. It uploads the normal checkerboard, then
issues `READ_STATUS sel=8` twice for each target and immediate neighbor; the
first value is logged as the pipeline-lag candidate and the second value is
the confirmation result. It does not change the production transport or
register contract.

Mode 7 is the authorized display-indirect discriminator. It changes the
proof-only bitmap asset at the two target words (`0x100008`, `0x101000`) and
their immediate neighbors to byte pattern `0xAA` (palette index 2), uploads
through the normal CRC8 path, and enables Mode 0 display fetch. The target
regions should therefore appear in the palette-2 color if SDRAM contains the
uploaded bytes; serial health is logged before upload, after upload, and after
display enable.
