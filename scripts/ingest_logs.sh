#!/usr/bin/env bash
# Ingests the experiment's own engine logs through the engine's SQL front
# door, then filters them with the engine itself.
#
# docs/experiment-design.md takes the y axis -- median durationMs -- from the
# engine's own SELECT log line, and the partition counts from the pruning log
# lines. This script checks that the numbers in results/raw.csv really are
# what the engine logged, using the engine as the reader rather than a
# separate parser: it loads every per-setting run.log into a `logs` table,
# then runs the same three analyses Exercise 5 asks for (one session, one
# statement number, the failures).
#
# Usage: scripts/ingest_logs.sh
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

OUT="$REPO/results"
LOGS="$OUT/logs"
INGEST_DATA="$OUT/ingest"
DATA_FILE="$OUT/ingest-all.log"

if [ ! -d "$LOGS" ]; then
  echo "no logs at $LOGS; run scripts/run_experiment.sh first" >&2
  exit 1
fi

# One flat file with every setting's lines keeps the COPY to a single
# statement, so the whole ingestion is one engine session. Log4j2 flushes
# whole lines, so the concatenation stays line-aligned.
: > "$DATA_FILE"
for d in "$LOGS"/*/; do
  cat "$d/run.log" >> "$DATA_FILE"
done

rm -rf "$INGEST_DATA"
mkdir -p "$INGEST_DATA/logs"

cat > "$OUT/ingest.sql" <<SQL
CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG, logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM '$DATA_FILE';
SQL

# COPY prints nothing; SELECTs below write their rows to the CSV files.
( cd "$INGEST_DATA" && "$REPO/engine" -f "$OUT/ingest.sql" > /dev/null )

# `skip` and `limit` are not in the grammar, so each analysis is a `WHERE`
# the engine can prune and filter: the ERROR lines come from the log-level
# column, and the two session/statement analyses pick one value each.
SESSION="$(awk -F, '$6 == "StorageEngine" { print $2; exit }' "$DATA_FILE")"

# Three analyses, each its own SELECT so one query's CSV lands in one file:
#   errors.csv     -- every ERROR line in all the experiment logs
#   session.csv    -- one session start to finish (exercises sessionId)
#   statement7.csv -- statement 7 from every session (statementNumber alone
#                     is not unique across sessions; sessionId is)
cat > "$OUT/ingest-queries.sql" <<SQL
SELECT * FROM logs WHERE logLevel = 'ERROR';
SELECT * FROM logs WHERE sessionId = '$SESSION';
SELECT * FROM logs WHERE statementNumber = 7;
SQL

( cd "$INGEST_DATA" && \
  "$REPO/engine" -c "SELECT * FROM logs WHERE logLevel = 'ERROR';" > "$OUT/errors.csv" && \
  "$REPO/engine" -c "SELECT * FROM logs WHERE sessionId = '$SESSION';" > "$OUT/session.csv" && \
  "$REPO/engine" -c "SELECT * FROM logs WHERE statementNumber = 7;" > "$OUT/statement7.csv" )

awk -F, '$5 == "ERROR" { n++ } END { print "error_lines=" n+0 }' "$DATA_FILE"
echo "ingested_lines=$(wc -l < "$DATA_FILE" | tr -d ' ')"
echo "sample_session=$SESSION"
echo "session_rows=$(wc -l < "$OUT/session.csv" | tr -d ' ')"
echo "statement7_rows=$(wc -l < "$OUT/statement7.csv" | tr -d ' ')"
echo "errors_rows=$(wc -l < "$OUT/errors.csv" | tr -d ' ')"
echo "sql=$OUT/ingest.sql $OUT/ingest-queries.sql"
