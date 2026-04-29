#!/usr/bin/env python3
"""Transport-gate classifier for HDMI captures.

Implements the three-gate proof flow from BrightForge #8740 / BronzeGate #8731:

1. Transport gate: is the final-stage canary visible?
   - Bottom-right 16x16 bright-cyan block (v1 canary)
   - 1-pixel frame border at active-window edges (v2 canary, uncommitted)
2. Content gate: scenario-specific canaries / overlays rendering?
3. Scene gate: only now analyze scenario rendering.

This script handles gate 1. It refuses to classify content/scene when transport
fails, preventing the team from chasing phantom scene bugs caused by Guermok
USB2 TMDS-lock failures.

Exit codes:
  0 = transport gate PASS (canary visible)
  1 = transport gate FAIL (uniform black / no canary)
  2 = runtime error (cannot open capture, etc.)
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np


# Colour of the transport canary: bright cyan in BGR.
# FPGA emits (R=0, G=255, B=255). After RTSP/MJPEG capture the values
# compress slightly. Empirical mean from canary_v1_sc50 captures:
#   B≈252, G≈252, R≈39  (std≈13 on each channel).
CANARY_BGR_EXPECTED = np.array([255.0, 255.0, 0.0])
CANARY_BGR_LOOSE = np.array([200.0, 200.0, 80.0])   # lower bounds

# Capture-path lock failure produces uniform TV-black pixels with value 6
# (YUV black mapped to RGB). Some encoders emit 0 instead.
TRANSPORT_BLACK_VALUES = {0, 6}


def find_active_region(frame: np.ndarray) -> tuple[int, int, int, int]:
    """Heuristic: find the 640x480 active region inside a 1920x1080 capture.

    Returns (x0, y0, x1, y1) in capture coordinates.
    For now we assume 1:1 mapping on the standard RTSP stream:
      active x ∈ [0, 1920), active y ∈ [0, 1080)
    Future: detect black bars and crop.
    """
    h, w = frame.shape[:2]
    return 0, 0, w, h


def check_corner_canary(frame: np.ndarray, x0: int, y0: int, x1: int, y1: int) -> dict:
    """Check the bottom-right 16x16 corner block for bright cyan.

    In a 1920x1080 capture the FPGA 640x480 active window is scaled
    non-uniformly. Empirically the 16x16 FPGA block lands at:
        capture x ∈ [1872, 1920), y ∈ [1044, 1080)
    For other resolutions we scale proportionally from the bottom-right.
    """
    h, w = frame.shape[:2]

    # Known-good mapping for 1920x1080 RTSP captures.
    if w == 1920 and h == 1080:
        cx0, cy0, cx1, cy1 = 1872, 1044, 1920, 1080
    else:
        # Fallback: proportional from bottom-right, targeting ~2.5% width/height
        cx1, cy1 = x1, y1
        cx0 = max(0, x1 - int((x1 - x0) * 0.025))
        cy0 = max(0, y1 - int((y1 - y0) * 0.025))

    probe = frame[cy0:cy1, cx0:cx1]

    if probe.size == 0:
        return {"present": False, "reason": "empty_probe", "score": 0.0}

    # Cyan mask: high blue & green, low red
    mask = (
        (probe[:, :, 0] > CANARY_BGR_LOOSE[0]) &
        (probe[:, :, 1] > CANARY_BGR_LOOSE[1]) &
        (probe[:, :, 2] < CANARY_BGR_LOOSE[2])
    )
    cyan_fraction = float(mask.sum() / mask.size)

    # Require at least 50% of the precise corner region to be cyan
    present = cyan_fraction > 0.50

    return {
        "present": present,
        "cyan_fraction": round(cyan_fraction, 4),
        "probe_shape": probe.shape[:2],
        "probe_coords": [cx0, cy0, cx1, cy1],
    }


def check_frame_border_canary(frame: np.ndarray, x0: int, y0: int, x1: int, y1: int) -> dict:
    """Check for 1-pixel frame border at active-window edges.

    The v2 canary adds cyan pixels at the four edges of the active region.
    We look for cyan on at least 3 of the 4 edges.
    """
    h, w = frame.shape[:2]
    edges = {
        "top":    frame[y0, x0:x1],
        "bottom": frame[y1 - 1, x0:x1],
        "left":   frame[y0:y1, x0],
        "right":  frame[y0:y1, x1 - 1],
    }

    edge_scores = {}
    lit_edges = 0
    for name, strip in edges.items():
        mask = (
            (strip[:, 0] > CANARY_BGR_LOOSE[0]) &
            (strip[:, 1] > CANARY_BGR_LOOSE[1]) &
            (strip[:, 2] < CANARY_BGR_LOOSE[2])
        )
        frac = float(mask.sum() / mask.size) if mask.size else 0.0
        edge_scores[name] = round(frac, 4)
        if frac > 0.10:  # at least 10% of the edge strip is cyan
            lit_edges += 1

    return {
        "present": lit_edges >= 3,
        "lit_edges": lit_edges,
        "edge_scores": edge_scores,
    }


def check_uniform_black(frame: np.ndarray) -> dict:
    """Detect the Guermok TMDS-lock failure signature.

    Signature: every pixel is the same value, typically 6 (TV-black)
    or 0 (full black).
    """
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    std = float(gray.std())
    mean = float(gray.mean())
    median = float(np.median(gray))

    # Flat: std is essentially zero
    is_flat = std < 0.5
    # Uniform black: the single pixel value is in the transport-black set
    is_black = int(round(median)) in TRANSPORT_BLACK_VALUES

    return {
        "is_uniform_black": is_flat and is_black,
        "std": round(std, 4),
        "mean": round(mean, 4),
        "median": round(median, 4),
        "dominant_value": int(round(median)),
    }


def classify(capture_path: Path, sample_frame: int = 0) -> dict:
    cap = cv2.VideoCapture(str(capture_path))
    if not cap.isOpened():
        return {"status": "ERROR", "reason": f"cannot_open {capture_path}", "exit_code": 2}

    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    frame_idx = min(sample_frame, total - 1) if total > 0 else 0
    cap.set(cv2.CAP_PROP_POS_FRAMES, frame_idx)

    ok, frame = cap.read()
    cap.release()
    if not ok or frame is None:
        return {"status": "ERROR", "reason": f"cannot_read_frame_{frame_idx}", "exit_code": 2}

    x0, y0, x1, y1 = find_active_region(frame)

    corner = check_corner_canary(frame, x0, y0, x1, y1)
    border = check_frame_border_canary(frame, x0, y0, x1, y1)
    black = check_uniform_black(frame)

    report = {
        "capture": str(capture_path),
        "frame_sampled": frame_idx,
        "capture_size": {"width": frame.shape[1], "height": frame.shape[0]},
        "corner_canary": corner,
        "border_canary": border,
        "uniform_black": black,
    }

    # Decision hierarchy
    if black["is_uniform_black"]:
        report["status"] = "TRANSPORT_FAIL"
        report["reason"] = (
            f"Uniform black frame (median={black['dominant_value']}, std={black['std']}). "
            "Guermok USB2 TMDS-lock failure. Do NOT analyze scene; fix transport first."
        )
        report["exit_code"] = 1
        return report

    if corner["present"] or border["present"]:
        report["status"] = "TRANSPORT_PASS"
        report["reason"] = "Transport canary visible. Proceed to content gate."
        report["exit_code"] = 0
        return report

    # Frame is not uniform black, but no canary either.
    report["status"] = "NEEDS_REVIEW"
    report["reason"] = (
        "Frame is not uniform black, yet transport canary is absent. "
        "Possible: partial lock, wrong resolution, or canary not yet built into bitstream."
    )
    report["exit_code"] = 1
    return report


def main() -> int:
    p = argparse.ArgumentParser(description="Transport-gate classifier")
    p.add_argument("capture", type=Path, help="MP4 or PNG capture file")
    p.add_argument("--json", type=Path, default=None,
                   help="optional JSON report output path")
    p.add_argument("--frame", type=int, default=0,
                   help="which frame to sample (default 0, use -1 for middle)")
    args = p.parse_args()

    frame_idx = args.frame
    if frame_idx < 0:
        cap = cv2.VideoCapture(str(args.capture))
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        cap.release()
        frame_idx = total // 2

    result = classify(args.capture, frame_idx)

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(result, indent=2))

    print(f"check_transport: {result['status']} — {result['reason']}")
    return result["exit_code"]


if __name__ == "__main__":
    sys.exit(main())
