package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Differential cases: statements whose literals or comments JSqlParser's own lexer reads
  * differently from DuckDB. DuckDB reads the subquery in each as code (checked on the 2.0 and 1.5.6
  * CLIs), so the gate must see the table it reads. Before the parser read lexer-canonical input,
  * each came back NothingGated: an unconditional allow in both ACL modes.
  */
class AccessGateLexerParitySpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  private def gatedTables(sql: String): Set[String] =
    AccessGate.evaluate(sql, cfg, "lake", filteredMetadata = true, clientDataScans = false) match
      case GateOutcome.Gated(g, _) => g.map(_.table.table)
      case other                   => fail(s"expected Gated, got $other for: $sql")

  "AccessGate" should "see a table after an escape string holding an escaped quote" in:
    gatedTables("SELECT E'\\'' , (SELECT v FROM secret) AS x --'") should contain("secret")
    gatedTables("SELECT e'\\'' , (SELECT v FROM secret) AS x --'") should contain("secret")
    gatedTables("SELECT E'a\\\\' , (SELECT v FROM secret) AS x") should contain("secret")

  it should "see a table after a dollar-quoted string holding a quote" in:
    gatedTables("SELECT $x$'$x$ AS a, (SELECT v FROM secret) AS x --'") should contain("secret")
    gatedTables("SELECT $$'$$ AS a, (SELECT v FROM secret) AS x --'") should contain("secret")

  it should "see a table after a plain literal ending in a backslash" in:
    gatedTables("SELECT '\\' AS a, (SELECT v FROM secret) AS x") should contain("secret")

  it should "see a table after DuckDB's integer division operator" in:
    // JSqlParser reads `//` as a line comment
    gatedTables("SELECT 7 // 2, (SELECT v FROM secret) AS x") should contain("secret")

  it should "not admit a table behind a word directly followed by a literal" in:
    // JSqlParser reads q'{ ... }' as one Oracle quote. DuckDB reads the word q, then a literal (a
    // cast to a type named q); the parser input sets them apart, and the gate refuses the shape.
    AccessGate.evaluate(
      "SELECT q'{' AS x, (SELECT v FROM secret) AS y, '}' AS z",
      cfg,
      "lake",
      filteredMetadata = true,
      clientDataScans = false
    ) match
      case GateOutcome.Gated(g, _)     => g.map(_.table.table) should contain("secret")
      case GateOutcome.Refuse(_, _, _) => succeed
      case other                       => fail(s"admitted: $other")

  it should "keep gating an ordinary statement unchanged" in:
    gatedTables("SELECT v FROM secret WHERE v = E'it\\'s' AND w = $$x$$") shouldBe Set("secret")
