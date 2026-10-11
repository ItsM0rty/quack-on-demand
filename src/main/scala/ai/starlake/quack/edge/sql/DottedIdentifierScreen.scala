package ai.starlake.quack.edge.sql

import ai.starlake.sql.SqlLexer

import java.util.Locale

/** Refuses a quoted identifier whose name holds a dot, outside an alias.
  *
  * DuckDB reads `"a.b"` (or `` `a.b` ``) as ONE identifier: `SELECT * FROM "a.b"` reads the table
  * literally named `a.b` in the default schema. JSqlParser splits the quoted name on its dots
  * instead, so the access check, the row-policy rewrite and every other parser-based screen see
  * schema `a`, table `b` (`"a.b.c"`: catalog, schema, table), in every table position: reads,
  * INSERT / UPDATE / DELETE / MERGE / TRUNCATE targets, ALTER, CREATE TABLE / VIEW. A principal
  * granted on schema `a` would read, write and alter `main."a.b"`, and create tables in the default
  * schema (checked on the DuckDB 2.0 CLI with both `a.b` and `main."a.b"` present). Tracking which
  * positions JSqlParser splits would mean modelling its grammar, so the rule is lexical and covers
  * every position: a double-quoted or backtick-quoted identifier token whose name holds a `.` is
  * refused, read from [[SqlLexer]] tokens so a dot inside a string literal or a comment never
  * counts.
  *
  * Two exceptions. A name directly after the keyword `AS` is an alias (`SELECT x AS "t.x"`, `FROM t
  * AS "a.b"`), the shape BI tools generate for column labels; in DuckDB's grammar `AS` is never
  * followed by a table reference, so an alias names no table the check could misread. A name
  * holding `://` is a remote replacement scan (`FROM "s3://b/x.parquet"` reads the object), judged
  * as a path by [[LockdownScreen]] and refused as a file read by the ACL; split on its dots it
  * yields a schema no grant can name.
  *
  * Runs for callers the ACL checks (QoD grants and OPA, through [[AccessGate]]), for principals
  * with column or row policies (the protected-write guard), and on lockdown pools (every caller).
  * Off lockdown, a table whose name holds a dot stays reachable to superusers and to callers the
  * ACL does not check.
  */
object DottedIdentifierScreen:

  val Reason: String =
    "a quoted identifier containing a dot is not supported: DuckDB reads \"a.b\" as one name, " +
      "which the access check cannot tell from a qualified a.b; rename the object or use an " +
      "alias without a dot"

  /** Some(reason) when `sql` holds a quoted identifier with a dot outside an alias. */
  def find(sql: String): Option[String] =
    if sql == null || sql.indexOf('.') < 0 then None
    else
      val view = SqlLexer.engineView(sql)
      val toks = SqlLexer.tokens(view).filterNot(_.isTrivia)
      val hit  = toks.indices.exists { i =>
        val t    = toks(i)
        val text = view.substring(t.start, t.end)
        t.kind == SqlLexer.Kind.Literal && isQuotedIdentifier(view, t) &&
        text.indexOf('.') >= 0 && !text.contains("://") &&
        !(i > 0 && isAs(view, toks(i - 1)))
      }
      Option.when(hit)(Reason)

  private def isQuotedIdentifier(view: String, t: SqlLexer.Token): Boolean =
    val c = view.charAt(t.start)
    c == '"' || c == '`'

  private def isAs(view: String, t: SqlLexer.Token): Boolean =
    t.kind == SqlLexer.Kind.Word && view.substring(t.start, t.end).toLowerCase(Locale.ROOT) == "as"
