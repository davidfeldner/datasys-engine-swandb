# Optimizer Design

## Statistics

What does the planner know per table and per column? Row counts per partition are
the minimum, and your catalog already has them. The recommended next step is NDV,
the number of distinct values per column, collected during COPY like min/max.
Without NDV, every equi-join looks alike; with it, the formula below can rank them.

Answer: We will add NDV as a statistic in the catalog

## Where they live and how they merge

NDV is collected per partition, but the estimate needs one number per table.
Unlike min/max, NDV does not merge exactly: the same value may appear in many
partitions, so the maximum of the per-partition NDVs is a lower bound and their
sum is an upper bound. Pick a rule and defend it. Sketches such as HyperLogLog
merge exactly, which is why industry uses them; that is an optional aside from
lecture 10.

Answer: We will use max NDV as a statistic, since we use large partitions and
if we assume a uniform distribution, it should be accurate in many cases.

## Roll forward

Tables copied before this release carry no NDV. Lecture 6's question returns: what
does a missing statistic mean, and how can you roll forward and roll back safely?

Answer: We will add the NDV statistic as an nullable value, in a separate minor
release where it is not used fx v10.1. In a later release we will add NDVs when we open a legacy table like the following:

1. Read the partition metadata and skip partitions whose NDVs are already present.
2. For each remaining partition, scan it once and count distinct values per column using the same logic as COPY. Persist the counts and discard the sets before moving to the next partition.
3. Let the planner take the maximum partition NDV for each column as usual.

The tradeoff is a one-time delay on the first query that opens an old table

## The estimator

Which formulas do you use, and where do they come from? State them in the
document with their source, whether lecture 10, a paper, or your own reasoning.

Answer: We will use the same formulas as used on slide 17, lecture 11:
public final class Estimator {
static long filter(long rows, long ndv, Comparison comparison) {
return rounded(comparison == Comparison.EQUALS
? (double) rows / Math.max(ndv, 1)
: rows _INEQUALITY_SELECTIVITY); // 1.0 / 3
}
public static long join(long leftRows, long rightRows,
long leftNdv, long rightNdv) {
return rounded((double) leftRows _ rightRows
/ Math.max(Math.max(leftNdv, rightNdv), 1));
}
}

## The heuristic

Greedy smallest-intermediate-first is the course baseline. With at most four tables,
exhaustive enumeration is also cheap.

Answer: Greedy smallest-intermediate-first
