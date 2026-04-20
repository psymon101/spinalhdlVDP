#!/usr/bin/env python3
"""Task 43 — reusable stability analyzer for HDMI captures.

Core metrics (every scenario):
  - frame_count, duration, fps
  - inter-frame delta (mean, stddev, per-frame samples)
  - glitch_fraction  — frames with delta > 5σ above median
  - freeze_fraction  — fraction of frames with delta < 0.01 after 0-sigma
                       noise normalization (run-of-4 identical frames)
  - motion_percent   — fraction of pixels that changed > 20 (0..255) on
                       consecutive frames, averaged over the capture
  - mean_brightness, nonblack_fraction

Scenario-specific overlays are *not* part of this base analyzer; they
belong to per-scenario files that wrap this one and add their own
oracles (sprite positions, scroll rates, etc.). Task 43 keeps the
baseline metrics uniform so trending + CI pass/fail are well-defined.

Output: JSON report at <out>. Exit codes:
  0 = all assertions pass
  1 = assertion failure (metrics outside bounds)
  2 = runtime error (capture open, etc.)
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np


def analyze(capture_path: Path, out_path: Path, scenario: str,
            glitch_max: float = 0.01, freeze_max: float = 0.10,
            motion_min: float = 0.005, motion_max: float = 0.60,
            mid_frame_path: Path | None = None,
            mean_frame_path: Path | None = None) -> int:
    cap = cv2.VideoCapture(str(capture_path))
    if not cap.isOpened():
        print(f"analyze: cannot open {capture_path}", file=sys.stderr)
        return 2

    fps = cap.get(cv2.CAP_PROP_FPS)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total_px = w * h

    sum_f = None
    prev = None
    deltas = []             # mean abs-diff per frame-pair
    motion_counts = []      # pixels with channel-max delta > 20
    brightness = []
    nonblack_frac = []
    idx = 0

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        ff = frame.astype(np.float32)
        if sum_f is None:
            sum_f = np.zeros_like(ff, dtype=np.float64)
        sum_f += ff
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        brightness.append(float(gray.mean()))
        nonblack_frac.append(float((gray > 10).mean()))
        if prev is not None:
            d = cv2.absdiff(frame, prev)
            deltas.append(float(d.mean()))
            motion_counts.append(int((d.max(axis=2) > 20).sum()))
        prev = frame
        idx += 1

    cap.release()
    if idx == 0:
        print("analyze: zero frames", file=sys.stderr)
        return 2

    deltas = np.asarray(deltas, dtype=np.float64)
    motion_counts = np.asarray(motion_counts, dtype=np.float64)
    brightness = np.asarray(brightness, dtype=np.float64)
    nonblack_frac = np.asarray(nonblack_frac, dtype=np.float64)

    d_median = float(np.median(deltas)) if deltas.size else 0.0
    d_std = float(np.std(deltas)) if deltas.size else 0.0
    glitch_thresh = d_median + 5.0 * d_std
    glitch_n = int((deltas > glitch_thresh).sum()) if d_std > 0.0 else 0
    glitch_fraction = float(glitch_n / deltas.size) if deltas.size else 0.0

    # Freeze: look for long runs of very-near-zero delta (pipeline stuck).
    # MJPEG capture always has some encode noise between adjacent frames
    # even on a pixel-identical source, so "truly frozen" is a tight
    # floor — 0.1 mean abs-delta across a 1920x1080 BGR frame. Legitimate
    # static-banded scenes (Sc33) sit around median 0.7 and wouldn't
    # trip this. Require a run of ≥ 8 (≥ 0.16 s at 50 fps) to call it
    # a freeze — transient MJPEG repeats of 3–4 identical frames are
    # normal at low-motion boundaries.
    freeze_eps = 0.1
    is_frozen = deltas < freeze_eps
    freeze_frames = 0
    run = 0
    for v in is_frozen:
        if v:
            run += 1
            if run >= 8:
                freeze_frames += 1
        else:
            run = 0
    freeze_fraction = float(freeze_frames / deltas.size) if deltas.size else 0.0

    motion_percent = float(motion_counts.mean() / total_px) if motion_counts.size else 0.0

    # Optional artifacts for human inspection.
    mean_img = (sum_f / idx).astype(np.uint8)
    if mean_frame_path is not None:
        cv2.imwrite(str(mean_frame_path), mean_img)
    if mid_frame_path is not None:
        cap2 = cv2.VideoCapture(str(capture_path))
        cap2.set(cv2.CAP_PROP_POS_FRAMES, idx // 2)
        ok, mid = cap2.read()
        cap2.release()
        if ok and mid is not None:
            cv2.imwrite(str(mid_frame_path), mid)

    report = {
        "scenario": scenario,
        "capture": str(capture_path),
        "frames": int(idx),
        "fps_container": round(fps, 3),
        "fps_derived": round(idx / (idx / fps) if fps > 0 else 0.0, 3),
        "width": w, "height": h,
        "mean_brightness": round(float(brightness.mean()), 3),
        "nonblack_fraction": round(float(nonblack_frac.mean()), 4),
        "inter_frame_delta": {
            "median": round(d_median, 4),
            "stddev": round(d_std, 4),
            "max": round(float(deltas.max()) if deltas.size else 0.0, 4),
        },
        "glitch": {
            "threshold_abs_delta": round(glitch_thresh, 4),
            "count": glitch_n,
            "fraction": round(glitch_fraction, 6),
            "max_allowed_fraction": glitch_max,
        },
        "freeze": {
            "near_zero_eps": round(freeze_eps, 4),
            "frozen_frames": freeze_frames,
            "fraction": round(freeze_fraction, 6),
            "max_allowed_fraction": freeze_max,
        },
        "motion": {
            "percent": round(motion_percent, 6),
            "min_allowed": motion_min,
            "max_allowed": motion_max,
        },
    }

    # Pass/fail gates.
    assertions = []
    if glitch_fraction > glitch_max:
        assertions.append(f"glitch_fraction {glitch_fraction:.4f} > {glitch_max}")
    if freeze_fraction > freeze_max:
        assertions.append(f"freeze_fraction {freeze_fraction:.4f} > {freeze_max}")
    if motion_percent < motion_min:
        assertions.append(f"motion_percent {motion_percent:.4f} < {motion_min}")
    if motion_percent > motion_max:
        assertions.append(f"motion_percent {motion_percent:.4f} > {motion_max}")

    report["pass"] = len(assertions) == 0
    report["failures"] = assertions

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, indent=2))

    print(f"analyze: {scenario} {'PASS' if report['pass'] else 'FAIL'} "
          f"frames={idx} motion={motion_percent:.4f} "
          f"glitch={glitch_fraction:.4f} freeze={freeze_fraction:.4f}")
    if assertions:
        for a in assertions:
            print(f"  FAIL: {a}")
        return 1
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Task 43 stability analyzer")
    p.add_argument("capture", type=Path, help="MP4 capture file")
    p.add_argument("out", type=Path, help="JSON report output path")
    p.add_argument("--scenario", default="unknown",
                   help="scenario label recorded in the report")
    p.add_argument("--mid", type=Path, default=None,
                   help="optional mid-frame PNG output path")
    p.add_argument("--mean", type=Path, default=None,
                   help="optional 30-second mean PNG output path")
    p.add_argument("--glitch-max", type=float, default=0.01)
    p.add_argument("--freeze-max", type=float, default=0.10)
    p.add_argument("--motion-min", type=float, default=0.005)
    p.add_argument("--motion-max", type=float, default=0.60)
    args = p.parse_args()
    return analyze(args.capture, args.out, args.scenario,
                   args.glitch_max, args.freeze_max,
                   args.motion_min, args.motion_max,
                   args.mid, args.mean)


if __name__ == "__main__":
    sys.exit(main())
