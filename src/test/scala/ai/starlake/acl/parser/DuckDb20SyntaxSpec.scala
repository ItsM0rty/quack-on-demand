package ai.starlake.acl.parser

import ai.starlake.acl.model.{Config, DenyReason, TableRef}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** What the ACL parser does with DuckDB 2.0 syntax (design spec section 8). A ParseError is refused
  * for principals without a wildcard ALL grant; that is the accepted fail-closed state for the
  * bump. When a JSqlParser upgrade changes an outcome here, update this table and re-check the gate
  * for that construct.
  */
class DuckDb20SyntaxSpec extends AnyFunSuite with Matchers:

  private val config = Config.forDuckDB("lake", "main")

  private def outcome(sql: String): StatementResult = SqlParser.extract(sql, config).statements.head

  private def isParseError(sql: String): Boolean =
    outcome(sql).isInstanceOf[StatementResult.ParseError]

  private def reads(sql: String): Set[TableRef] = outcome(sql) match
    case e: StatementResult.Extracted => e.accesses.map(_.table)
    case other                        => fail(s"expected Extracted, got $other")

  test("statements JSqlParser cannot parse yet are ParseErrors (refused unless ALL)") {
    isParseError("CONNECT 'quack:h:1'") shouldBe true
    isParseError("DISCONNECT") shouldBe true
    isParseError(
      "CREATE TRIGGER t AFTER INSERT ON a FOR EACH STATEMENT INSERT INTO b VALUES (1)"
    ) shouldBe true
    isParseError("CREATE SCHEMA lake.finance.reports") shouldBe true
    isParseError("CREATE EXTENSION REPOSITORY r WITH PREFIX 'https://x/'") shouldBe true
  }

  test("new read syntax JSqlParser already parses keeps its table refs") {
    reads("SELECT * FROM t FETCH FIRST 2 ROWS ONLY") shouldBe Set(TableRef("lake", "main", "t"))
    reads("SELECT a, count(*) FROM t GROUP BY UNNEST([1,2])") shouldBe Set(
      TableRef("lake", "main", "t")
    )
    reads(
      "SELECT * FROM a INNER JOIN b APPROX NEAREST 2 BY SIMILARITY array_cosine_similarity(a.v, b.v)"
    ) shouldBe Set(TableRef("lake", "main", "a"), TableRef("lake", "main", "b"))
    reads("SELECT variant_type(x) FROM t") shouldBe Set(TableRef("lake", "main", "t"))
    reads("SELECT $x") shouldBe Set.empty
    reads("SELECT OVERLAY('abc' PLACING 'x' FROM 2 FOR 1)") shouldBe Set.empty
  }

  test("security-relevant 2.0 constructs fail closed (Tasks 1 and 2)") {
    outcome("WITH w AS (DELETE FROM t RETURNING *) SELECT * FROM w") match
      case e: StatementResult.Extracted =>
        e.unsupported.exists(_.startsWith("data-modifying CTE")) shouldBe true
      case other => fail(s"expected Extracted, got $other")
    outcome("SELECT * FROM lake.finance.reports.r") match
      case e: StatementResult.Extracted =>
        e.qualificationErrors.exists(_.isInstanceOf[DenyReason.NestedSchemaRef]) shouldBe true
      case other => fail(s"expected Extracted, got $other")
  }
