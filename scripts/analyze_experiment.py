#!/usr/bin/env python3
"""Reduce results/raw.csv to the plot in docs/experiment-design.md.

y = median durationMs per (ordering, rows-per-partition, query), plus the
partitions read for that query. Writes results/summary.csv, one row per
point, and results/plot.png.

Usage: scripts/analyze_experiment.py
"""

import csv
import statistics
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RAW = REPO / "results" / "raw.csv"
SUMMARY = REPO / "results" / "summary.csv"
PLOT = REPO / "results" / "plot.png"


def label(const: str) -> str:
    """Human-readable query name for a constant; selectivity = 1 - C/ROWS."""
    rows = 1_000_000
    if const == "ALL":
        return "full scan (no WHERE)"
    c = int(const)
    return "id > %d (%.1f%%)" % (c, 100.0 * (rows - c) / rows)


def main() -> int:
    if not RAW.exists():
        print("missing %s; run scripts/run_experiment.sh first" % RAW, file=sys.stderr)
        return 1

    groups: dict[tuple, list[dict]] = {}
    with RAW.open() as f:
        for row in csv.DictReader(f):
            key = (row["ordering"], int(row["rowsPerPartition"]), row["const"])
            groups.setdefault(key, []).append(row)

    points = []
    for (ordering, cap, const), rows in groups.items():
        times = sorted(int(r["durationMs"]) for r in rows)
        reads = sorted({int(r["partitionsRead"]) for r in rows})
        rows_out = sorted({int(r["rowsOut"]) for r in rows})
        points.append({
            "ordering": ordering,
            "rowsPerPartition": cap,
            "partitions": max(int(r["partitionsRead"]) + int(r["partitionsPruned"]) for r in rows),
            "const": const,
            "query": label(const),
            "selectivityPct": "" if const == "ALL" else "%.1f" % (100.0 * (1_000_000 - int(const)) / 1_000_000),
            "repeats": len(times),
            "medianDurationMs": statistics.median(times),
            "minDurationMs": times[0],
            "maxDurationMs": times[-1],
            "partitionsRead": reads[0] if len(reads) == 1 else "mixed:" + ",".join(map(str, reads)),
            "rowsOut": rows_out[0] if len(rows_out) == 1 else "mixed",
        })

    points.sort(key=lambda p: (p["ordering"], p["rowsPerPartition"], p["const"]))
    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    with SUMMARY.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(points[0].keys()))
        w.writeheader()
        w.writerows(points)

    for p in points:
        print("%-8s cap=%-7d %-22s median=%-6s partitionsRead=%-4s rowsOut=%s"
              % (p["ordering"], p["rowsPerPartition"], p["query"],
                 p["medianDurationMs"], p["partitionsRead"], p["rowsOut"]))

    make_plot(points)
    print("wrote %s and %s" % (SUMMARY, PLOT))
    return 0


def make_plot(points) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    orderings = sorted({p["ordering"] for p in points})
    consts = sorted({p["const"] for p in points}, key=lambda c: (c == "ALL", c))
    fig, axes = plt.subplots(1, len(orderings), figsize=(7 * len(orderings), 5), sharey=True)
    if len(orderings) == 1:
        axes = [axes]

    for ax, ordering in zip(axes, orderings):
        for const in consts:
            pts = sorted((p for p in points
                          if p["ordering"] == ordering and p["const"] == const),
                         key=lambda p: p["rowsPerPartition"])
            ax.plot([p["rowsPerPartition"] for p in pts],
                    [p["medianDurationMs"] for p in pts],
                    marker="o", label=pts[0]["query"])
        ax.set_xscale("log")
        ax.set_xlabel("rows per partition (maxRowsPerPartition)")
        ax.set_title("%s input" % ordering)
        ax.grid(True, which="both", alpha=0.3)
    axes[0].set_ylabel("median durationMs (engine log)")
    axes[-1].legend(fontsize=8)
    fig.suptitle("Partition size vs. SELECT latency (1M rows)")
    fig.tight_layout()
    fig.savefig(PLOT, dpi=150)


if __name__ == "__main__":
    sys.exit(main())
