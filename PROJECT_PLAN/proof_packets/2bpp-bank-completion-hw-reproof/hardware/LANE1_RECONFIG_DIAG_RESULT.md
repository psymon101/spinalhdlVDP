# Lane 1 reconfiguration diagnostic result

Date: 2026-08-02

PM authorization: MCP mail #14628. This was one fresh diagnostic-bitstream
reconfigure/readout only; no campaign cycle 02 or ten-cycle campaign was run.

## Inputs

- Diagnostic bitstream:
  `/home/itadmin/github/lane1-reconfig-diag/fpga/tang20k/impl/pnr/project_eaad44f8_lane1diag.fs`
- Bitstream SHA-256:
  `eaad44f8b012081f401b03840ea855aa50f45ad765b2c42f239a6b050ddf1b67`
- Diagnostic source branch/commit: `brightforge/lane1-reconfig-diag`, `506600c7`
- Firmware proof-only source commit: `f0531869` (base mode-0 source `48ce715a`)
- FPGA load: `openFPGALoader --board tangnano20k`, SRAM load PASS
- Post-load settle: 1.2 s
- Firmware flash: ESP32-P4 v1.3, ESP-IDF v6.0.2, flash verification PASS

## Observed serial markers

```text
CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200
CS_POST_INIT_PROBE cs_gpio=20 level=1
SPI_CONFIG cs_io_num=20 cs_ena_pretrans=2 cs_ena_posttrans=8 mode=0 clock_hz=2000000 idle_policy=driver-default
scaler proof mode=0 magic=0x22222222
LANE1_RECONFIG_DIAG sel=0x0D raw=0x00004045 sawCsHigh=1 csnNow=0 sawSclk=1 firstPhase=0 firstBitc=1 txnCount=4 err=0
```

## Interpretation

The FPGA did observe CS# high since configuration (`sawCsHigh=1`) and saw
SCLK activity (`sawSclk=1`). The first transaction ended in phase 0 (`CMD`),
with bit counter 1, rather than phase 5 (`RDATA`). Therefore the discriminator
selects: CS# reset fired, but the first transaction was mis-framed at the
configuration-boundary reset-release/first-SCLK interaction. `csnNow=0` is the
expected live state during the recovered transaction; it does not contradict
the sticky `sawCsHigh` result. `txnCount=4` reflects the recovered readout
traffic and is diagnostic-grade only.

This result does not prove a production RTL defect and does not authorize
modifying `a5a047a2`. BrightForge/TopazCliff must direct the next diagnostic or
fix. The ten-cycle reproof remains blocked.

Evidence:

- Serial: `firmware/lane1_diag_readout_serial.log`, SHA-256
  `015a5cc8bd3da8f3f1b0844f75645c446fce3c10ece7e55c63858fcddf2cfb34`
- Loader: `hardware/lane1_diag_readout_openfpgaloader.log`, SHA-256
  `098c64525677820e37de71d8ac40bc947dd60884fe646210cc0c205489a02b75`
