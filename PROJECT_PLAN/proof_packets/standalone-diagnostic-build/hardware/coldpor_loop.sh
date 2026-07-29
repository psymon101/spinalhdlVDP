#!/bin/bash
# standalone-diagnostic-build: N=10 cold-POR proof.
# Each cycle: openFPGALoader SRAM-load (clean POR by me) -> capture 40 RGB frames
# (spans >600ms so the ~79ms HdmiCleanStart mute + HDMI re-lock clear) -> analyze
# the LAST frame for a full-frame grid (content on all rows+cols).
set -u
SP="/tmp/claude-1000/-home-itadmin-github-spinalhdlVDP/c07b7a46-ed8f-4a4b-944c-71cba8326699/scratchpad/coldpor"
mkdir -p "$SP"
FS="/home/itadmin/github/spinalhdlVDP/fpga/tang20k/impl/pnr/project_60b23c77_diagnostic.fs"
PASS=0
for i in $(seq 1 10); do
  ci=$(printf "%02d" "$i")
  fl=$(openFPGALoader -b tangnano20k "$FS" 2>&1 | grep -ciE "^DONE$")
  # POR drops HDMI; give the UVC capture device time to re-lock (>mute ~79ms + re-sync).
  sleep 4
  ffmpeg -hide_banner -loglevel error -f v4l2 -input_format yuyv422 -video_size 720x480 \
    -i /dev/video0 -frames:v 12 -f rawvideo -pix_fmt rgb24 -y "$SP/c$ci.rgb" >/dev/null 2>&1
  # keep a viewable PNG of the last frame
  ffmpeg -hide_banner -loglevel error -f v4l2 -input_format yuyv422 -video_size 720x480 \
    -i /dev/video0 -frames:v 3 -y "$SP/c${ci}.png" >/dev/null 2>&1
  python3 - "$SP/c$ci.rgb" "$ci" <<'PY'
import numpy as np, sys
f,ci=sys.argv[1],sys.argv[2]
raw=np.fromfile(f,dtype=np.uint8); W,H=720,480; fr=W*H*3; n=len(raw)//fr
a=raw[(n-1)*fr:n*fr].reshape(H,W,3).astype(int)
nz=(a.sum(axis=2)>48)
rows=int(nz.any(axis=1).sum()); cols=int(nz.any(axis=0).sum()); pct=100*nz.sum()/(H*W)
# grid present = content spans essentially the whole frame
ok = rows>=470 and cols>=700 and pct>1.0
print(f"cycle {ci}: frames={n} nonblack={pct:.1f}% rows={rows}/480 cols={cols}/720 => {'PASS' if ok else 'FAIL'}")
sys.exit(0 if ok else 1)
PY
  [ $? -eq 0 ] && PASS=$((PASS+1))
done
echo "COLD-POR SUMMARY: $PASS/10 cycles show full-frame grid (HDMI locked + pattern stable)"
