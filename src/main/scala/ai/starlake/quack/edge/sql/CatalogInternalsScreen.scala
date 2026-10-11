package ai.starlake.quack.edge.sql

import java.util.Locale

/** DuckLake catalog internals, refused to every caller that is not a superuser, whatever the ACL
  * mode (QoD grants, OPA, ACL off) and whatever the grants (nothing here is wildcard-coverable),
  * and to every caller, superusers included, on a pool under lockdown.
  *
  * Every node attaches its DuckLake catalog's Postgres database as `__ducklake_metadata_<alias>`.
  * Its `ducklake_data_file` / `ducklake_delete_file` tables hold the per-file encryption keys of an
  * encrypted database (verified on the 2.0 CLI: a plain `SELECT encryption_key FROM
  * __ducklake_metadata_lake.ducklake_data_file` returns them), and the whole catalog is the raw
  * truth the gateway's grants, branch fences and maintenance rely on. A `*.*.* ALL` grant (the
  * built-in `qod_all_tables`) would otherwise reach it like any other catalog. Refused:
  *
  *   - any mention of `__ducklake_metadata` (case-insensitive) in an identifier, a string literal
  *     (decoded: escape strings included, and the bodies `query('...')` runs, as SQL in their own
  *     right) or a comment, which covers a qualified name under any quoting, `USE`,
  *     `SET search_path = '...'`, `postgres_query('__ducklake_metadata_x', ...)`, `ATTACH ... AS`
  *     and `CREATE VIEW` over it. A comment mentioning it over-denies, the safe direction.
  *   - the DuckLake functions that return file encryption keys or raw catalog rows
  *     ([[KeyFunctions]]): `ducklake_list_files` returns every data and delete file of a table with
  *     its `*_encryption_key`, and `ducklake_commit` writes catalog rows by metadata schema name.
  *     The other `ducklake_*` functions (snapshots, table_info, settings, options, table changes,
  *     maintenance) return no key and stay admitted here; [[CatalogReachingCalls]] still denies the
  *     whole family to tenant-scoped principals when the ACL validates.
  *   - a run-time-computed target, which a lexical screen cannot read: a call of
  *     [[RunTimeTargetCalls]] (`query_table('__duck' || 'lake_metadata_x...')` reaches the catalog)
  *     or a `SET search_path` / `SET schema` (verified on 2.0: `SET search_path = '__duck' ||
  *     'lake_metadata_lake'` takes effect) whose arguments are not plain constants.
  *
  * Manager-internal SQL (DuckLakeInitializer, the catalog readers, BranchCloner, MergeFence, the
  * merge and maintenance node calls through the adapter) never goes through the router.
  */
object CatalogInternalsScreen:

  /** The prefix of the DuckDB alias DuckLake attaches its metadata catalog under. */
  val MetadataPrefix: String = "__ducklake_metadata"

  /** DuckLake functions returning file encryption keys or raw catalog rows (2.0 CLI). */
  val KeyFunctions: Set[String] = Set("ducklake_list_files", "ducklake_commit")

  /** Calls whose target (a catalog, a table name, SQL text, an invoked function) is a value. */
  val RunTimeTargetCalls: Set[String] = Set(
    "query",
    "query_table",
    "json_execute_serialized_sql",
    "postgres_query",
    "postgres_execute",
    "postgres_scan",
    "postgres_scan_pushdown",
    "quack_query_by_name",
    "create_external_resource",
    "register_external_resource",
    "register_external_resource_type",
    "deregister_external_resource",
    "destroy_external_resource"
  )

  /** Settings whose value names the catalog that unqualified names resolve against. */
  val NameSettings: Set[String] = Set("search_path", "schema")

  val MetadataRefusal: String =
    "the DuckLake metadata catalog is internal and cannot be queried through the gateway"

  def keyFunctionRefusal(fn: String): String =
    s"$fn returns DuckLake file encryption keys or raw catalog rows and is disabled"

  def computedRefusal(name: String): String =
    s"$name with a computed argument cannot be screened for DuckLake catalog internals " +
      "and is disabled"

  val NestingRefusal: String =
    "statement nests string literals too deeply to screen and is disabled"

  /** The refusal for `sql`, if any. */
  def screen(sql: String): Option[String] =
    val scan = FunctionCalls.scan(sql)
    if scan.texts.exists(_.toLowerCase(Locale.ROOT).contains(MetadataPrefix)) then
      Some(MetadataRefusal)
    else
      scan.calls
        .collectFirst {
          case c if KeyFunctions(c.name) => keyFunctionRefusal(c.name)
          case c if RunTimeTargetCalls(c.name) && !FunctionCalls.constantArguments(c) =>
            computedRefusal(c.name)
        }
        .orElse(computedNameSetting(sql))
        .orElse(if scan.truncated then Some(NestingRefusal) else None)

  /** A `SET [scope] search_path | schema` whose value is anything but constants and names. */
  private def computedNameSetting(sql: String): Option[String] =
    LockdownScreen
      .splitStatements(sql)
      .iterator
      .flatMap { stmt =>
        val h = StatementHead.of(stmt)
        val i = h.start
        if !h.word(i).contains("set") then None
        else
          val nameAt =
            if h.word(i + 1).exists(Set("session", "global", "local")) &&
              h.identifier(i + 2).isDefined
            then i + 2
            else i + 1
          h.identifier(nameAt).filter(NameSettings).filter { _ =>
            h.toks.drop(nameAt + 1).zipWithIndex.exists { case (t, k) =>
              val text = h.view.substring(t.start, t.end)
              // The first `=` (or TO) introduces the value; any operator, paren or cast after it
              // computes the value at run time.
              val introducer = k == 0 && (text == "=" || text.equalsIgnoreCase("to"))
              !introducer && (t.kind match
                case ai.starlake.sql.SqlLexer.Kind.Operator => true
                case ai.starlake.sql.SqlLexer.Kind.Punct    =>
                  text != "," && text != "." && text != ";"
                case _ => false)
            }
          }
      }
      .nextOption()
      .map(computedRefusal)
