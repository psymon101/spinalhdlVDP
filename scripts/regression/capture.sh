#!/usr/bin/env bash
# Task 43 — regression capture harness.
#
# Records a 30 s MJPEG MP4 from an HDMI capture card, auto-detecting
# the V4L2 device that advertises 1920x1080 @ 50 fps MJPEG unless
# CAPTURE_DEV is set explicitly. Output path is given as $1.
#
# Usage:
#   CAPTURE_DEV=/dev/video2 scripts/regression/capture.sh out.mp4
#   scripts/regression/capture.sh out.mp4        # auto-detect
set -euo pipefail

OUT="${1:-capture.mp4}"
DURATION="${DURATION:-30}"
WIDTH="${WIDTH:-1920}"
HEIGHT="${HEIGHT:-1080}"
FPS="${FPS:-50}"

find_capture_dev() {
    for dev in /dev/video0 /dev/video2 /dev/video4 /dev/video6; do
        [[ -c "$dev" ]] || continue
        # Prefer a device that lists 1920x1080@50 MJPEG discretely.
        if v4l2-ctl -d "$dev" --list-formats-ext 2>/dev/null \
             | grep -q "1920x1080" \
             && v4l2-ctl -d "$dev" --list-formats-ext 2>/dev/null \
                  | grep -q "50.000 fps"; then
            echo "$dev"
            return 0
        fi
    done
    return 1
}

if [[ -z "${CAPTURE_DEV:-}" ]]; then
    CAPTURE_DEV="$(find_capture_dev || true)"
    if [[ -z "$CAPTURE_DEV" ]]; then
        echo "capture.sh: no HDMI capture device found; set CAPTURE_DEV=/dev/videoN" >&2
        exit 1
    fi
fi

echo "capture.sh: device=$CAPTURE_DEV  ${WIDTH}x${HEIGHT}@${FPS}  duration=${DURATION}s  out=$OUT"

# Give the device a moment to settle (some capture cards drop the first
# few frames during negotiation).
sleep 3

# Two-step: raw MJPEG grab (minimal CPU during live capture), then
# transcode to H.264 so OpenCV cv2.VideoCapture can decode the frames.
# The raw MJPEG container used to leave analyze.py seeing "zero frames".
RAW="${OUT%.mp4}_raw.mp4"
ffmpeg -hide_banner -loglevel error -y \
    -f v4l2 -input_format mjpeg -video_size "${WIDTH}x${HEIGHT}" \
    -framerate "$FPS" -i "$CAPTURE_DEV" -t "$DURATION" \
    -c:v copy "$RAW"

ffmpeg -hide_banner -loglevel error -y \
    -i "$RAW" -c:v libx264 -preset ultrafast "$OUT"

rm -f "$RAW"
echo "capture.sh: wrote $OUT"
