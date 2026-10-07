# Experiment design: partition size vs. SELECT latency

One dimension: partition size. Written before the first measurement.

## Question and x axis

- How does the partition size affect how long a `SELECT` takes?
- x = rows per partition (`maxRowsPerPartition`), log scale, from about 100k rows up to one partition holding the whole table.
- Table size and everything else stay the same, so larger partitions means fewer of them; partition count is the same sweep read backwards.
- The size is set in the catalog between `CREATE TABLE` and `COPY`, so no engine change is needed.

## Metric and y axis

- y = median `durationMs` from the engine's own `SELECT` log line.
- Also record how many partitions were read, from the pruning log lines.
- Plot: x = rows per partition (log), y = median `durationMs`, one line per query — a full scan plus a few predicates at different selectivities.

## Procedure

- A script generates two CSVs with the same rows: one sorted by the predicate column, one shuffled.
- One predicate with a few thresholds, giving different selectivities.
- For each partition size: fresh data directory, `CREATE`, set the cap, `COPY`, then run a script doing one warm-up and a few timed repeats of each query.
- Repeats of one setting share a single engine session; the warm-up run is discarded.
- Fresh log directory per setting; results matched by `sessionId`.
- A small pilot on existing data set the table size and heap; it swept nothing.

## Hypothesis

There is a trade-off between partition size and SELECT latency. Partitions that are too large cannot be pruned finely, so a selective predicate still decodes a large share of the table; partitions that are too small add per-partition work. Latency should fall as partitions get smaller, then flatten or rise.

Quantified: at 1 % selectivity, the smallest setting decodes roughly one partition instead of the whole table, so its median `durationMs` should be at least 3× lower than the single-partition setting. The best partition size should get smaller as the predicate gets more selective.
