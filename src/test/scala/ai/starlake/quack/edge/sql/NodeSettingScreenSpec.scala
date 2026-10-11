package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale

class NodeSettingScreenSpec extends AnyFlatSpec with Matchers:

  private def refused(sql: String): Boolean = NodeSettingScreen.screen(sql).isDefined

  private val values = Map(
    "dialect_compatibility_mode"           -> "'spark'",
    "current_dialect"                      -> "'spark'",
    "active_grammar_extensions"            -> "['g']",
    "allow_parser_override_extension"      -> "'FALLBACK'",
    "preserve_identifier_case"             -> "'uppercase'",
    "show_behavior"                        -> "'setting'",
    "table_function_identifier_conversion" -> "'DISABLE_IMPLICIT_STRING'"
  )

  "screen" should "cover exactly the parser settings it documents" in {
    values.keySet shouldBe NodeSettingScreen.ParserSettings
  }

  it should "refuse every parser setting in every SET / RESET / PRAGMA form" in
    values.foreach { case (name, value) =>
      val upper = name.toUpperCase(Locale.ROOT)
      withClue(name) {
        refused(s"SET $name = $value") shouldBe true
        refused(s"SET $name TO $value") shouldBe true
        refused(s"set $upper = $value") shouldBe true
        refused(s"SET SESSION $name = $value") shouldBe true
        refused(s"SET GLOBAL $name = $value") shouldBe true
        refused(s"SET LOCAL $name = $value") shouldBe true
        refused(s"RESET $name") shouldBe true
        refused(s"RESET GLOBAL $name") shouldBe true
        refused(s"RESET SESSION $name") shouldBe true
        refused(s"PRAGMA $name = $value") shouldBe true
        refused(s"PRAGMA $name($value)") shouldBe true
        refused(s"SET \"$name\" = $value") shouldBe true
        refused(s"SET \"$upper\" = $value") shouldBe true
        refused(s"SET `$name` = $value") shouldBe true
        refused(s"SET GLOBAL `$upper` = $value") shouldBe true
      }
    }

  it should "refuse a quoted name glued to the keyword before it (no whitespace needed on 2.0)" in
    // Each applies the setting on the 2.0 CLI: the tokenizer ends a keyword at the quote.
    values.foreach { case (name, value) =>
      withClue(name) {
        refused(s"SET\"$name\"=$value") shouldBe true
        refused(s"SET`$name`=$value") shouldBe true
        refused(s"SET GLOBAL\"$name\"=$value") shouldBe true
        refused(s"SET SESSION\"$name\" TO $value") shouldBe true
        refused(s"SET LOCAL`$name`=$value") shouldBe true
        refused(s"PRAGMA\"$name\"=$value") shouldBe true
        refused(s"RESET\"$name\"") shouldBe true
        refused(s"RESET GLOBAL`$name`") shouldBe true
        refused(s"SET/**/$name=$value") shouldBe true
        refused(s"SET/**/GLOBAL/**/\"$name\"=$value") shouldBe true
        refused(s"EXPLAIN ANALYZE SET\"$name\"=$value") shouldBe true
        refused(s"EXPLAIN(ANALYZE)SET\"$name\"=$value") shouldBe true
      }
    }

  it should "see through comments, unicode spaces and leading trivia" in {
    refused("/* x */ SET dialect_compatibility_mode = 'spark'") shouldBe true
    refused("SET/**/dialect_compatibility_mode = 'spark'") shouldBe true
    refused(
      "SET -- c\n GLOBAL /* a /* b */ c */ dialect_compatibility_mode = 'spark'"
    ) shouldBe true
    refused("SET\u00a0dialect_compatibility_mode = 'spark'") shouldBe true
    refused("\ufeff  SET\u2003show_behavior = 'setting'") shouldBe true
  }

  it should "refuse a parser setting anywhere in a batch" in {
    refused("SELECT 1; SET dialect_compatibility_mode = 'spark'") shouldBe true
    refused("BEGIN; SET GLOBAL preserve_identifier_case = 'uppercase'; COMMIT") shouldBe true
  }

  it should "refuse a parser setting behind every EXPLAIN prefix (EXPLAIN ANALYZE runs the SET)" in {
    // Verified on the 2.0 CLI: EXPLAIN ANALYZE SET dialect_compatibility_mode = 'spark' sets it.
    refused("EXPLAIN ANALYZE SET dialect_compatibility_mode = 'spark'") shouldBe true
    refused("EXPLAIN ANALYSE SET dialect_compatibility_mode = 'spark'") shouldBe true
    refused("EXPLAIN (ANALYZE true, FORMAT json) RESET show_behavior") shouldBe true
    refused("EXPLAIN SET current_dialect = 'spark'") shouldBe true
    refused("EXPLAIN (ANALYZE) SET show_behavior = 'setting'") shouldBe true
    refused("EXPLAIN ANALYZE (FORMAT json) SET show_behavior = 'setting'") shouldBe true
    refused("EXPLAIN (ANALYZE, FORMAT ')') SET show_behavior = 'setting'") shouldBe true
    refused("EXPLAIN /* ( */ ANALYZE SET show_behavior = 'setting'") shouldBe true
  }

  it should "judge only the statement an EXPLAIN carries, not a SET clause inside it" in {
    // A column named like a setting in an UPDATE's SET clause is not a setting.
    refused("EXPLAIN UPDATE t SET show_behavior = 1") shouldBe false
    refused("EXPLAIN ANALYZE UPDATE t SET current_dialect = 'x' WHERE id = 1") shouldBe false
    refused("UPDATE t SET show_behavior = 1") shouldBe false
    refused("EXPLAIN SELECT 'set current_dialect = 1' AS s") shouldBe false
    refused("EXPLAIN (FORMAT 'set show_behavior') SELECT 1") shouldBe false
  }

  it should "refuse a parser setting hidden behind a dollar-quoted or E-string splitter desync" in {
    val set = "SET GLOBAL dialect_compatibility_mode = 'spark'"
    refused(s"SELECT $$x$$'$$x$$; $set; SELECT $$y$$'$$y$$") shouldBe true
    refused(s"SELECT E'\\''; $set; SELECT E'\\''") shouldBe true
  }

  it should "read dollar-quote tags case-sensitively, lexing before folding case" in {
    // $A$ is not closed by $a$ on DuckDB 2.0, so the SET after the second $A$ runs.
    val set = "SET dialect_compatibility_mode = 'spark'"
    refused(s"EXPLAIN (ANALYZE $$A$$x$$a$$ /* $$A$$) $set -- */") shouldBe true
    // matching tags: the literal closes at $a$ and the SET sits in a comment for DuckDB too
    refused(s"EXPLAIN (ANALYZE $$a$$x$$a$$ /* $$a$$) $set -- */") shouldBe false
  }

  it should "refuse IMPORT DATABASE, which replays SQL files the edge never reads" in {
    refused("IMPORT DATABASE 's3://bucket/export'") shouldBe true
    refused("import  database\n'/tmp/export'") shouldBe true
    refused("SELECT 1; IMPORT DATABASE 'x'") shouldBe true
    NodeSettingScreen.screen("IMPORT DATABASE 'x'") shouldBe
      Some(NodeSettingScreen.ImportRefusalMessage)
  }

  it should "refuse PRAGMA import_database, the pragma form IMPORT DATABASE lowers to on 2.0" in {
    // Verified on the 2.0 CLI: PRAGMA import_database('<dir>') with a SET GLOBAL in schema.sql
    // applies the setting; only the pragma form exists (CALL / FROM import_database do not).
    val msg = Some(NodeSettingScreen.ImportRefusalMessage)
    NodeSettingScreen.screen("PRAGMA import_database('exp')") shouldBe msg
    NodeSettingScreen.screen("PRAGMA\"import_database\"('exp')") shouldBe msg
    NodeSettingScreen.screen("pragma IMPORT_DATABASE('exp')") shouldBe msg
    NodeSettingScreen.screen("SELECT 1; PRAGMA import_database('exp')") shouldBe msg
    NodeSettingScreen.screen("SELECT * FROM query('PRAGMA import_database(''exp'')')") shouldBe msg
    refused("SELECT import_database FROM t") shouldBe false
  }

  it should "refuse a statement nesting literals past the scan depth, fail-closed" in {
    // Each level is a plain string literal (quotes doubled), so the scan recurses into the body;
    // past FunctionCalls.MaxDepth it truncates, and a call hidden there could be an import_database.
    def nest(n: Int): String =
      if n == 0 then "SELECT 1" else "SELECT '" + nest(n - 1).replace("'", "''") + "'"
    NodeSettingScreen.screen(nest(9)) shouldBe Some(NodeSettingScreen.NestingRefusalMessage)
    NodeSettingScreen.screen(nest(2)) shouldBe None
  }

  it should "NOT refuse postgres_query / postgres_execute (tenant+ACL and lockdown own those)" in {
    // Owner ruling 2026-10-11: superusers keep the federation passthrough, so NodeSettingScreen
    // (every-caller) leaves these alone; CatalogReachingCalls denies them for tenant-scoped
    // principals and LockdownScreen for every caller under lockdown.
    refused("SELECT * FROM postgres_execute('c', 'SELECT 1')") shouldBe false
    refused("SELECT * FROM postgres_query('c', 'SELECT 1')") shouldBe false
    refused("CALL postgres_execute('c', 'DROP TABLE t')") shouldBe false
    refused("SELECT getenv('pgPassword')") shouldBe false
    refused("SELECT * FROM quack_query('quack:h:9494', 'SELECT 1', token := 't')") shouldBe false
  }

  it should "not read a setting name inside a literal or a comment as a SET" in {
    refused("SELECT 'SET dialect_compatibility_mode = 1' AS s") shouldBe false
    refused("SELECT $$SET GLOBAL show_behavior = 'table'$$") shouldBe false
    refused("SELECT 1 -- SET dialect_compatibility_mode = 'spark'") shouldBe false
    refused("SELECT 1 /* RESET preserve_identifier_case */") shouldBe false
    refused("SELECT current_setting('dialect_compatibility_mode')") shouldBe false
    refused("SELECT 'IMPORT DATABASE x' AS s") shouldBe false
  }

  it should "leave every other setting and statement alone" in {
    refused("SET schema = 'tpch1'") shouldBe false
    refused("SET http_timeout = 300") shouldBe false
    refused("SET VARIABLE dialect_compatibility_mode = 'spark'") shouldBe false
    refused("SET search_path = 'show_behavior'") shouldBe false
    refused("SET dialect_compatibility_mode_x = 1") shouldBe false
    refused("RESET threads") shouldBe false
    refused("PRAGMA table_info('t')") shouldBe false
    refused("CREATE TABLE show_behavior(a INT)") shouldBe false
    refused("EXPORT DATABASE 's3://bucket/export'") shouldBe false
    refused("SELECT * FROM import_log") shouldBe false
  }

  it should "refuse the credential-exposing settings in every form, glued quotes included" in
    // Verified on 2.0: SET redact_http_logs = false logs an http secret's bearer token unredacted.
    NodeSettingScreen.SecretSettings.foreach { name =>
      withClue(name) {
        val msg = Some(NodeSettingScreen.SecretRefusalMessage)
        NodeSettingScreen.screen(s"SET $name = false") shouldBe msg
        NodeSettingScreen.screen(s"SET GLOBAL $name = false") shouldBe msg
        NodeSettingScreen.screen(s"SET SESSION\"$name\" TO false") shouldBe msg
        NodeSettingScreen.screen(s"SET`$name`=false") shouldBe msg
        NodeSettingScreen.screen(s"RESET $name") shouldBe msg
        NodeSettingScreen.screen(s"PRAGMA\"$name\"=false") shouldBe msg
        NodeSettingScreen.screen(s"EXPLAIN ANALYZE SET $name = false") shouldBe msg
        NodeSettingScreen.screen(s"SELECT 1; SET $name = false") shouldBe msg
      }
    }

  it should "leave the other logging settings to lockdown" in {
    NodeSettingScreen.Settings shouldBe
      (NodeSettingScreen.ParserSettings ++ Set("redact_http_logs", "allow_unredacted_secrets"))
    refused("SET logging_level = 'DEBUG'") shouldBe false
    refused("SELECT current_setting('redact_http_logs')") shouldBe false
  }

  it should "carry the documented refusal message" in {
    NodeSettingScreen.screen("SET dialect_compatibility_mode = 'spark'") shouldBe
      Some(NodeSettingScreen.RefusalMessage)
  }

class NodeWideSettingScreenSpec extends AnyFlatSpec with Matchers:

  private def refused(sql: String): Boolean = NodeSettingScreen.screenNodeWide(sql).isDefined

  "screenNodeWide" should "refuse the node-wide settings in every SET / RESET / PRAGMA form" in
    Seq(
      "threads"                        -> "2",
      "worker_threads"                 -> "2",
      "external_threads"               -> "2",
      "async_threads"                  -> "2",
      "memory_limit"                   -> "'100GB'",
      "max_memory"                     -> "'100GB'",
      "max_temp_directory_size"        -> "'1TB'",
      "temp_directory"                 -> "'/tmp/x'",
      "allocator_flush_threshold"      -> "'1MB'",
      "checkpoint_threshold"           -> "'1GB'",
      "wal_autocheckpoint"             -> "'1GB'",
      "enable_profiling"               -> "'json'",
      "profiling_output"               -> "'/tmp/p'",
      "quack_server_max_connections"   -> "1",
      "quack_debug_drop_batch"         -> "true",
      "quack_debug_send_delay_ms"      -> "60000",
      "debug_checkpoint_scan_sleep_ms" -> "1000",
      "pg_pool_max_connections"        -> "1",
      "enable_logging"                 -> "true",
      "disabled_optimizers"            -> "'filter_pushdown'"
    ).foreach { case (name, value) =>
      val upper = name.toUpperCase(java.util.Locale.ROOT)
      withClue(name) {
        refused(s"SET $name = $value") shouldBe true
        refused(s"SET $name TO $value") shouldBe true
        refused(s"set $upper = $value") shouldBe true
        refused(s"SET SESSION $name = $value") shouldBe true
        refused(s"SET LOCAL $name = $value") shouldBe true
        refused(s"RESET $name") shouldBe true
        refused(s"RESET SESSION $name") shouldBe true
        refused(s"PRAGMA $name = $value") shouldBe true
        refused(s"PRAGMA $name($value)") shouldBe true
        refused(s"""SET"$name"=$value""") shouldBe true
        refused(s"SET`$name`=$value") shouldBe true
        refused(s"SET/* c */$name = $value") shouldBe true
        refused(s"EXPLAIN ANALYZE SET $name = $value") shouldBe true
        refused(s"SELECT 1; SET $name = $value") shouldBe true
      }
    }

  it should "refuse GLOBAL scope for any setting, and the node-wide pragmas and calls" in
    Seq(
      "SET GLOBAL TimeZone = 'UTC'",
      "RESET GLOBAL search_path",
      "SET GLOBAL\"http_timeout\" = 1",
      "PRAGMA enable_profiling",
      "PRAGMA verify_parallelism",
      "PRAGMA disable_verify_parallelism",
      "PRAGMA enable_checkpoint_on_shutdown",
      "PRAGMA force_checkpoint",
      "FORCE CHECKPOINT",
      "SELECT * FROM quack_stop('quack:0.0.0.0:21900')",
      "SELECT * FROM quack_serve('quack:0.0.0.0:9999', token := 'x')",
      "CALL quack_serve('quack:0.0.0.0:9999', token := 'x')",
      "SELECT * FROM quack_identify('n', 'p', 'h', 'r', 'm')",
      "SELECT * FROM quack_active_connections()",
      "SELECT query FROM quack_active_connections() WHERE state = 'running'",
      "SELECT count(*) FROM \"quack_active_connections\"()",
      "FROM quack_active_connections()",
      "SELECT * FROM quack_server_list()",
      "SELECT * FROM quack_cancel('qod', '42')",
      "SELECT * FROM query('SELECT * FROM quack_active_connections()')",
      "SELECT * FROM quack_clear_cache()",
      "SELECT * FROM pg_clear_cache()",
      "SELECT * FROM enable_logging()",
      "SELECT * FROM truncate_duckdb_logs()",
      "SELECT * FROM query('SELECT * FROM quack_stop(''quack:0.0.0.0:21900'')')"
    ).foreach(sql => withClue(sql)(refused(sql) shouldBe true))

  it should "admit the session settings an ordinary client sends" in
    Seq(
      "SET search_path = 'lake.main'",
      "SET schema = 'main'",
      "SET TimeZone = 'Europe/Paris'",
      "SET SESSION TimeZone = 'UTC'",
      "SET Calendar = 'gregorian'",
      "SET http_timeout = 300",
      "SET default_order = 'DESC'",
      "SET default_null_order = 'nulls_first'",
      "SET preserve_insertion_order = false",
      "SET enable_progress_bar = false",
      "SET errors_as_json = true",
      "SET max_execution_time = '10s'",
      "SET VARIABLE x = 42",
      "RESET VARIABLE x",
      "RESET search_path",
      "CHECKPOINT",
      "SELECT current_setting('threads')",
      "SELECT * FROM duckdb_settings() WHERE name = 'memory_limit'",
      "UPDATE t SET threads = 2"
    ).foreach(sql => withClue(sql)(refused(sql) shouldBe false))

  "NodeWideSettings" should "not overlap the settings refused for every caller" in {
    NodeSettingScreen.NodeWideSettings.intersect(NodeSettingScreen.Settings) shouldBe empty
  }

  // A node's init SQL reaches its DuckDB CLI through stdin, so every line lands in the CLI's shell
  // history: CREATE SECRET with the object-store keys, the ATTACH carrying the catalog password,
  // quack_serve with the node token. On the 2.0 CLI a quack client's
  // `quack_query(.., 'SELECT id, sql FROM shell_history()')` printed all of them.
  "shell_history" should "be refused to every caller, in every spelling" in
    List(
      "SELECT * FROM shell_history()",
      "SELECT sql FROM SHELL_HISTORY() WHERE sql ILIKE '%secret%'",
      "SELECT * FROM \"shell_history\"()",
      "SELECT * FROM `shell_history`()",
      "SELECT * FROM system.main.shell_history()",
      "SELECT 1; FROM shell_history()",
      "EXPLAIN ANALYZE SELECT * FROM shell_history()",
      "SELECT (SELECT max(sql) FROM shell_history())"
    ).foreach { sql =>
      withClue(sql) {
        NodeSettingScreen.screen(sql) shouldBe Some(NodeSettingScreen.SecretCallRefusalMessage)
      }
    }

  // A literal body is scanned as SQL too (`query('..')` runs one), so `'shell_history()'` over-denies.
  it should "leave a column of that name alone" in {
    NodeSettingScreen.screen("SELECT shell_history FROM t") shouldBe None
  }
