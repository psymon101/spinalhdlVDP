# Lane 1 ten-cycle reproof — fresh-load cycle 01 recurrence

Date: 2026-08-02  
Authorization: TopazCliff #14615  
Source firmware commit: `48ce715a`  
Firmware ELF SHA-256: `2eadbe69dccca5325ca8499c71ca433a17f8c0a3b17f7c605f2525a712d0338c`  
Firmware BIN SHA-256: `ceaeed136ecba097e2fe1acedec903fa8c8804d2e33cd4dd78c22515ba3bf440`  
Bitstream: `a5a047a2`  
Bitstream SHA-256: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`

The existing ten-cycle runner performed an explicit SRAM load, waited 1.2 s,
reset the ESP32-P4, and applied the probe-instrumented mode-0 firmware. It
stopped at the first magic gate as required; no second cycle or video capture
was attempted.

Observed serial markers:

```text
CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200
CS_POST_INIT_PROBE cs_gpio=20 level=1
SPI_CONFIG cs_io_num=20 cs_ena_pretrans=2 cs_ena_posttrans=8 mode=0 clock_hz=2000000 idle_policy=driver-default
scaler proof mode=0 magic=0x22222222
HEALTH_BEFORE_UPLOAD raw=0x00000000
HEALTH_AFTER_UPLOAD raw=0x00000000
HEALTH_AFTER_ENABLE raw=0x00000000
SCALER_PROOF mode=0 pass=1
```

The later upload/readback checks are not valid campaign proof because the magic
precondition failed. This is a clean recurrence of the original wrong-magic
signature with post-init CS# still high, so the firmware CS-low hypothesis is
not supported. No capture was produced.

Artifacts:

- Serial: `firmware/reprobe_cycle_01_serial.log`, SHA-256 `6a41e693d7ce78b6ad9cd71c24e19ceee1e7aa6d8d23568d6d0a2a5439db56ff`.
- SRAM loader: `hardware/reprobe_cycle_01_openfpgaloader.log`, SHA-256 `f130c7690c698dc87ddbaadd5d181bd094106d92e7b42cbd3d99076b60b8a71b`.

Disposition: STOP after cycle 01 and escalate to TopazCliff/BrightForge for the
approved fallback diagnostic bitstream. No RTL or runner changes were made.

— BronzeGate
