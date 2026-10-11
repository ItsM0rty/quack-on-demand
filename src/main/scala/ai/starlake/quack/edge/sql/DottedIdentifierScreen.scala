package ai.starlake.quack.edge.sql

import ai.starlake.sql.SqlLexer

import java.util.Locale

/** Refuses a quoted identifier JSqlParser does not read as the one name DuckDB reads, outside an
  * alias: a name holding a dot, an `@` or an escaped quote.
  *
  * DuckDB reads `"a.b"` (or `` `a.b` ``) as ONE identifier: `SELECT * FROM "a.b"` reads the table
  * literally named `a.b` in the default schema. JSqlParser splits the quoted name on its dots
  * instead, so the access check, the row-policy rewrite and every other parser-based screen see
  * schema `a`, table `b` (`"a.b.c"`: catalog, schema, table), in every table position: reads,
  * INSERT / UPDATE / DELETE / MERGE / TRUNCATE targets, ALTER, CREATE TABLE / VIEW. A principal
  * granted on schema `a` would read, write and alter `main."a.b"`, and create tables in the default
  * schema (checked on the DuckDB 2.0 CLI with both `a.b` and `main."a.b"` present). It splits
  * `"secret@x"` the same way at the `@` (an Oracle database link: the check sees table `secret`
  * while DuckDB reads `secret@x`), and keeps an escaped quote doubled (`"a""b"` is checked as
  * `a""b`, DuckDB reads `a"b`). Every other printable ASCII and unicode character was probed in
  * both quoting styles and reaches the check as the name DuckDB 2.0 reads (unicode spaces inside a
  * backtick name become ASCII spaces on both sides, DuckDB's pre-pass knowing no backtick).
  * Tracking which positions JSqlParser splits would mean modelling its grammar, so the rule is
  * lexical and covers every position: a double-quoted or backtick-quoted identifier token holding a
  * `.`, an `@` or its own doubled delimiter is refused, read from [[SqlLexer]] tokens so a string
  * literal or a comment never counts.
  *
  * Two exceptions. A name directly after the keyword `AS` is an alias (`SELECT x AS "t.x"`, `FROM t
  * AS "a.b"`), the shape BI tools generate for column labels; in DuckDB's grammar `AS` is never
  * followed by a table reference, so an alias names no table the check could misread. A name that
  * starts with a URL scheme (`FROM "s3://b/x.parquet"`, the scheme holding no dot) is a remote
  * replacement scan reading the object, judged as a path by [[LockdownScreen]]; split on its dots
  * its first part holds the `://`, so the access check refuses it as the file reference it is
  * (`DuckDBDialectMapper.isFileReference`: only a wildcard ALL grant covers it, like `FROM
  * 's3://b/x.parquet'`). A `://` anywhere else is no exception (`"other.x://"` would be checked as
  * schema `other`).
  *
  * Runs for callers the ACL checks (QoD grants and OPA, through [[AccessGate]]), for principals
  * with column or row policies (the protected-write guard), and on lockdown pools (every caller).
  * Off lockdown, a table whose name holds one of these stays reachable to superusers and to callers
  * the ACL does not check.
  */
object DottedIdentifierScreen:

  val Reason: String =
    "a quoted identifier containing a dot, an @ or an escaped quote is not supported: DuckDB " +
      "reads \"a.b\" as one name, which the access check cannot tell from a qualified a.b; " +
      "rename the object or use an alias without one"

  // A scheme with no dot, so JSqlParser's first split part always holds the `://`.
  private val UrlScheme = "^[A-Za-z][A-Za-z0-9+-]*://.*".r

  /** Some(reason) when `sql` holds such a quoted identifier outside an alias. */
  def find(sql: String): Option[String] =
    if sql == null || !(sql.indexOf('.') >= 0 || sql.indexOf('@') >= 0 ||
        sql.contains("\"\"") || sql.contains("``"))
    then None
    else
      val view = SqlLexer.engineView(sql)
      val toks = SqlLexer.tokens(view).filterNot(_.isTrivia)
      val hit  = toks.indices.exists { i =>
        val t = toks(i)
        t.kind == SqlLexer.Kind.Literal && isQuotedIdentifier(view, t) &&
        misread(view.substring(t.start, t.end)) && !(i > 0 && isAs(view, toks(i - 1)))
      }
      Option.when(hit)(Reason)

  /** Whether JSqlParser reads the quoted identifier `text` (delimiters included) as another name.
    */
  private def misread(text: String): Boolean =
    val quote = text.charAt(0)
    val body  = text.drop(1).dropRight(1)
    val split = (body.indexOf('.') >= 0 || body.indexOf('@') >= 0) && !UrlScheme.matches(body)
    split || body.contains(s"$quote$quote")

  private def isQuotedIdentifier(view: String, t: SqlLexer.Token): Boolean =
    val c = view.charAt(t.start)
    c == '"' || c == '`'

  private def isAs(view: String, t: SqlLexer.Token): Boolean =
    t.kind == SqlLexer.Kind.Word && view.substring(t.start, t.end).toLowerCase(Locale.ROOT) == "as"
