> Live project state is maintained in `PROJECT_PLAN/STATUS.md`. This document does not own active-lane status, blockers, or engineering history.

# Runbook: Set Up Development Environment

**Owner:** `BrightForge` / `BronzeGate`  
**Status:** template — requires validation

## Supported environment

- Linux workstation.
- Java / sbt for SpinalHDL.
- Gowin IDE for synthesis.
- ESP-IDF v6.0.2 for ESP32-P4 firmware.
- `openFPGALoader` for FPGA programming.

## Prerequisites

- `sbt` installed.
- `GOWIN_HOME` set or `gw_sh` on PATH.
- `openFPGALoader` installed.
- ESP-IDF v6.0.2 environment sourced.

## Commands

```bash
# Verify sbt
cd /home/itadmin/github/spinalhdlVDP
sbt about

# Verify Gowin
gw_sh --version

# Verify openFPGALoader
openFPGALoader --help

# Verify ESP-IDF
idf.py --version
```

## Expected output

`sbt about` reports Scala 2.13.14 and SpinalHDL 1.12.3.

## Common failures

- Missing `GOWIN_HOME` — set or ensure `gw_sh` is on PATH.
- Wrong ESP-IDF version — source v6.0.2.

## Evidence to save

- Tool version output.
