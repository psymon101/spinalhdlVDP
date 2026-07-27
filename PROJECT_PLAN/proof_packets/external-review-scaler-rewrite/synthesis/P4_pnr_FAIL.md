# P4 — Gowin PnR (FAIL, timing) — external-review-scaler-rewrite

**Tool:** Gowin V1.9.12.01 · **Device:** GW2AR-LV18QN88C8/I7 · **Effort:** place=2 route=2, timing_driven
**Source:** RTL commit `5514d1d` (P1b); Verilog regenerated `hw/gen/top_tang20k.v` sha `4d38976e34e151cedd6c70d4d06a80b29df5ce2c2a7c442e4821e4ccb61ccb2d`
**Command:** `QT_QPA_PLATFORM=minimal QT_OPENGL=software LIBGL_ALWAYS_SOFTWARE=1 gw_sh fpga/tang20k/build.tcl`
**Bitstream produced:** `impl/pnr/project.fs` sha `ce37a377...` (NOT flashed; sim+PnR lane). Authority `a5a047a2` preserved to `project_a5a047a2_bankcompletion.fs` + `project_preP4_authority_a5a047a2.fs` before PnR.

## Resource — BSRAM saving CONFIRMED
| Resource | Baseline (with sink scaler, Jul26) | Current (ScaleCoordGen, scaler retired) |
|---|---|---|
| BSRAM | 42 (33 SDPB + 9 SDPX9B), 92% | **40 (31 SDPB + 9 SDPX9B), 87%** → −2 |
| Logic | 11355 (55%) | 11702 (57%) |
| Register | 5612 (36%) | 5580 (36%) |
| CLS | 7671 (74%) | 7778 (76%) |
| DSP | 46% | 46% |

Structural: 0 `PixelRepeatScaler`/`scaler` refs in the current netlist; 140 `ScaleCoordGen`/`logicalX/Y` refs. The −2 BSRAM is the freed 640-deep sink line buffer. (`pixelArea_video/lineBuf` in the report is a separate legitimate buffer, not the retired scaler.)

## Timing — FAIL (blocker)
| Clock | Constraint | Actual Fmax | TNS |
|---|---|---|---|
| **clk_pixel** | 25.200 MHz | **14.666 MHz** | **−435.825 ns (48 endpoints)** |
| clk_x5 | 126 MHz | 635.7 MHz | 0 |
| clk_sdram | 40.501 MHz | 59.05 MHz | 0 |
| qspi_sck | 40 MHz | 143.8 MHz | 0 |

Baseline (with scaler) clk_pixel Fmax was 30.870 MHz, TNS 0.

Worst setup path (−28.499 ns): From `pixelArea_video/logicHeightReg_1_s1/Q` To `pixelArea_video/lineBuf/.../DI[4]`, data delay 68.147 ns vs 39.682 ns period, 82 logic levels.

## Root cause + fix
`ScaleCoordGen` is fully combinational by design (P0 chose a combinational divide `relX/scaleXEff` to keep zero added latency for 1× byte-identity). The divide + `fitScale` (6-way multiply/compare) + clamp, feeding the render path combinationally, is an 82-level path that cannot close at 25.2 MHz.

FIX: replace the divide with a COUNTER/accumulator (registered `srcX`/`srcY` + combinational `+1` for the current column/line), giving zero-added-latency source coordinates on a SHORT path — keeps 1× byte-identical (no Landmine-1 latency reopen) and the module IO unchanged (ScaleCoordGenSim + ScaleUpFrameCoSim re-validate). Then re-run the full 1× regression + >1× proof + re-PnR.
