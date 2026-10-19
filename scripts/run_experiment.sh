#!/usr/bin/env bash
# Runs the experiment in docs/experiment-design.md: partition size vs. SELECT
# latency on the packaged engine.
#
# One setting = one (ordering, maxRowsPerPartition) pair. Per the design:
#   * fresh data directory, CREATE, set the cap, COPY;
#   * one script per setting doing one warm-up plus R repeats of each query;
#   * repeats of one setting share a single engine session, warm-up discarded;
#   * fresh log directory per setting, results matched by sessionId.
#
# Conditions are stated once in results/environment.txt, which this script
# also writes. Re-running overwrites results/ from scratch.
#
# Usage: scripts/run_experiment.sh [repeats]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

REPEATS="${1:-5}"
ROWS=1000000
# x axis: rows per partition, log scale, 1k..100k (docs/experiment-design.md).
CAPS=(1000 3000 10000 30000 100000)
# id > C, run with constants giving different selectivities.
# C=0 is the full scan. Share of the table passing = (1 - C/ROWS).
CONSTS=(0 500000 900000 990000)
ORDERINGS=(sorted shuffled)

DATA_DIR="$REPO/experiment-data"
OUT="$REPO/results"
LOGS="$OUT/logs"
# Heap: fixed for every setting and every statement, so the sweep is not
# measuring the allocator. 2g comfortably holds the 1M-row load (pilot peak
# RSS ~392 MB) and the largest result set. The partition cap is appended per
# setting below: docs/experiment-design.md sets it in the catalog between
# CREATE TABLE and COPY, so it is a -D system property on the CREATE run.
HEAP_OPTS="-Xmx2g"

if [ ! -f target/engine.jar ]; then
  echo "target/engine.jar is missing; run 'mvn package' first" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$LOGS"

# Regenerate the inputs from the committed script and the fixed seed, then
# confirm the files actually on disk match what the script produces. A
# teammate reruns the sweep against exactly these bytes.
if [ ! -f "$DATA_DIR/sorted.csv" ] || [ ! -f "$DATA_DIR/shuffled.csv" ]; then
  python3 scripts/gen_experiment_data.py "$DATA_DIR" "$ROWS" > /dev/null
fi
python3 scripts/gen_experiment_data.py "$DATA_DIR" "$ROWS" > "$OUT/data.txt"

# --- State the conditions once -------------------------------------------
{
  echo "commit=$(git rev-parse HEAD)"
  echo "commit_subject=$(git log -1 --pretty=%s)"
  echo "branch=$(git rev-parse --abbrev-ref HEAD)"
  echo "measured_at=$(date -Iseconds)"
  echo "machine=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
  echo "cpu_cores=$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
  echo "memory_bytes=$(sysctl -n hw.memsize 2>/dev/null || echo unknown)"
  echo "os=$(sw_vers -productName 2>/dev/null || uname -s) $(sw_vers -productVersion 2>/dev/null || uname -r)"
  echo "arch=$(uname -m)"
  echo "jvm=$(java -version 2>&1 | head -1)"
  echo "jvm_vendor=$(java -version 2>&1 | sed -n 2p)"
  echo "heap=$HEAP_OPTS"
  echo "rows=$ROWS"
  echo "caps=${CAPS[*]}"
  echo "consts=${CONSTS[*]}"
  echo "orderings=${ORDERINGS[*]}"
  echo "repeats=$REPEATS (1 warm-up discarded + $REPEATS timed)"
  echo "seed=20261019 (scripts/gen_experiment_data.py)"
  for f in sorted.csv shuffled.csv; do
    echo "data_$f=sha256:$(shasum -a 256 "$DATA_DIR/$f" | cut -d' ' -f1)"
  done
} > "$OUT/environment.txt"

cat "$OUT/environment.txt"

# --- Sweep ----------------------------------------------------------------
echo "setting,ordering,rowsPerPartition,const,repeat,statementNumber,sessionId,durationMs,partitionsRead,partitionsPruned,rowsOut" \
  > "$OUT/raw.csv"

for ordering in "${ORDERINGS[@]}"; do
  for cap in "${CAPS[@]}"; do
    name="${ordering}_${cap}"
    work="$OUT/work/$name"
    mkdir -p "$work"
    logdir="$LOGS/$name"
    mkdir -p "$logdir"

    # Fresh data directory per setting.
    ( cd "$work" && mkdir -p data )

    # Statement 1: CREATE (the cap is captured into the catalog here).
    # Statement 2: COPY.
    ( cd "$work" && ENGINE_JAVA_OPTS="$HEAP_OPTS -Dengine.maxRowsPerPartition=$cap" \
        "$REPO/engine" -c "CREATE TABLE trips (id LONG, tag LONG, value DOUBLE, city STRING); COPY trips FROM '$DATA_DIR/$ordering.csv';" \
        > /dev/null )

    # Guard: the cap must actually reach the catalog, otherwise the x axis
    # does not vary and the whole sweep measures one setting ten times.
    stored="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["tables"]["trips"]["maxRowsPerPartition"])' "$work/data/catalog.json")"
    built="$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))["tables"]["trips"]["partitions"]))' "$work/data/catalog.json")"
    if [ "$stored" != "$cap" ]; then
      echo "cap did not reach the catalog for $name: wanted $cap, catalog says $stored" >&2
      exit 1
    fi
    echo "  $name: cap=$stored partitions=$built"

    # Fresh log directory per setting: move the created log into place, then
    # run the timed session against an empty live log.
    mv "$work/logs/engine.log" "$logdir/load.log"
    rm -rf "$work/logs"

    # One script: 1 warm-up + REPEATS of each query, in one session.
    script="$work/queries.sql"
    : > "$script"
    for c in "${CONSTS[@]}"; do
      printf 'SELECT * FROM trips WHERE id > %s;\n' "$c" >> "$script"
    done
    printf 'SELECT * FROM trips;\n' >> "$script"
    for _ in $(seq 1 "$REPEATS"); do
      for c in "${CONSTS[@]}"; do
        printf 'SELECT * FROM trips WHERE id > %s;\n' "$c" >> "$script"
      done
      printf 'SELECT * FROM trips;\n' >> "$script"
    done

    ( cd "$work" && ENGINE_JAVA_OPTS="$HEAP_OPTS" \
        "$REPO/engine" -f queries.sql > /dev/null )
    mv "$work/logs/engine.log" "$logdir/run.log"

    # Reduce this setting's own log with awk: keep the StorageEngine SELECT
    # summary lines (they carry durationMs and the partition counts), skip
    # the warm-up (first occurrence of each const / full scan), and emit the
    # timed repeats.
    awk -v setting="$name" -v ordering="$ordering" -v cap="$cap" -v repeats="$REPEATS" '
      BEGIN { FS=","; OFS="," }
      $6 != "StorageEngine" { next }
      $7 !~ /rowsOut=/ { next }
      {
        # statementNumber is field 3; only SELECT summaries reach here.
        const = ""; kind = "scan"
        if (match($7, /const=[^ ]+/)) { const = substr($7, RSTART+6, RLENGTH-6); kind = "pred" }
        else if (match($7, /partitionsRead=/)) { const = "ALL"; kind = "scan" }
        if (const == "") next
        key = kind ":" const
        seen[key]++
        if (seen[key] <= 1) next           # warm-up, discarded
        rep = seen[key] - 1
        # Pull the measurements out of the message.
        dur=""; pr=""; pp=""; ro=""
        if (match($7, /durationMs=[0-9]+/)) dur = substr($7, RSTART+11, RLENGTH-11)
        if (match($7, /partitionsRead=[0-9]+/)) pr = substr($7, RSTART+15, RLENGTH-15)
        if (match($7, /partitionsPruned=[0-9]+/)) pp = substr($7, RSTART+17, RLENGTH-17)
        if (match($7, /rowsOut=[0-9]+/)) ro = substr($7, RSTART+8, RLENGTH-8)
        print setting, ordering, cap, const, rep, $3, $2, dur, pr, pp, ro
      }
    ' "$logdir/run.log" >> "$OUT/raw.csv"

    echo "done $name"
  done
done

echo "raw measurements: $OUT/raw.csv ($(($(wc -l < "$OUT/raw.csv") - 1)) rows)"
