package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[AccessGate.rewriteReachesBeyond]]: the router runs the statement an edge rewriter printed, not
  * the one the gate authorized, so a rewrite reaching anything the original did not is refused.
  */
class AccessGateRewriteSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("lake"), Some("tpch1"), Set("lake"))

  private def beyond(original: String, rewritten: String): Option[String] =
    AccessGate.rewriteReachesBeyond(original, rewritten, cfg, "lake", clientDataScans = false)

  "a rewrite" should "be refused when a CTE reference was printed schema-qualified" in {
    beyond(
      "WITH secret AS (SELECT c_id AS v FROM customer WHERE c_email LIKE '%') SELECT v FROM secret",
      "WITH secret AS (SELECT c_id AS v FROM customer WHERE '***' LIKE '%') " +
        "SELECT v FROM tpch1.secret"
    ).getOrElse(fail("admitted")) should include("lake.tpch1.secret")
  }

  it should "be refused when it reaches another table or verb, or cannot be authorized" in {
    beyond("SELECT a FROM t", "SELECT a FROM t, u") shouldBe defined
    beyond("SELECT a FROM t", "SELECT a FROM t WHERE a IN (SELECT b FROM u)") shouldBe defined
    beyond("SELECT a FROM t", "INSERT INTO t SELECT a FROM t") shouldBe defined
    beyond("SELECT a FROM t", "SELECT a FROM read_parquet('/x')") shouldBe defined
    beyond("SELECT a FROM t", "SELECT a FROM (") shouldBe defined
  }

  it should "admit the masking, row filter and metadata substitutions" in {
    beyond("SELECT c_email FROM customer", "SELECT '***' AS c_email FROM customer") shouldBe None
    beyond(
      "SELECT * FROM customer c",
      "SELECT * FROM (SELECT * FROM customer WHERE c_region = 'EU') c"
    ) shouldBe None
    beyond(
      "SELECT * FROM information_schema.tables",
      "SELECT * FROM (SELECT * FROM information_schema.tables WHERE table_catalog = 'lake') t"
    ) shouldBe None
    beyond(
      "SELECT * FROM duckdb_tables()",
      "SELECT * FROM (SELECT * FROM duckdb_tables() WHERE database_name = 'lake') duckdb_tables"
    ) shouldBe None
    // SHOW TABLES is control flow; the filter substitutes a filtered metadata read for it
    beyond(
      "SHOW TABLES",
      "SELECT table_name AS name FROM information_schema.tables WHERE table_catalog = 'lake'"
    ) shouldBe None
  }

  it should "leave an original it cannot compare to the router's re-validation" in {
    beyond("SELECT * FROM read_parquet('/x')", "SELECT * FROM read_parquet('/y')") shouldBe None
  }
