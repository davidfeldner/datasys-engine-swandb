# Optimizer Design

This document records the join optimizer of Exercise 11: the statistics it
reads, the rules that turn per-partition figures into per-table ones, the
estimator with its sources, the search heuristic, and what the engine does
when a statistic is missing. It describes the code that is in the repository,
not a plan for future work.

## Statistics

What does the planner know per table and per column? Row counts per partition are
the minimum, and your catalog already has them. The recommended next step is NDV,
the number of distinct values per column, collected during COPY like min/max.
Without NDV, every equi-join looks alike; with it, the formula below can rank them.

**Answer.** The planner reads two statistics, both from the catalog:

| Statistic | Collected | Per | Used for |
|---|---|---|---|
| `rowCount` | `COPY`, `DELETE` | partition | pruning, base-table estimate |
| `min`, `max` per column | `COPY`, `DELETE` | partition | pruning |
| `ndv` per column | `COPY`, `DELETE` | partition | filter and join estimates |

`Catalog.ColumnSummary` carries `Long ndv` next to `min`/`max`; unlike them it
is nullable, because catalogs written before this release have no NDV (see
*Roll forward*). `COPY` computes it with `DistinctValues.count`, one `HashSet`
per column per partition, and so does a rewritten partition in `DELETE`.

Each summary is logged once, when it is created, as the exercise asks:

```
table=orders partition=3 column=o_custname rows=1000 min=Anna max=Zoe ndv=997
```

The line carries the min/max of Exercise 2 through 10 inside the same line, so
every statistic a partition has is visible at the moment it is created.

**`STRING` columns are counted too**, with the same `HashSet`-of-values logic:
the engine never parses a string to a numeric range, so the count is exact and
the join keys that matter most (names, cities, product names) get estimates.
For `DOUBLE` the count uses `Double.equals`, which keeps `0.0` and `-0.0`
distinct and treats all NaNs as one value — exactly how the hash join's
`HashMap` compares keys, so the statistic matches the operator that uses it.

**At a billion rows** the `HashSet` per column would be the wrong data
structure: its memory grows with the number of distinct values, not with the
error we can tolerate. The switch is to a mergeable sketch — HyperLogLog
(~1.5 KB for a 2 % error) or KMV/Theta. Both merge exactly, which also removes
the approximation in *Where they live and how they merge*. The interface stays
`DistinctValues.count` → a per-partition value, so the planner and the catalog
format would not change.

## Where they live and how they merge

NDV is collected per partition, but the estimate needs one number per table.
Unlike min/max, NDV does not merge exactly: the same value may appear in many
partitions, so the maximum of the per-partition NDVs is a lower bound and their
sum is an upper bound. Pick a rule and defend it. Sketches such as HyperLogLog
merge exactly, which is why industry uses them; that is an optional aside from
lecture 10.

**Answer.** NDV lives in the per-partition catalog summary, like min/max, and
`Valuation.tableNdv(List<Long>)` merges it by **maximum**.

The bound matters for defence. The maximum is the *lower* bound on the number
of distinct values in the table, so the planner systematically
*under*-estimates NDV. In the two formulas below, a smaller NDV gives a *larger*
estimated output, so the rule errs toward over-estimating intermediate sizes —
the safe direction, because it tends to make the optimizer join the table that
shrinks the result most. It also never overcounts, which the sum would: with
four partitions of 1 000 rows each, a `c_name` that appears in all four has a
true NDV of 997, while the sum would claim nearly 4 000 and make the join look
four times more selective than it is.

The maximum is defensible because of partition size, not only of the bound.
With 10 000 rows per partition, a partition already sees most of a column's
values: for TPC-H `customer.c_custkey`, one partition is close to the table's
NDV, and the largest partition's count is the closest single observation we
have. The course baseline of lecture 10 uses the same rule.

Rows merge by exact sum (`Valuation.tableRows`), because that is exact.

Both helpers are unit-tested in `NdvTest`: the maximum over partitions, the
case where every partition reports the same NDV, an empty partition (`0` must
not drag the maximum down), the empty table (`NDV 1`, so every divisor stays
positive), and negative input rejection.

## Roll forward

Tables copied before this release carry no NDV. Lecture 6's question returns: what
does a missing statistic mean, and how can you roll forward and roll back safely?

**Answer.** In the catalog a missing NDV is `null`, which is a different value
from `0`: `0` means "the column has no values in this partition", `null` means
"nobody measured it". The planner never guesses from `0`.

The engine rolls a table forward **on first open**. `StorageEngine.openQuery`
calls `rollForwardStatistics` before it pins any version:

1. Read the newest version's partition metadata and skip the partitions whose
   summaries already carry an NDV.
2. For each remaining partition, read it once, count distinct values per
   column with the same `DistinctValues` logic `COPY` uses, persist the counts
   and discard the sets before moving to the next partition.
3. Commit the new partition list as an ordinary version, so the planner takes
   the maximum partition NDV as usual — and so the fill is itself versioned.

The tradeoff the exercise asks about is a one-time delay on the first query
that opens an old table: every legacy partition is read once, then never again.
The cost is bounded by the table, not by the query, and the commit is safe
because it is the same validated swap every other write uses: a reader on an
older version keeps seeing the catalog without NDV, and if a concurrent writer
commits first the roll-forward loses with `ConflictException`, which it catches
so the query simply runs on the statistics it could read. Rolling back is the
mirror image: the previous version still lists the partitions without NDV, so
the retention window's older version is the pre-fill state.

The panic path matters too. The planner's `TableInput` now always carries a
partition list, and `Planner.estimate` reads every column's summary: a summary
that is *absent* is an `IllegalStateException` ("refusing to prune/estimate on
incomplete catalog metadata"), exactly as min/max has been treated since
Exercise 2. A summary that is present but has a `null` NDV is not an error
only because the roll-forward has already filled it in for anything the planner
sees.

## The estimator

Which formulas do you use, and where do they come from? State them in the
document with their source, whether lecture 10, a paper, or your own reasoning.

**Answer.** `Estimator` holds three pure functions over catalog numbers. No
data files, no state, one unit test per case.

**Base table.** The row counts of the surviving partitions, summed:

```
base(rows) = max(rows, 1)
```

Source: no assumption — it is exactly what the scan will read, once the
planner has pruned. The `max(..., 1)` floor is our own addition: an estimate of
zero makes the next join in the chain estimate zero, so every order would tie.

**Filter.** An equality keeps `rows / ndv` rows; an inequality keeps
`rows · 1/3`:

```
filter(rows, ndv, EQUALS) = rows / max(ndv, 1)
filter(rows, ndv, < or >) = rows · 1/3
```

The equality rule is lecture 10's uniform-distribution assumption: if the
column's values are spread evenly over its `ndv` distinct values, one value
matches `rows/ndv` rows. The `1/3` is **Selinger's magic constant** for a
comparison on a range column: Selinger et al., *Access Path Selection in a
Relational Database Management System*, SIGMOD 1979, use `1/3` for an
unknown range selectivity, which is where the constant in the course slides
comes from: the slides cite the paper, and the paper is the origin, so this
document cites the paper.

We deliberately do **not** interpolate from min/max for `<` and `>`. The
catalog has the ranges, so interpolation is possible, but min and max are
extremes of a sample: a single outlier moves max arbitrarily, and one extreme
value in a 1 000-row partition would swing the equality estimate far more than
the `1/3` constant errs. Selinger's constant is at least a documented,
stable choice, and the report in week 12 is about where estimates go wrong —
a constant is easy to explain and easy to replace.

**Equi-join.** Lecture 10's formula:

```
join(|R|, |S|, ndv(R.a), ndv(S.b)) = |R| · |S| / max(ndv(R.a), ndv(S.b))
```

Source: lecture 10. It assumes every value of the more selective side occurs
in the other side's domain — the foreign-key case it is designed for — which
is why the divisor is the *larger* NDV rather than the product.

**NDV through a chain** is the exercise's assumption, written down here because
the week 12 report may revisit it: *an intermediate result's NDV for a column is
the NDV of the base table that contributed it.* In a left-deep chain that is
enough: every step's connecting predicate has one end in a base table that is
not joined yet, so the estimate always reads a base table's NDV — either for
the new table or, on the inside, for the already-joined table that owns the
key. `JoinOptimizer.intermediateNdvs` lays the joined tables' per-column NDVs
out in row order so the inside key can be looked up at its concatenated
position.

The refinement we deliberately do *not* apply is narrowing a matched key's NDV
to the smaller of the two sides' NDVs once a join has filtered it. For a
left-deep chain no later estimate reads such a key, so the rule would be dead
code that looks like extra accuracy; if week 12 shows a chain where it would
matter (a star shape with a second predicate on the same key), it belongs here.

All estimates are floored at 1 and rounded to whole rows
(`Math.max(Math.round(x), 1)`).

## The heuristic

Greedy smallest-intermediate-first is the course baseline. With at most four
tables, exhaustive enumeration is also cheap.

**Answer.** Greedy smallest-intermediate-first, exactly as section 4 of the
exercise states, in `JoinOptimizer`:

1. Estimate every table's size after pruning and filters (`Planner.estimate`).
2. Among **all** join predicates, start with the one whose estimated output is
   smallest; its two tables are the first two in the chain.
3. Repeat: among the tables connected by a predicate to the tables joined so
   far, add the one whose estimated join output is smallest. Every combination
   checked is logged with its estimate (`mode=optimized step=… considered=…
   joined=… estRows=…`), so a reader can audit the choice.
4. Ties break on the table's `FROM`-list index, so the plan is deterministic and
   a tie keeps the earlier table — which is also why a two-table query reduces
   exactly to the `FROM` order.

We chose greedy over exhaustive enumeration because the exercise fixes the
heuristic as the baseline and because week 12 needs a *predictable* optimizer
to measure; with at most four tables the difference in plan quality is small,
and the `-j syntax` flag already gives the benchmark its forced-worst plan.
Exhaustive enumeration remains the obvious extension, and `JoinOptimizer`'s
`Decision` (a list of steps plus their estimates) is shaped so a different
search can produce the same output.

**Cross-product guardrail.** A table that no predicate connects to the joined
set is ranked last and is reached only when no connected table is left, but in
practice the engine refuses such a query first: `JoinGraph.connected()` must
hold, and a `FROM` list that is not one connected chain is rejected with
"no join predicate connects table … to the other tables of the FROM list;
reorder the FROM list" — week 10's error, kept on purpose because a cross
product is not a plan this project executes. A second predicate connecting the
same table is rejected too, since the supported shape is a chain with one key
per added table.

**Build side.** The input with the smaller estimated size builds the hash table,
the larger probes; on a tie the intermediate result builds (the week 10 shape).
When the added table builds, its columns come first in the output row, so the
planner records the row's real table layout (`Planner.JoinStep`) and every
later key position is computed from that layout, not from the `FROM` list.
`ColumnOrder.restore` then wraps the finished chain in one
`ColumnReorderOperator` whenever the row order differs from the `FROM` list, so
`SELECT *` emits the same columns in the same order under both flags. A
`-j syntax` plan that swaps a build side is reordered the same way: the flag
controls the join *order*, not the column order.

## Modes: `-j optimized` and `-j syntax`

`./engine -j optimized` is the default and runs the greedy planner.
`./engine -j syntax` folds the `FROM` list left to right, the Exercise 10 plan,
unchanged in order and build-side rule (it now also reorders columns if a swap moved them,
which Exercise 10 could not hit because the intermediate result always built).
Both modes return identical rows; only the plan and the row order before the
final projection differ. Forcing a specific order is therefore trivial: write
the `FROM` list in that order and run `-j syntax`, the mechanism week 12's
benchmark uses.

An unknown `-j` value is a usage error (exit code 2) that names the two
accepted modes, because a typo silently planning a different order would
invalidate a benchmark run.

## Estimates and actuals side by side

Every execution of a join reports both numbers through its statement, so the
log already carries the comparison Exercise 11 asks for:

```
statement=SELECT table=orders;customers rowsOut=4 … joinOrder=orders;customers mode=optimized durationMs=5
join=1 tables=customers<orders estRows=4 rowsBuild=3 rowsProbe=4 rowsOut=4 crossProduct=false
```

The join line is written by `HashJoinOperator.close()` and carries:

- `join` — the join's position in the left-deep chain (1-based);
- `tables` — the two inputs in **build/probe order** (`build<probe`), so a log
  reader can tell a reordered plan from the `FROM` list;
- `estRows` — the optimizer's estimate of this join's output;
- `rowsBuild`, `rowsProbe`, `rowsOut` — the actual sizes, `rowsOut` being the
  intermediate result's real size.

`estRows` against `rowsOut` is the estimate error per join, and
`crossProduct=true` marks a join that had no predicate. Both fields are
recoverable per statement because the CSV log's `statementNumber` field is in
front of every line, including the join lines and the `SELECT` summary that
also names the chosen order. The estimate is passed at plan time
(`JoinEstimate`), not re-derived at run time.
