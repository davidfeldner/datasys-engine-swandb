/*
 * Grammar for the SQL subset of Part 1: CREATE TABLE, COPY, SELECT.
 *
 * The directory holding this file below src/main/antlr4 is the Java package
 * of the generated classes, so the lexer, parser, listener and visitor all
 * land in dk.itu.swandb.sql.parser (the hand-written front end in
 * dk.itu.swandb.sql owns the names SqlParser, SqlParseException, ... and
 * would otherwise collide with the generated SqlParser).
 */
grammar Sql;

// Keywords are case-insensitive; token text keeps the casing as written,
// so an identifier is an identifier whatever its case, and its spelling
// survives into the AST.
options { caseInsensitive = true; }

// --- Parser -----------------------------------------------------------

// A whole script: one or more ';'-terminated statements, then end of input.
script      : (statement ';')+ EOF ;
statement   : createTable | copy | select ;

createTable : CREATE TABLE IDENTIFIER '(' columnDef (',' columnDef)* ')' ;
columnDef   : IDENTIFIER columnType ;
columnType  : STRING | LONG | DOUBLE ;

copy        : COPY IDENTIFIER FROM STRING_LITERAL ;

// WHERE is optional, as in DuckDB.
select      : SELECT '*' FROM IDENTIFIER (WHERE predicate)? ;
predicate   : IDENTIFIER comparison=('=' | '<' | '>') literal ;
literal     : STRING_LITERAL | LONG_LITERAL | DOUBLE_LITERAL ;

// --- Lexer ------------------------------------------------------------

// Keyword rules MUST precede IDENTIFIER, or IDENTIFIER swallows them.
CREATE : 'CREATE' ;   TABLE : 'TABLE' ;   COPY : 'COPY' ;   FROM : 'FROM' ;
SELECT : 'SELECT' ;   WHERE : 'WHERE' ;
// The type names are tokens of their own, distinct from the literals below.
STRING : 'STRING' ;   LONG : 'LONG' ;   DOUBLE : 'DOUBLE' ;

IDENTIFIER      : [A-Z_] [A-Z_0-9]* ;        // caseInsensitive covers a-z
// Longest match wins, so '-1.5' is one DOUBLE_LITERAL, not a LONG_LITERAL
// followed by junk. Both numeric forms may be negative.
LONG_LITERAL    : '-'? [0-9]+ ;
DOUBLE_LITERAL  : '-'? [0-9]+ '.' [0-9]+ ;
// No escaped quotes: a literal ends at the next quote on the same line.
STRING_LITERAL  : '\'' ~['\r\n]* '\'' ;
LINE_COMMENT    : '--' ~[\r\n]* -> skip ;
WS              : [ \t\r\n]+ -> skip ;
