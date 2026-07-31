# Refresh-pressure hardware cross-check results

Date: 2026-07-31
Board: Tang Nano 20K + ESP32-P4 v1.3, `/dev/ttyACM0`
FPGA image: `project_38002d5c_scaler_hwproof.fs`
FPGA SHA-256: `38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`

## Results

| Condition | N | Pass | Fail | Target mismatches | Health |
|---|---:|---:|---:|---:|---|
| Mode 4, layer disabled / low display workload | 30 | 0 | 30 | 60 (2/cycle) | `raw=0`, `overflow=0`, `malformed=0` |
| Mode 0, layer enabled / display fetch active | 30 | 0 | 30 | 60 (2/cycle) | `raw=0`, `overflow=0`, `malformed=0` |

Mode 4 returned `DIAG_READ_RESULT pass=0 repeats=8 addresses=13` and
`DIAG_RESULT pass=0` on all 30 cycles. Its expected-`0x55555555` target
words at `0x100008` and `0x101000` read as `0x00000000` on every cycle.

Mode 0 returned `SCALER_PROOF mode=0 pass=0` on all 30 cycles. The same two
target words failed on every cycle (`expected=0x55555555 got=0x00000000`).
The sampled zero neighbors remained zero. Uploads completed at 4 MHz and
readbacks at 2 MHz. No CRC/retry or transport-health error was reported.

## Disposition

The residual did not scale between the two tested display workloads: both
conditions were deterministic 30/30 failures at the same two addresses. This
is a workload-correlation result only; it does not establish a mechanism or
exonerate refresh timing. The true cause remains open as directed by
TopazCliff #14531 and BrightForge #14533. No production firmware, RTL, or
bitstream change was made.

## Rule 10 prior-art citation

Searched: `PROJECT_PLAN/TASKS_HISTORY.md`, `PROJECT_PLAN/TASKS/*/`,
`PROJECT_PLAN/archive/artifacts/`, `PROJECT_PLAN/archive/tasks/`, `kb/*/`,
`firmware/GOTCHAS.md`, MCP memory, and git history.

Found: `BurstRefreshDataSurvivalSim` in
`hw/spinal/spinalhdlvdp/BurstRefreshDataSurvivalSim.scala` and its coverage
record in `PROJECT_PLAN/proof_packets/qspi-upload-si-hardening/sim_coverage_matrix_BrightForge.md`
(main `6e6a1f3`) prove real SDRAM write/refresh/readback but bypass the
transport/bridge upload path. `firmware/GOTCHAS.md` GOTCHA-035 fixes the
canonical bulk rate at 4 MHz; GOTCHA-030/031 document transport/SDRAM limits.
`PROJECT_PLAN/archive/tasks/TASK_R4_2_L0_FETCH_SCROLL_JITTER.md` and
`kb/gowin/GOTCHAS.md` contain refresh-pressure/scheduling history but no
equivalent upload-path hardware arbiter. Git refs `989f0315`, `684adfa9`,
`167438a5`, and memory `8f55f7315e63dab6530b2b1819d5614870c7d3bd0fa0e7e6c056a834f4e82bad`
were also checked.
