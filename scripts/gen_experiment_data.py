#!/usr/bin/env python3
"""Generate the experiment's two CSVs, byte-for-byte reproducible.

See docs/experiment-design.md. The table is fixed at 1,000,000 rows with the
schema (id LONG, tag LONG, value DOUBLE, city STRING). Two files hold the
*same rows in different orders*:

  * sorted.csv   -- ordered by the predicate column `id`, so a partition's
                    min/max range is tight and pruning can skip most of them;
  * shuffled.csv -- the same rows in a fixed pseudo-random order, so every
                    partition's id range spans almost the whole domain and
                    min/max pruning can skip almost nothing.

The predicate column is `id` with values 1..N, so `id > C` matches exactly
`N - C` rows and a constant maps to an exact selectivity.

The seed is fixed: re-running this script rewrites byte-identical files.
`scripts/run_experiment.sh` verifies the checksums before it sweeps, so a
teammate can regenerate the data and rerun the sweep with the same inputs.

Usage:
    python3 scripts/gen_experiment_data.py [out_dir] [rows]
"""

import hashlib
import random
import sys
from pathlib import Path

# Fixed seed: part of the experiment's stated conditions. Changing it changes
# the partition membership of shuffled.csv and therefore the measurements.
SEED = 20261019

ROWS = 1_000_000
HEADERLESS_SCHEMA = "id LONG, tag LONG, value DOUBLE, city STRING"
CITIES = ["Aalborg", "Aarhus", "Copenhagen", "Odense", "Roskilde"]


def row(i: int) -> str:
    """One line of the CSV. Field order matches HEADERLESS_SCHEMA."""
    return "%d,%d,%.4f,%s\n" % (i, i % 1000, (i * 7919) % 100000 / 100.0, CITIES[i % 5])


def write_csv(path: Path, rows, progress) -> None:
    with path.open("w") as f:
        for i in rows:
            f.write(row(i))
            progress(i)


def main() -> int:
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("experiment-data")
    n = int(sys.argv[2]) if len(sys.argv) > 2 else ROWS
    out_dir.mkdir(parents=True, exist_ok=True)

    # `sorted` is 1..n ascending; the predicate column's value equals the row
    # number, which is what makes each constant C an exact selectivity.
    sorted_ids = list(range(1, n + 1))

    # `shuffled` is a permutation of exactly the same ids under the fixed seed.
    shuffled_ids = list(sorted_ids)
    random.Random(SEED).shuffle(shuffled_ids)

    write_csv(out_dir / "sorted.csv", sorted_ids, lambda i: None)
    write_csv(out_dir / "shuffled.csv", shuffled_ids, lambda i: None)

    print("seed=%d rows=%d schema=%s" % (SEED, n, HEADERLESS_SCHEMA))
    for name in ("sorted.csv", "shuffled.csv"):
        p = out_dir / name
        digest = hashlib.sha256(p.read_bytes()).hexdigest()
        print("%s rows=%d bytes=%d sha256=%s" % (name, n, p.stat().st_size, digest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
