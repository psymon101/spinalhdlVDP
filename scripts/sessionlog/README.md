# Unified Sessionlog Infrastructure

Cross-agent activity capture and query system.

## Database

`~/.sessionlog/data.sqlite` — SQLite with WAL mode.

| Table | Source | Agent | Description |
|-------|--------|-------|-------------|
| `raw_entries` | `sessionlog` JSONL ingest | Claude, Codex | Messages, tool calls, errors, tokens |
| `kimi_entries` | `scripts/kimi_log_parser.py` | Kimi | Parsed from `~/.kimi/logs/kimi*.log` |
| `progress_entries` | `sessionlog` | Claude, Codex | Sub-agent progress, bash heartbeats |
| `sessions` | `sessionlog` | Claude, Codex | Aggregated per-session stats |
| `session_features` | `sessionlog` | Claude, Codex | Behavioral features per session |
| `messages_fts` | `sessionlog` | Claude, Codex | FTS5 full-text search index |

## Sync

A cronjob runs every minute (`crontab -l`):

```
* * * * * /home/itadmin/github/spinalhdlVDP/scripts/sync_sessionlog.sh
```

What it does:
1. `sessionlog ingest` — incremental parse of Claude/Codex JSONL logs
2. `scripts/kimi_log_parser.py` — incremental parse of Kimi plain-text logs
3. Appends stats to `~/.sessionlog/sync.log`

## Query Tools

### MCP server `sqlite-sessionlog`

Wired into **all agent configs**:

| Agent | Platform | Config path |
|-------|----------|-------------|
| BrightForge | Claude Code | `~/.claude/settings.json` |
| BrightForge | Gemini | `~/.gemini/settings.json` |
| BronzeGate | Claude Code | `~/.claude/settings.json` |
| BronzeGate | Gemini | `~/.gemini/settings.json` |
| BronzeGate | Codex | `~/.codex/config.toml` |
| CyanPeak | Claude Code | `~/.claude/settings.json` |
| CyanPeak | Antigravity CLI (`agy`) | `~/.gemini/config/mcp_config.json` |
| TopazCliff | Claude Code | `~/.claude/settings.json` |
| TopazCliff | Gemini | `~/.gemini/settings.json` |
| TopazCliff | Kimi | `~/.kimi/mcp.json` |
| CoralReef | Claude Code | `~/.claude/settings.json` |
| CoralReef | Gemini | `~/.gemini/settings.json` |
| CoralReef | Kimi | `~/.kimi/mcp.json` |

Tools exposed:
- `read_query` — SELECT
- `write_query` — INSERT/UPDATE/DELETE
- `list_tables` — show all tables
- `describe_table` — schema for one table
- `get_schema_ddl` — full CREATE TABLE dump
- `connect_database` — switch DB file
- `get_custom_instructions` — best practices doc

### CLI wrapper: `scripts/query_logs.py`

```bash
python3 scripts/query_logs.py overview        # High-level stats
python3 scripts/query_logs.py tail -n 20      # Last 20 entries
python3 scripts/query_logs.py search "scaler" # Full-text search
python3 scripts/query_logs.py errors -n 10    # Recent tool errors
python3 scripts/query_logs.py files -n 20     # Most touched files
```

### Activity log: `scripts/log_activity.py`

```bash
python3 scripts/log_activity.py append \
  --actor BrightForge --category BUILD \
  --summary "CP-C PASS" \
  --mail 10691 --commit 93e5924 --tag "palette-lane"

python3 scripts/log_activity.py query --tag "palette-lane"
```

## Files

| File | Purpose |
|------|---------|
| `scripts/sync_sessionlog.sh` | Cron script: sessionlog ingest + kimi parser |
| `scripts/kimi_log_parser.py` | Parse `~/.kimi/logs/kimi*.log` into SQLite |
| `scripts/query_logs.py` | Cross-agent query CLI |
| `scripts/log_activity.py` | Append/query `PROJECT_PLAN/ACTIVITY_LOG.md` |
