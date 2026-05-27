#!/usr/bin/env python3
"""Cross-agent activity query tool for the unified sessionlog database."""

import sqlite3
import argparse
import sys
import os
from datetime import datetime, timedelta
from collections import Counter

DB_PATH = os.path.expanduser("~/.sessionlog/data.sqlite")


def get_conn():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def cmd_overview(args):
    conn = get_conn()
    print("=" * 60)
    print("CROSS-AGENT ACTIVITY OVERVIEW")
    print("=" * 60)

    # Agent counts
    print("\n--- Entries by Agent ---")
    c = conn.execute("""
        SELECT 'claude' as agent, COUNT(*) as n FROM raw_entries WHERE agent_type='claude'
        UNION ALL
        SELECT 'codex', COUNT(*) FROM raw_entries WHERE agent_type='codex'
        UNION ALL
        SELECT 'kimi', COUNT(*) FROM kimi_entries
    """)
    total = 0
    for row in c.fetchall():
        print(f"  {row['agent']:10s} {row['n']:>8,} entries")
        total += row['n']
    print(f"  {'TOTAL':10s} {total:>8,} entries")

    # Time range
    print("\n--- Time Range ---")
    c = conn.execute("""
        SELECT MIN(ts) as earliest, MAX(ts) as latest FROM (
            SELECT MIN(timestamp_utc) as ts FROM raw_entries
            UNION ALL
            SELECT MIN(timestamp_utc) FROM kimi_entries
        )
    """)
    row = c.fetchone()
    print(f"  Earliest: {row['earliest']}")
    c = conn.execute("""
        SELECT MAX(ts) as latest FROM (
            SELECT MAX(timestamp_utc) as ts FROM raw_entries
            UNION ALL
            SELECT MAX(timestamp_utc) FROM kimi_entries
        )
    """)
    row = c.fetchone()
    print(f"  Latest:   {row['latest']}")

    # Today's activity
    today = datetime.utcnow().strftime("%Y-%m-%d")
    print(f"\n--- Today ({today}) ---")
    c = conn.execute("""
        SELECT 'claude/codex' as agent, COUNT(*) as n FROM raw_entries
        WHERE timestamp_utc LIKE ?
        UNION ALL
        SELECT 'kimi', COUNT(*) FROM kimi_entries WHERE timestamp_utc LIKE ?
    """, (f"{today}%", f"{today}%"))
    for row in c.fetchall():
        print(f"  {row['agent']:12s} {row['n']:>6,} entries")

    # Sessions
    print("\n--- Sessions ---")
    c = conn.execute("SELECT COUNT(DISTINCT session_id) FROM raw_entries")
    print(f"  Claude/Codex sessions: {c.fetchone()[0]:,}")
    c = conn.execute("SELECT COUNT(DISTINCT session_id) FROM kimi_entries")
    print(f"  Kimi sessions:         {c.fetchone()[0]:,}")

    # Tool usage summary (Claude/Codex only)
    print("\n--- Top Tools (Claude/Codex) ---")
    c = conn.execute("""
        SELECT tool_names, COUNT(*) as n
        FROM raw_entries
        WHERE tool_names IS NOT NULL AND tool_names != '[]'
        GROUP BY tool_names
        ORDER BY n DESC
        LIMIT 10
    """)
    for row in c.fetchall():
        print(f"  {row['tool_names']:30s} {row['n']:>6,}")

    # Error summary (Claude/Codex only)
    print("\n--- Tool Errors (Claude/Codex) ---")
    c = conn.execute("""
        SELECT tool_result_error_type, COUNT(*) as n
        FROM raw_entries
        WHERE tool_result_error = 1
        GROUP BY tool_result_error_type
        ORDER BY n DESC
    """)
    for row in c.fetchall():
        print(f"  {row['tool_result_error_type'] or 'unknown':30s} {row['n']:>6,}")

    conn.close()


def cmd_tail(args):
    conn = get_conn()
    limit = args.n
    print("=" * 80)
    print(f"LAST {limit} ENTRIES (newest first)")
    print("=" * 80)

    # Unified query
    c = conn.execute("""
        SELECT * FROM (
            SELECT
                timestamp_utc,
                agent_type,
                entry_type,
                tool_names,
                SUBSTR(COALESCE(user_text, text_content, ''), 0, 100) as snippet
            FROM raw_entries
            UNION ALL
            SELECT
                timestamp_utc,
                'kimi' as agent_type,
                entry_type,
                tool_name as tool_names,
                SUBSTR(COALESCE(user_text, text_content, ''), 0, 100) as snippet
            FROM kimi_entries
        )
        ORDER BY timestamp_utc DESC
        LIMIT ?
    """, (limit,))

    for row in c.fetchall():
        ts = row['timestamp_utc'][:19] if row['timestamp_utc'] else '???'
        agent = row['agent_type'] or '?'
        etype = row['entry_type'] or '?'
        tools = row['tool_names'] or ''
        snippet = (row['snippet'] or '').replace('\n', ' ')[:80]
        print(f"{ts} | {agent:8s} | {etype:12s} | {tools:15s} | {snippet}")

    conn.close()


def cmd_search(args):
    conn = get_conn()
    query = args.query
    limit = args.limit
    print(f"Searching for: '{query}'")
    print("=" * 80)

    # Search raw_entries
    c = conn.execute("""
        SELECT timestamp_utc, agent_type, entry_type, user_text, text_content
        FROM raw_entries
        WHERE user_text LIKE ? OR text_content LIKE ? OR tool_input_preview LIKE ?
        ORDER BY timestamp_utc DESC
        LIMIT ?
    """, (f"%{query}%", f"%{query}%", f"%{query}%", limit))

    count = 0
    for row in c.fetchall():
        count += 1
        ts = row['timestamp_utc'][:19] if row['timestamp_utc'] else '???'
        text = row['user_text'] or row['text_content'] or ''
        print(f"\n[{ts}] {row['agent_type']} | {row['entry_type']}")
        print(text[:500])

    # Search kimi_entries
    c = conn.execute("""
        SELECT timestamp_utc, entry_type, user_text, text_content
        FROM kimi_entries
        WHERE user_text LIKE ? OR text_content LIKE ?
        ORDER BY timestamp_utc DESC
        LIMIT ?
    """, (f"%{query}%", f"%{query}%", limit))

    for row in c.fetchall():
        count += 1
        ts = row['timestamp_utc'][:19] if row['timestamp_utc'] else '???'
        text = row['user_text'] or row['text_content'] or ''
        print(f"\n[{ts}] kimi | {row['entry_type']}")
        print(text[:500])

    print(f"\n--- {count} matches ---")
    conn.close()


def cmd_errors(args):
    conn = get_conn()
    limit = args.n
    since = args.since
    print(f"Recent errors (since {since})")
    print("=" * 80)

    c = conn.execute("""
        SELECT timestamp_utc, agent_type, entry_type, tool_result_error_type,
               SUBSTR(user_text, 0, 300) as error_text
        FROM raw_entries
        WHERE tool_result_error = 1
          AND timestamp_utc >= ?
        ORDER BY timestamp_utc DESC
        LIMIT ?
    """, (since, limit))

    for row in c.fetchall():
        ts = row['timestamp_utc'][:19] if row['timestamp_utc'] else '???'
        print(f"\n[{ts}] {row['agent_type']} | {row['tool_result_error_type']}")
        print(row['error_text'] or '(no text)')

    conn.close()


def cmd_files(args):
    conn = get_conn()
    limit = args.n
    print(f"Most touched files (Claude/Codex)")
    print("=" * 80)

    c = conn.execute("""
        SELECT tool_file_paths, COUNT(*) as n
        FROM raw_entries
        WHERE tool_file_paths IS NOT NULL AND tool_file_paths != '[]'
        GROUP BY tool_file_paths
        ORDER BY n DESC
        LIMIT ?
    """, (limit,))

    for row in c.fetchall():
        print(f"{row['n']:>4} | {row['tool_file_paths']}")

    conn.close()


def main():
    parser = argparse.ArgumentParser(
        description="Query unified cross-agent activity logs",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s overview                  # High-level summary
  %(prog)s tail -n 20                # Last 20 entries across all agents
  %(prog)s search "scaler"           # Search all text for "scaler"
  %(prog)s errors -n 10              # Last 10 tool errors
  %(prog)s files -n 20               # Most touched files
        """,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_overview = sub.add_parser("overview", help="High-level activity summary")

    p_tail = sub.add_parser("tail", help="Recent entries (newest first)")
    p_tail.add_argument("-n", type=int, default=20, help="Number of entries")

    p_search = sub.add_parser("search", help="Full-text search")
    p_search.add_argument("query", help="Search string")
    p_search.add_argument("-l", "--limit", type=int, default=20, help="Max results")

    p_errors = sub.add_parser("errors", help="Recent tool errors")
    p_errors.add_argument("-n", type=int, default=10, help="Number of errors")
    p_errors.add_argument(
        "--since",
        default=(datetime.utcnow() - timedelta(days=7)).strftime("%Y-%m-%d"),
        help="ISO date cutoff (default: 7 days ago)",
    )

    p_files = sub.add_parser("files", help="Most touched files")
    p_files.add_argument("-n", type=int, default=20, help="Number of files")

    args = parser.parse_args()

    if not os.path.exists(DB_PATH):
        print(f"Database not found: {DB_PATH}", file=sys.stderr)
        sys.exit(1)

    globals()[f"cmd_{args.command}"](args)


if __name__ == "__main__":
    main()
