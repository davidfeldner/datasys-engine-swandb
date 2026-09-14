# Fix: pruned partitions were still read from disk

## The problem

`select` decided correctly per partition whether to prune, but the decision
only affected the *counting*: every partition classified as `READ` called
`SwanFile.readAll`, which reads and decodes the **whole** `.swan` file.

Before the fix, the read inside the scan loop was:

```java
SwanFile.Partition part;
try {
    // Per-call full read is simple; the format also supports
    // seeking straight to a single partition.
    List<SwanFile.Partition> all = SwanFile.readAll(dataPath, table.columns);
    part = all.get(pe.index());
} catch (IOException e) {
    throw new IllegalStateException("failed to read " + dataPath, e);
}
```

`readAll` walks every partition in the file and calls
`ValueCodec.decodeColumn` on every column of every partition. So when a
predicate pruned 3 of 4 partitions, the one surviving `READ` still decoded
all four. With more than one `READ` partition the same whole file was
re-read once per `READ` partition.

Consequences:

- The required I/O pruning was not implemented: pruning saved no data-file
  reads at all, only bookkeeping.
- `ScanStats.partitionsRead` understated the partitions physically read,
  because it was incremented from the pruning decision rather than from the
  actual read. A test that only inspected `ScanStats` and the returned rows
  passed even with this bug.

## The fix

The catalog already had to carry per-partition metadata, and
`docs/storage-design.md` already decided that it should "keep track of each
partition file offset, for each table, so it can efficiently jump to the
partitions needed". The fix implements that decision.

1. **Record the offset of every partition.**
   `Catalog.PartitionEntry` gains a persisted `offset` field (public, `long`,
   defaulting to `-1`). `-1` is also what a catalog written before offset
   tracking deserialises to.

2. **Return the offsets from the writer.**
   `SwanFile.write` now returns the byte offset of each partition, captured
   with `FileChannel.position()` immediately before the partition's
   `rowCount` field is written. `StorageEngine.copyFile` stores those offsets
   on the `PartitionEntry`s before saving the catalog, so they survive a
   restart.

3. **Read a single partition by seeking to it.**
   `SwanFile.readPartition(path, schema, offset)` opens the file, calls
   `channel.position(offset)`, and decodes exactly one partition. It shares
   the per-partition decoding with `readAll` through a private
   `readPartitionInternal` helper, so both paths stay byte-compatible.

4. **Seek only for live partitions.**
   `StorageEngine.select` reads a partition only after the min/max pruning
   decision kept it, and reads it by stored offset:

   ```java
   long partitionOffset = pe.offset();
   // Seek straight to the live partition; pruned partitions are never touched.
   // Fallback for catalogs written before offsets were tracked.
   if (partitionOffset < 0) part = SwanFile.readAll(dataPath, table.columns).get(pe.index());
   else part = SwanFile.readPartition(dataPath, table.columns, partitionOffset);
   ```

   A pruned partition is `continue`d before this block, so its bytes are
   never fetched. The `offset < 0` branch keeps older catalogs working, at
   the cost of the previous whole-file read.

No format changes: partition offsets are metadata in `catalog.json`, and the
`.swan` layout, value encodings, byte order, and the
"min/max summaries live in the catalog only" decision are untouched.

## Verification

The regression is guarded at two levels.

- **Unit (`SwanFileTest`)** — `write` returns strictly ascending offsets that
  start past the file header, and `readPartition` at the second offset
  returns exactly the second partition's rows and values.
- **Integration (`StorageEngineIT.prunedPartitionsAreNeverReadFromDisk`)** —
  after loading the distance-sorted golden CSV with `maxRowsPerPartition = 2`,
  the test overwrites the column-length prefix of the three partitions that
  `distance > 200` prunes with `-1`. A fresh engine then runs the query. If
  any pruned partition were decoded, the invalid length would throw
  `NegativeArraySizeException`. The query must instead return exactly
  `210` and `299`, with `partitionsRead = 1` and `partitionsPruned = 3`.

This test was checked against the pre-fix code path (temporarily restoring
`readAll`): it fails with `NegativeArraySizeException: -1`, and passes with
the fix. The existing `pruningObservable` test is kept, but it is the new
test that actually pins the physical I/O behaviour.

- **Legacy catalog (`StorageEngineIT.catalogWithoutOffsetsFallsBackToSequentialRead`)**
  removes every `offset` field from `catalog.json`, reloads, and checks the
  entries default to `-1` and that selects still return the right rows
  through the sequential fallback.

Full suite after the fix: 28 unit tests and 11 integration tests, all green.
