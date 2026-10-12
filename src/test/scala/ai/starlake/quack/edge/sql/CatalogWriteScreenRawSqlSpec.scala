package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.quack.route.StatementClassifier
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The raw-SQL passthrough rule of [[CatalogWriteScreen]]: `postgres_execute` / `postgres_query` /
  * `mysql_execute` / `mysql_query` against a read-only catalog could end the remote's read-only
  * transaction (`CALL postgres_execute('<alias>', 'COMMIT')` then a write ran the write on the 2.0
  * CLI). The screen runs for every caller, superusers included, so these cases hold for all.
  */
class CatalogWriteScreenRawSqlSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val attached = Set("acme_db", "shop", "pg_rw", "memory", "system", "temp")
  private val cfg      = Config.forDuckDB(Some("acme_db"), Some("main"), attached)
  private val readOnly = Set("shop")

  private def screen(sql: String, ro: Set[String] = readOnly): Option[String] =
    CatalogWriteScreen.screen(sql, StatementClassifier.default.classify, ro, cfg)

  private val functions = CatalogWriteScreen.RawSqlFunctions.toList.sorted

  "the raw-SQL rule" should "refuse each function, CALL and table-function forms, on a read-only catalog" in {
    for f <- functions do
      withClue(f) {
        screen(s"CALL $f('shop', 'COMMIT')").value should include("read-only")
        screen(s"SELECT * FROM $f('shop', 'SELECT 1')").value should include("shop")
      }
  }

  it should "refuse the COMMIT-then-write sequence the engine read-only mode does not stop" in {
    screen(
      "BEGIN; CALL postgres_execute('shop', 'COMMIT'); " +
        "CALL postgres_execute('shop', 'INSERT INTO public.misc VALUES (5)'); COMMIT"
    ) should not be None
  }

  it should "match the catalog case-insensitively and through every literal form" in {
    for arg <- List("'SHOP'", "'Shop'", "E'sh\\x6fp'", "$$shop$$", "$t$shop$t$", "N'shop'") do
      withClue(arg)(screen(s"CALL postgres_execute($arg, 'COMMIT')") should not be None)
  }

  it should "match the function name through every quoting, case and qualification" in {
    for call <- List(
        "POSTGRES_EXECUTE",
        "\"postgres_execute\"",
        "`postgres_execute`",
        "system.main.postgres_execute",
        "\"System\".\"Main\".\"POSTGRES_EXECUTE\"",
        "postgres_execute /* c */ "
      )
    do withClue(call)(screen(s"CALL $call('shop', 'COMMIT')") should not be None)
  }

  it should "see the call through EXPLAIN, PREPARE and a query() string body" in {
    screen("EXPLAIN SELECT * FROM postgres_query('shop', 'SELECT 1')") should not be None
    screen("PREPARE p AS SELECT * FROM mysql_query('shop', 'SELECT 1')") should not be None
    screen("SELECT * FROM query('CALL postgres_execute(''shop'', ''COMMIT'')')") should not be
      None
  }

  it should "refuse a catalog argument that is not a plain string literal" in {
    for arg <- List(
        "'sh' || 'op'",
        "lower('SHOP')",
        "$1",
        "?",
        "some_column",
        "\"shop\"",
        "x'73686f70'",
        "CAST('shop' AS VARCHAR)"
      )
    do
      withClue(arg)(
        screen(s"CALL postgres_execute($arg, 'COMMIT')").value should include(
          "not a string literal"
        )
      )
  }

  it should "refuse a literal continuation that folds to the read-only catalog" in {
    screen("CALL postgres_execute('sh'\n'op', 'COMMIT')") should not be None
  }

  it should "leave a call to a writable catalog untouched, superuser passthrough included" in {
    for f <- functions do
      withClue(f) {
        screen(s"CALL $f('pg_rw', 'INSERT INTO t VALUES (1)')") shouldBe None
        screen(s"SELECT * FROM $f('pg_rw', 'SELECT 1')") shouldBe None
      }
  }

  it should "be inert when nothing on the pool is read-only, even for a computed argument" in {
    screen("CALL postgres_execute('shop', 'COMMIT')", Set.empty) shouldBe None
    screen("CALL postgres_execute('sh' || 'op', 'COMMIT')", Set.empty) shouldBe None
  }

  it should "not read a mention in a comment or a quoted identifier as a call" in {
    screen("SELECT 1 /* postgres_execute('shop', 'COMMIT') */") shouldBe None
    screen("SELECT \"postgres_execute\" FROM acme_db.main.t") shouldBe None
  }

  it should "refuse whatever the catalog when an argument is computed and any catalog is read-only" in {
    // pg_rw is writable, but the screen cannot know the concatenation does not yield 'shop'.
    screen("CALL postgres_execute('pg' || '_rw', 'SELECT 1')") should not be None
  }

  "FunctionCalls.firstStringArgument" should "decode exactly one VARCHAR literal argument" in {
    def first(sql: String) =
      FunctionCalls.scan(sql).calls.find(_.name == "f").flatMap(FunctionCalls.firstStringArgument)
    first("SELECT f('a''b', 1)") shouldBe Some("a'b")
    first("SELECT f(e'\\x41', 1)") shouldBe Some("A")
    first("SELECT f($q$x$q$)") shouldBe Some("x")
    first("SELECT f('a' || 'b')") shouldBe None
    first("SELECT f()") shouldBe None
    first("SELECT f(b'0101')") shouldBe None
  }
