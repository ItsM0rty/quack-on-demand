package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CatalogInternalsScreenSpec extends AnyFlatSpec with Matchers:

  private def refused(sql: String): Boolean = CatalogInternalsScreen.screen(sql).isDefined

  "screen" should "refuse the DuckLake metadata catalog in any quoting, case or position" in
    Seq(
      "SELECT encryption_key FROM __ducklake_metadata_lake.ducklake_data_file",
      "SELECT * FROM __ducklake_metadata_lake.main.ducklake_delete_file",
      """SELECT * FROM "__DuckLake_Metadata_LAKE".main.ducklake_data_file""",
      "SELECT * FROM `__ducklake_metadata_lake`.ducklake_data_file",
      "USE __ducklake_metadata_lake",
      "USE \"__ducklake_metadata_lake\".main",
      "SET search_path = '__ducklake_metadata_lake'",
      "SET SESSION schema = '__ducklake_metadata_lake.main'",
      "SELECT * FROM postgres_query('__ducklake_metadata_lake', 'SELECT * FROM ducklake_data_file')",
      "SELECT * FROM query_table('__ducklake_metadata_lake.ducklake_data_file')",
      "SELECT * FROM duckdb_table_sample('__ducklake_metadata_lake.ducklake_data_file')",
      "SELECT * FROM json_execute_serialized_sql('{\"x\": \"__ducklake_metadata_lake\"}')",
      "SELECT * FROM query('SELECT * FROM __ducklake_metadata_lake.ducklake_data_file')",
      "SELECT * FROM query(e'SELECT * FROM \\x5f\\x5fducklake_metadata_lake.ducklake_data_file')",
      "CREATE VIEW v AS SELECT * FROM __ducklake_metadata_lake.ducklake_data_file",
      "EXPLAIN ANALYZE SELECT * FROM __ducklake_metadata_lake.ducklake_data_file",
      "PREPARE p AS SELECT * FROM __ducklake_metadata_lake.ducklake_data_file",
      "SELECT 1; SELECT * FROM __ducklake_metadata_lake.ducklake_data_file",
      "SELECT * FROM __ducklake_metadata_lake . ducklake_data_file",
      "DETACH __ducklake_metadata_lake"
    ).foreach(sql => withClue(sql)(refused(sql) shouldBe true))

  it should "refuse the DuckLake functions that return file encryption keys or raw catalog rows" in
    Seq(
      "SELECT * FROM ducklake_list_files('lake', 't')",
      "SELECT data_file_encryption_key FROM \"DuckLake_List_Files\"('lake', 't')",
      "SELECT * FROM `ducklake_list_files`('lake', 't')",
      "SELECT * FROM system.main.ducklake_list_files('lake', 't')",
      "SELECT * FROM ducklake_commit('m', 1)",
      "SELECT * FROM query('SELECT * FROM ducklake_list_files(''lake'', ''t'')')"
    ).foreach(sql => withClue(sql)(refused(sql) shouldBe true))

  it should "refuse json_execute_serialized_sql outright, whatever plan it carries" in {
    // A serialized plan names its catalogs inside JSON strings, which DuckDB decodes (`\u005f`
    // escapes included) after the screen has read them: no lexical read of the literal is sound.
    Seq(
      "SELECT * FROM json_execute_serialized_sql('{\"catalog_name\":" +
        "\"\\u005f_ducklake_metadata_lake\"}')",
      "SELECT * FROM json_execute_serialized_sql('{}')",
      "SELECT * FROM json_execute_serialized_sql(json_serialize_sql('SELECT 1'))",
      "SELECT * FROM \"JSON_EXECUTE_SERIALIZED_SQL\"('{}')",
      "SELECT * FROM system.main.json_execute_serialized_sql('{}')",
      "PRAGMA json_execute_serialized_sql('{}')",
      "EXPLAIN ANALYZE SELECT * FROM json_execute_serialized_sql('{}')",
      "SELECT * FROM query('SELECT * FROM json_execute_serialized_sql(''{}'')')"
    ).foreach { sql =>
      withClue(sql)(
        CatalogInternalsScreen.screen(sql) shouldBe Some(
          CatalogInternalsScreen.SerializedPlanRefusal
        )
      )
    }
    // Serializing is harmless: it returns text and executes nothing.
    refused("SELECT json_serialize_sql('SELECT 1')") shouldBe false
  }

  it should "refuse a run-time-computed target" in
    Seq(
      "SELECT * FROM query_table('__duck' || 'lake_metadata_lake.ducklake_data_file')",
      "SELECT * FROM query('SELECT * FROM ' || getvariable('t'))",
      "SELECT * FROM postgres_query(concat('__duck', 'lake_metadata_lake'), 'SELECT 1')",
      "SELECT * FROM query_table(getvariable('t'))",
      "SELECT * FROM duckdb_table_sample('__duck' || 'lake_metadata_lake.ducklake_data_file')",
      "SELECT * FROM json_execute_serialized_sql(getvariable('plan'))",
      "SELECT * FROM query_table('x'::VARCHAR)",
      "SELECT * FROM destroy_external_resource('query' || '_table', 'x')",
      "SET search_path = '__duck' || 'lake_metadata_lake'",
      "SET search_path = concat('a', 'b')",
      "SET GLOBAL schema = getvariable('s')"
    ).foreach(sql => withClue(sql)(refused(sql) shouldBe true))

  it should "admit ordinary reads, the data tables, constant targets and the other DuckLake calls" in
    Seq(
      "SELECT * FROM lake.main.customer",
      "SELECT * FROM customer WHERE name = 'ducklake'",
      "SELECT * FROM ducklake_snapshots('lake')",
      "SELECT * FROM ducklake_table_info('lake')",
      "SELECT * FROM ducklake_table_insertions('lake', 'main', 't', 0, 3)",
      "SELECT * FROM ducklake_expire_snapshots('lake', dry_run => true, versions => [1])",
      "SELECT * FROM lake.main.t AT (VERSION => 3)",
      "SELECT * FROM query_table('lake.main.customer')",
      "SELECT * FROM duckdb_table_sample('lake.main.customer')",
      "SELECT * FROM query_table(['a', 'b'], true)",
      "SELECT * FROM query('SELECT 42')",
      "SELECT * FROM postgres_query('pg', 'SELECT -1', use_transaction := false)",
      "SET search_path = 'lake.main,memory.main'",
      "SET search_path TO lake",
      "SET schema = 'main';",
      "SET http_timeout = 300"
    ).foreach(sql => withClue(sql)(refused(sql) shouldBe false))
