#!/bin/bash
# Sync all agent logs into the unified sessionlog database.
# Runs sessionlog ingest (Claude/Codex) + Kimi log parser.
# Called by cron every minute.

LOG_FILE="/home/itadmin/.sessionlog/sync.log"
DB_PATH="/home/itadmin/.sessionlog/data.sqlite"
REPO_DIR="/home/itadmin/github/spinalhdlVDP"
export SESSIONLOG_DB="$DB_PATH"

echo "=== $(date -Iseconds) ===" >> "$LOG_FILE"

# 1. Ingest Claude + Codex JSONL logs
cd "$REPO_DIR" && \
  . .venv-sessionlog/bin/activate && \
  sessionlog ingest >> "$LOG_FILE" 2>&1
INGEST_STATUS=$?
if [ $INGEST_STATUS -eq 0 ]; then
  echo "sessionlog ingest: OK" >> "$LOG_FILE"
else
  echo "sessionlog ingest: FAILED (exit $INGEST_STATUS)" >> "$LOG_FILE"
fi

# 2. Ingest Kimi plain-text logs
cd "$REPO_DIR" && \
  python3 scripts/kimi_log_parser.py >> "$LOG_FILE" 2>&1
KIMI_STATUS=$?
if [ $KIMI_STATUS -eq 0 ]; then
  echo "kimi parser: OK" >> "$LOG_FILE"
else
  echo "kimi parser: FAILED (exit $KIMI_STATUS)" >> "$LOG_FILE"
fi

# 3. Quick stats
echo "DB size: $(du -h "$DB_PATH" | cut -f1)" >> "$LOG_FILE"
echo "---" >> "$LOG_FILE"
