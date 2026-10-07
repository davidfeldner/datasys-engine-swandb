# Experiment design: partition size vs. SELECT latency

We want to test partition size, and how it effects the speed of selects. 

## Question and x axis

- How does the partition size affect how long a `SELECT` takes?
- x = rows per partition (`maxRowsPerPartition`), log scale, from 1k to 100k rows.
- The table is fixed at 1 million rows, and everything else stays the same, so larger partitions means fewer of them.
- The size is set in the catalog between `CREATE TABLE` and `COPY`, so no engine change is needed.

## Metric and y axis

- y = median `durationMs` from the engine's own `SELECT` log line.
- Also record how many partitions were read, from the pruning log lines.
- Plot: x = rows per partition (log), y = median `durationMs`, one line per query — a full scan plus a few predicates at different selectivities.

## Procedure

- A script generates two CSVs with the same rows: one sorted by the predicate column, one shuffled.
- One predicate, `id > C`, run with a few different constants `C`. A larger `C` matches fewer rows, so each constant gives a different selectivity — the share of the table that passes the predicate.
- For each partition size: fresh data directory, `CREATE`, set the cap, `COPY`, then run a script doing one warm-up and a few timed repeats of each query.
- Repeats of one setting share a single engine session; the warm-up run is discarded.
- Fresh log directory per setting; results matched by `sessionId`.
- A small pilot on existing data set the table size and heap; it swept nothing.

## Hypothesis

There is a trade-off between partition size and SELECT latency. Partitions that are too large cannot be pruned finely, so a selective predicate still decodes a large share of the table; partitions that are too small add per-partition work. Latency should fall as partitions get smaller, then flatten or rise. The best partition size also depends on the selectivity of the query, smaller partitions should be better as the query gets more selective.
