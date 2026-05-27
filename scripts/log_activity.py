#!/usr/bin/env python3
"""
log_activity.py — Append or query the spinalhdlVDP activity log.

Usage:
    python3 scripts/log_activity.py append \
        --actor BrightForge \
        --category BUILD \
        --summary "CP-C single build PASS" \
        --mail 10691 \
        --commit 93e5924 \
        --artifact "fpga/tang20k/captures/cpc_step1_e4b44484.json" \
        --tag "palette-lane" --tag "cp-c"

    python3 scripts/log_activity.py query --date 2026-05-25
    python3 scripts/log_activity.py query --actor BrightForge
    python3 scripts/log_activity.py query --tag "palette-lane"
    python3 scripts/log_activity.py query --category BUILD
"""

import argparse
import datetime
import os
import re
import sys

LOG_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "PROJECT_PLAN", "ACTIVITY_LOG.md"
)

HEADER = """# spinalhdlVDP Activity Log

**Purpose:** Chronological record of all significant project activity — agent mail, builds, simulations, code changes, PM decisions, and errors. This is the source of truth for "what happened when."

**How to read:** Entries are newest-first. Each entry links to evidence (mail IDs, commit hashes, artifact paths). Use `scripts/log_activity.py query` to filter.

**Agents:** When you complete a checkpoint, hit a blocker, or observe an error, append an entry. One line of summary is enough — the links do the heavy lifting.

---

"""


def ensure_log_exists():
    if not os.path.exists(LOG_PATH):
        os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
        with open(LOG_PATH, "w") as f:
            f.write(HEADER)
            f.write("<!-- END HEADER -->\n\n")


def format_entry(actor, category, summary, mail=None, commit=None, artifact=None, tags=None):
    ts = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
    tag_str = " ".join(f"`{t}`" for t in (tags or []))
    lines = [f"## {ts} | {actor} | {category}"]
    if tag_str:
        lines.append(f"**Tags:** {tag_str}")
    lines.append("")
    lines.append(summary.strip())
    lines.append("")
    evidence = []
    if mail:
        evidence.append(f"- **Mail:** #{mail}")
    if commit:
        evidence.append(f"- **Commit:** `{commit}`")
    if artifact:
        evidence.append(f"- **Artifact:** `{artifact}`")
    if evidence:
        lines.append("**Evidence:**")
        lines.extend(evidence)
        lines.append("")
    lines.append("---")
    lines.append("")
    return "\n".join(lines)


def append_entry(args):
    ensure_log_exists()
    entry = format_entry(
        actor=args.actor,
        category=args.category,
        summary=args.summary,
        mail=args.mail,
        commit=args.commit,
        artifact=args.artifact,
        tags=args.tag,
    )
    with open(LOG_PATH, "r") as f:
        content = f.read()

    # Insert after the header marker
    marker = "<!-- END HEADER -->\n\n"
    if marker in content:
        pos = content.index(marker) + len(marker)
        new_content = content[:pos] + entry + content[pos:]
    else:
        new_content = content + entry

    with open(LOG_PATH, "w") as f:
        f.write(new_content)
    print(f"Appended entry to {LOG_PATH}")


def query_entries(args):
    if not os.path.exists(LOG_PATH):
        print("Log file does not exist yet.")
        sys.exit(1)

    with open(LOG_PATH, "r") as f:
        content = f.read()

    # Split into entries (each starts with "## YYYY-MM-DD")
    raw_entries = re.split(r"\n(?=## \d{4}-\d{2}-\d{2})", content)
    matches = []
    for e in raw_entries:
        if not e.strip().startswith("## 2"):
            continue
        ok = True
        if args.date and args.date not in e:
            ok = False
        if args.actor and args.actor not in e:
            ok = False
        if args.category and args.category not in e:
            ok = False
        if args.tag:
            for t in args.tag:
                if f"`{t}`" not in e:
                    ok = False
                    break
        if ok:
            matches.append(e)

    if not matches:
        print("No matching entries.")
        return

    print(f"Found {len(matches)} matching entry(ies):\n")
    for m in matches:
        print(m.strip())
        print()


def main():
    parser = argparse.ArgumentParser(description="Activity log for spinalhdlVDP")
    sub = parser.add_subparsers(dest="cmd")

    p_append = sub.add_parser("append", help="Append an entry")
    p_append.add_argument("--actor", required=True, help="Agent or person name")
    p_append.add_argument("--category", required=True,
                          choices=["MAIL", "BUILD", "SIM", "CODE", "DECISION", "ERROR", "RESEARCH", "REVERT", "MERGE"])
    p_append.add_argument("--summary", required=True, help="One-line summary")
    p_append.add_argument("--mail", type=int, default=None, help="Mail message ID")
    p_append.add_argument("--commit", default=None, help="Git commit hash")
    p_append.add_argument("--artifact", default=None, help="Artifact path")
    p_append.add_argument("--tag", action="append", default=[], help="Tag (repeatable)")

    p_query = sub.add_parser("query", help="Query entries")
    p_query.add_argument("--date", default=None, help="Date substring (YYYY-MM-DD)")
    p_query.add_argument("--actor", default=None, help="Actor name")
    p_query.add_argument("--category", default=None, help="Category")
    p_query.add_argument("--tag", action="append", default=[], help="Tag (repeatable)")

    args = parser.parse_args()
    if args.cmd == "append":
        append_entry(args)
    elif args.cmd == "query":
        query_entries(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
