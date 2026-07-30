# standalone-diagnostic-build — hardware results

Board: Tang Nano 20K (GW2A(R)-18C). Bitstream `60b23c77…` (`project_60b23c77_diagnostic.fs`).
Flash: `openFPGALoader -b tangnano20k` SRAM load (clean POR by BrightForge, POR cold-start proxy per tierB methodology). Capture: `/dev/video0` YUYV 720×480 (FPGA 640×480, UVC H-scale 640→720).

## Cold-POR reliability — PASS 10/10
Script `coldpor_loop.sh`; log `coldpor_results.log`. Each cycle: SRAM-load POR →
`sleep 4` (let the UVC re-lock past the ~79 ms HdmiCleanStart mute + HDMI re-sync) →
12-frame capture → verify full-frame content.

All 10 cycles: **non-black 3.3%, rows-with-content 480/480, cols-with-content 720/720 ⇒ PASS**
(HDMI locked, full-screen grid every cycle). The captured grid frame is **byte-identical
(sha `7803de18…`) across cycles 1/5/10 and the reference** — the pattern is deterministic
and perfectly stable across power cycles.

## Visual
Clean full-screen **grid** (evenly-spaced lines across the whole 640×480) at 1× scale
(no auto-center), with the cyan transport canary at the lower-right corner. No host
interaction, no QSPI traffic, no SDRAM upload — the on-chip bootstrap runs and the L0
test pattern renders directly.
Reference: `captures/diagnostic_grid_reference.png`; per-cycle: `captures/coldpor_cycle{01,05,10}.png`.

## Debug note (first build 3e96c1d3 → fixed 60b23c77)
The first diagnostic build displayed a SPARSE grid (L0 on only the ~60 every-8th-line
linestate bands) because `effectiveL0Enable = per-line linestate AND global LAYER_ENABLE`
and `LinestateStore.defaultInit` ships all lines L0-off. Fixed by shipping the linestate
L0-enabled default in diagnosticMode (all 480 lines) — see `../source/` and commit `ac90dfbe`.

## Capture-timing note
Capturing immediately after a POR flash yields all-zero frames — the UVC device loses HDMI
lock and needs ~seconds to re-lock. The loop's `sleep 4` after each flash is required; a
back-to-back grab is a capture artifact, not a display fault.
