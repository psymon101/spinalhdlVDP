> Live project state is maintained in repository-root `STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Host Transport ABI

**Status:** draft  
**Owner:** `BronzeGate`  
**Reviewer:** `TopazCliff`, `BrightForge`

## Scope

Host-facing transport protocol for configuring the Tang Nano 20K VDP.

## Canonical host path

- **QSPI / ESP32-P4** is the canonical Tang Nano 20K host path.
- Components: `QspiSlave` / `QspiDecoder` / `QspiSdramBridge`.
- Historical i80/ESP32-S3 and legacy SPI paths are retired as primary targets.

## Transport rate

- **4 MHz** is the canonical bulk SDRAM upload clock for the current wiring.
- Legacy SPI contract: 2 MHz SCK, 10 µs CS hold, 20 µs OSR drain.

## Transport health

- Health selector: active word-drain `sel=0x0A`.
- `raw=0x00000000`, `overflow=0`, `malformed=0` expected in clean operation.
- Silent value corruption (no health flag) is a known SI-margin risk at higher
  rates; mitigated by 4 MHz canonical rate.

## Public API

Reusable host logic belongs in `firmware/libvdp/`. Applications remain thin
wrappers.

## References

- `firmware/libvdp/`
- `firmware/GOTCHAS.md`
- `STATUS.md` lanes `QSPI-SI-CEILING-183`, `HAM6 removal + 2bpp indexed replacement`
