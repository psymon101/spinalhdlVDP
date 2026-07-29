# Hardware results — scaler-rewrite-hw-proof

## Phase A hardware smoke — HDMI bring-up / lock — PASS (2026-07-27)

**Bitstream flashed:** `project_38002d5c_scaler_hwproof.fs` sha
`38002d5c2bd1ca00c9460fc0349874a5e0f65afad120ab19c26b2144d41b9c09`
(fresh scaler-rewrite build; RTL `7f8dde6`, Verilog `662dcfad`≡`b246aed7`).

**Flash:** `openFPGALoader -b tangnano20k …_scaler_hwproof.fs` → SRAM load, exit 0
(clean POR by BrightForge). Board: Tang Nano 20K (GW2A(R)-18C). Bitstream bytes
re-verified `38002d5c…` before flash.

**Capture:** `/dev/video0` YUYV 4:2:2 720x480, ffmpeg 6.1.1.
- 3 PNG frames byte-identical: sha `12038428e646bfc556aea90c5163b21376f6df8b374455efac5b8875ef8311d2` (temporally stable → HDMI locked, no jitter).
- Raw YUYV single frame (720×480×2 = 691200 B): sha `a81f1d9b4586ddf883f1c006b95ac576153b5a5fe70eba7976234793f097f6e8`.
- signalstats: YMIN=0, YMAX=239, YAVG=0.173, **YDIF=0** (frame-to-frame identical).

**Visual:** clean black POR backdrop across the full 720×480 frame with the cyan
transport canary at the lower-right corner (expected canary position
[624,639]×[464,479]). SDRAM empty (no content uploaded yet) → backdrop is the POR
attr→bank4→palette[64]=black, exactly as expected.

**Verdict:** the fresh scaler-rewrite bitstream `38002d5c` configures the FPGA,
locks HDMI, and emits a valid, temporally stable 720×480 frame with the correct
backdrop + canary on real silicon. Basic hardware bring-up de-risked. No FPGA
reflash needed for Phase B/C — content upload + SCALE_CTRL writes go over QSPI to
this running bitstream.

## Phase B — 1× regression vs a5a047a2 — FIRMWARE PASS (2026-07-27)

BronzeGate flashed the ESP32-P4 proof app against the active scaler bitstream
`38002d5c…`, uploaded the deterministic 320×240 2bpp checkerboard and attr
plane at 4 MHz QSPI, then switched to 2 MHz for control/readback. The serial
proof shows `magic=0x51560002`, untouched default `SCALE_CTRL`, health
`raw=0x00000000 overflow=0 malformed=0`, six bitmap readback PASS samples, and
`SCALER_PROOF mode=0 pass=1`. Serial artifact:
`firmware/phase_b_1x_serial.log`.

Video capture and comparison to the `a5a047a2` baseline remain BrightForge's
step.

## Phase C — >1× bezel test (2×/3×) — FIRMWARE PASS (2026-07-27)

The same app used the approved `libvdp` API in the required size-then-scale
order. The 2× run used logic 300×220, predicted bezel 20×20, and `ctrl=0xA2`;
the 3× run used logic 200×150, predicted bezel 20×15, and `ctrl=0xB3`. Both
runs report clean health, six readback PASS samples, line-state PASS, and
`SCALER_PROOF pass=1`. Serial artifacts:
`firmware/phase_c_2x_serial.log`, `firmware/phase_c_3x_serial.log`.

The first 3× attempt saw one transient attribute upload TX error; a clean reset
and rerun passed, with no sticky health error. Video capture, bezel measurement,
and classification remain BrightForge's step.

Board currently active: scaler `38002d5c`, SDRAM empty. HW authority `a5a047a2`
preserved to `project_a5a047a2_bankcompletion.fs` (reflashable to restore baseline).
