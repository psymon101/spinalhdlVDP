# Resource and Timing Budget

Foundation Gate 0 must lock the current baseline.

| Resource | Baseline | Warning threshold | Hard ceiling |
|---|---:|---:|---:|
| LUT | TBD | TBD | device limit minus reserve |
| Block RAM | TBD | TBD | device limit minus reserve |
| DSP | TBD | TBD | device limit minus reserve |
| PLL | TBD | TBD | device limit |
| I/O | TBD | TBD | board routing limit |
| Worst slack | TBD | 0 ns | < 0 ns blocks release |

Every platform design checkpoint includes an estimated and measured delta.
Resource growth beyond the approved threshold requires an ADR.
