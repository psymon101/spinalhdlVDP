# P3b Gowin PnR — PASS

**Tool:** Gowin V1.9.12.01 (`gw_sh fpga/tang20k/build.tcl`, place=2 route=2 timing_driven correct_hold_violation)
**Device:** GW2AR-LV18QN88C8/I7
**Source Verilog:** `hw/gen/top_tang20k.v` sha `8bac5ca2f427d5eb93d5436705279709c13994a5461561662d8c7dd9a9f3a60d`
(P3b RTL; contains the `bitmapSrcRow` grant-boundary detector)
**Bitstream:** `impl/pnr/project.fs` sha `8772d0c198ca486b6d7fba975da71fc1f87f48578b8dd30f07bf522e811c37ae`
(preserved to `impl/pnr/project_8772d0c1_p3b.fs`; sim+PnR lane — not flashed)

## Timing — PASS (TNS=0 all clocks, setup + hold)

| Clock | Setup TNS | Hold TNS | Failing endpoints |
|---|---|---|---|
| clk_pixel | 0.000 | 0.000 | 0 |
| clk_x5    | 0.000 | 0.000 | 0 |
| I_clk     | 0.000 | 0.000 | 0 |
| clk_sdram | 0.000 | 0.000 | 0 |
| qspi_sck  | 0.000 | 0.000 | 0 |

clk_pixel Fmax **29.148 MHz** vs 25.2 constraint (**+15.7% margin**). The small drop
vs the pre-P3b 30.705 MHz reflects the added `logicY>>1` grant-boundary comparison;
still comfortably above constraint with TNS=0.

## Resources — no unexpected growth vs scaler-hw-proof (38002d5c)

| Resource | P3b | scaler-hw-proof | Δ |
|---|---|---|---|
| Logic    | 11498/20736 (56%) | 11501 (56%) | −3 |
| Register | 5685/15915 (36%)  | 5680 (36%)  | +5 (bitmapSrcRow/bitmapSrcRowPrev) |
| CLS      | 7688/10368 (75%)  | 7774 (75%)  | −86 |
| BSRAM    | **40/46 (87%)**   | **40/46**   | **0 (no new BSRAM)** |
| DSP      | 12/24 (50%)       | 12          | 0 |

Fetch-side coordinate remap adds only the grant step-detector registers; BSRAM/DSP
unchanged. Report: `project.rpt.txt`.

## Verdict
PnR PASS: TNS=0, no unexpected resource growth. Sim+PnR lane (no HW flash unless PM
opens a gate).
