# The SQL front end (Exercise 3)

Text processing only: SQL text becomes a typed, validated AST. Nothing is
executed yet — wiring the AST to the storage engine is week 4's work — and
no SQL-processing library is involved beyond ANTLR itself.

## Where everything lives

| Piece | Location |
|---|---|
| Grammar | `src/main/antlr4/dk/itu/swandb/sql/parser/Sql.g4` |
| Generated lexer, parser, visitor | `target/generated-sources/antlr4/...` (never committed) |
| AST records | `src/main/java/dk/itu/swandb/sql/ast/` |
| Visitor that builds the AST | `src/main/java/dk/itu/swandb/sql/SqlAstBuilder.java` |
| Facade `parse(String) -> List<Statement>` | `src/main/java/dk/itu/swandb/sql/SqlParser.java` |
| `SqlParseException` with line and column | `src/main/java/dk/itu/swandb/sql/SqlParseException.java` |
| Pretty-printer | `src/main/java/dk/itu/swandb/sql/SqlPrinter.java` |
| Binder | `src/main/java/dk/itu/swandb/sql/Binder.java` |

The grammar sits one directory deeper than the exercise's
`src/main/antlr4/dk/itu/datasys/sql/Sql.g4` on purpose. The Maven plugin
derives the generated package from the directory, so a grammar at
`.../sql/Sql.g4` would generate `dk.itu.swandb.sql.SqlParser` — the exact
name the exercise gives to the hand-written facade. Keeping the generated
code in `dk.itu.swandb.sql.parser` lets both names stand, and it keeps
generated classes in a package of their own.

## Parsing

```java
List<Statement> statements = new SqlParser().parse("""
        CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
        COPY trips FROM 'trips.csv';
        SELECT * FROM trips WHERE distance > 100;
        SELECT * FROM trips;
        """);
```

The grammar uses `options { caseInsensitive = true; }`, so keywords and type
names match in any case while the token text keeps the casing it was written
with: `MyTable` stays `MyTable` in the AST, and `string` becomes
`ColumnType.STRING`. `WHERE` is optional, `--` comments and all whitespace are
skipped, and both numeric literals accept a leading `-`; the lexer's
longest-match rule makes `-1.5` one `DOUBLE_LITERAL`, not `-1` followed by
junk.

`SqlParser` removes ANTLR's default error listeners from both the lexer and
the parser and installs one listener that throws `SqlParseException` at the
first syntax error. Recovery is what would otherwise hand the visitor a
half-built tree, and the default listener prints to stderr. The exception
carries ANTLR's conventions: a 1-based line and a 0-based column.

AST typing is where the front end meets the engine API. Literals become the
exact Java class `StorageEngine.select` demands — `'Odense'` a `String`, `12`
a `Long`, `23.5` a `Double` — and `ColumnSpec`, `ColumnType` and
`Comparison` are the week 2 types, not copies of them.

## Binding

`Binder` holds a `StorageEngine` and validates one statement against the
catalog: `CREATE TABLE` needs a non-empty column list without duplicate
names, `COPY` and `SELECT` need an existing table, and a `SELECT ... WHERE`
needs an existing column whose type matches the constant exactly. The binder
reads the schema through `StorageEngine.schema(String)`, the read-only
accessor added in this exercise, so no scan is involved.

What the binder deliberately does *not* check is whether a `CREATE TABLE`
target already exists: execution performs that check, where it can be made
atomically with the create. A `COPY` whose CSV file is missing binds too —
file access is execution's concern.

## Printing

`SqlPrinter` renders from the AST alone; no original input text is kept
anywhere. Output is normalized (uppercased keywords and type names, comments
gone), so the guarantee is the round-trip property

```
parse(print(s)).equals(s)
```

which is what makes the printer evidence that the parse captured everything.
Two traps in the double literal are worth knowing:

- `Double.toString` may emit scientific notation (`1.0E10`), which the
  literal rule does not match, so the printer renders plain decimal text.
- A plain `10000000000` would lex as a `LONG_LITERAL`, so the printed double
  always carries a decimal point.

Both renderings stay exact because `BigDecimal.valueOf` reads the shortest
decimal that identifies the double, the same digits `Double.toString` uses.

## Running it

```bash
mvn compile exec:java   # prints the four Task 1 statements, one per line
mvn -B verify           # unit tests (Surefire) and integration tests (Failsafe)
```

## Logging

One line per `parse` call, in the seven-field CSV format of
`docs/csv-logging.md`; the failure line records the position, never the
message, so a comma in a message can never split a log line:

```
DEBUG SqlParser statements=4 durationMs=2
ERROR SqlParser failed line=1 col=19 durationMs=0
```
