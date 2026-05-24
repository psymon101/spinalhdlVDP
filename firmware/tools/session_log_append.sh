#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  cat <<'EOF' >&2
Usage:
  session_log_append.sh AGENT TITLE BODY [REFS]

Example:
  session_log_append.sh \
    BronzeGate \
    "ESP32-S3 audit" \
    "Confirmed 0x0300 write path is correct; remaining suspect is bootstrap/copper ownership." \
    "mail #10531"
EOF
  exit 2
fi

agent="$1"
title="$2"
body="$3"
refs="${4:-}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
log_path="$repo_root/PROJECT_PLAN/archive/SESSION_LOG.md"
today="$(date +%F)"
stamp="$(date +%H:%M:%S\ %Z)"

mkdir -p "$(dirname "$log_path")"
touch "$log_path"

python3 - "$log_path" "$today" "$stamp" "$agent" "$title" "$body" "$refs" <<'PY'
from pathlib import Path
import sys

log_path = Path(sys.argv[1])
today = sys.argv[2]
stamp = sys.argv[3]
agent = sys.argv[4]
title = sys.argv[5]
body = sys.argv[6]
refs = sys.argv[7]

text = log_path.read_text() if log_path.exists() else "# Session Log\n"
day_header = f"## {today}"

entry_lines = [
    f"### {stamp} — {agent} — {title}",
    "",
    body.strip(),
]
if refs.strip():
    entry_lines.extend(["", f"Refs: {refs.strip()}"])
entry = "\n".join(entry_lines).rstrip() + "\n"

if day_header not in text:
    if not text.endswith("\n"):
        text += "\n"
    if text.strip():
        text += "\n"
    text += f"{day_header}\n\n{entry}"
else:
    marker = day_header
    idx = text.index(marker) + len(marker)
    tail = text[idx:]
    next_day = tail.find("\n## ")
    if next_day == -1:
        insert_at = len(text)
    else:
        insert_at = idx + next_day
    prefix = text[:insert_at].rstrip() + "\n\n"
    suffix = text[insert_at:]
    text = prefix + entry + suffix

log_path.write_text(text)
PY

echo "Appended session log entry to $log_path"
