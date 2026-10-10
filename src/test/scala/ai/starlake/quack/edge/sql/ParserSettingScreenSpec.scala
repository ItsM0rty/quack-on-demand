package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale

class ParserSettingScreenSpec extends AnyFlatSpec with Matchers:

  private def refused(sql: String): Boolean = ParserSettingScreen.screen(sql).isDefined

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
    values.keySet shouldBe ParserSettingScreen.Settings
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
    ParserSettingScreen.screen("IMPORT DATABASE 'x'") shouldBe
      Some(ParserSettingScreen.ImportRefusalMessage)
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

  it should "carry the documented refusal message" in {
    ParserSettingScreen.screen("SET dialect_compatibility_mode = 'spark'") shouldBe
      Some(ParserSettingScreen.RefusalMessage)
  }
