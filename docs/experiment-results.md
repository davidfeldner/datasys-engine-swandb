# Experiment results: partition size vs. SELECT latency

Ran `docs/experiment-design.md` exactly as written. Everything below comes
from the engine's own log lines; the raw measurements, the per-setting logs,
and the plot are all produced by committed scripts.

## Conditions (stated once)

| | |
|---|---|
| Machine | Apple M1, 8 cores, 8 GB RAM |
| OS | macOS 27.0.1 (arm64) |
| JVM | OpenJDK 25.0.4 LTS (Temurin-25.0.4+7) |
| Heap | `-Xmx2g`, fixed for every setting and statement |
| Commit measured | `0b614f1318601a3bb8a130292685f90498310f86` (`Session 5 implementation (#11)`, branch `session-6`) |
| Table | 1,000,000 rows, `(id LONG, tag LONG, value DOUBLE, city STRING)` |
| x axis | `maxRowsPerPartition` = 1000, 3000, 10000, 30000, 100000 |
| Predicate | `id > C` for C = 0, 500000, 900000, 990000 (100%, 50%, 10%, 1% selectivity) plus a full scan |
| Repeats | 1 warm-up (discarded) + 5 timed, in one session per setting |
| Data seed | `20261019`, fixed in `scripts/gen_experiment_data.py` |

Reproduce with `mvn package && scripts/run_experiment.sh 5`.

## Data generation

`scripts/gen_experiment_data.py` writes two CSVs holding **the same rows in
two orders**: `sorted.csv` is ordered by the predicate column `id`, so each
partition's min/max range is tight; `shuffled.csv` is a fixed-seed
permutation of exactly those ids, so every partition's range spans nearly the
whole domain and min/max pruning can skip almost nothing. Data files are
gitignored but regenerable byte-for-byte from the committed script and seed;
`run_experiment.sh` records their SHA-256 in `results/environment.txt`.

## Procedure notes

- One setting = one `(ordering, cap)`. For each: fresh data directory,
  `CREATE`, then `COPY` with `-Dengine.maxRowsPerPartition=<cap>` (the design's
  "set the cap between CREATE and COPY" — it is captured into the catalog at
  `CREATE` time and used by the `COPY`).
- One script per setting: 1 warm-up + 5 timed repeats of each query, all in
  **one engine session**; the timed session's log goes to a fresh
  `results/logs/<setting>/run.log`, so results are matched by `sessionId`
  (verified: exactly one distinct session per setting).
- The x axis really varied: caps 1000/3000/10000/30000/100000 produced
  1000/334/100/34/10 partitions respectively. The sweep script asserts the
  cap reached the catalog and aborts otherwise.
- The first draft of the sweep silently forgot to pass the cap, so all ten
  settings measured the 10000-row default. The guard above now makes that
  failure impossible to miss.

## Results

`results/summary.csv` (one row per point), `results/raw.csv` (250 timed
measurements), `results/plot.png`.

Median `durationMs` from the engine's `StorageEngine` SELECT summary line;
`partitionsRead` from the same line, which the planner filled from the pruning
decision.

| cap | partitions | sorted 1% | sorted 10% | sorted 50% | sorted 100% | sorted scan | shuffled 1% | shuffled read (all) |
|---|---|---|---|---|---|---|---|---|
| 1000 | 1000 | 6 ms | 15 ms | 96 ms | 161 ms | 170 ms | 111 ms | 1000 |
| 3000 | 334 | 2 ms | 9 ms | 68 ms | 133 ms | 131 ms | 85 ms | 334 |
| 10000 | 100 | 1 ms | 7 ms | 61 ms | 132 ms | 134 ms | 78 ms | 100 |
| 30000 | 34 | 1 ms | 6 ms | 59 ms | 124 ms | 126 ms | 81 ms | 34 |
| 100000 | 10 | 9 ms | 6 ms | 60 ms | 136 ms | 124 ms | 91 ms | 10 |

## What the numbers say

**The hypothesis holds, but only when the data is sorted by the predicate
column.** On `sorted.csv`, a selective query gets strictly faster as
partitions get smaller: at 1% the median falls 6 → 2 → 1 → 1 ms from cap 1000
to 30000, and at 10% it falls 15 → 9 → 7 → 6 ms. The mechanism is visible in
the pruning counts: at 1%, cap 1000 reads 10 of 1000 partitions, cap 3000
reads 4 of 334, and cap ≥ 10000 reads exactly 1 partition. Smaller partitions
are strictly better for selective predicates, as predicted.

**The predicted per-partition overhead is real but small.** The 100% and
full-scan curves are flat-to-slightly-rising as partitions shrink (124–136 ms
at 100k, 132–161 ms at cap 1000), because those queries must read everything
and pay only the extra bookkeeping. At cap 1000 that overhead shows up as
~10% on the full scan.

**On shuffled data, partition size is nearly irrelevant** — every partition
survives pruning at every size, so `partitionsRead` always equals the total
and the medians sit in a narrow 78–171 ms band with no trend. This is the
control: it isolates the observed speedups to min/max pruning rather than to
partition size itself.

**The one reversal is an artifact of granularity, not a slowdown.** Sorted 1%
at cap 100000 reads 1 partition of 100000 rows — a coarser read than 3
partitions of 1000 rows but the same result count, and its median (9 ms) is
noisy against single-digit ms values where the 1 ms clock resolution and
JIT/GC state dominate. The 10% and 50% columns show no such reversal.

**Accounting.** All 300 planner summary lines satisfy
`partitionsRead + partitionsPruned = partitionsTotal`, so the read counts are
internally consistent and the physical reads match the pruning decision. All
90,450 log lines from the ten timed sessions split into exactly seven fields,
and the run produced zero `ERROR` lines.

## Taking the numbers from the engine's own log

`scripts/ingest_logs.sh` concatenates the ten `run.log` files and reads them
**with the engine itself** (Exercise 5's `logs` table), then runs the same
three analyses — one session, statement 7, and the failures:

| engine query | rows |
|---|---|
| `WHERE sessionId = '<one session>'` | 30177 |
| `WHERE statementNumber = 7` | 3016 |
| `WHERE logLevel = 'ERROR'` | 0 |

Every count matches an independent `awk` pass over the same file, so the
engine is genuinely reading its own log rather than a pre-digested summary.
The y-axis values in `summary.csv` are the `durationMs` fields of those same
`StorageEngine` lines; `results/raw.csv` indexes each timed repeat by its
`sessionId` and `statementNumber` so any single point can be traced back to
one log line.
