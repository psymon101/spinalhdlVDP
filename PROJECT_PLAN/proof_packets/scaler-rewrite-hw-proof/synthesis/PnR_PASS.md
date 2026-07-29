# Fresh Gowin PnR — scaler-rewrite-hw-proof (Phase A)

**Tool:** Gowin V1.9.12.01 (`gw_sh fpga/tang20k/build.tcl`)
**Device:** GW2AR-LV18QN88C8/I7 (GW2AR-18C)
**Options:** place=2, route=2, timing_driven=1, correct_hold_violation=1, verilog_std sysv2017
**Source Verilog:** `hw/gen/top_tang20k.v` sha `662dcfad52c017cec92b16c881ca361f26b791e4c4310f47645cd1e108212704`
(logic ≡ P4 `b246aed7`; only the `// Git hash` header comment differs — RTL `7f8dde6`, HEAD `5f82f94`; see `../generated_rtl/REGEN_provenance.md`)
**Bitstream produced:** `impl/pnr/project.fs` sha `38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`
(preserved to `impl/pnr/project_38002d5c_scaler_hwproof.fs`)
**Build finished:** Mon Jul 27 19:51:18 2026

## Timing — PASS (TNS=0, all clocks, setup + hold)

Total Negative Slack Summary (Endpoints TNS / #failing endpoints):

| Clock | Setup TNS | Hold TNS | Failing endpoints |
|---|---|---|---|
| clk_pixel | 0.000 | 0.000 | 0 |
| clk_x5    | 0.000 | 0.000 | 0 |
| I_clk     | 0.000 | 0.000 | 0 |
| clk_sdram | 0.000 | 0.000 | 0 |
| qspi_sck  | 0.000 | 0.000 | 0 |

Max Frequency Summary:

| Clock | Constraint | Actual Fmax | Margin |
|---|---|---|---|
| clk_pixel | 25.200 MHz | **30.705 MHz** | +21.8% |
| clk_x5    | 126.000 MHz | 636.273 MHz | — |

Matches the P4 sim+PnR result exactly (clk_pixel 30.705 MHz, TNS=0).

## Resources

| Resource | Usage | Util |
|---|---|---|
| Logic     | 11501/20736 | 56% |
| Register  | 5680/15915  | 36% |
| CLS       | 7774/10368  | 75% |
| BSRAM     | **40/46**   | 87% |
| DSP       | 12/24       | 50% |
| I/O Port  | 21/66       | 32% |

BSRAM 40 (−2 vs the pre-scaler 42 — the retired sink line buffer). DSP 12 (+2
reciprocal multiplies). No surprise BSRAM growth. Reports: `project.rpt.txt`,
`project_tr_content.html`.

## Verdict
Phase A (build) PASS. Fresh bitstream `38002d5c…` from verified scaler-rewrite
source, TNS=0, clean fit. Ready to flash (Phase B/C gated on BronzeGate content +
scaled-mode firmware). HW authority `a5a047a2…` preserved; not overwritten.
