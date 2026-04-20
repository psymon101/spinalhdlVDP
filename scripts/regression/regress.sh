#!/usr/bin/env bash
# Task 43 — master regression orchestrator.
#
# Sequences: gen-verilog matrix → (optional) synth-matrix → capture +
# analyze representative scenarios → preserve artifacts under
# captures/regression_YYYYMMDD/. Single entry point for both developers
# and a future CI runner.
#
# Env toggles (all optional):
#   REGRESS_SYNTH=1     — run full Gowin synthesis per scenario (slow)
#   REGRESS_CAPTURE=1   — run hardware capture + analyze for repr set
#                         (requires HDMI capture card + flashed Tang)
#   REPR_SCENARIOS="0 17 33" — capture set override
#   REGRESSION_DIR=...  — artifact root override
#
# Exit status is the OR of every step's status. A CI runner can trust
# `regress.sh` to return non-zero whenever any scenario fails.
set -u
set -o pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

STAMP="$(date +%Y%m%d)"
: "${REGRESSION_DIR:=$REPO_ROOT/captures/regression_${STAMP}}"
: "${REPR_SCENARIOS:=0 17 33}"

mkdir -p "$REGRESSION_DIR"
SUMMARY_JSON="$REGRESSION_DIR/regress_summary.json"
MASTER_FAIL=0

echo "== regress.sh =="
echo "repo_root        : $REPO_ROOT"
echo "regression_dir   : $REGRESSION_DIR"
echo "repr_scenarios   : $REPR_SCENARIOS"
echo "regress_synth    : ${REGRESS_SYNTH:-0}"
echo "regress_capture  : ${REGRESS_CAPTURE:-0}"
echo

# -------- Step 1: gen + optional synth matrix --------
echo "-- step 1: build matrix --"
if REGRESSION_DIR="$REGRESSION_DIR" \
   "$REPO_ROOT/scripts/regression/build_all.sh"; then
    BUILD_STATUS=pass
else
    BUILD_STATUS=fail
    MASTER_FAIL=1
fi

# -------- Step 2: capture + analyze repr set (optional) --------
declare -a REPR_RESULTS
if [[ "${REGRESS_CAPTURE:-0}" == "1" ]]; then
    echo "-- step 2: capture + analyze representative scenarios --"
    for N in $REPR_SCENARIOS; do
        SCDIR="$REGRESSION_DIR/sc${N}"
        mkdir -p "$SCDIR"

        # Ensure the bitstream for this scenario is on the Tang. If synth
        # wasn't run as part of step 1 and REGRESS_CAPTURE is set, this
        # block builds+flashes the scenario sequentially.
        echo "[sc$N] build+flash"
        pushd fpga/tang20k >/dev/null
        if ! SCENARIO="$N" xvfb-run -a make flash >>"$SCDIR/build_flash.log" 2>&1; then
            echo "[sc$N] build+flash FAIL — see $SCDIR/build_flash.log"
            REPR_RESULTS+=("sc$N build_flash=fail")
            MASTER_FAIL=1
            popd >/dev/null
            continue
        fi
        popd >/dev/null

        echo "[sc$N] capture → $SCDIR/capture.mp4"
        if ! "$REPO_ROOT/scripts/regression/capture.sh" "$SCDIR/capture.mp4" \
               >"$SCDIR/capture.log" 2>&1; then
            echo "[sc$N] capture FAIL — see $SCDIR/capture.log"
            REPR_RESULTS+=("sc$N capture=fail")
            MASTER_FAIL=1
            continue
        fi

        echo "[sc$N] analyze"
        if python3 "$REPO_ROOT/scripts/regression/analyze.py" \
               "$SCDIR/capture.mp4" "$SCDIR/analysis.json" \
               --scenario "sc$N" \
               --mid "$SCDIR/mid_frame.png" \
               --mean "$SCDIR/mean.png" >>"$SCDIR/analyze.log" 2>&1; then
            REPR_RESULTS+=("sc$N analyze=PASS")
        else
            REPR_RESULTS+=("sc$N analyze=FAIL")
            MASTER_FAIL=1
        fi
    done
else
    echo "-- step 2: skipped (set REGRESS_CAPTURE=1 to enable) --"
fi

# -------- Step 3: write master summary --------
{
    echo "{"
    echo "  \"timestamp\": \"$(date -Iseconds)\","
    echo "  \"repo_root\": \"$REPO_ROOT\","
    echo "  \"regression_dir\": \"$REGRESSION_DIR\","
    echo "  \"build_status\": \"$BUILD_STATUS\","
    echo -n "  \"repr_scenarios\": ["
    first=1
    for N in $REPR_SCENARIOS; do
        if [[ $first -eq 1 ]]; then first=0; else echo -n ","; fi
        echo -n " \"sc$N\""
    done
    echo " ],"
    echo -n "  \"repr_results\": ["
    first=1
    for r in "${REPR_RESULTS[@]:-}"; do
        [[ -z "$r" ]] && continue
        if [[ $first -eq 1 ]]; then first=0; else echo -n ","; fi
        echo -n " \"$r\""
    done
    echo " ],"
    echo "  \"master_fail\": $MASTER_FAIL"
    echo "}"
} > "$SUMMARY_JSON"

echo
echo "== summary =="
echo "build_status   : $BUILD_STATUS"
for r in "${REPR_RESULTS[@]:-}"; do
    [[ -z "$r" ]] || echo "  $r"
done
echo "summary_json   : $SUMMARY_JSON"
echo "master_fail    : $MASTER_FAIL"

exit "$MASTER_FAIL"
