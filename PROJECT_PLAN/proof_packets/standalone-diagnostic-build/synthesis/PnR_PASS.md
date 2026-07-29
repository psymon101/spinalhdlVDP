# standalone-diagnostic-build — Gowin PnR PASS

**Tool:** Gowin V1.9.12.01 (`gw_sh fpga/tang20k/build_diagnostic.tcl`, place=2 route=2 timing_driven correct_hold_violation)
**Device:** GW2AR-LV18QN88C8/I7
**Top:** `top_tang20k_diagnostic` (native 640×480, reuses `tang20k_hdmi.cst`/`.sdc`)
**Source Verilog:** `hw/gen/top_tang20k_diagnostic.v` sha `5c579f2a5b15dc648278d73ee67fcd223cc746c9636690ac54244de41f1ae62c` (git `ac90dfb`, with the linestate L0-default fix)
**Bitstream:** `impl/pnr/project.fs` sha `60b23c77219faa0067cb74baad108be53a7244480134013ddeae99e35a2849cb`
(preserved to `impl/pnr/project_60b23c77_diagnostic.fs`)

NOTE: an earlier build `3e96c1d3` (Verilog `f8718c9e`, pre-fix) had the same TNS=0/
resources but displayed a sparse grid (L0 only on every-8th linestate line) — see
`../hardware/RESULTS.md`. The `60b23c77` build (linestate ships L0-enabled on all
lines in diagnosticMode) is the validated full-screen-grid bitstream.

## Timing — PASS (TNS=0 all clocks, setup + hold)
clk_pixel / clk_x5 / I_clk / clk_sdram / qspi_sck — all Setup TNS 0.000 / Hold TNS 0.000 / 0 failing endpoints.
clk_pixel Fmax **30.052 MHz** vs 25.2 constraint (**+19.3%**).

## Resources — no new alarms
| Resource | diagnostic | production (`8b241328`) | note |
|---|---|---|---|
| Logic    | 11537/20736 (56%) | 11498 (56%) | ~same |
| Register | 5417/15915 (35%)  | 5685 (36%)  | −268 (no host/QSPI-driven paths exercised) |
| CLS      | 7637/10368 (74%)  | 7688 (75%)  | ~same |
| BSRAM    | **37/46 (81%)**   | 40/46       | −3 (L0 test-pattern path drops SDRAM bitmap line-buffers) |
| DSP      | 12/24 (50%)       | 12          | same |

No new resource growth (BSRAM actually lower). Reports: `project.rpt.txt`, `project_tr_content.html`.

## Verdict
PnR PASS: TNS=0, +19.3% margin, no new resource alarms. Ready to flash for the
≥10 cold-POR hardware proof.
