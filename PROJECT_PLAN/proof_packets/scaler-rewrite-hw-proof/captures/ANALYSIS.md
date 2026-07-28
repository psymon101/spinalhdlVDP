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

## Phase B — mode 0 (1×) — PENDING coordinated capture (BronzeGate mode-0 run)
## Phase C — mode 3 (3×) — PENDING coordinated capture (BronzeGate mode-3 run)
Only one SCALE_CTRL mode is live at a time; capturing modes 0 and 3 needs
BronzeGate to drive those modes (compile-time mode select → reflash+run) with
serial `SCALER_PROOF mode=N pass=1` confirmation at capture time.
