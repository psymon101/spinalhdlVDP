# Hardware proof procedure — scaler-rewrite-hw-proof

Board: Tang Nano 20K (GW2AR-LV18QN88C8/I7). Host: ESP32-P4 over QSPI.
Bitstream under test: fresh Gowin build of `top_tang20k.v` (scaler-rewrite RTL
`7f8dde6`, current HEAD `5f82f94`); see `generated_rtl/REGEN_provenance.md`.
Baseline: HW-proven `a5a047a2…` (`2bpp-hardware-reproof-4mhz`).

Flash (BrightForge, clean POR by me):
```
openFPGALoader -b tangnano20k fpga/tang20k/impl/pnr/project.fs
```
Reconfigure clears SDRAM → host MUST re-upload content after each flash.

Capture (BrightForge): direct `/dev/video0` YUYV 720x480, 3 frames, per
`reference_bench_fpga_direct_usb`/tierB methodology (no `--query-dv-timings` on
this UVC device; detect HDMI lock by frame-content consistency).

---

## Phase B — 1× regression (must match baseline)

Goal: prove the source-coordinate scaler at SCALE_CTRL=0 (=1×, mux-bypassed in
VdpTop) is byte-equivalent to the HW-proven `a5a047a2` baseline.

1. BrightForge: flash scaler bitstream; verify openFPGALoader exit 0; re-hash `.fs`.
2. BronzeGate: upload the SAME 2bpp checkerboard content used for the
   `a5a047a2` reproof (default registers; do NOT write SCALE_CTRL). Report
   transport health `sel=0x0A` (raw=0/overflow=0/malformed=0) + readback PASS.
3. BrightForge: capture 3× `/dev/video0` YUYV frames; hash each.
4. Compare to the `a5a047a2` reproof baseline frames:
   - raw YUYV baseline SHA-256 `a2094a30…`
   - PNG frame baseline SHA-256 `d35ecc33…`
   PASS = byte-identical (or, if capture-chain jitter, visually identical
   checkerboard with cyan canary and no geometry change).

## Phase C — >1× scaled (bezel-test discriminator, GOTCHA-13)

Goal: prove 2×/3× source-coordinate repetition + auto-center on real hardware.
Registers already host-writable (no ABI change): `SCALE_CTRL 0x0349`
([2:0]=scaleX, [6:4]=scaleY, [7]=autoCenter), `LOGIC_WIDTH 0x034A`,
`LOGIC_HEIGHT 0x034B`.

Firmware (BronzeGate handoff — libvdp API exists: `vdp_mode0_scale_ctrl()`,
`vdp_mode0_set_scale_ctrl()`, `vdp_mode0_set_logic_size()`), ordering per
GOTCHA-12 (size first, then scale):
```
vdp_mode0_set_logic_size(logicW, logicH);         // e.g. 320x240
vdp_mode0_set_scale_ctrl(vdp_mode0_scale_ctrl(2,2,true));   // 2x, autoCenter
```
1. 2× run: logicW/H chosen so 2·logicW ≤ 640; upload a deterministic pattern
   (checkerboard or vertical stripes). Predict bezel = (640 − 2·logicW)/2 and
   (480 − 2·logicH)/2.
2. BrightForge: capture; verify (a) auto-center bezel width matches prediction,
   (b) stripe run-length == scaleX, (c) checkerboard tile spacing == 16·scale.
3. Repeat with 3× (3·logicW ≤ 640).
4. Classification (CyanPeak to review): exact / visually-equivalent / divergent.

## Result classification & baseline references
- 1× baseline (a5a047a2): STATUS `2bpp-hardware-reproof-4mhz` — YUYV `a2094a30…`,
  PNG `d35ecc33…`, serial `870fde17…`.
- All capture settings, exact register writes, and per-frame hashes recorded in
  `captures/` and `hardware/RESULTS.md` (filled at capture time).
