# READ_DONE option-4 — 3-build STA summary (hardware-ready gate #14574)

**Lane:** qspi-upload-si-hardening (option 4, READ_DONE completion-poll readback)
**Owner:** BrightForge · **PM:** TopazCliff · **Date:** 2026-08-01
**RTL source commit:** `5ef5db2a` (branch `brightforge/read-done-diag`)
**Generated RTL:** `hw/gen/top_tang20k.v` SHA-256 `ff01ab712a1758b1844a60459cbfaf2f2e628bf20ed45bcb2ae77e13ede5bccb`
**Tool:** Gowin V1.9.12.01 headless (`QT_QPA_PLATFORM=minimal QT_OPENGL=software LIBGL_ALWAYS_SOFTWARE=1 gw_sh build.tcl`)
**Device:** GW2AR-LV18QN88C8/I7 (Tang Nano 20K)

## Authoritative bitstream (the artifact BronzeGate flashes)

- **`fpga/tang20k/impl/pnr/project_0c218b9a_readdone.fs`**
- **SHA-256 `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2`**
- This file is byte-identical to the `impl/pnr/project.fs` produced as build 3 of the corrected
  3-build STA run below, preserved read-only to a named path per the artifact-match rule
  (synthesis clobbers the shared `project.fs`).

### Timing of the flashed bitstream (its own `project_tr_content.html`)

Total Negative Slack Summary — **all clocks TNS = 0.000, 0 endpoints**:

| Clock | Setup TNS | Setup endpoints | Hold TNS | Hold endpoints |
|---|---|---|---|---|
| clk_pixel | 0.000 | 0 | 0.000 | 0 |
| clk_x5    | 0.000 | 0 | 0.000 | 0 |
| I_clk     | 0.000 | 0 | 0.000 | 0 |
| clk_sdram | 0.000 | 0 | 0.000 | 0 |
| qspi_sck  | 0.000 | 0 | 0.000 | 0 |

Max Frequency Summary (Actual Fmax vs constraint):

| Clock | Constraint | Actual Fmax | Margin |
|---|---|---|---|
| clk_pixel | 25.200 MHz | 29.101 MHz | +15.5% |
| clk_x5    | 126.000 MHz | 636.273 MHz | +405% |
| clk_sdram | 40.501 MHz | 70.809 MHz | +74.8% |
| qspi_sck  | 40.000 MHz | 152.948 MHz | +282% |

Tightest margin is clk_pixel at +15.5% (well above the ~1% flash-gate floor).

### Resources of the flashed bitstream (`project.rpt.txt`)

| Resource | Used/Avail | % |
|---|---|---|
| Logic | 11378/20736 | 55% |
| Register | 5606/15915 | 36% |
| CLS | 7623/10368 | 74% |
| BSRAM | **40/46** | 87% |
| DSP | 12/24 | 50% |

**BSRAM 40/46 = current production baseline (post scaler-rewrite merge `a442707`, p3b `196765b`).
No new BSRAM. No resource regression.**

## Corrected 3-build STA run (task bj9ocj9x9)

Three independent clean PnR builds from the same source (`5ef5db2a` / gen `ff01ab71`).
TNS extracted with a table-scoped parser (`Total Negative Slack Summary` … `</table>`), 22
setup+hold endpoint rows per build:

| Build | rc | Bitstream SHA-256 | BSRAM | TNS (all clocks) |
|---|---|---|---|---|
| 1 | 0 | `05bc5ede09b2cbba21139dd8cae75ec2ad34f40c0fc765fcddc774f2bfcbdaeb` | 40/46 | 22 endpoints, nonzero: NONE → **TNS=0** |
| 2 | 0 | `6fd0a81f17db852381096d0d3e491c74ae789064530dcabf016418629ebe1353` | 40/46 | **TNS=0** |
| 3 | 0 | `0c218b9a1f6d68fa53ea26dc4e9176fd1d52751cc82ca335a3eb95f0478b31e2` | 40/46 | **TNS=0** ← authoritative (preserved) |

All three builds: TNS=0 on every clock, BSRAM 40/46, identical resource footprint. The three
bitstream SHAs differ — this is documented Gowin non-determinism (same Verilog → different bitstream
SHA); the point of the 3-build STA is to show timing/resources are stable across that
non-determinism, which they are.

### Provenance note (honesty)

An earlier 3-build run (task brmys81gy) preserved bitstreams `build1/2/3_readdone.fs`
(SHAs `e179c3be` / `a174766a` / `030765a0`, same source, identical resources) but used a broken
TNS extractor that scraped the whole report instead of the summary table, so its per-build TNS
was not cleanly captured. That run is **superseded**; the corrected run `bj9ocj9x9` above is the
STA of record, and the flashed artifact `0c218b9a` is a direct member of it with its own
independently-extracted TNS=0 report (shown above). STATUS.md previously cited `6fd0a81f`, which
was never preserved to disk (its preserve-copy failed); the flash artifact is therefore the
preserved `0c218b9a`.

## Gate line items (#14574)

- [x] 3-build STA: **TNS=0 all clocks, all 3 builds**
- [x] **No new BSRAM** (40/46 = baseline)
- [x] **No regression** (identical resource footprint across builds and vs baseline)
- [x] Authoritative bitstream SHA-256 recorded and preserved read-only
