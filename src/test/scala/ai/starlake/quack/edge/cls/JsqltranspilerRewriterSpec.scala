package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsqltranspilerRewriterSpec extends AnyFlatSpec with Matchers:

  private val rw = new JsqltranspilerRewriter

  private val maskEmail = RoleColumnPolicy(
    id = "cp-1",
    roleId = "r-1",
    catalogName = "*",
    schemaName = "tpch1",
    tableName = "customer",
    columnName = "c_email",
    action = "mask",
    transformSql = Some("'***'")
  )

  private val schema = Map("customer" -> List("c_id", "c_email", "c_phone"))

  "rewrite" should "mask a directly-projected covered column" in {
    val out = rw.rewrite(
      sql = "SELECT c_email FROM customer",
      schema = schema,
      policies = List(maskEmail),
      defaultCatalog = Some("acme_tpch"),
      defaultSchema = Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside a function call in the projection" in {
    val out = rw.rewrite(
      "SELECT length(c_email) FROM customer",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside CASE WHEN arms" in {
    val out = rw.rewrite(
      "SELECT CASE WHEN c_id > 0 THEN c_email ELSE 'fallback' END FROM customer",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside CAST" in {
    val out = rw.rewrite(
      "SELECT CAST(c_email AS VARCHAR) FROM customer",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside IN list" in {
    val out = rw.rewrite(
      "SELECT c_id FROM customer WHERE c_email IN ('a@x', 'b@x')",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside BETWEEN" in {
    val out = rw.rewrite(
      "SELECT c_id FROM customer WHERE c_email BETWEEN 'a' AND 'z'",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside EXTRACT" in {
    // EXTRACT semantically expects a date/time, but the parser doesn't enforce that. Use it as a
    // smoke test that the visitor descends into the inner expression.
    val out = rw.rewrite(
      "SELECT EXTRACT(YEAR FROM c_email) FROM customer",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside window PARTITION BY" in {
    val out = rw.rewrite(
      "SELECT row_number() OVER (PARTITION BY c_email) FROM customer",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside window ORDER BY" in {
    val out = rw.rewrite(
      "SELECT row_number() OVER (ORDER BY c_email) FROM customer",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside LIKE pattern" in {
    val out = rw.rewrite(
      "SELECT c_id FROM customer WHERE c_email LIKE '%@acme.com'",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "mask c_email inside row constructor" in {
    val out = rw.rewrite(
      "SELECT (c_id, c_email) FROM customer",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "expand multi-table SELECT * and mask per-table" in {
    val multi = Map(
      "customer" -> List("c_id", "c_email"),
      "orders"   -> List("o_id", "o_customer")
    )
    val out = rw.rewrite(
      "SELECT * FROM customer c JOIN orders o ON c.c_id = o.o_customer",
      multi,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) =>
        sql should include("'***'")
        sql.toLowerCase should include("o_id")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "resolve unqualified c_email in a multi-join to the customer table" in {
    val multi = Map(
      "customer" -> List("c_id", "c_email"),
      "orders"   -> List("o_id", "o_customer")
    )
    val out = rw.rewrite(
      "SELECT c_email FROM customer JOIN orders ON c_id = o_customer",
      multi,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "resolve CTE column c_email to the base table" in {
    val out = rw.rewrite(
      "WITH x AS (SELECT c_email FROM customer) SELECT c_email FROM x",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) => sql should include("'***'")
      case other                         => fail(s"expected Rewritten, got $other")
  }

  it should "deny SELECT against an unknown table in STRICT mode" in {
    val out = rw.rewrite(
      sql = "SELECT c_email FROM unknown_table",
      schema = Map.empty, // resolver has no entry for unknown_table
      policies = List(maskEmail),
      defaultCatalog = Some("acme_tpch"),
      defaultSchema = Some("tpch1"),
      unresolvedMode = UnresolvedMode.Deny
    )
    out match
      case RewriteOutcome.Denied(_) => succeed
      case other                    => fail(s"expected Denied, got $other")
  }

  it should "pass through SELECT against an unknown table in LENIENT mode" in {
    val out = rw.rewrite(
      sql = "SELECT c_email FROM unknown_table",
      schema = Map.empty,
      policies = List(maskEmail),
      defaultCatalog = Some("acme_tpch"),
      defaultSchema = Some("tpch1"),
      unresolvedMode = UnresolvedMode.Pass
    )
    out match
      case RewriteOutcome.Passthrough => succeed
      case other                      => fail(s"expected Passthrough, got $other")
  }

  it should "not mask a same-named column on a table the policy doesn't target" in {
    val multi = Map("other_table" -> List("c_id", "c_email"))
    val out   = rw.rewrite(
      "SELECT c_email FROM other_table",
      multi,
      // Policy targets customer.c_email, not other_table.c_email
      List(maskEmail.copy(tableName = "customer")),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Passthrough    => succeed
      case RewriteOutcome.Rewritten(sql) =>
        fail(
          s"v1 over-mask regression: policy on customer.c_email leaked to other_table.c_email: $sql"
        )
      case other => fail(s"expected Passthrough, got $other")
  }
  // ------------------------------------------------------------------
  // Closed masking gap: outer-scope aliases inside correlated subqueries
  // ------------------------------------------------------------------

  private val maskPhone = maskEmail.copy(id = "cp-2", columnName = "c_phone")

  it should "mask an outer-aliased covered column inside a correlated EXISTS subquery" in {
    val out = rw.rewrite(
      "SELECT c.c_id FROM customer c WHERE EXISTS " +
        "(SELECT 1 FROM customer c2 WHERE c2.c_id = c.c_id AND c.c_phone = '555')",
      schema,
      List(maskPhone),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) =>
        sql should include("'***'")
        // The outer-alias comparison against the true value must be gone.
        (sql should not).include("c.c_phone = '555'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "mask an outer-aliased covered column inside a correlated IN subquery" in {
    val multi = Map(
      "customer" -> List("c_id", "c_email", "c_phone"),
      "orders"   -> List("o_id", "o_cid", "o_note")
    )
    val out = rw.rewrite(
      "SELECT c.c_id FROM customer c WHERE c.c_id IN " +
        "(SELECT o_cid FROM orders WHERE orders.o_note = c.c_phone)",
      multi,
      List(maskPhone),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out match
      case RewriteOutcome.Rewritten(sql) =>
        sql should include("'***'")
        (sql should not).include("c.c_phone")
      case other => fail(s"expected Rewritten, got $other")
  }

  // ------------------------------------------------------------------
  // Closed fail-open gap: unresolved tables nested in subqueries fail closed
  // ------------------------------------------------------------------

  it should "deny an unresolvable table nested inside a subquery in STRICT mode" in {
    val out = rw.rewrite(
      "SELECT 1 FROM information_schema.tables WHERE EXISTS (SELECT 1 FROM unknown_table)",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1"),
      UnresolvedMode.Deny
    )
    out match
      case RewriteOutcome.Denied(_) => succeed
      case other                    => fail(s"expected Denied, got $other")
  }

  it should "pass through an unresolvable subquery-nested table in LENIENT mode" in {
    val out = rw.rewrite(
      "SELECT 1 FROM information_schema.tables WHERE EXISTS (SELECT 1 FROM unknown_table)",
      schema,
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1"),
      UnresolvedMode.Pass
    )
    out match
      case RewriteOutcome.Denied(reason) => fail(s"LENIENT must not deny: $reason")
      case _                             => succeed
  }

  // ---- positions the masking visitor does not model ----------------------------------------
  //
  // A covered column the visitor leaves in place reaches the node unmasked. Each of these used to
  // pass through; the residual check refuses them instead.

  private def notForwardedUnmasked(sql: String): Unit =
    val withOrders = schema + ("orders" -> List("o_id", "o_total"))
    rw.rewrite(sql, withOrders, List(maskEmail), Some("acme_tpch"), Some("tpch1")) match
      case RewriteOutcome.Denied(_)      => succeed
      case RewriteOutcome.Rewritten(out) =>
        out.toLowerCase should not include "c_email"
      case other => fail(s"expected Denied or a masked rewrite, got $other for: $sql")

  "a covered column outside a modeled expression" should "never be forwarded unmasked" in {
    notForwardedUnmasked("SELECT map {'a': c_email} FROM customer")
    notForwardedUnmasked("SELECT [c_email] FROM customer")
    notForwardedUnmasked("SELECT map {'a': (SELECT c_email FROM customer)}")
    notForwardedUnmasked("SELECT list_transform([1], x -> c_email) FROM customer")
    notForwardedUnmasked("SELECT c_email->>'$' FROM customer")
    notForwardedUnmasked("SELECT struct_pack(a := c_email) FROM customer")
    notForwardedUnmasked("SELECT trim(c_email) FROM customer")
    notForwardedUnmasked("SELECT c_id FROM customer WHERE map {'a': c_email}['a'] = 'x'")
    notForwardedUnmasked("SELECT c.c_id FROM customer c JOIN orders o ON c.c_email = o.o_id")
  }

  it should "leave a same-named column of a table without a policy alone" in {
    val out = rw.rewrite(
      "SELECT [o.c_email] FROM orders o",
      schema + ("orders" -> List("o_id", "c_email")),
      List(maskEmail),
      Some("acme_tpch"),
      Some("tpch1")
    )
    out shouldBe RewriteOutcome.Passthrough
  }

  // ---- the residual check binds an unqualified column per query block, innermost first --------
  //
  // `orders` has its own `c_email`, with no policy. DuckDB binds an unqualified name to the
  // innermost select that has it, and a CTE body cannot see the FROM of the select declaring it,
  // so each of these reads orders.c_email; they were denied as if they read customer.c_email.

  private val withOrders = Map(
    "customer" -> List("c_id", "c_name", "c_email", "c_phone"),
    "orders"   -> List("o_id", "c_id", "c_email", "o_total"),
    "lineitem" -> List("l_id", "o_id")
  )

  private def outcome(sql: String): RewriteOutcome =
    rw.rewrite(sql, withOrders, List(maskEmail), Some("acme_tpch"), Some("tpch1"))

  "the residual check" should "admit a same-named column read in a CTE, derived table, subquery" in
    List(
      "WITH o AS (SELECT c_id, c_email FROM orders) " +
        "SELECT c.c_name FROM customer c JOIN o ON o.c_id = c.c_id",
      "SELECT c.c_name FROM customer c JOIN " +
        "(SELECT c_id, max(c_email) m FROM orders GROUP BY c_id) x USING (c_id)",
      "SELECT c_name FROM customer WHERE c_id IN (SELECT c_id FROM orders WHERE c_email = 'x')",
      "SELECT c_name FROM customer WHERE EXISTS (SELECT 1 FROM orders WHERE c_email = 'x')",
      "SELECT c_name, (SELECT max(c_email) FROM orders) FROM customer",
      "WITH o AS (SELECT [c_email] AS l FROM orders) SELECT c_name FROM customer, o",
      "SELECT [x.c_email] FROM orders x WHERE EXISTS (SELECT 1 FROM customer x)"
    ).foreach(sql => withClue(sql)(outcome(sql) shouldBe RewriteOutcome.Passthrough))

  it should "still deny a correlated reference the inner select cannot bind" in {
    // lineitem has no c_email, so DuckDB binds it to the outer customer.
    outcome(
      "SELECT c_name FROM customer WHERE EXISTS (SELECT 1 FROM lineitem WHERE c_email = 'x')"
    ) shouldBe a[RewriteOutcome.Denied]
    // An unknown-column table never ends the search: the name may bind further out.
    notForwardedUnmasked("SELECT [c_email] FROM orders, customer")
  }

  it should "resolve a qualifier through the innermost select that names it" in
    // The outer `c` is customer: the inner `orders c` shadows it only inside EXISTS.
    notForwardedUnmasked("SELECT [c.c_email] FROM customer c WHERE EXISTS (SELECT 1 FROM orders c)")

  // ---- positions the visitor masks rather than deny ---------------------------------------------

  private def masked(sql: String): Unit =
    outcome(sql) match
      case RewriteOutcome.Rewritten(out) =>
        out should include("'***'")
        out.toLowerCase should not include "c.c_email"
        out.toLowerCase.replace("o.c_email", "") should not include "c_email"
      case other => fail(s"expected a masked rewrite, got $other for: $sql")

  "the masking visitor" should "mask IS [NOT] NULL, TRIM, QUALIFY, FILTER and JOIN ON" in {
    masked("SELECT c_email IS NULL FROM customer")
    masked("SELECT c_id FROM customer WHERE c_email IS NOT NULL")
    masked("SELECT count(*) FILTER (WHERE c_email IS NOT NULL) FROM customer")
    masked("SELECT trim(c_email) FROM customer")
    masked("SELECT trim(BOTH 'x' FROM c_email) FROM customer")
    masked("SELECT c_id FROM customer QUALIFY row_number() OVER (PARTITION BY c_email) = 1")
    masked("SELECT c.c_id FROM customer c JOIN orders o ON c.c_email = o.c_email")
    masked(
      "SELECT c.c_id FROM customer c LEFT JOIN orders o ON o.c_id = c.c_id AND c.c_email IS NULL"
    )
  }

  it should "keep denying JOIN ... USING on a covered column (it names a column)" in {
    outcome("SELECT c.c_id FROM customer c JOIN orders o USING (c_email)") shouldBe
      a[RewriteOutcome.Denied]
  }

  // ---- a TABLE statement in expression position -------------------------------------------------
  //
  // `ARRAY(TABLE customer)` / `x = ANY (TABLE customer)` read every column of customer, the masked
  // ones raw, with no column node for the visitor to mask.

  "a TABLE statement passed to ARRAY() or ANY" should "be denied under column policies" in
    List(
      "SELECT ARRAY(TABLE customer)",
      "SELECT len(ARRAY(TABLE customer))",
      "SELECT c_id FROM customer WHERE 'a@x' = ANY (TABLE customer)",
      "SELECT ARRAY(TABLE v_customer)"
    ).foreach(sql => withClue(sql)(outcome(sql) shouldBe a[RewriteOutcome.Denied]))

  // ---- CTE visibility in the residual check -----------------------------------------------------
  //
  // A CTE body does not see its own name nor a later sibling's (DuckDB 2.0 binds both to the real
  // table), so a covered column read there is a base read, not a read of the CTE's output.

  "the residual check" should "bind a CTE body's own or later-sibling name to the real table" in {
    notForwardedUnmasked(
      "WITH customer AS (SELECT [c_email] AS l FROM customer) SELECT l FROM customer"
    )
    notForwardedUnmasked(
      "WITH a AS (SELECT [c_email] AS l FROM customer), customer AS (SELECT 'x' AS c_email) " +
        "SELECT l FROM a"
    )
    notForwardedUnmasked(
      "WITH RECURSIVE customer AS (SELECT [c_email] AS l FROM customer UNION ALL " +
        "SELECT l FROM customer WHERE false) SELECT l FROM customer"
    )
  }

  // ---- the rewrite keeps every table name as the caller wrote it -------------------------------
  //
  // The resolver schema-qualified every table reference, CTE references included, and a qualified
  // name never binds to a CTE: `WITH t AS (..) SELECT .. FROM t` became `.. FROM tpch1.t`, a read
  // of the real table t the ACL gate never checked.

  "a column policy rewrite" should "leave a CTE reference unqualified" in {
    outcome(
      "WITH t AS (SELECT c_id AS a FROM customer WHERE c_email LIKE '%') SELECT a FROM t"
    ) shouldBe RewriteOutcome.Rewritten(
      "WITH t AS (SELECT c_id AS a FROM customer WHERE '***' LIKE '%') SELECT a FROM t"
    )
    outcome(
      "WITH orders AS (SELECT c_id, c_email FROM customer) SELECT c_email FROM orders"
    ) match
      case RewriteOutcome.Rewritten(sql) =>
        sql should include("FROM orders")
        sql should not include "tpch1.orders"
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "print every table name exactly as written" in {
    outcome("SELECT c_email FROM customer") shouldBe
      RewriteOutcome.Rewritten("SELECT '***' AS c_email FROM customer")
    outcome("SELECT c_email FROM tpch1.customer") shouldBe
      RewriteOutcome.Rewritten("SELECT '***' AS c_email FROM tpch1.customer")
    outcome("SELECT c_email FROM acme_tpch.tpch1.customer") shouldBe
      RewriteOutcome.Rewritten("SELECT '***' AS c_email FROM acme_tpch.tpch1.customer")
  }

  // ---- star expansion and whole-row forms -------------------------------------------------
  //
  // DuckDB expands COLUMNS(...), `* LIKE` / ILIKE / GLOB / SIMILAR TO patterns, unpacked
  // `*COLUMNS(...)`, a table referenced as a column (the whole row as a STRUCT) and a statement in
  // table position into the table's columns, with no column node for the visitor to mask. Each
  // returned the raw values on the 2.0 CLI.

  private def denied(sql: String): Unit =
    withClue(sql)(outcome(sql) shouldBe a[RewriteOutcome.Denied])

  "a star expansion form over a masked table" should "be denied" in
    List(
      "SELECT COLUMNS('c_e.*') FROM customer",
      "SELECT COLUMNS(*) FROM customer",
      "SELECT COLUMNS(['c_email']) FROM customer",
      "SELECT COLUMNS('c_e.*') AS e FROM customer",
      "SELECT * LIKE 'c_e%' FROM customer",
      "SELECT * ILIKE 'C_E%' FROM customer",
      "SELECT * NOT LIKE 'c_i%' FROM customer",
      "SELECT * GLOB 'c_e*' FROM customer",
      "SELECT * SIMILAR TO 'c_e.*' FROM customer",
      "SELECT c.* LIKE '%mail' FROM customer c",
      "SELECT concat(*COLUMNS(*)) FROM customer",
      "SELECT list_value(*COLUMNS('c_e.*')) FROM customer",
      "SELECT max(COLUMNS('c_e.*')) FROM customer",
      "SELECT c_id FROM customer WHERE COLUMNS('c_e.*') LIKE 'a%'",
      "SELECT c_id FROM customer ORDER BY COLUMNS('c_e.*')",
      "SELECT count(*) FROM customer GROUP BY COLUMNS('c_e.*')",
      "SELECT o_id, (SELECT max(COLUMNS('c_e.*')) FROM customer) FROM orders",
      "WITH x AS (SELECT COLUMNS('c_e.*') FROM customer) SELECT * FROM x"
    ).foreach(denied)

  "a table referenced as a column (the whole row)" should "be denied" in
    List(
      "SELECT c FROM customer c",
      "SELECT customer FROM customer",
      "SELECT to_json(c) FROM customer c",
      "SELECT c_id FROM customer c WHERE c::VARCHAR LIKE '%alice%'",
      "SELECT main.customer FROM customer",
      "SELECT list(c) FROM customer c",
      "SELECT o_id FROM orders WHERE EXISTS (SELECT 1 FROM customer c WHERE c IS NOT NULL)"
    ).foreach(denied)

  "a statement in table position over a masked table" should "be denied" in
    List(
      "SELECT * FROM (TABLE customer)",
      "SELECT * FROM (SUMMARIZE customer)",
      "SELECT o_id FROM orders, (TABLE customer) c"
    ).foreach { sql =>
      withClue(sql) {
        outcome(sql) should (be(a[RewriteOutcome.Denied]) or be(RewriteOutcome.ParseFailed))
      }
    }

  "star modifiers" should "be expanded to an explicit, masked column list" in {
    outcome("SELECT * EXCLUDE (c_phone) FROM customer") shouldBe RewriteOutcome.Rewritten(
      "SELECT customer.c_id, customer.c_name, '***' AS c_email FROM customer"
    )
    outcome("SELECT * EXCLUDE (C_PHONE, c_name) FROM customer c") shouldBe
      RewriteOutcome.Rewritten("SELECT c.c_id, '***' AS c_email FROM customer c")
    outcome(
      "SELECT c.* EXCLUDE (c_phone) FROM customer c JOIN orders o ON o.c_id = c.c_id"
    ) shouldBe
      RewriteOutcome.Rewritten(
        "SELECT c.c_id, c.c_name, '***' AS c_email FROM customer c JOIN orders o ON o.c_id = c.c_id"
      )
    outcome("SELECT * REPLACE (upper(c_email) AS c_email) FROM customer") shouldBe
      RewriteOutcome.Rewritten(
        "SELECT customer.c_id, customer.c_name, upper('***') AS c_email, customer.c_phone " +
          "FROM customer"
      )
    outcome("SELECT * EXCLUDE (c_phone) REPLACE (c_id + 1 AS c_id) FROM customer") shouldBe
      RewriteOutcome.Rewritten(
        "SELECT c_id + 1 AS c_id, customer.c_name, '***' AS c_email FROM customer"
      )
    // nothing masked once excluded: the caller's statement goes out as written
    outcome("SELECT * EXCLUDE (c_email) FROM customer") shouldBe RewriteOutcome.Passthrough
  }

  it should "qualify an aliased table's star by its alias" in {
    outcome("SELECT * FROM customer c") shouldBe RewriteOutcome.Rewritten(
      "SELECT c.c_id, c.c_name, '***' AS c_email, c.c_phone FROM customer c"
    )
    outcome("SELECT c.* FROM customer c") shouldBe RewriteOutcome.Rewritten(
      "SELECT c.c_id, c.c_name, '***' AS c_email, c.c_phone FROM customer c"
    )
  }

  it should "be denied where they cannot be expanded soundly" in {
    denied("SELECT * EXCLUDE (o_total) FROM customer JOIN orders USING (c_id)")
    denied("SELECT * EXCLUDE (no_such) FROM customer")
    denied("SELECT * REPLACE (upper(c_email) AS no_such) FROM customer")
  }

  it should "leave count(*) and stars over unmasked tables alone" in {
    outcome("SELECT count(*) FROM customer") shouldBe RewriteOutcome.Passthrough
    outcome("SELECT count(*) FROM customer WHERE c_email IS NULL") shouldBe a[
      RewriteOutcome.Rewritten
    ]
    outcome("SELECT COLUMNS('o_.*') FROM orders") shouldBe RewriteOutcome.Passthrough
    outcome("SELECT * LIKE 'o_%' FROM orders") shouldBe RewriteOutcome.Passthrough
  }
