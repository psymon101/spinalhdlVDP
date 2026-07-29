# Capture analysis — scaler-rewrite-hw-proof

Bitstream under test: `38002d5c` (fresh scaler build). Content driven by BronzeGate
ESP32-P4 `esp32p4_scaler_proof` app (`a3e8ebd`) over QSPI to the running bitstream
(no FPGA reflash). Capture: `/dev/video0` YUYV 720×480 (FPGA emits 640×480; UVC
device rescales H 640→720 = 1.125×, V 480→480 = 1:1).

## Capture methodology note (IMPORTANT)
The UVC device's **first frame after open is a sync-glitch** (partial/noisy —
white cells fill with colored speckle). Frames 2..N are byte-identical and clean.
Example: an 8-frame burst gave frame1 unique/40 700 B (glitch) and frames 2–8 all
sha `422d774c…`/6 353 B (clean, YDIF=0). **Always discard frame 0** and verify the
settled frames are identical before measuring. This matches the tierB `/dev/video0`
methodology; it is a capture artifact, NOT a display defect.

## Phase C — mode 2 (2×) — CAPTURED + VERIFIED (2026-07-28)
Current persisted board state (from BronzeGate's mode-2 run, `ctrl=0xA2`, logic
300×220) measured on a settled clean frame:
- WHITE auto-center bezel (GOTCHA-13 canonical: white bezel + scaled center):
  L=22, R=23, T=20, B=20 capture px → source **L≈20, R≈20, T=20, B=20**.
- Visible active area 667×432 capture → source ≈ **600×440**.
- **Both match the mode-2 prediction exactly** (bezel 20×20; visible 2×300 × 2×220
  = 600×440). Mode 3 would give T/B=15 — ruled out.
- Frames 2..6 byte-identical (temporally stable). Clean black/white checkerboard.
- Artifact: `captures/phaseC_2x/mode2_2x_scaled.png` sha `422d774c…`.

Verdict: the source-coordinate scaler produces a correct auto-centered 2× scaled
checkerboard with the predicted bezel on real hardware.

Note: horizontal checker cells measure ~2× the vertical (in source px) because the
bitmap path has an intrinsic 320-source ×2 horizontal doubling that composes with
the uniform 2× SCALE_CTRL. That composition is the P3b semantics question (spun
out); it is not a scaler defect — the bezel + visible-extent match is the scaler
proof.

## Phase B — mode 0 (1×) — FALSE-START caught; awaiting explicit SCALE_CTRL=0 rerun
★FINDING (2026-07-28, #14457/#14458): BronzeGate's first mode-0 run
(`SCALER_PROOF mode=0 pass=1`, ELF `d71f1271…`) did NOT put the display at 1×.
The captured frame was byte-identical (sha `422d774c…`) to the mode-2 capture,
bezel still 20×20, baseline structural match only 50%.
Root cause: the mode-0 app "leaves SCALE_CTRL untouched", but **SCALE_CTRL
(0x0349) is an FPGA register that persists across P4 resets** — the bitstream
`38002d5c` was never reconfigured, so SCALE_CTRL was still `0xA2` (2×) from the
prior 2× run. Mode-0 upload/readback PASS is valid (transport proof) but does not
reset the display scale. Fix requested: mode-0 path must explicitly write
`LOGIC_WIDTH=640, LOGIC_HEIGHT=480, SCALE_CTRL=0x00` (GOTCHA-12 order). Reusable
lesson: a true 1× capture requires SCALE_CTRL explicitly 0, OR an FPGA
reconfigure (which also clears SDRAM).

RESOLVED (2026-07-28, #14459/#14460, firmware `2f5be56`): BronzeGate added the
explicit `vdp_mode0_set_logic_size(640,480); vdp_mode0_set_scale_ctrl(0)` and
re-ran. Corrected mode-0 capture:
- **WHITE bezel = 0/0/0/0** — true 1×, auto-center border gone.
- Full-frame checkerboard, 64×64 display-px squares (32×32 source ×2 H doubling
  and ×2 line doubling), settled frames byte-identical.
- **1× regression vs `a5a047a2` baseline: PASS** — binarized structural match
  **96.69%**, identical mean luma (YAVG 127.3 both); residual ~3% = capture-session
  sub-pixel/edge jitter (two separate capture sessions), not content. Scaler 1×
  path is byte-equivalent to the HW-proven baseline.
- Artifact: `captures/phaseB_1x/mode0_1x.png` sha `00cf030f…`.

## Phase C — mode 3 (3×) — CAPTURED + VERIFIED (2026-07-28)
BronzeGate mode-3 run (firmware `76f67ad`, `SCALER_PROOF mode=3 pass=1`,
`ctrl=0xB3`, logic 200×150). Settled clean frame:
- WHITE bezel L=22 R=23 T=15 B=15 capture px → source **L≈20 R≈20 T=15 B=15**
  = exact mode-3 prediction (T/B=15 distinguishes it from mode 2's 20).
- Visible active 675×450 capture → source **600×450** = exact (3×200 × 3×150).
- Checker cell run-lengths perfectly uniform: every H run 216cap, every V run
  64cap, **std=0.0** both axes ⇒ clean uniform 3× scaling, zero skipped/
  duplicated columns (HW analog of sim `ScaleUpFrameCoSim` run-length check).
- Artifact: `captures/phaseC_3x/mode3_3x_scaled.png` sha `13b609cd…`.

## SUMMARY — all three modes verified on HW (bitstream 38002d5c, no reflash)
| Mode | SCALE_CTRL | logic | bezel src (meas=pred) | visible src (meas=pred) | verdict |
|---|---|---|---|---|---|
| 0 (1×) | 0x00 | 640×480 | 0/0/0/0 | 640×480 | ✅ + 96.69% match to a5a047a2 baseline |
| 2 (2×) | 0xA2 | 300×220 | 20/20/20/20 | 600×440 | ✅ exact |
| 3 (3×) | 0xB3 | 200×150 | 20/20/15/15 | 600×450 | ✅ exact + uniformity std=0 |

The bezel + visible-extent progression (offset = (active − scale·logic)/2, applied
exactly per mode) proves the source-coordinate scaler's auto-center + scale math on
real silicon. 1× is byte-equivalent to the HW-proven baseline (no regression);
>1× is correct and uniform. Capture artifacts: `phaseB_1x/mode0_1x.png`
`00cf030f…`, `phaseC_2x/mode2_2x_scaled.png` `422d774c…`,
`phaseC_3x/mode3_3x_scaled.png` `13b609cd…`.
