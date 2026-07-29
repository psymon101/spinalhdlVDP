# P4 — Gowin PnR (PASS) — external-review-scaler-rewrite

**Tool:** Gowin V1.9.12.01 · **Device:** GW2AR-LV18QN88C8/I7 · **Effort:** place=2 route=2, timing_driven
**Source:** RTL commit `7f8dde6` (P4 timing fix); Verilog regenerated `hw/gen/top_tang20k.v` sha `b246aed77237c9af8c42d60c80da40686c1daec6dfa9354dd4f0d5cbc2b26e46`
**Command:** `QT_QPA_PLATFORM=minimal QT_OPENGL=software LIBGL_ALWAYS_SOFTWARE=1 gw_sh fpga/tang20k/build.tcl`
**Bitstream:** `impl/pnr/project.fs` sha `ca0b7a8324b3ce589e27813d0749a91a042a8766a4aafb690cd443c3e28a08aa` (NOT flashed; sim+PnR lane). HW-proven authority `a5a047a2` preserved to named paths before any PnR.

## Timing — PASS (TNS=0, all clocks)
| Clock | Constraint | Actual Fmax | Margin | TNS |
|---|---|---|---|---|
| **clk_pixel** | 25.200 MHz | **30.705 MHz** | **+21.8%** | **0.000 (0 endpoints)** |
| clk_x5 | 126 MHz | 636.3 MHz | — | 0 |
| clk_sdram | 40.501 MHz | 66.4 MHz | — | 0 |
| qspi_sck | 40 MHz | 165.1 MHz | — | 0 |

clk_pixel logic levels 35 (essentially the pre-scaler baseline of 33; the failing divide build was 82).

## Resource — BSRAM saving preserved
| Resource | Baseline (sink scaler) | Final (ScaleCoordGen) |
|---|---|---|
| **BSRAM** | 42 (33 SDPB + 9 SDPX9B) | **40 (31 SDPB + 9 SDPX9B)** → −2 |
| DSP | 46% | 50% (6 MULT18X18; +2 reciprocal mults) |
| Logic (LUT/ALU) | 11355 (55%) | 11501 (56%) |
| Register | 5612 (36%) | 5680 (36%) (+68 coord/config regs) |
| CLS | 7671 (74%) | 7774 (75%) |
| SSRAM(RAM16) | 332 | 332 |

Structural: 0 `PixelRepeatScaler`/`scaler` refs in the netlist; the −2 BSRAM is the freed 640-deep sink line buffer.

## Timing-closure history (why 3 PnR passes)
| Build | clk_pixel Fmax | TNS | Cause / change |
|---|---|---|---|
| v1 (`5514d1d`) | 14.67 MHz | −435.8 ns | P0 combinational divide + fitScale, 82 levels — the failure P4 caught |
| v2 (`38ee153`) | 23.88 MHz | −16.6 ns | reciprocal-multiply (`floor(x/s)=(x·⌈2¹⁸/s⌉)>>18`) + registered config terms; Y path still combinational to lineBuf |
| **v3 (`7f8dde6`)** | **30.705 MHz** | **0.000** | register sourceX/sourceY/sourceValid → multiply isolated between registers |

The fix keeps 1× byte-identical (VdpTop muxes to hCounter/fillLine at 1×, so the registered coords are unused) and the >1× behavior correct (phase-independent run-length proof tolerates the +1-cycle scaled-mode latency). Full functional re-validation on `7f8dde6`: ScaleCoordGenSim 8/8 exact, ScaleUpFrameCoSim >1× PASS (stripes run-length 1/2/3, checker spacing 32/48), Indexed2bppFine/Frame + DirectColorFrame (bgOrDirect group) + VdpInnerBorder/BitmapDirectColor (io.red group) all 1× byte-identical.
