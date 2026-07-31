# `sel=8` SCLK sweep results

Date: 2026-07-31  
Lane: `qspi-upload-si-hardening`  
Assignment: #14547; execution acknowledged in #14549  
FPGA: approved `38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`  
Firmware: `SCALER_PROOF_MODE=5`, ESP-IDF v6.0.2

## Observed serial proof

Boot identified magic `0x51560002`. The app logged:

```text
SWEEP_START rates=2000000,1000000,500000,250000 cycles=30 cs_post=8 targets=0x100008,0x101000 neighbors=word+-1
SWEEP_SUMMARY hz=2000000 reads=180 pass=60 zeros=180 errors=0
SWEEP_SUMMARY hz=1000000 reads=180 pass=60 zeros=180 errors=0
SWEEP_SUMMARY hz=500000 reads=180 pass=60 zeros=180 errors=1
SWEEP_SUMMARY hz=250000 reads=180 pass=60 zeros=180 errors=0
SWEEP_RESULT pass=0
SWEEP_DONE pass=0
```

The `pass=60` count at each rate is the two expected-zero neighbors per cycle.
The four expected-`0x55555555` words (`0x100008`, `0x10000C`, `0x101000`,
`0x101004`) returned `0x00000000` throughout. The two expected-zero words
(`0x100004`, `0x100FFC`) returned zero. Thus each rate produced 120 target/
expected-nonzero mismatches and 60 expected-zero matches. All 30 health polls
per rate were `raw=0x00000000 overflow=0 malformed=0 err=0`. The one 0.5 MHz
read error was:

```text
SWEEP_READ hz=500000 cycle=28 addr=0x101004 expected=0x55555555 got=0x00000000 ok=0 err=5
```

No slower requested rate returned the expected `0x55555555`; therefore this
experiment does not support a readback timing/SI explanation for the fixed
zeros. It leaves the lane blocked on the next approved discriminator:
display-indirect readback or physical QSPI bus capture. This is a correlation
result, not a new root-cause claim.

## Evidence source

The complete monitor output was captured during the run. Curated result lines
above are reproduced from the ESP-IDF monitor log generated for build
`idf_py_stdout_output_3737053`; exact firmware and partition hashes are in
`manifest.yaml` and `hashes.sha256`.

No production firmware/RTL, host command, register, or CS timing change was
made.

— BronzeGate
