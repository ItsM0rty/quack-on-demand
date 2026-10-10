package ai.starlake.quack.edge.sql

/** Node settings no caller may change, superusers included, whatever the ACL mode, OPA mode and the
  * lockdown flag: those that change how a node lexes, parses or resolves SQL, and those that would
  * print the node's credentials.
  *
  * The edge's classifier, ACL parser and screens read SQL the way DuckDB 2.0 does by default.
  * Changing one of these settings on a node makes the node read later statements differently (under
  * `dialect_compatibility_mode = 'spark'`, `'a\'b'` no longer terminates), and most are GLOBAL, so
  * one session's SET also changes how every other session's statements parse on that node. No
  * caller may hold that switch: a superuser setting it breaks the ACL for everyone on the pool.
  *
  * `redact_http_logs = false` writes HTTP header values unredacted into the node's log (verified on
  * 2.0: with an http secret carrying `Authorization: Bearer ...`, a read logs the bearer token in
  * `duckdb_logs`), so node-held credentials (federation and catalog bearer tokens, S3 session
  * tokens) become readable by anyone who can read the log; with ACL off, the default, that is every
  * user. Credentials never round-trip to a gateway caller, superusers included (the control plane
  * redacts them in every response too), and the setting is GLOBAL, so one session's change exposes
  * every session's traffic. `allow_unredacted_secrets` is startup-only on 2.0 and refused here so a
  * later runtime-settable version cannot reopen it.
  *
  * Three ways of running SQL the edge never screens are refused for the same reason:
  *   - `IMPORT DATABASE 'dir'` and its pragma form `PRAGMA import_database('dir')` (the lowering
  *     DuckDB 2.0 itself uses) replay the `schema.sql` / `load.sql` of a directory or URL the edge
  *     never reads. Verified on 2.0: a `SET GLOBAL dialect_compatibility_mode` in `schema.sql`
  *     applies, from a local dir or an `http://` URL, so the import would carry any of these
  *     settings past this screen.
  *   - `postgres_execute('<catalog>', '<sql>')` and `postgres_query('<catalog>', '<sql>')` run an
  *     arbitrary SQL string on an attached Postgres with the node's credentials. A DuckLake node
  *     has its catalog Postgres attached (`__ducklake_metadata_<alias>`), which holds every
  *     per-file key; verified on 2.0 that `postgres_execute` runs DDL there. The edge never parses
  *     that string, so no ACL, catalog scoping or policy applies to it.
  *
  * Settings read from DuckDB 2.0's own tokens, the carried statement of an EXPLAIN / PREPARE
  * wrapper included ([[StatementHead]]); the three SQL-running calls are found by [[FunctionCalls]]
  * (any quoting, qualified, and inside `query()` literal bodies). Manager-internal SQL (DuckLake
  * initializer, node bootstrap's own `postgres_query` advisory locks, demo seed) never goes through
  * the router, and `quack_query` (the front door's own federation primitive, which reaches a remote
  * quack endpoint that applies its own ACL) is deliberately NOT refused.
  */
object NodeSettingScreen:

  val RefusalMessage =
    "settings that change how SQL is parsed cannot be changed through the gateway"

  val SecretRefusalMessage =
    "settings that expose node credentials cannot be changed through the gateway"

  val ImportRefusalMessage =
    "IMPORT DATABASE replays SQL the gateway cannot screen and is disabled"

  val ExecRefusalMessage =
    "postgres_execute / postgres_query run SQL the gateway cannot screen and are disabled"

  /** The DuckDB 2.0 settings that change lexing, parsing, identifier folding or name resolution. */
  val ParserSettings: Set[String] = Set(
    "dialect_compatibility_mode",
    "current_dialect",
    "active_grammar_extensions",
    "allow_parser_override_extension",
    "preserve_identifier_case",
    "show_behavior",
    "table_function_identifier_conversion"
  )

  /** The settings that would print node credentials in clear. */
  val SecretSettings: Set[String] = Set("redact_http_logs", "allow_unredacted_secrets")

  /** Every setting this screen refuses. */
  val Settings: Set[String] = ParserSettings ++ SecretSettings

  private val Scopes = Set("session", "global", "local")

  /** Table functions / pragmas that run SQL the edge never screens, mapped to their refusal. */
  private val DeniedCalls: Map[String, String] = Map(
    "import_database"  -> ImportRefusalMessage,
    "postgres_execute" -> ExecRefusalMessage,
    "postgres_query"   -> ExecRefusalMessage
  )

  /** The refusal for the first statement of `sql` that changes one of [[Settings]], imports a
    * database, or calls a SQL-running function, if any.
    */
  def screen(sql: String): Option[String] =
    LockdownScreen
      .splitStatements(sql)
      .iterator
      .flatMap(screenOne)
      .nextOption()
      .orElse(
        FunctionCalls.scan(sql).calls.iterator.flatMap(c => DeniedCalls.get(c.name)).nextOption()
      )

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
        h.identifier(target).collect {
          case n if ParserSettings(n) => RefusalMessage
          case n if SecretSettings(n) => SecretRefusalMessage
        }
      case Some("import") if h.word(i + 1).contains("database") => Some(ImportRefusalMessage)
      case _                                                    => None
