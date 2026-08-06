#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
bitstream="$repo_root/fpga/tang20k/impl/pnr/project_a5a047a2_bankcompletion.fs"
packet="$repo_root/PROJECT_PLAN/proof_packets/2bpp-bank-completion-hw-reproof"
serial_port="/dev/ttyACM0"
video_device="/dev/video0"
python_bin="/home/itadmin/.agent-homes/bronzegate/home/.espressif/python_env/idf6.0_py3.12_env/bin/python"

for cycle in $(seq 1 10); do
    tag=$(printf '%02d' "$cycle")
    loader_log="$packet/hardware/prime_cycle_${tag}_openfpgaloader.log"
    serial_log="$packet/firmware/prime_cycle_${tag}_serial.log"
    capture="$packet/captures/prime_cycle_${tag}_720x480.yuyv"

    echo "CYCLE_BEGIN cycle=$cycle"
    openFPGALoader --board tangnano20k --bitstream "$bitstream" >"$loader_log" 2>&1
    sleep 1.2

    "$python_bin" - "$serial_port" <<'PY' >"$serial_log"
import serial
import sys
import time

port = sys.argv[1]
ser = serial.Serial(port, 115200, timeout=0.2)
ser.reset_input_buffer()
ser.dtr = False
ser.rts = True
time.sleep(0.1)
ser.rts = False
deadline = time.monotonic() + 30.0
while time.monotonic() < deadline:
    data = ser.readline()
    if data:
        line = data.decode("utf-8", "replace")
        print(line, end="")
        if "SCALER_PROOF mode=0 pass=" in line:
            break
ser.close()
PY

    required=(
        "CS_IDLE_PROOF cs_gpio=20 level=1 settle_ms=1200"
        "LANE1_PRIME_DISCARD"
        "scaler proof mode=0 magic=0x51560002"
        "HEALTH_BEFORE_UPLOAD raw=0x00000000"
        "HEALTH_AFTER_UPLOAD raw=0x00000000"
        "HEALTH_AFTER_ENABLE raw=0x00000000"
        "SCALER_PROOF mode=0 pass=1"
    )
    for marker in "${required[@]}"; do
        grep -Fq "$marker" "$serial_log" || {
            echo "CYCLE_FAIL cycle=$cycle missing=$marker log=$serial_log" >&2
            exit 1
        }
    done
    if grep -Eq "READBACK FAIL|upload failed|host init failed|SCALER_PROOF mode=0 pass=0" "$serial_log"; then
        echo "CYCLE_FAIL cycle=$cycle serial_failure=$serial_log" >&2
        exit 1
    fi
    if [[ "$(grep -c 'READBACK PASS addr=' "$serial_log")" -lt 6 ]]; then
        echo "CYCLE_FAIL cycle=$cycle incomplete_readback=$serial_log" >&2
        exit 1
    fi

    ffmpeg -hide_banner -loglevel error \
        -f v4l2 -input_format yuyv422 -video_size 720x480 -framerate 30 \
        -i "$video_device" -frames:v 3 -f rawvideo -pix_fmt yuyv422 -y "$capture"
    [[ "$(stat -c '%s' "$capture")" -eq 2073600 ]] || {
        echo "CYCLE_FAIL cycle=$cycle capture_size=$capture" >&2
        exit 1
    }
    echo "CYCLE_PASS cycle=$cycle serial_sha=$(sha256sum "$serial_log" | awk '{print $1}') capture_sha=$(sha256sum "$capture" | awk '{print $1}')"
done

echo "CAMPAIGN_PASS cycles=10"
