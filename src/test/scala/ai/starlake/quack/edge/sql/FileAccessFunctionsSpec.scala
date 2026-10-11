package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.InProcessDuckDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.io.Source

/** Pins the derivation of [[FileAccessScreen.FileFunctions]]: every DuckDB 2.0 table function, with
  * every extension a node loads, must be judged for tenant principals, either refused (a file
  * reader, a catalog-reaching or node-escaping call, a node-wide or credential-exposing call) or
  * admitted on purpose ([[FileAccessScreen.NoFileFunctions]]). A function nobody judged is how
  * `sql_auto_complete` (a directory listing) and `shell_history` (the node's init SQL) went
  * unscreened: a new 2.0 table function fails this spec until someone decides.
  */
class FileAccessFunctionsSpec extends AnyFlatSpec with Matchers:

  private def unjudged(names: Iterable[String]): List[String] =
    names.filterNot(FileAccessScreen.judged).toList.distinct.sorted

  "the 2.0 CLI's table functions, every node extension loaded" should "all be judged" in {
    val src   = Source.fromResource("lockdown/duckdb-2.0-table-functions.csv")
    val names =
      try src.getLines().filterNot(l => l.startsWith("#") || l.isBlank).map(_.split(',')(0)).toList
      finally src.close()
    names should contain allOf ("sql_auto_complete", "shell_history", "read_csv")
    unjudged(names) shouldBe Nil
  }

  "the bundled DuckDB's own table functions" should "all be judged" in {
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    try
      val st = conn.createStatement()
      val rs = st.executeQuery(
        "SELECT DISTINCT function_name FROM duckdb_functions() " +
          "WHERE function_type IN ('table', 'table_macro')"
      )
      val names =
        Iterator.continually(rs.next()).takeWhile(identity).map(_ => rs.getString(1)).toList
      rs.close()
      st.close()
      names should not be empty
      unjudged(names) shouldBe Nil
    finally conn.close()
  }

  "sql_auto_complete" should "be refused: it lists node directories" in {
    FileAccessScreen.FileFunctions should contain("sql_auto_complete")
    FileAccessScreen.find("SELECT * FROM sql_auto_complete('SELECT * FROM ''/etc/ho')") shouldBe
      defined
  }

/** The node-state judgment of every DuckDB 2.0 introspection table function and system view: what a
  * tenant principal learns from it, and the verdict. "refused" entries must be exactly
  * [[NodeStateScreen.Refused]]; every introspection function the file screen admits
  * ([[FileAccessScreen.NoFileFunctions]]) and every default system view
  * (`TableExtractor.DuckDbDefaultViews`) must be judged here, so the list stays exhaustive. Read
  * off the 2.0 CLI with every node extension loaded (DESCRIBE of each, a secret and an attached
  * file present).
  */
class NodeStateJudgmentSpec extends AnyFlatSpec with Matchers:

  private def refused(why: String)  = ("refused", why)
  private def admitted(why: String) = ("admitted", why)

  private val Judgments: Map[String, (String, String)] = Map(
    // credentials, their names, node identity
    "duckdb_secrets" -> refused("name, scope, key_id, Postgres host / user / db, DuckLake paths"),
    "which_secret"   -> refused("names the secret serving a path"),
    "whoami"         -> refused("node cloud identity, hostname, region, uptime"),
    // setting values (s3_secret_access_key, http_proxy_password, directories: unredacted on 2.0)
    "duckdb_settings" -> refused("every setting value, credentials and directories included"),
    "pg_settings"     -> refused("view over duckdb_settings"),
    // other sessions and node files
    "duckdb_logs"                   -> refused("other sessions' logged statements"),
    "duckdb_logs_parsed"            -> refused("other sessions' logged statements"),
    "duckdb_log_contexts"           -> refused("connection, transaction and query ids"),
    "duckdb_external_file_cache"    -> refused("path of every file any session read"),
    "duckdb_temporary_files"        -> refused("spill file paths"),
    "duckdb_databases"              -> refused("attached database paths and options"),
    "pragma_database_list"          -> refused("attached database paths"),
    "duckdb_extensions"             -> refused("install paths, origins, key fingerprints"),
    "duckdb_extension_repositories" -> refused("repository locations and public keys"),
    "duckdb_external_resources"     -> refused("resource URIs, references and handles"),
    "pragma_storage_info" -> refused("segment statistics (min / max) of a string-named table"),
    // the caller's own session
    "duckdb_variables"           -> admitted("the caller's own session variables"),
    "duckdb_prepared_statements" -> admitted("the caller's own prepared statements"),
    "pg_prepared_statements"     -> admitted("view over duckdb_prepared_statements"),
    "duckdb_profiling_settings"  -> admitted("the caller's own profiling configuration"),
    // aggregates naming nothing
    "duckdb_memory"                -> admitted("byte counts per buffer tag"),
    "duckdb_connection_count"      -> admitted("a count of connections, no ids"),
    "duckdb_approx_database_count" -> admitted("a count"),
    "duckdb_eviction_queues"       -> admitted("buffer eviction counters"),
    "pragma_database_size"         -> admitted("sizes and block counts of the attached databases"),
    "pragma_metadata_info"         -> admitted("block ids of the default database"),
    // static catalogs of DuckDB itself
    "duckdb_available_metrics"       -> admitted("static metric list"),
    "duckdb_dialects"                -> admitted("static dialect list"),
    "duckdb_external_resource_types" -> admitted("static resource type list"),
    "duckdb_functions"          -> admitted("function catalog (macros of attached catalogs too)"),
    "duckdb_grammar_extensions" -> admitted("static grammar extension list"),
    "duckdb_keywords"           -> admitted("static keyword list"),
    "duckdb_optimizers"         -> admitted("static optimizer list"),
    "duckdb_secret_type_parameters" -> admitted("static parameter list of secret types"),
    "duckdb_secret_types"           -> admitted("static secret type list"),
    "duckdb_types"                  -> admitted("type catalog"),
    "pragma_collations"             -> admitted("static collation list"),
    "pragma_platform"               -> admitted("platform string"),
    "pragma_user_agent"             -> admitted("DuckDB user agent"),
    "pragma_version"                -> admitted("DuckDB version"),
    "icu_calendar_names"            -> admitted("static calendar list"),
    "pg_timezone_names"             -> admitted("static time zone list"),
    "tpch_answers"                  -> admitted("static TPC-H text"),
    "tpch_queries"                  -> admitted("static TPC-H text"),
    // catalog metadata: gated as before by the ACL and the metadata filter
    "duckdb_columns"            -> admitted("catalog metadata, metadata filter"),
    "duckdb_tables"             -> admitted("catalog metadata, metadata filter"),
    "duckdb_views"              -> admitted("catalog metadata, metadata filter"),
    "duckdb_schemas"            -> admitted("catalog metadata, metadata filter"),
    "duckdb_constraints"        -> admitted("catalog metadata, ACL-gated as a system view"),
    "duckdb_dependencies"       -> admitted("catalog metadata, ACL-gated"),
    "duckdb_indexes"            -> admitted("catalog metadata, ACL-gated"),
    "duckdb_sequences"          -> admitted("catalog metadata, ACL-gated"),
    "duckdb_triggers"           -> admitted("catalog metadata, ACL-gated"),
    "duckdb_coordinate_systems" -> admitted("catalog metadata (CRS per schema), ACL-gated"),
    "pragma_table_info"         -> admitted("columns of a named table, ACL-gated table function"),
    "pragma_show"               -> admitted("columns of a named table, ACL-gated table function"),
    // generators, tokenizers, scans of an argument, test tables
    "arrow_scan"                  -> admitted("Arrow pointer"),
    "arrow_scan_dumb"             -> admitted("Arrow pointer"),
    "check_peg_parser"            -> admitted("parses its argument"),
    "sql_tokenize"                -> admitted("tokenizes its argument"),
    "json_each"                   -> admitted("walks its JSON argument"),
    "json_tree"                   -> admitted("walks its JSON argument"),
    "generate_series"             -> admitted("generator"),
    "range"                       -> admitted("generator"),
    "repeat"                      -> admitted("generator"),
    "repeat_row"                  -> admitted("generator"),
    "unnest"                      -> admitted("unnests its argument"),
    "summary"                     -> admitted("summarizes its input table"),
    "histogram"                   -> admitted("histogram of its input"),
    "histogram_values"            -> admitted("histogram of its input"),
    "seq_scan"                    -> admitted("test scan"),
    "test_all_types"              -> admitted("static test table"),
    "test_vector_types"           -> admitted("test table of its argument"),
    "checkpoint"                  -> admitted("checkpoints a catalog, no output state"),
    "iceberg_scan_tasks"          -> admitted("tasks of a scan plan read by a refused reader"),
    "scan_data_from_quack_client" -> admitted("the front door client's own stream")
  )

  private val views = ai.starlake.acl.parser.TableExtractor.DuckDbDefaultViews

  "the node-state judgments" should "refuse exactly what NodeStateScreen refuses" in {
    Judgments.collect { case (n, ("refused", _)) =>
      n
    }.toSet shouldBe NodeStateScreen.Refused.keySet
  }

  it should "cover every introspection function the file screen admits" in {
    (FileAccessScreen.NoFileFunctions -- Judgments.keySet) shouldBe empty
  }

  it should "cover every node-level system view" in {
    // the remaining default views (sqlite_*, pg_* catalog views) are catalog metadata, gated as a
    // bare system view by the ACL
    (views.filter(v =>
      v.startsWith("duckdb_") || v.startsWith("pragma_") || v == "pg_settings" ||
        v == "pg_prepared_statements"
    ) -- Judgments.keySet) shouldBe empty
  }

  it should "hold for every spelling NodeStateScreen reads" in
    Judgments.foreach { case (name, (verdict, _)) =>
      val forms =
        List(s"SELECT * FROM $name()", s"SELECT * FROM $name", s"SELECT * FROM \"$name\"()")
      forms.foreach { sql =>
        withClue(sql)(NodeStateScreen.find(sql).isDefined shouldBe (verdict == "refused"))
      }
    }

  "NodeStateScreen" should "refuse the PRAGMA forms, any quoting, and literal bodies" in {
    List(
      "PRAGMA database_list",
      "PRAGMA storage_info('customer')",
      "PRAGMA extension_versions",
      "SELECT * FROM system.main.duckdb_secrets()",
      "SELECT * FROM `duckdb_settings`()",
      "SELECT name, value FROM duckdb_settings WHERE name LIKE 's3%'",
      "SELECT * FROM query('SELECT * FROM duckdb_secrets()')",
      "SELECT 1; SELECT * FROM whoami()",
      "SELECT current_setting('s3_secret_access_key')",
      "SELECT current_setting('s3_' || 'secret_access_key')",
      "SELECT current_setting(name) FROM t",
      "SELECT current_setting('TimeZone', 'x')"
    ).foreach(sql => withClue(sql)(NodeStateScreen.find(sql) shouldBe defined))
    List(
      "SELECT current_setting('TimeZone')",
      "SELECT current_setting( 'search_path' )",
      "SELECT * FROM duckdb_memory()",
      "PRAGMA table_info('orders')",
      "PRAGMA version",
      "SELECT * FROM orders"
    ).foreach(sql => withClue(sql)(NodeStateScreen.find(sql) shouldBe None))
  }
