package ai.starlake.acl.parser

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Which names a CTE body sees, exactly as DuckDB 2.0 binds them (each expectation below was read
  * off the 2.0 CLI with distinct row values in the real table and the CTE). A CTE name is only a
  * CTE where DuckDB binds it to the CTE; anywhere else the same bare name is a read of the real
  * table and must be gated. The walk used to put every name of a WITH clause in scope before any
  * body, so a body reading its own name (`WITH orders AS (SELECT * FROM orders)`, common in dbt
  * SQL) or a later sibling's name read the real table with nothing gated.
  *
  *   - Non-recursive WITH: body i sees the CTEs declared before it, never itself or a later one;
  *     the main query sees them all. MATERIALIZED / NOT MATERIALIZED change nothing.
  *   - WITH RECURSIVE (the keyword covers the whole clause): the same, except a CTE whose body is a
  *     UNION [ALL] at the top (not BY NAME, not under an EXCEPT or INTERSECT) sees its own name in
  *     the recursive term, the arms after the last top-level UNION, nested subqueries included. The
  *     anchor arms read the real table, and so does any other body shape.
  *   - Enclosing CTEs stay visible inside a nested WITH (an inner `s AS (SELECT * FROM s)` reads
  *     the outer CTE s).
  */
class CteVisibilitySpec extends AnyFlatSpec with Matchers:

  private val config = Config.forDuckDB("db", "main")

  private def reads(sql: String): Set[String] =
    SqlParser
      .extract(sql, config)
      .statements
      .flatMap {
        case e: StatementResult.Extracted =>
          e.unsupported shouldBe empty
          e.accesses.filter(_.verb == Verb.Read).map(_.table.table)
        case other => fail(s"expected Extracted, got $other for: $sql")
      }
      .toSet

  private def check(cases: (String, Set[String])*): Unit =
    cases.foreach((sql, expected) => withClue(sql)(reads(sql) shouldBe expected))

  "a non-recursive CTE body" should "read the real table through its own name" in
    check(
      "WITH secret AS (SELECT * FROM secret) SELECT * FROM secret"              -> Set("secret"),
      "WITH secret AS MATERIALIZED (SELECT * FROM secret) SELECT * FROM secret" -> Set("secret"),
      "WITH secret AS NOT MATERIALIZED (SELECT * FROM secret) SELECT v FROM secret" ->
        Set("secret"),
      "WITH secret(v) AS (SELECT v FROM secret WHERE v > 0) SELECT * FROM secret" -> Set("secret")
    )

  it should "read the real table through a later sibling's name" in
    check(
      "WITH a AS (SELECT * FROM b), b AS (SELECT 1 AS v) SELECT * FROM a"           -> Set("b"),
      "WITH x AS (SELECT * FROM secret), secret AS (SELECT 1 AS v) SELECT * FROM x" ->
        Set("secret"),
      "WITH a AS (SELECT (SELECT v FROM b) AS v), b AS (SELECT 1 AS v) SELECT * FROM a" -> Set("b")
    )

  it should "see the CTEs declared before it, and the main query should see them all" in
    check(
      "WITH b AS (SELECT 1 AS v), a AS (SELECT * FROM b) SELECT * FROM a"   -> Set(),
      "WITH a AS (SELECT 1 AS v), b AS (SELECT 2 AS v) SELECT * FROM a, b"  -> Set(),
      "WITH b AS (SELECT * FROM t), a AS (SELECT * FROM b) SELECT * FROM a" -> Set("t")
    )

  "a nested WITH" should "follow the same rule, scoped to its own query" in
    check(
      "SELECT (WITH secret AS (SELECT * FROM secret) SELECT v FROM secret)" -> Set("secret"),
      "SELECT ARRAY(WITH x AS (SELECT * FROM secret), secret AS (SELECT 1 AS v) SELECT v FROM x)" ->
        Set("secret"),
      "SELECT * FROM (WITH secret AS (SELECT * FROM secret) SELECT * FROM secret) d" ->
        Set("secret"),
      // the body of `a` is not yet in a's scope: its nested `FROM a` is the real table
      "WITH a AS (WITH b AS (SELECT * FROM a) SELECT * FROM b) SELECT * FROM a" -> Set("a"),
      // an enclosing CTE stays visible: the inner body reads the OUTER CTE s
      "WITH s AS (SELECT 1 AS v) SELECT * FROM (WITH s AS (SELECT * FROM s) SELECT * FROM s) d" ->
        Set()
    )

  "a WITH clause on a write" should "gate the real table its CTE bodies read" in {
    val ins = SqlParser.extract(
      "WITH secret AS (SELECT * FROM secret) INSERT INTO t SELECT * FROM secret",
      config
    )
    ins.statements.head match
      case e: StatementResult.Extracted =>
        e.accesses.map(a => (a.table.table, a.verb)) should contain(("secret", Verb.Read))
      case other => fail(s"expected Extracted, got $other")
    check(
      "WITH secret AS (SELECT * FROM secret) DELETE FROM t WHERE a IN (SELECT v FROM secret)" ->
        Set("secret"),
      "WITH a AS (SELECT * FROM b), b AS (SELECT 1 AS v) UPDATE t SET x = (SELECT v FROM a)" ->
        Set("b")
    )
  }

  "a WITH RECURSIVE union" should "see its own name in the recursive term only" in
    check(
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL SELECT v FROM s WHERE false) SELECT * FROM s" ->
        Set(),
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION SELECT v + 1 FROM s WHERE v < 3) SELECT * FROM s" ->
        Set(),
      "WITH RECURSIVE s(v) USING KEY (v) AS (SELECT 1 UNION SELECT v FROM s) SELECT * FROM s" ->
        Set(),
      "WITH RECURSIVE s AS ((SELECT 1 AS v UNION ALL SELECT v FROM s)) SELECT * FROM s" -> Set(),
      // a later CTE of the clause is recursive too (the keyword covers the clause)
      "WITH RECURSIVE b AS (SELECT 1 AS v), s AS (SELECT 1 AS v UNION ALL SELECT s.v FROM s, b) " +
        "SELECT * FROM s" -> Set(),
      // the recursive term's subqueries and nested WITHs see it too
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL " +
        "SELECT v FROM t WHERE v IN (SELECT v FROM s)) SELECT * FROM s" -> Set("t"),
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL " +
        "(WITH s2 AS (SELECT v FROM s) SELECT v FROM s2)) SELECT * FROM s" -> Set(),
      // A UNION ALL (B INTERSECT C): the recursive term is the INTERSECT
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL SELECT v FROM s INTERSECT SELECT v FROM s) " +
        "SELECT * FROM s" -> Set()
    )

  it should "read the real table from the anchor and from any other body shape" in
    check(
      "WITH RECURSIVE s AS (SELECT * FROM s UNION ALL SELECT 1 WHERE false) SELECT * FROM s" ->
        Set("s"),
      "WITH RECURSIVE s AS (SELECT (SELECT v FROM s) AS v UNION ALL SELECT v FROM s) " +
        "SELECT * FROM s" -> Set("s"),
      // A UNION ALL B UNION ALL C: the anchor is A UNION ALL B
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL SELECT v FROM s UNION ALL SELECT 2) " +
        "SELECT * FROM s"                                     -> Set("s"),
      "WITH RECURSIVE s AS (SELECT * FROM s) SELECT * FROM s" -> Set("s"),
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL BY NAME SELECT v FROM s) SELECT * FROM s" ->
        Set("s"),
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL SELECT 2 EXCEPT SELECT v FROM s) " +
        "SELECT * FROM s"                                                             -> Set("s"),
      "WITH RECURSIVE s AS (SELECT 1 AS v INTERSECT SELECT v FROM s) SELECT * FROM s" -> Set("s"),
      "WITH RECURSIVE s AS (SELECT 1 AS v EXCEPT SELECT v FROM s) SELECT * FROM s"    -> Set("s")
    )

  it should "still not see a later sibling" in
    check(
      "WITH RECURSIVE a AS (SELECT * FROM b), b AS (SELECT 1 AS v) SELECT * FROM a" -> Set("b"),
      "WITH RECURSIVE s AS (SELECT 1 AS v UNION ALL SELECT v FROM b), b AS (SELECT 2 AS v) " +
        "SELECT * FROM s" -> Set("b")
    )
