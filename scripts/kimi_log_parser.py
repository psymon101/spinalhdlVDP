#!/usr/bin/env python3
"""
kimi_log_parser.py — Parse Kimi CLI plain-text logs and append to the sessionlog SQLite DB.

Usage:
    python3 scripts/kimi_log_parser.py ingest
    python3 scripts/kimi_log_parser.py query --today
    python3 scripts/kimi_log_parser.py query --session <uuid>
    python3 scripts/kimi_log_parser.py daemon
"""

import argparse
import datetime
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

# Paths
HOME_DIR = Path("/home/itadmin")
DEFAULT_KIMI_LOG_DIRS = [
    HOME_DIR / ".kimi" / "logs",
    HOME_DIR / ".agent-homes" / "topazcliff" / "home" / ".kimi" / "logs",
    HOME_DIR / ".agent-homes" / "coralreef" / "home" / ".kimi" / "logs",
]
SESSIONLOG_DB = HOME_DIR / ".sessionlog" / "data.sqlite"

# Regex patterns
RE_TIMESTAMP = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)")
RE_LOG_LEVEL = re.compile(r"\|\s+(DEBUG|INFO|WARNING|ERROR|CRITICAL)\s+\|")
RE_LOGGER = re.compile(r"\|\s+([\w\.]+:\w+:\d+)\s+\|")
RE_SESSION_UUID = re.compile(r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
RE_USER_INPUT = re.compile(r"Running soul with user input:.*text='([^']+)'")
RE_TOOL_CALL = re.compile(r"Tool\s+(\w+)\s+(completed|failed|started)")
RE_TOOL_DURATION = re.compile(r"completed in ([\d.]+)s")
RE_LLM_STEP = re.compile(r"LLM step completed in ([\d.]+)s \(input=(\d+), output=(\d+)\)")
RE_ERROR = re.compile(r"ERROR.*?(Exception|Error|Failed|Traceback)", re.IGNORECASE)


def parse_line(line: str) -> dict | None:
    """Parse a single Kimi log line into a structured dict."""
    line = line.strip()
    if not line:
        return None

    ts_match = RE_TIMESTAMP.match(line)
    if not ts_match:
        return None

    ts_str = ts_match.group(1)
    try:
        ts = datetime.datetime.strptime(ts_str, "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None

    level_match = RE_LOG_LEVEL.search(line)
    level = level_match.group(1) if level_match else "INFO"

    logger_match = RE_LOGGER.search(line)
    logger = logger_match.group(1) if logger_match else "unknown"

    session_match = RE_SESSION_UUID.search(line)
    session_id = session_match.group(1) if session_match else ""

    # Classify entry type
    entry_type = "unknown"
    user_text = ""
    tool_name = ""
    tool_error = 0
    tool_error_type = ""
    duration_ms = None
    input_tokens = None
    output_tokens = None
    text_content = line.split("|", 4)[-1].strip() if line.count("|") >= 4 else ""

    if "run_soul_command" in logger:
        input_match = RE_USER_INPUT.search(line)
        if input_match:
            entry_type = "user_input"
            user_text = input_match.group(1)
    elif "toolset:_call" in logger:
        tool_match = RE_TOOL_CALL.search(line)
        if tool_match:
            entry_type = "tool_call"
            tool_name = tool_match.group(1)
            status = tool_match.group(2)
            if status == "failed":
                tool_error = 1
        dur_match = RE_TOOL_DURATION.search(line)
        if dur_match:
            duration_ms = int(float(dur_match.group(1)) * 1000)
    elif "kimisoul:_step" in logger:
        step_match = RE_LLM_STEP.search(line)
        if step_match:
            entry_type = "llm_step"
            duration_ms = int(float(step_match.group(1)) * 1000)
            input_tokens = int(step_match.group(2))
            output_tokens = int(step_match.group(3))
    elif level in ("ERROR", "CRITICAL"):
        entry_type = "error"
        tool_error = 1
        err_match = RE_ERROR.search(line)
        if err_match:
            tool_error_type = err_match.group(1)

    return {
        "timestamp_utc": ts.isoformat(),
        "session_id": session_id,
        "level": level,
        "logger": logger,
        "entry_type": entry_type,
        "user_text": user_text,
        "tool_name": tool_name,
        "tool_error": tool_error,
        "tool_error_type": tool_error_type,
        "duration_ms": duration_ms,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "text_content": text_content[:500],  # truncate
    }


def ensure_kimi_table(conn: sqlite3.Connection):
    """Create the kimi_entries table if it doesn't exist."""
    conn.execute("""
        CREATE TABLE IF NOT EXISTS kimi_entries (
            entry_id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp_utc TEXT NOT NULL,
            session_id TEXT,
            level TEXT,
            logger TEXT,
            entry_type TEXT,
            user_text TEXT,
            tool_name TEXT,
            tool_error INTEGER DEFAULT 0,
            tool_error_type TEXT,
            duration_ms INTEGER,
            input_tokens INTEGER,
            output_tokens INTEGER,
            text_content TEXT,
            ingested_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_kimi_ts ON kimi_entries(timestamp_utc)
    """)
    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_kimi_session ON kimi_entries(session_id)
    """)
    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_kimi_type ON kimi_entries(entry_type)
    """)
    conn.commit()


def get_last_ingested_ts(conn: sqlite3.Connection) -> str:
    """Return the most recent timestamp already in the DB, or empty string."""
    cursor = conn.execute("SELECT MAX(timestamp_utc) FROM kimi_entries")
    row = cursor.fetchone()
    return row[0] or ""


def ingest_logs(db_path: Path, log_dirs: list[Path], force: bool = False):
    """Parse all Kimi log files and insert into DB."""
    conn = sqlite3.connect(str(db_path))
    ensure_kimi_table(conn)

    if force:
        print("Force mode: clearing kimi_entries table...")
        conn.execute("DELETE FROM kimi_entries")
        conn.commit()
        last_ts = ""
    else:
        last_ts = get_last_ingested_ts(conn)

    total = 0
    inserted = 0

    all_log_files = []
    for log_dir in log_dirs:
        if log_dir.exists():
            files = sorted(log_dir.glob("kimi*.log"))
            all_log_files.extend(files)
            print(f"  {log_dir}: {len(files)} file(s)")
        else:
            print(f"  {log_dir}: not found, skipping")

    print(f"\nFound {len(all_log_files)} total log file(s)")

    for log_file in all_log_files:
        print(f"  Parsing {log_file.name} ...", end=" ", flush=True)
        file_total = 0
        file_inserted = 0
        with open(log_file, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                parsed = parse_line(line)
                if not parsed:
                    continue
                file_total += 1
                total += 1
                if not force and parsed["timestamp_utc"] <= last_ts:
                    continue
                conn.execute("""
                    INSERT INTO kimi_entries
                    (timestamp_utc, session_id, level, logger, entry_type,
                     user_text, tool_name, tool_error, tool_error_type,
                     duration_ms, input_tokens, output_tokens, text_content)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    parsed["timestamp_utc"], parsed["session_id"], parsed["level"],
                    parsed["logger"], parsed["entry_type"], parsed["user_text"],
                    parsed["tool_name"], parsed["tool_error"], parsed["tool_error_type"],
                    parsed["duration_ms"], parsed["input_tokens"], parsed["output_tokens"],
                    parsed["text_content"]
                ))
                file_inserted += 1
                inserted += 1
        print(f"{file_total} parsed, {file_inserted} new")

    conn.commit()
    conn.close()
    print(f"\nDone. {total} lines parsed, {inserted} new entries inserted.")


def query_logs(db_path: Path, today: bool = False, session_id: str = "", limit: int = 50):
    """Query Kimi entries from the DB."""
    conn = sqlite3.connect(str(db_path))
    ensure_kimi_table(conn)

    sql = "SELECT timestamp_utc, session_id, level, entry_type, tool_name, user_text, text_content FROM kimi_entries"
    params = []
    conditions = []

    if today:
        today_str = datetime.datetime.now().strftime("%Y-%m-%d")
        conditions.append("timestamp_utc LIKE ?")
        params.append(f"{today_str}%")

    if session_id:
        conditions.append("session_id = ?")
        params.append(session_id)

    if conditions:
        sql += " WHERE " + " AND ".join(conditions)

    sql += " ORDER BY timestamp_utc DESC LIMIT ?"
    params.append(limit)

    cursor = conn.execute(sql, params)
    rows = cursor.fetchall()
    conn.close()

    if not rows:
        print("No matching entries.")
        return

    print(f"{'Timestamp':<26} {'Level':<8} {'Type':<14} {'Tool':<12} {'Text':<50}")
    print("-" * 120)
    for row in rows:
        ts, sid, lvl, etype, tool, utext, content = row
        display = utext or content or ""
        display = display[:48].replace("\n", " ")
        print(f"{ts:<26} {lvl:<8} {etype:<14} {tool or '':<12} {display}")


def run_daemon(db_path: Path, interval: int = 30):
    """Watch Kimi log files and ingest new lines continuously."""
    print(f"Daemon mode: watching {KIMI_LOG_DIR} every {interval}s")
    print("Press Ctrl+C to stop.")
    while True:
        try:
            ingest_logs(db_path, force=False)
            time.sleep(interval)
        except KeyboardInterrupt:
            print("\nDaemon stopped.")
            break


def main():
    parser = argparse.ArgumentParser(description="Kimi CLI log parser for sessionlog")
    parser.add_argument("--db", default=str(SESSIONLOG_DB), help="SQLite DB path")
    sub = parser.add_subparsers(dest="cmd")

    p_ingest = sub.add_parser("ingest", help="One-shot ingestion of all Kimi logs")
    p_ingest.add_argument("--force", action="store_true", help="Re-ingest everything")

    p_query = sub.add_parser("query", help="Query parsed Kimi entries")
    p_query.add_argument("--today", action="store_true", help="Only today")
    p_query.add_argument("--session", default="", help="Filter by session UUID")
    p_query.add_argument("--limit", type=int, default=50, help="Max rows")

    p_daemon = sub.add_parser("daemon", help="Continuous ingestion daemon")
    p_daemon.add_argument("--interval", type=int, default=30, help="Seconds between scans")

    args = parser.parse_args()
    db_path = Path(args.db)

    if args.cmd == "ingest":
        ingest_logs(db_path, log_dirs=DEFAULT_KIMI_LOG_DIRS, force=args.force)
    elif args.cmd == "query":
        query_logs(db_path, today=args.today, session_id=args.session, limit=args.limit)
    elif args.cmd == "daemon":
        run_daemon(db_path, interval=args.interval)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
