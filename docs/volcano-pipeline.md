# The Volcano pipeline and the SQL front door (Exercise 4)

Exercise 4 splits the two jobs week 2's `select` did at once — deciding *what
to read* and *reading it* — into a planner and a pull-based operator tree. The
storage decisions in `docs/storage-design.md` are unchanged; this document
records where each of them now lives.

## The operator model

| Piece | Location |
|---|---|
| `Operator` (`open`/`next`/`close`) | `dk.itu.swandb.plan.Operator` |
| `ScanOperator` — reads exactly the partitions it is handed | `dk.itu.swandb.plan.ScanOperator` |
| `FilterOperator` — row test, logs `rowsIn`/`rowsOut` in `close()` | `dk.itu.swandb.plan.FilterOperator` |
| `Planner` — catalog lookup, pruning, plan building | `dk.itu.swandb.plan.Planner` |
| `Plan` (root operator + `ScanStats`) | `dk.itu.swandb.plan.Plan` |
| Storage-level predicate | `dk.itu.swandb.plan.Selection` |
| Executor — parse → bind → plan → execute per statement | `dk.itu.swandb.sql.Executor` |
| CSV renderer | `dk.itu.swandb.CsvWriter` |
| Front door | `dk.itu.swandb.Engine` |

Rows stay `Object[]` in schema column order, so the engine's observable result
type did not change.

## Where pruning happens

The planner is the only place that consults min/max summaries. For a
`WHERE` it walks the table's catalog partitions, calls the same
`Pruning.canPrune` helper week 2 used, and keeps the partitions that may
match. The survivors become `ScanOperator`; a present predicate wraps that
scan in a `FilterOperator`. Without a `WHERE`, the plan is a bare
`ScanOperator` over every partition.

A partition whose summary is missing, or whose min/max is absent, is never
pruned: `Planner` raises an error and `Pruning.canPrune` rejects null
bounds. A partition the filter never reads cannot be recovered, so an
incomplete catalog would silently drop matching rows; failing loudly is the
only safe outcome.

`StorageEngine.select(table, column, comparison, constant)` keeps its exact
week 2 signature and now plans and drains that pipeline internally;
`StorageEngine.selectAll(table)` is the unfiltered sibling the front door
needs for `SELECT * FROM table;`. `ScanStats` is taken from the `Plan` and is
still available through `lastScanStats()`.

The decision log lines moved with the decision: they are now emitted by
`Planner` (`className = Planner`) and are written before any data file is
opened. `FilterOperator.close()` reports `rowsIn`/`rowsOut`.

## How this stays true to `storage-design.md`

- **Min/max summaries live in the catalog only.** The planner reads them from
  `catalog.json` and never opens a `.swan` file to decide.
- **Per-partition offsets are persisted.** `ScanOperator` seeks straight to the
  offset of each surviving partition; a pruned partition is never touched. The
  `offset < 0` fallback for pre-offset catalogs is preserved. This is the same
  seek the `io-pruning.md` fix introduced, only moved from `select` into the
  scan operator.
- **Row-wise partition layout, value encodings, big-endian framing and the
  partition size are untouched.** The operators only use the existing
  `SwanFile` reader/writer.
- **Restart handling is untouched.** Both the planner and the scan read the
  same persisted catalog and `.swan` files; a fresh engine plans and reads
  identically.

## Sessions and statement numbers

`Executor` is one run of the engine over one script. It parses the whole
script with `statementNumber = 0`, then increments a counter before each
statement, stores it in the MDC, and binds and executes that statement with
the number in place. So every line a statement writes — binder, planner,
engine — carries the same number, the first statement is 1, and
`Engine`'s start/stop lines stay at 0.

## The front door

`Engine.main` has three modes: no arguments (team name and usage), one
argument (a single statement, with or without its trailing semicolon), and
`-f <script.sql>` (a whole script). Each `SELECT`'s rows are printed to
stdout as headerless CSV and nothing else is; the console log and errors go
to stderr, so `> ours.csv` captures exactly the result. The data directory
defaults to `data/`, which is in `.gitignore`.

## Tests

- `FilterOperatorTest` over the `TestListOperator` stub.
- `ScanOperatorTest`: the rows of the handed partitions, and the empty list.
- `PlannerTest`: pruning asserted through `ScanStats` over the distance-sorted
  golden data with `maxRowsPerPartition = 2`, plus the `Filter`-over-`Scan`
  and bare-`Scan` plan shapes.
- `StorageEngineIT` unchanged and green (the Exercise 2 integration tests).
- `EngineIT`: a script's stdout compared byte for byte, a failing script whose
  message lands on stderr with stdout clean, and the per-statement numbering.
