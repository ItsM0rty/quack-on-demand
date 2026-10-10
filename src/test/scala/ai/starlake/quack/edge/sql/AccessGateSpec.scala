package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.Verb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AccessGateSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch"))

  private def eval(sql: String, fm: Boolean = false) = AccessGate.evaluate(sql, cfg, "tpch", fm)

  "AccessGate" should "gate every table a join touches" in:
    eval("SELECT * FROM orders o JOIN lineitem l ON o.k = l.k") match
      case GateOutcome.Gated(g, _) => g.map(_.table.table) shouldBe Set("orders", "lineitem")
      case other                   => fail(other.toString)

  it should "mark writes" in:
    eval("INSERT INTO orders SELECT * FROM lineitem") match
      case GateOutcome.Gated(g, _) =>
        g.map(a => a.table.table -> a.verb) shouldBe Set(
          "orders"   -> Verb.Write,
          "lineitem" -> Verb.Read
        )
      case other => fail(other.toString)

  it should "gate nothing for control flow" in:
    eval("COMMIT") shouldBe a[GateOutcome.NothingGated]

  it should "refuse a parse error as wildcard-coverable" in:
    eval("SELECT FROM WHERE (") match
      case GateOutcome.Refuse(_, true, _) => succeed
      case other                          => fail(other.toString)

  it should "refuse a catalog-reaching call as not wildcard-coverable" in:
    eval("SELECT * FROM read_parquet('s3://b/x.parquet')") match
      case GateOutcome.Refuse(_, _, _) => succeed
      case other                       => fail(other.toString)

  it should "refuse a query_table call as not wildcard-coverable" in:
    eval("SELECT * FROM query_table('orders')") match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  it should "find a catalog-reaching call behind dollar-quote tags differing only in case" in:
    // DuckDB 2.0 compares dollar tags case-sensitively: $A$ is not closed by $a$, so the call after
    // the block-comment opener runs. Folding case before lexing read it as a comment.
    CatalogReachingCalls.find(
      "SELECT $A$x$a$ /* $A$, * FROM query_table('orders') -- */"
    ) shouldBe Some("query_table")
    // matching tags: the call sits in a comment for DuckDB too
    CatalogReachingCalls.find(
      "SELECT $a$x$a$ /* $a$, * FROM query_table('orders') -- */"
    ) shouldBe None

  it should "find a catalog-reaching call under every DuckDB 2.0 identifier quoting" in:
    // DuckDB 2.0 accepts backtick-quoted function names: `query_table`('orders') runs on the 2.0
    // CLI. A call is an identifier token, bare, double-quoted or backtick-quoted and possibly
    // qualified, followed past any trivia by an opening parenthesis token.
    CatalogReachingCalls.find("SELECT * FROM `query_table`('orders')") shouldBe Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM `QUERY`('SELECT 1')") shouldBe Some("query")
    CatalogReachingCalls.find("SELECT * FROM \"query_table\"('orders')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM `system`.`main`.`query_table`('orders')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM main.`query_table` /* c */ ('orders')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM `ducklake_snapshots`('cat')") shouldBe
      Some("ducklake_snapshots")
    eval("SELECT * FROM `query_table`('orders')") match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  it should "not treat a backtick-quoted identifier with no parenthesis as a catalog-reaching call" in:
    CatalogReachingCalls.find("SELECT `query_table`, `query` FROM t") shouldBe None
    CatalogReachingCalls.find("SELECT \"query\" FROM t") shouldBe None

  it should "refuse an ambiguous two-part name on an attached catalog as not wildcard-coverable" in:
    val attached = Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch", "fedpg"))
    AccessGate.evaluate("SELECT * FROM fedpg.orders", attached, "tpch", false) match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  it should "admit a pure info-schema read only with filtered metadata on" in:
    eval("SELECT table_name FROM information_schema.tables", fm = true) shouldBe
      a[GateOutcome.NothingGated]
    eval("SELECT table_name FROM information_schema.tables") shouldBe a[GateOutcome.Gated]
