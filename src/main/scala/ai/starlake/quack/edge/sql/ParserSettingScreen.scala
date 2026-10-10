package ai.starlake.quack.edge.sql

import ai.starlake.sql.{SqlCommentStripper, SqlTrivia}

import java.util.Locale

/** Settings that change how a node lexes, parses or resolves SQL are refused for EVERY caller,
  * superusers included, whatever the ACL mode, OPA mode and the lockdown flag.
  *
  * The edge's classifier, ACL parser and screens read SQL the way DuckDB 2.0 does by default.
  * Changing one of these settings on a node makes the node read later statements differently (under
  * `dialect_compatibility_mode = 'spark'`, `'a\'b'` no longer terminates), and most are GLOBAL, so
  * one session's SET also changes how every other session's statements parse on that node. No
  * caller may hold that switch: a superuser setting it breaks the ACL for everyone on the pool.
  *
  * `IMPORT DATABASE` is refused for the same reason: it replays the `schema.sql` / `load.sql` of a
  * directory the edge never reads (verified on 2.0: a `SET dialect_compatibility_mode` in
  * `schema.sql` takes effect), so it can carry any of these settings past this screen.
  *
  * Token-based like [[TriggerScreen]]: each statement is split and lexed from the ORIGINAL text and
  * case is folded afterwards. Manager-internal SQL (DuckLake initializer, node bootstrap, demo
  * seed) never goes through the router and sets none of these.
  */
object ParserSettingScreen:

  val RefusalMessage =
    "settings that change how SQL is parsed cannot be changed through the gateway"

  val ImportRefusalMessage =
    "IMPORT DATABASE replays SQL the gateway cannot screen and is disabled"

  /** The DuckDB 2.0 settings that change lexing, parsing, identifier folding or name resolution. */
  val Settings: Set[String] = Set(
    "dialect_compatibility_mode",
    "current_dialect",
    "active_grammar_extensions",
    "allow_parser_override_extension",
    "preserve_identifier_case",
    "show_behavior",
    "table_function_identifier_conversion"
  )

  private val Names = Settings.toList.sorted.mkString("|")

  /** EXPLAIN takes ANALYZE / ANALYSE and an option list of arbitrary expressions, and EXPLAIN
    * ANALYZE executes a SET (verified on 2.0), so the statement counts anywhere after EXPLAIN, as
    * in [[TriggerScreen]].
    */
  private val ExplainPrefix = "(?s)^(?:explain\\b.*\\b)?"

  /** SET / RESET / PRAGMA with an optional SESSION / GLOBAL / LOCAL scope, naming the setting bare,
    * double-quoted or backtick-quoted (DuckDB 2.0 accepts all three, case-insensitively).
    */
  private val SettingTarget =
    (ExplainPrefix + "(?:set|reset|pragma)\\s+(?:(?:session|global|local)\\s+)?" +
      s"(?:\"(?:$Names)\"|`(?:$Names)`|(?:$Names)(?![a-z0-9_$$]))").r

  private val ImportDatabase = (ExplainPrefix + "import\\s+database\\b").r

  /** The refusal for the first statement of `sql` that changes a parser setting or imports a
    * database, if any.
    */
  def screen(sql: String): Option[String] =
    LockdownScreen
      .splitStatements(sql)
      .iterator
      .map(normalize)
      .flatMap { s =>
        if SettingTarget.findFirstIn(s).isDefined then Some(RefusalMessage)
        else if ImportDatabase.findFirstIn(s).isDefined then Some(ImportRefusalMessage)
        else None
      }
      .nextOption()

  /** Lexes the ORIGINAL statement and folds case only afterwards (dollar-quote tags compare
    * case-sensitively in DuckDB; see [[TriggerScreen]]).
    */
  private def normalize(stmt: String): String =
    SqlTrivia
      .normalize(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(stmt)))
      .toLowerCase(Locale.ROOT)
      .trim
