package ai.starlake.quack.edge.sql

import ai.starlake.sql.SqlLexer

import java.util.Locale

/** Introspection that shows a caller node-level state no tenant may read: another session's
  * statements, connection ids or paths, the node's file paths, its credentials or their names, and
  * setting values that reveal the deployment. Refused to every caller who is not a superuser,
  * whatever the ACL mode and the grants (a node's state is not a table), and to every caller on a
  * lockdown pool, like [[NodeSettingScreen.screenNodeWide]] beside which the router runs it.
  *
  * Every DuckDB 2.0 table function and system view was judged on the 2.0 CLI with every node
  * extension loaded (FileAccessFunctionsSpec holds the judgment of each, refused or admitted, and
  * fails on one nobody judged). Refused ([[Refused]], with what each shows):
  *
  *   - credentials and their names: `duckdb_secrets()` (name, scope, `key_id`, Postgres host / user
  *     / database, DuckLake data paths), `which_secret()` (the secret serving a path), `whoami()`
  *     (the node's cloud identity, hostname, region, uptime);
  *   - setting values: `duckdb_settings()` and its `pg_settings` view (`s3_secret_access_key`,
  *     `http_proxy_password`, temp / secret / extension directories, all unredacted on 2.0), and
  *     `current_setting(..)` except over a literal in [[SafeSettings]] (session values a client
  *     sets itself);
  *   - other sessions and node files: `duckdb_logs()` / `duckdb_logs_parsed()` /
  *     `duckdb_log_contexts()` (logged statements, connection and query ids),
  *     `duckdb_external_file_cache()` (every path any session read), `duckdb_temporary_files()`
  *     (spill files), `duckdb_databases()`, its `pragma_database_list` view and `PRAGMA
  *     database_list` (attached database paths), `duckdb_extensions()` and `PRAGMA
  *     extension_versions` (install paths and origins), `duckdb_extension_repositories()`,
  *     `duckdb_external_resources()` (resource URIs and handles);
  *   - `pragma_storage_info('<table>')` and `PRAGMA storage_info`: per-segment statistics, min and
  *     max values included, of a table named by a string no grant check sees.
  *
  * Admitted: static catalogs of DuckDB itself (types, functions, keywords, secret types, ...), the
  * caller's own session (`duckdb_variables()`, `duckdb_prepared_statements()`), aggregates that
  * name nothing (`duckdb_memory()`, `duckdb_connection_count()`), and the catalog views the ACL and
  * the metadata filter already gate.
  *
  * Read from DuckDB 2.0's tokens ([[FunctionCalls]]): a refused name is refused wherever it appears
  * as an identifier (a call, a bare view name, any quoting, qualified, inside a literal body a
  * function may run), so `FROM duckdb_settings` and `query('.. duckdb_secrets()')` are caught; a
  * column of such a name over-denies, the safe direction.
  */
object NodeStateScreen:

  /** The refused names, each with what it shows. */
  val Refused: Map[String, String] = Map(
    "duckdb_secrets"     -> "secret names, scopes, key ids, hosts, users and data paths",
    "which_secret"       -> "the secret serving a path",
    "whoami"             -> "the node's cloud identity, hostname and region",
    "duckdb_settings"    -> "setting values, credentials and node directories included",
    "pg_settings"        -> "setting values, credentials and node directories included",
    "duckdb_logs"        -> "other sessions' logged statements",
    "duckdb_logs_parsed" -> "other sessions' logged statements",
    // a table macro over duckdb_settings, judged by its body
    "duckdb_profiling_settings"     -> "setting values (profiling settings, output path included)",
    "duckdb_log_contexts"           -> "other sessions' connection and query ids",
    "duckdb_external_file_cache"    -> "the path of every file any session read",
    "duckdb_temporary_files"        -> "the node's spill file paths",
    "duckdb_databases"              -> "attached database paths",
    "pragma_database_list"          -> "attached database paths",
    "duckdb_extensions"             -> "extension install paths and origins",
    "duckdb_extension_repositories" -> "extension repository locations and keys",
    "duckdb_external_resources"     -> "external resource URIs and handles",
    "pragma_storage_info"           -> "column statistics, min and max values, of a named table"
  )

  /** `PRAGMA <name>` statements showing the same state, with the function they stand for. */
  val RefusedPragmas: Map[String, String] = Map(
    "database_list"      -> "pragma_database_list",
    "extension_versions" -> "duckdb_extensions",
    "storage_info"       -> "pragma_storage_info"
  )

  /** Settings whose value `current_setting('<name>')` may return: session settings a client sets
    * itself, revealing nothing about the node.
    */
  val SafeSettings: Set[String] = Set(
    "timezone",
    "calendar",
    "search_path",
    "schema",
    "default_order",
    "default_null_order",
    "null_order",
    "preserve_insertion_order",
    "errors_as_json",
    "max_execution_time",
    "http_timeout",
    "enable_progress_bar"
  )

  def refusal(name: String): String =
    s"$name shows node state (${Refused.getOrElse(name, "setting values")}) and is reserved " +
      "to superusers"

  /** Some(reason) when `sql` reads refused node state. */
  def find(sql: String): Option[String] =
    if sql == null then None
    else
      val scan = FunctionCalls.scan(sql)
      scan.texts.iterator
        .flatMap(refusedIdentifier)
        .nextOption()
        .map(refusal)
        .orElse(
          scan.calls
            .find(c => c.name == "current_setting" && !safeSettingCall(c))
            .map(_ => refusal("current_setting"))
        )
        .orElse(
          LockdownScreen.splitStatements(sql).iterator.flatMap(refusedPragma).nextOption()
        )

  private def refusedIdentifier(text: String): Option[String] =
    val view = SqlLexer.engineView(text)
    SqlLexer
      .tokens(view)
      .iterator
      .flatMap(t => FunctionCalls.identifier(view, t))
      .find(Refused.contains)

  /** `current_setting('<safe name>')`: one plain string literal naming a [[SafeSettings]] entry. */
  private def safeSettingCall(c: FunctionCalls.Call): Boolean =
    val toks = SqlLexer.tokens(c.text.substring(c.argsFrom)).filterNot(_.isTrivia)
    val rest = c.text.substring(c.argsFrom)
    toks.length >= 2 && toks(0).kind == SqlLexer.Kind.Literal && rest.charAt(
      toks(0).start
    ) == '\'' &&
    rest.substring(toks(1).start, toks(1).end) == ")" && {
      val body = rest.substring(toks(0).start + 1, toks(0).end - 1)
      !body.contains("'") && SafeSettings.contains(body.toLowerCase(Locale.ROOT))
    }

  private def refusedPragma(stmt: String): Option[String] =
    val h = StatementHead.of(stmt)
    if h.word(h.start).contains("pragma") then
      h.identifier(h.start + 1).flatMap(RefusedPragmas.get).map(refusal)
    else None
