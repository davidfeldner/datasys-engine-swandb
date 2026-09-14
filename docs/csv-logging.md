# Fix: comma-bearing values broke the CSV log line

## The requirement

Section 3 of `docs/Exercise2.md` requires one CSV log line per API call,
with the measurements as `key=value` pairs:

```
%d{yyyy-MM-dd HH:mm:ss.SSS},%X{sessionId},%X{statementNumber},%tid,%level,%logger{1},%m%n
```

That is exactly seven comma-separated values, so **the message must not
contain any commas**; otherwise the line parses into more than seven values
and every field after the extra comma shifts.

## The bug

The first version of the sanitizer escaped the comma instead of removing it:

```java
private static String sanitizeForLog(Object value) {
    if (value == null)
        return "null";
    return value.toString().replace(",", "\\,");
}
```

`"\\,"` is a backslash followed by a comma, so the comma is still in the
message. A positional `split(",")` therefore still gains a field:

```
2026-09-14 16:17:49.634,,,3,DEBUG,StorageEngine,table=trips column=city comparison=EQUALS const=Aarhus\, Denmark partitionsTotal=0 ...
split(",") -> 8 fields
```

The escape also only covered the predicate constant. Table names, column
names, CSV file paths, and the min/max values were interpolated raw, so a
comma in any of those broke the line too. Measured with a probe, the constant
line, the unknown-column error line, and both `createTable`/`select` lines
for a table named `we,ird` each produced 8 fields.

## The fix

1. **Replace the comma, do not escape it.** `sanitizeForLog` now maps `,`
   to `;`, so the character cannot survive into the message:

   ```java
   return value.toString().replace(',', ';');
   ```

2. **Sanitize every interpolated field**, not just the constant:
   `table`, `column`, `file`, `min`, `max`, and `const` in every
   `StorageEngine` log statement (`createTable`, both `copyFile` lines, the
   empty-table, per-partition PRUNED/READ, summary, and error lines of
   `select`). `Engine`'s lines are string literals and need no change.

A value like `Aarhus, Denmark` is logged as `const=Aarhus; DK`, which keeps
the line at seven fields. The information is preserved except for the comma
character itself, which the format forbids.

## Verification

`StorageEngineIT.commaBearingValuesStillYieldSevenLogFields` drives commas
through every reachable field at once — a table named `tab,<uuid>`, a column
named `ci,ty`, a CSV path `a,b-<uuid>.csv`, and a constant
`Aarhus, DK <uuid>` — plus a failing select to hit the error line. It then
reads `logs/engine.log`, keeps only the lines for that UUID, and asserts each
one splits into exactly seven values.

Re-checked after the fix: all 177 lines produced by the full test run split
into seven fields, including the comma-bearing probe lines. Full suite:
28 unit tests and 12 integration tests, all green.
