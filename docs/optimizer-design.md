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

## Roll forward

Tables copied before this release carry no NDV. Lecture 6's question returns: what
does a missing statistic mean, and how can you roll forward and roll back safely?

## The estimator

Which formulas do you use, and where do they come from? State them in the
document with their source, whether lecture 10, a paper, or your own reasoning.

## The heuristic

Greedy smallest-intermediate-first is the course baseline. With at most four tables,
exhaustive enumeration is also cheap.

Answer: Greedy smallest-intermediate-first
