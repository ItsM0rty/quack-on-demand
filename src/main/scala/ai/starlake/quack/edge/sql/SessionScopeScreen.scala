package ai.starlake.quack.edge.sql

import ai.starlake.sql.{SqlCommentStripper, SqlTrivia}

import java.util.Locale

/** Refuses a multi-statement submission that changes the session's schema or search path.
  *
  * The access check qualifies every unqualified table name against the pool's default schema, and
  * the router pins that schema on the node with a `USE <db>.<schema>` prelude. A `USE`, `SET
  * search_path` or `SET schema` INSIDE the submission runs after the prelude, so DuckDB resolves
  * every later bare name against a schema (or catalog) the check never looked at:
  * `SET search_path='db.other'; SELECT * FROM t` reads `db.other.t` while `db.def.t` was
  * authorized. Tracking the change through the batch instead would have to model DuckDB's
  * resolution exactly, and a multi-entry search path resolves a bare name to the first entry that
  * HOLDS it, which needs the catalog, not the text. So the statement is refused when it shares a
  * submission with anything else; sent on its own it is unchanged (control flow, and the next
  * statement gets the prelude again).
  *
  * Pure and token-based on purpose: it runs on the ORIGINAL text, split by the same lexer DuckDB's
  * quoting and comment rules are modelled in ([[SqlCommentStripper.splitStatements]]), so neither a
  * comment, a string, a quoted identifier nor a dollar-quoted body can hide a separator or a verb,
  * and it never depends on whether JSqlParser can parse the statement (it cannot parse `SET x TO`,
  * `PRAGMA`, or a two-part `USE`).
  *
  * Recognized, fail closed: `USE ...` (any form); `SET` / `RESET`, optionally `SESSION` / `GLOBAL`
  * / `LOCAL`, of `search_path`, `schema` or `ALL`, the name bare or double-quoted in any case, and
  * a `SET` / `RESET` whose setting name cannot be read; `PRAGMA search_path` / `PRAGMA schema`.
  * `SET VARIABLE` sets a variable even when it is called `search_path`, and other settings do not
  * move name resolution (checked against DuckDB 1.5.6 and 2.0), so both stay batchable.
  */
object SessionScopeScreen:

  /** The DuckDB settings that decide where an unqualified name resolves. */
  private val ScopeSettings = Set("search_path", "schema")

  private val ScopeModifiers = Set("session", "global", "local")

  /** Some(reason) when the submission must be refused. */
  def find(sql: String): Option[String] =
    val statements = SqlCommentStripper.splitStatements(sql)
    if statements.lengthCompare(2) < 0 then None
    else statements.find(changesScope).map(reason)

  private def reason(statement: String): String =
    val shown = SqlTrivia.normalize(SqlCommentStripper.stripComments(statement)).trim
    val brief = if shown.length > 120 then shown.take(120) + "..." else shown
    "USE, SET search_path and SET schema must be sent as their own statement, not batched with " +
      "other statements: table names after them are authorized against the pool's default " +
      s"schema, not the one they select ($brief)"

  /** True when `statement` (one top-level statement, original text) moves name resolution. */
  private[sql] def changesScope(statement: String): Boolean =
    val text =
      SqlTrivia
        .normalize(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(statement)))
        .trim
        .toLowerCase(Locale.ROOT)
    val (verb, afterVerb) = word(text)
    verb match
      case "use"           => true
      case "set" | "reset" =>
        val (first, afterFirst) = name(afterVerb)
        val (second, _)         = name(afterFirst)
        // `SET session = 1` names a setting called `session`; `SET SESSION x = 1` names `x`.
        val setting =
          if ScopeModifiers.contains(first) && second.nonEmpty && second != "=" && second != "to"
          then second
          else first
        setting.isEmpty || setting == "all" || ScopeSettings.contains(setting)
      case "pragma" => ScopeSettings.contains(name(afterVerb)._1)
      case _        => false

  private def isWordChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'

  /** The leading run of word characters and the remainder after it. */
  private def word(s: String): (String, String) =
    val w = s.takeWhile(isWordChar)
    (w, s.drop(w.length))

  /** The next name: a double-quoted identifier (`""` doubling, unquoted for comparison), a bare
    * word, or a single operator character (`=`), with the remainder after it.
    */
  private def name(s: String): (String, String) =
    val t = s.dropWhile(_ == ' ')
    if t.startsWith("\"") then
      val sb     = new StringBuilder
      var i      = 1
      var closed = false
      while i < t.length && !closed do
        if t.charAt(i) == '"' then
          if i + 1 < t.length && t.charAt(i + 1) == '"' then
            sb.append('"')
            i += 2
          else
            closed = true
            i += 1
        else
          sb.append(t.charAt(i))
          i += 1
      (sb.toString, t.drop(i))
    else
      val (w, rest) = word(t)
      if w.nonEmpty then (w, rest)
      else if t.nonEmpty then (t.take(1), t.drop(1))
      else ("", "")
