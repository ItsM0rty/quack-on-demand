package ai.starlake.sql

/** Removes single-line (`--`) and multi-line (`/* ... */`) SQL comments from a query string,
  * mirroring DuckDB's own lexer rather than approximating it.
  *
  * Mirrors the implementation from the Starlake codebase (`ai.starlake.sql.SqlCommentStripper`) so
  * SQL-handling utilities stay consistent across projects; the lexer fidelity below is this
  * project's own, driven by what a real DuckDB 1.5.4 accepts.
  *
  * Three properties, each verified by execution against DuckDB 1.5.4 rather than assumed:
  *
  *   1. A comment is a SEPARATOR, not a weld. `INSERT/*x*/INTO t VALUES (1)` runs and writes the
  *      row, because DuckDB treats the comment as whitespace between the two keywords rather than
  *      as if it had never been there. A closed block comment is therefore replaced by a single
  *      ASCII space, so `INSERT/*x*/INTO` strips to `INSERT INTO` and not to the welded
  *      `INSERTINTO` that matches no classifier keyword bucket. A line comment contributes its own
  *      terminator (or, unterminated, runs to end of input with nothing left to weld to).
  *   2. Block comments NEST. `/* a /* b */ c */ INSERT INTO t VALUES (1)` executes, and so does
  *      three levels deep. A scanner that closes on the first closing marker it sees leaves the
  *      text between the inner and the outer close in the stream, which is not merely "less
  *      stripped": it puts a non-keyword where the next keyword should be (`EXPLAIN /* a /* b */ c
  *      */ ANALYZE INSERT ...` reads as plain EXPLAIN, and DuckDB writes the row) and it breaks the
  *      keyword-argument adjacency the lockdown screen's regexes key on (`FROM/* a /* b */ c
  *      */'/etc/passwd.parquet'` reads the file). So the block arm tracks depth.
  *   3. A comment opener inside a QUOTED region is not a comment. DuckDB 2.0 has five quoting
  *      forms, all of which accept a block-comment opener and `--` verbatim: plain strings `'...'`,
  *      escape strings `e'...'` / `E'...'` (a backslash escapes whatever follows it), double-quoted
  *      and backtick identifiers, and dollar-quoted strings `$$...$$` / `$tag$...$tag$`. Tracking
  *      only the first of them lets a block-comment opener in a column alias open a comment that
  *      never closes and swallows the rest of the statement. A SELECT whose alias is the four
  *      characters x, slash, star, y, reading /etc/passwd.csv through read_csv, executes on DuckDB
  *      and reached every downstream screen as `select 1 as "x` (the alias is spelled out rather
  *      than written inline because an unbalanced opener would end this scaladoc;
  *      `SqlCommentStripperSpec` carries it verbatim as a string literal). WHERE each quoted region
  *      opens and ends (which `$` and which `e` start one, which characters a dollar tag holds,
  *      DuckDB's unicode-space pre-pass) is `SqlLexer`'s job, ported from the DuckDB 2.0 source and
  *      shared with the statement splitter and the classifier.
  *
  * The result is for CLASSIFICATION and SCREENING. `PrepareStrategy` is the one caller that sends
  * derived text back to the engine, and every property above moves that path closer to the original
  * statement, never further from it: comments are what DuckDB itself discards, and quoted regions
  * are now copied through byte for byte instead of being truncated at a quoted comment marker.
  */
object SqlCommentStripper:

  def stripComments(sql: String): String =
    if sql == null || sql.isEmpty then sql
    else
      val result = new StringBuilder()
      // Every boundary decision comes from `SqlLexer`, the one reader shared with
      // `LockdownScreen.splitStatements` and `StatementClassifier`, so this stripper can never
      // disagree with them (or with DuckDB 2.0) on where a literal or a comment ends.
      SqlLexer.tokens(sql).foreach { t =>
        t.kind match
          // A closed block comment becomes a single ASCII space, not nothing: DuckDB treats the
          // comment as a separator, so the two tokens it sat between must not weld together
          // (`INSERT/*x*/INTO` -> `INSERT INTO`, not `INSERTINTO`). Harmless when the comment
          // already sat next to real whitespace; load-bearing when it didn't.
          case SqlLexer.Kind.BlockComment => result.append(' ')
          // The terminator is not part of the token: it follows as whitespace and is kept, so it
          // separates the two lines.
          case SqlLexer.Kind.LineComment => ()
          case _                         => result.append(sql.substring(t.start, t.end))
      }
      removeEmptyLines(result.toString())

  def removeEmptyLines(input: String): String =
    val res = input.linesIterator.filter(_.trim.nonEmpty).mkString("\n")
    if res.trim.endsWith(";") then res.trim.dropRight(1) else res
