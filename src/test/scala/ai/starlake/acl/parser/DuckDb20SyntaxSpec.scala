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

  // --- What a DuckDB 2.0 quack client sends through an attached catalog ---

  private def isControlFlow(sql: String): Boolean =
    outcome(sql).isInstanceOf[StatementResult.ControlFlow]

  test("the quack client's transaction statements are control flow") {
    // duckdb-quack src/storage/quack_transaction.cpp: ForceStart / Commit / Rollback
    isControlFlow("BEGIN TRANSACTION") shouldBe true
    isControlFlow("begin transaction") shouldBe true
    isControlFlow("  BEGIN \t TRANSACTION ; ") shouldBe true
    isControlFlow("COMMIT") shouldBe true
    isControlFlow("ROLLBACK") shouldBe true
  }

  test("only the exact BEGIN TRANSACTION shape is recognized ahead of the parser") {
    // anything riding along stays the parser's call, so it is never admitted as control flow
    isControlFlow("BEGIN TRANSACTION; DROP TABLE t") shouldBe false
    isControlFlow("BEGIN TRANSACTION READ ONLY") shouldBe false
    isControlFlow("BEGIN TRANSACTION /* x */") shouldBe false
    isControlFlow("BEGIN TRANSACTION; BEGIN TRANSACTION") shouldBe false
    outcome("BEGIN TRANSACTION; DROP TABLE t") match
      case _: StatementResult.ControlFlow => fail("a batch must not collapse to control flow")
      case _                              => succeed
  }

  private val StreamId = "0f8e2c3a-1b2c-4d5e-8f90-0123456789ab"

  private def clientInsert(scan: String): String = s"INSERT INTO main.t SELECT * FROM $scan"

  private val ClientScan =
    s"scan_data_from_quack_client('$StreamId', NULL::STRUCT(col0 INTEGER, col1 VARCHAR, " +
      "col2 DECIMAL(18,3), col3 VARCHAR[]), ordered := true)"

  private def extractedOf(sql: String): StatementResult.Extracted = outcome(sql) match
    case e: StatementResult.Extracted => e
    case other                        => fail(s"expected Extracted, got $other")

  test("the quack client's INSERT source is a client data scan, the target a Write") {
    val e = extractedOf(clientInsert(ClientScan))
    e.accesses shouldBe Set(TableAccess(TableRef("lake", "main", "t"), Verb.Write))
    e.unsupported.size shouldBe 1
    TableExtractor.isClientDataScanMarker(e.unsupported.head) shouldBe true
    val unordered =
      extractedOf(clientInsert(ClientScan.replace("ordered := true", "ordered := false")))
    unordered.unsupported.forall(TableExtractor.isClientDataScanMarker) shouldBe true
  }

  test("any other spelling of the client data scan stays an ordinary table function") {
    val variants = List(
      // not a uuid stream id
      ClientScan.replace(s"'$StreamId'", "'x'"),
      // a subquery as the prototype
      ClientScan.replace("NULL::STRUCT(", "(SELECT x FROM secret)::STRUCT("),
      // a non-STRUCT prototype
      s"scan_data_from_quack_client('$StreamId', NULL::INTEGER, ordered := true)",
      // an extra argument
      ClientScan.replace("ordered := true)", "ordered := true, extra := 1)"),
      // no ordered argument
      s"scan_data_from_quack_client('$StreamId', NULL::STRUCT(col0 INTEGER))",
      // a non-literal ordered value
      ClientScan.replace("ordered := true", "ordered := (SELECT true FROM secret)"),
      // qualified or quoted name
      "main." + ClientScan,
      ClientScan.replace("scan_data_from_quack_client", "\"scan_data_from_quack_client\"")
    )
    variants.foreach { v =>
      outcome(clientInsert(v)) match
        case e: StatementResult.Extracted =>
          withClue(v)(e.unsupported.exists(TableExtractor.isClientDataScanMarker) shouldBe false)
          withClue(v)(e.unsupported should not be empty)
        case _: StatementResult.ParseError => succeed
        case other                         => fail(s"$v: $other")
    }
  }
