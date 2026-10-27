# Transactions: DELETE, copy-on-write and a versioned catalog (Exercise 7)

This is the design we wrote down before implementing, extended with what the
implementation settled. Every question the exercise asks is answered below,
and the section "Open questions and suggestions" lists the parts we consider
risky.

# Versioning
Each table gets a version number that a committed COPY or DELETE increments.
A version number maps to a partition list: a version *is* a partition list
nobody may modify, and the newest one is the version a statement reads.

`CREATE TABLE` produces version 0, the empty table. Versions are dense —
`0..base` exactly — with `base = newest committed version`. Density is what
makes reclamation simple: the lowest live version is a truncation point and no
holes can strand versions below it.

Version numbers live in memory, in `dk.itu.swandb.VersionedTable`, one object
per table, holding the live versions in a `ConcurrentHashMap<Integer, Version>`.
The newest partition list is also kept in the catalog document in memory.

# Commit
A commit swaps the table's partition list and increments the version,
atomically.

In one JVM: the sink is `versions.putIfAbsent(base + 1, next)` on the table's
`ConcurrentHashMap`, followed by one reference assignment that publishes the
new version as newest. One script has one writer, so the CAS always succeeds
today; the same operation is what makes the loser lose when week 8 races two
writers.

On disk: `catalog.json` is written to `catalog.json.tmp` and renamed over
`catalog.json` (`ATOMIC_MOVE`, falling back to a plain replace where the
filesystem refuses the atomic variant). A reader or a crash sees the old
catalog or the new one, never half of each.

New partitions are written to the table's data file *before* the swap. A crash
between the write and the swap leaves partitions no catalog entry points at:
invisible and harmless, and reclaimed by the next commit (see *Retention*).

# Snapshot
A statement pins the version it reads when it starts and never re-reads
metadata.

What it pins: a reference to a version (`PinnedVersion`), not a copy of the
partition list. Versions are immutable, and commits only ever append to the
data file, so a pinned list stays valid for as long as the pin is held.

When: in `StorageEngine.select`/`delete`, before the plan is built. The planner
is handed the pinned schema and partition list (`new Planner(columns,
partitions, dataPath)`) rather than the catalog, so there is nothing left for it
to re-read. `PinnedVersion.close()` in a `finally` releases the pin.

# Retention
Versions are reference counted, with a retention window on top.

- `RETAINED_VERSIONS = 4`. The newest four versions are always kept, so a
  version is never dropped earlier than four commits after it was written.
- A version additionally stays while any statement pins it. A pin of the newest
  version counts for every older live version, because a newer writer's append
  only ever lands after the data a reader may still be looking at.
- Collection only ever drops from the *bottom* of the range. A hole in the
  middle would strand everything under it, since reclamation truncates the
  file back to the lowest live version.

A statement that asks for a version that is gone gets
`StaleVersionException`, an `IllegalStateException`, because with correct
reference counting it can only mean the engine dropped a version a statement
still needed. It is not a user error.

What a statement sees when it asks for a version that is gone: that exception,
never silently different rows. Because a statement pins at start and only the
newest version can be pinned, no statement can even be on its way to a version
that the collector is free to drop.

Reclamation: every partition entry records `offset` and `endOffset`. The bytes
still owed to a live version end at the last `endOffset` of any live version's
partitions (`VersionedTable.liveEnd`). The next commit truncates the data file
to that point and rewrites the partition count before appending, so the bytes
of expired versions — and of commits that never happened — are returned to the
filesystem.

# The rewrite set
DELETE must rewrite exactly the partitions that may contain matches, which is
the complement of week 4's pruning decision.

The shared helper lives in `dk.itu.swandb.Pruning`, next to `canPrune`. It now
exposes the decision once, in the model's own vocabulary:

```java
public enum Decision { PROVABLY_FREE, MAY_MATCH }
public static Decision decide(comparison, constant, min, max, type)
public static boolean mustRewrite(...)   // decide(...) == MAY_MATCH
public static boolean canPrune(...)      // decide(...) == PROVABLY_FREE
```

`Planner` prunes with `decide(...) == PROVABLY_FREE`; `DELETE` rewrites with
`mustRewrite(...)`. The two cannot disagree about a partition, because they
are one expression. The row test is shared the same way, through
`Pruning.matches`, so a DELETE keeps exactly the rows a SELECT with the same
predicate would return.

A partition that may contain a match but turns out to contain none is carried
over unchanged, ledgered as *carried* rather than *rewritten*. A partition whose
every row matches is rewritten into nothing: it disappears from the next
version and no bytes are written for it.

# New version
A DELETE that matches nothing does not create a new version. The table stays at
the version it was at, nothing is written, and the summary line reports the
version it found.

# Partition placement
New partitions go at the end of the table's single data file.

The `.swan` layout puts the partition count in the header, so a commit cannot
literally append: it truncates the file back to the last byte a live version
needs, rewrites the count, and appends. Nothing below the truncation point is
ever rewritten, so every offset a live version recorded there still points at
the same partition. Carried-over partitions therefore keep their exact
`offset`, and older versions stay readable without copying their data.

# On disk
`catalog.json` holds the newest version of every table, and is written when the
engine closes (and once at `CREATE TABLE`, so a table that exists but was never
copied into survives a restart, as it did in Part 1).

An older version's partition list is not persisted. Because there is one data
file, the bytes an older version points at may be truncated away by a later
commit; writing those lists would produce a catalog that lies. The in-memory
variant was chosen for this reason, and the cost is that a restart sees the
newest version and no history.

Per-partition metadata is `index`, `rowCount`, `version` (the version that
wrote the rows), `offset` and `endOffset`. A partition is addressed by its
offset in the single data file; `index` is its position in its own version's
list.

# Logging
Each statement ends with one summary line naming the version it committed or
read:

```
statement=CREATE table=trips version=0 durationMs=0
statement=COPY table=trips rows=8 partitions=1 version=1 durationMs=1
statement=DELETE table=trips rowsDeleted=3 partitionsRewritten=1 partitionsCarried=0 version=2 durationMs=1
statement=SELECT table=trips rowsOut=5 partitionsRead=1 partitionsPruned=0 version=2 durationMs=0
```

stdout stays reserved for SELECT rows; DELETE prints nothing.

---

# Open questions and suggestions

1. **The catalog is only written on close.** This follows the decision above,
   but it means a `kill -9` after a DELETE loses every commit since startup,
   and a second engine started on the same directory sees a stale table. The
   on-disk half of a commit would be one atomic rename of a file that is a few
   hundred bytes. `StorageEngine.persist()` exists so a test or a caller can
   force the write; we suggest calling it per commit before week 8, when two
   writers make a stale catalog much harder to reason about.

2. **The single data file plus truncation is the riskiest part of this
   design.** Truncating to `liveEnd` on every commit means a commit mutates a
   file that older versions point into. It is safe only because the retention
   rule guarantees no live version points past `liveEnd`, and that argument is
   worth re-checking whenever retention changes. One `.swan` per version would
   make immutability structural instead of argued, at the cost of many small
   files.

3. **Retention is expressed twice**: `RETAINED_VERSIONS` (a count) and the
   pin count (a safety property). The pin count is what keeps a running
   statement correct; the count is what bounds memory. Both are needed, but only
   the count is tunable, so it deserves a test that an old version is reachable
   exactly `RETAINED_VERSIONS` commits later (the current test checks the size
   of the live set, not reachability).

4. **`version` is written per partition but never read for behaviour.** It is
   useful for explaining a catalog by hand and for the report, but if nothing
   consumes it, it is a field that can silently rot. Either use it in orphan
   detection ("which partitions belong to no live version") or drop it.

5. **No bounds check against the data file.** A partition offset from a
   tampered or truncated catalog seeks past the end of the file and fails with a
   raw `IllegalStateException("truncated file")`. A readable error naming the
   table, version and offset would make that failure diagnosable.

6. **Concurrency is unexercised.** The `putIfAbsent` sink and the per-table
   commit lock are in place, but every test drives one writer, so the loser's
   path in `commit()` (`return -1`) is dead code. Week 8 should cover it; until
   then it is untested behaviour rather than working behaviour.

7. **Orphan cleanup is implicit.** The exercise asks how we would find orphaned
   partitions. We do not search for them: every commit truncates anything past
   the last byte a live version needs, so orphans written by a crashed commit are
   reclaimed by the next one. A directory-level sweep would additionally catch
   data files whose table is gone from the catalog, which nothing does today.
