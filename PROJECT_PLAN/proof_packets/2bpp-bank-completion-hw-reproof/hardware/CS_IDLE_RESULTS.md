# CS#-high QSPI reset diagnostic result

Date: 2026-08-01  
Board: Tang Nano 20K + ESP32-P4 v1.3  
Serial: `/dev/ttyACM0`  
Bitstream: `fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs`  
Bitstream SHA-256: `a5a047a23d98293d077f2b0bdc322f375545677ffa53d0722a91be9cf327658c`  
Firmware source commit: `08ee736ae35b62cb3e9257487110ddc73394ac92`

Procedure:

1. Loaded the preserved `a5a047a2` SRAM bitstream with
   `openFPGALoader --board tangnano20k --bitstream ...project_a5a047a2_bankcompletion.fs`.
2. Flashed the committed `SCALER_PROOF_MODE=9` image with `idf.py -p
   /dev/ttyACM0 flash`; all three writes verified.
3. Reset the ESP32-P4. The proof application drove GPIO20 high before
   `vdp_host_init()`, held it high for 1200 ms, then performed the first magic
   read and immediate transport-health read.

Serial evidence: `firmware/cs_idle_serial.log`, SHA-256
`e3f8000d3b4cb778249888b7b6bf8510ad3a386a823c86a4b8f68457a21a9a91`.

Observed result:

```text
CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200
scaler proof mode=9 magic=0x51560002
CS_IDLE_PROOF magic_ok=1 health_raw=0x00000000 health_ok=1
CS_IDLE_PROOF_RESULT pass=1
```

The CS#-high pre-flight diagnostic passed on the previously failing
`a5a047a2` SRAM-loaded bitstream. This confirms the CS# reset hypothesis for
this reproduction and does not by itself authorize the ten-cycle reproof.

— BronzeGate
