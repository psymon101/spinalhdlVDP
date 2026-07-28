# ESP32-P4 scaler hardware-proof firmware

This is the PM-assigned P4 host application for `scaler-rewrite-hw-proof`.
Build the same source in three modes:

```sh
source /home/itadmin/.agent-homes/bronzegate/home/esp/esp-idf-v6.0.2/export.sh
idf.py set-target esp32p4
SCALER_PROOF_MODE=0 idf.py build       # 1x checkerboard regression
SCALER_PROOF_MODE=2 idf.py build       # 2x centered checkerboard
SCALER_PROOF_MODE=3 idf.py build       # 3x centered checkerboard
```

The mode-0 image leaves `SCALE_CTRL` at reset/default 1x. Modes 2 and 3 use
the existing `libvdp` helpers, programming `LOGIC_WIDTH`/`LOGIC_HEIGHT` before
`SCALE_CTRL`, and keep all bulk SDRAM uploads at the proven 4 MHz clock.
