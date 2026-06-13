#!/usr/bin/env bash
# Task 43 — regression build harness.
#
# Iterates every scenarioId that has a matching TopTang20kHdmiScenarioNVerilog
# generator, plus the default (scenario 0) target, and runs sbt `runMain ...`
# to generate Verilog. Optionally runs full Gowin synthesis when
# `REGRESS_SYNTH=1` is exported (each scenario takes ~80 s on this bench).
#
# Skips scenarios whose generator object does not exist (e.g. 14, 18).
#
# Usage:
#   scripts/regression/build_all.sh                # Verilog-only pass
#   REGRESS_SYNTH=1 scripts/regression/build_all.sh # + bitstream for every
#
# Env:
#   SCENARIOS   — space-separated list of ids, overrides auto-detect
#   REGRESS_SYNTH — "1" to run `SCENARIO=N make` per scenario
#   REGRESSION_DIR — artifact root (defaults to captures/regression_YYYYMMDD)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

TOP_SCALA="hw/spinal/spinalhdlvdp/TopTang20kHdmi.scala"
if [[ ! -f "$TOP_SCALA" ]]; then
    echo "build_all.sh: $TOP_SCALA not found — wrong repo root?" >&2
    exit 1
fi

# readAsync gate (TopazCliff P0, external-report verification thread) — fail-fast
# before any build if a new Mem.readAsync was introduced outside the audited,
# grandfathered set. readAsync is the recurring Gowin synthesis-fragility root
# cause; new ones must go through an audit + baseline bump.
if [[ -x scripts/readasync_lint.sh ]]; then
    scripts/readasync_lint.sh || { echo "build_all.sh: readAsync gate failed — aborting build" >&2; exit 1; }
fi

# Auto-detect scenarios if user didn't override. Parse the object names
# TopTang20kHdmiScenarioNVerilog out of the source file. Always prepend 0
# (the default) since its object is TopTang20kHdmiVerilog (no scenario id).
if [[ -z "${SCENARIOS:-}" ]]; then
    ids="$(grep -oE 'TopTang20kHdmiScenario[0-9]+Verilog' "$TOP_SCALA" \
          | sed 's/TopTang20kHdmiScenario//; s/Verilog//' | sort -n -u || true)"
    if [[ -z "$ids" ]]; then
        SCENARIOS="0"
    else
        SCENARIOS="0 $ids"
    fi
fi

: "${REGRESSION_DIR:=$REPO_ROOT/captures/regression_$(date +%Y%m%d)}"
mkdir -p "$REGRESSION_DIR/build_logs"

echo "== regression build_all =="
echo "repo_root      : $REPO_ROOT"
echo "scenarios      : $SCENARIOS"
echo "regress_synth  : ${REGRESS_SYNTH:-0}"
echo "artifact_dir   : $REGRESSION_DIR"
echo

STAMP="$(date +%Y%m%d_%H%M%S)"
FAIL=0
declare -a SUMMARY

for N in $SCENARIOS; do
    case "$N" in
        0)    TARGET="spinalhdlvdp.TopTang20kHdmiVerilog" ;;
        *)    TARGET="spinalhdlvdp.TopTang20kHdmiScenario${N}Verilog" ;;
    esac

    # Skip if the generator object is not actually in the source.
    if [[ "$N" != "0" ]] && ! grep -q "object TopTang20kHdmiScenario${N}Verilog" "$TOP_SCALA"; then
        echo "[sc$N] SKIP (no generator)"
        SUMMARY+=("sc$N skip")
        continue
    fi

    LOG="$REGRESSION_DIR/build_logs/sc${N}_${STAMP}.log"
    echo "[sc$N] gen → $LOG"
    if sbt "runMain $TARGET" >"$LOG" 2>&1; then
        GEN_STATUS=pass
    else
        GEN_STATUS=fail
        FAIL=$((FAIL + 1))
    fi
    echo "[sc$N] gen $GEN_STATUS"

    SYNTH_STATUS=skip
    if [[ "${REGRESS_SYNTH:-0}" == "1" ]] && [[ "$GEN_STATUS" == "pass" ]]; then
        echo "[sc$N] synth"
        pushd fpga/tang20k >/dev/null
        # Scenario 0 == the DEFAULT top (TopTang20kHdmiVerilog), which the Makefile
        # selects only when SCENARIO is EMPTY. Passing SCENARIO=0 makes the Makefile
        # look for a nonexistent TopTang20kHdmiScenario0Verilog (ClassNotFound) and
        # abort at `gen` before gw_sh runs. Map 0 -> empty so the default builds.
        SCEN_ARG=""; [[ "$N" != "0" ]] && SCEN_ARG="$N"
        if SCENARIO="$SCEN_ARG" xvfb-run -a make >>"$LOG" 2>&1; then
            SYNTH_STATUS=pass
        else
            SYNTH_STATUS=fail
            FAIL=$((FAIL + 1))
        fi
        popd >/dev/null
        echo "[sc$N] synth $SYNTH_STATUS"
    fi

    SUMMARY+=("sc$N gen=$GEN_STATUS synth=$SYNTH_STATUS")
done

echo
echo "== summary =="
for line in "${SUMMARY[@]}"; do echo "  $line"; done
echo
if [[ "$FAIL" -gt 0 ]]; then
    echo "build_all: $FAIL failures" >&2
    exit 2
fi
echo "build_all: all scenarios PASS"
