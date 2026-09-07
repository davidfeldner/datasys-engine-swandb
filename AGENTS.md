# Coding conventions for AI agents

## Java style

- **Single-statement `if`/`else` bodies go on one line, no braces.**
  Prefer `if (cond) doThing();` over `if (cond) { doThing(); }`.
  Multi-statement bodies still require braces.
- Apply this rule in new code and when editing existing code that already
  follows it. Do not reformat unrelated files just to enforce it.
