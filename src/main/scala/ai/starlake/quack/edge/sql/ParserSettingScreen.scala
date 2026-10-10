package ai.starlake.quack.edge.sql

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
  * Token-based: each statement is split and lexed from the ORIGINAL text by DuckDB 2.0's own
  * tokenizer and names are folded afterwards. Manager-internal SQL (DuckLake initializer, node
  * bootstrap, demo seed) never goes through the router and sets none of these.
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

  private val Scopes = Set("session", "global", "local")

  /** The refusal for the first statement of `sql` that changes a parser setting or imports a
    * database, if any.
    */
  def screen(sql: String): Option[String] =
    LockdownScreen.splitStatements(sql).iterator.flatMap(screenOne).nextOption()

  /** Read from DuckDB 2.0's own tokens, never from a regex over the text: the tokenizer needs no
    * whitespace between a keyword and a quoted name, so `SET"show_behavior"='setting'`,
    * ``SET GLOBAL`show_behavior`=...`` and `PRAGMA"show_behavior"=...` all apply on 2.0, and a
    * comment between two tokens separates them like a space. The statement is the one an EXPLAIN
    * carries, if any ([[StatementHead]]): `EXPLAIN ANALYZE SET ...` applies the setting, while
    * `EXPLAIN UPDATE t SET show_behavior = 1` names a column.
    */
  private def screenOne(stmt: String): Option[String] =
    val h = StatementHead.of(stmt)
    val i = h.start
    h.word(i) match
      case Some("set" | "reset" | "pragma") =>
        // SET [SESSION | GLOBAL | LOCAL] <name>: a scope word followed by a name is a scope
        val target =
          if h.word(i + 1).exists(Scopes) && h.identifier(i + 2).isDefined then i + 2 else i + 1
        h.identifier(target).filter(Settings).map(_ => RefusalMessage)
      case Some("import") if h.word(i + 1).contains("database") => Some(ImportRefusalMessage)
      case _                                                    => None
