# Versioning
Each table gets a version number that a committed COPY or DELETE increments.
A version number should map to a partition list.
The version metadata should live in memory and is only flushed when the engine exits.

# Commit
A commit swaps the table's partition list and increments the version, atomically.
One putIfAbsent of base + 1 into a ConcurrentHashMap.

# Snapshot
A statement pins the version it reads when it starts and never re-reads metadata.
A snapshot is The newest version with its partition list, pinned for a query when the plan is built.

# Retention
We can drop a version when no statements are using it by using reference counting.

# The rewrite set
DELETE must rewrite exactly the partitions that may contain matches, which is the complement of week 4's pruning decision.
The shared helper should live in the planner's pruning helper.
A partition without a match is carried over.

# New version
A DELETE that matches nothing does not create a new version

# Partition placement
New partitions go at the end of the table's single data file.

# On disk
We want catalog.json with the newest version, written once when the engine closes.
