#!/usr/bin/env bash
# readasync_lint.sh — readAsync gate (TopazCliff P0, mail #11949 / external-report
# verification thread). Policy: ban NEW Mem.readAsync in synthesizable RTL;
# grandfather the existing audited set (readAsync audit #10772).
#
# WHY: readAsync (combinational Mem read) is the recurring Gowin synthesis-
# fragility root cause — it blows distributed-RAM (SSRAM) cells and breaks BSRAM
# inference, and has caused multiple regressions (E3.14/16/24/29, scaler, blitter,
# planar). The remaining instances were each audited and justified (boot-ROM,
# or a deliberate 1-cycle-latency-sensitive path that must stay combinational).
# New ones must not be added casually — adding one is a conscious, reviewed act:
# audit it, then re-baseline with `--update` and justify the bump in review.
#
# Mechanism: content-keyed allowlist (line-number independent, survives refactor).
# Each .readAsync( call is keyed as "<basename><TAB><trimmed source line>".
# A call whose key is NOT in scripts/readasync_baseline.txt is a NEW readAsync
# and fails the gate. Sims (*Sim.scala) are excluded — they are not synthesized.
#
# Usage:
#   scripts/readasync_lint.sh            # check; exit 1 on any new readAsync
#   scripts/readasync_lint.sh --update   # re-snapshot the baseline (deliberate)
set -uo pipefail

ROOT="hw/spinal/spinalhdlvdp"
BASELINE="scripts/readasync_baseline.txt"

current_set() {
  grep -rn "\.readAsync(" "$ROOT"/*.scala 2>/dev/null \
    | grep -v "Sim\.scala" \
    | sed -E "s|^$ROOT/([^:]+):[0-9]+:[[:space:]]*|\1\t|" \
    | sort
}

# Baseline data lines only (drop the '#' header/comments and blanks).
baseline_set() { grep -vE '^#|^[[:space:]]*$' "$BASELINE" | sort; }

if [[ "${1:-}" == "--update" ]]; then
  current_set > "$BASELINE"
  echo "readasync_lint: baseline re-snapshotted -> $BASELINE ($(wc -l < "$BASELINE") entries)"
  exit 0
fi

if [[ ! -f "$BASELINE" ]]; then
  echo "readasync_lint: ERROR missing baseline $BASELINE (run --update to create)" >&2
  exit 2
fi

# Lines present now but absent from the baseline = newly-introduced readAsync.
new_calls="$(comm -13 <(baseline_set) <(current_set))"

cur_count="$(current_set | wc -l | tr -d ' ')"
base_count="$(baseline_set | wc -l | tr -d ' ')"

if [[ -n "$new_calls" ]]; then
  echo "readasync_lint: FAIL — NEW readAsync introduced (banned; audit + re-baseline if intentional):"
  echo "$new_calls" | sed 's/^/  + /'
  echo "readasync_lint: synthesizable readAsync count now $cur_count (baseline $base_count)"
  exit 1
fi

# Net count can only legitimately drop (conversions to readSync). A rise with no
# new keys is impossible, but guard anyway.
if (( cur_count > base_count )); then
  echo "readasync_lint: FAIL — readAsync count $cur_count exceeds baseline $base_count"
  exit 1
fi

removed="$(comm -23 <(baseline_set) <(current_set))"
if [[ -n "$removed" ]]; then
  echo "readasync_lint: NOTE — baselined readAsync removed/converted (good; run --update to prune):"
  echo "$removed" | sed 's/^/  - /'
fi

echo "readasync_lint: PASS — no new readAsync ($cur_count synthesizable, all grandfathered)"
exit 0
