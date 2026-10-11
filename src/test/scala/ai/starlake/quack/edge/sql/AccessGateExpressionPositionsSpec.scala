package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Expression positions JSqlParser holds outside its own AST node types: a MAP literal keeps its
  * entries as java.util.Map.Entry pairs, and a JSON key/value pair (json_object) types its key and
  * value as Object. A subquery in either was never walked, so the statement came back NothingGated,
  * an unconditional allow, while DuckDB 2.0 runs the subquery (checked on the CLI).
  */
class AccessGateExpressionPositionsSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  private def gated(sql: String): Set[String] =
    AccessGate.evaluate(sql, cfg, "lake", filteredMetadata = true, clientDataScans = false) match
      case GateOutcome.Gated(g, _) => g.map(_.table.canonical)
      case other                   => fail(s"expected Gated, got $other for: $sql")

  "a subquery in a MAP literal" should "be gated as a value" in:
    gated("SELECT map {'a': (SELECT v FROM secret)}") shouldBe Set("lake.main.secret")
    gated("SELECT map {'a': (SELECT v FROM secret)}['a']") shouldBe Set("lake.main.secret")

  it should "be gated as a key" in:
    gated("SELECT MAP {(SELECT v FROM secret): 1}") shouldBe Set("lake.main.secret")

  it should "be gated inside a write's source" in:
    gated("INSERT INTO t SELECT map {'a': (SELECT v FROM secret)}") should contain(
      "lake.main.secret"
    )

  "a subquery in a json_object argument" should "be gated" in:
    gated("SELECT json_object('a', (SELECT v FROM secret))") shouldBe Set("lake.main.secret")
