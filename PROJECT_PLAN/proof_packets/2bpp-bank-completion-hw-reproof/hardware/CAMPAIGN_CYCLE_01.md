# Lane 1 reproof campaign — cycle 01 blocker

Date: 2026-08-01  
Assignment: TopazCliff #14605  
Campaign runner: `run_ten_cycles.sh`, commit `38e00925`  
Firmware source: `7a93cfc1`  
Bitstream: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`

The first PM-authorized campaign cycle was stopped at the mandatory magic
precondition. The explicit SRAM load completed successfully and the firmware
logged the mandatory CS#-high pre-flight (`GPIO20 level=1`, 1200 ms). However,
the first magic read again returned `0x22222222` rather than `0x51560002`.

Evidence:

- Serial: `firmware/cycle_01_serial.log`, SHA-256
  `54ac6f38762a1b351f5abb4a3982141d69fbc8e0261d85e9288cf8b2bcd2e171`.
- SRAM loader: `hardware/cycle_01_openfpgaloader.log`, SHA-256
  `f130c7690c698dc87ddbaadd5d181bd094106d92e7b42cbd3d99076b60b8a71b`.

The same serial capture continued to show clean health and content checks
after the wrong magic (`HEALTH_BEFORE_UPLOAD`, `HEALTH_AFTER_UPLOAD`, and
`HEALTH_AFTER_ENABLE` all `raw=0x00000000`; six `READBACK PASS` lines; final
`SCALER_PROOF mode=0 pass=1`). These later checks are not valid campaign proof
because the first magic precondition failed.

Per #14605, no second cycle was attempted. The ten-cycle gate remains open and
the lane is escalated to TopazCliff/BrightForge for review.

— BronzeGate
