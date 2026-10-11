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

  it should "refuse an ambiguous two-part name on an attached catalog as not wildcard-coverable" in:
    val attached = Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch", "fedpg"))
    AccessGate.evaluate("SELECT * FROM fedpg.orders", attached, "tpch", false) match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  // A subquery in any expression position is a read of the table it names. DuckDB's
  // `ARRAY(SELECT ...)` parses as a function whose argument is a bare (unparenthesized) select,
  // which the walker used to mark visited as an expression and then skip as a select, so the
  // statement came back NothingGated and was admitted with no grant at all.
  it should "gate a table read by a subquery in every expression position" in:
    val other = "tpch.secret.orders"
    List(
      "SELECT ARRAY(SELECT o FROM tpch.secret.orders)",
      "SET VARIABLE x = ARRAY(SELECT o FROM tpch.secret.orders)",
      "SELECT list_value(ARRAY(SELECT o FROM tpch.secret.orders))",
      "SELECT coalesce(ARRAY(SELECT o FROM tpch.secret.orders), [])",
      "SELECT (SELECT o FROM tpch.secret.orders)",
      "SELECT coalesce((SELECT o FROM tpch.secret.orders), 'x')",
      "SELECT EXISTS (SELECT 1 FROM tpch.secret.orders)",
      "SELECT 1 WHERE 'a' IN (SELECT o FROM tpch.secret.orders)",
      "SELECT 1 WHERE 'a' = ANY (SELECT o FROM tpch.secret.orders)",
      "SELECT [(SELECT o FROM tpch.secret.orders)]",
      "SELECT list_transform([1], x -> (SELECT o FROM tpch.secret.orders))",
      "SELECT * FROM lineitem, LATERAL (SELECT o FROM tpch.secret.orders)",
      "SELECT CASE WHEN true THEN ARRAY(SELECT o FROM tpch.secret.orders) END",
      "SELECT 1 GROUP BY ARRAY(SELECT o FROM tpch.secret.orders)"
    ).foreach { sql =>
      withClue(sql) {
        eval(sql) match
          case GateOutcome.Gated(g, _)           => g.map(_.table.canonical) should contain(other)
          case GateOutcome.Refuse(_, _, _)       => succeed
          case nothing: GateOutcome.NothingGated => fail(s"admitted ungated: $nothing")
      }
    }

  it should "admit a pure info-schema read only with filtered metadata on" in:
    eval("SELECT table_name FROM information_schema.tables", fm = true) shouldBe
      a[GateOutcome.NothingGated]
    eval("SELECT table_name FROM information_schema.tables") shouldBe a[GateOutcome.Gated]
