package ai.starlake.quack.edge.opa

import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.sql.*
import ai.starlake.quack.model.{PoolKey, Tenant, TenantAcl}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpaValidatorSpec extends AnyFlatSpec with Matchers:

  private val pk     = PoolKey("acme", "tpch", "bi")
  private val tenant = Tenant("acme", acl = TenantAcl(Some("opa"), Some("http://opa")))

  private def eff(tenantScoped: Boolean) =
    EffectiveSet(
      user = RbacUser(
        id = "u1",
        tenant = if tenantScoped then Some("acme") else None,
        username = "alice",
        kind = "user",
        enabled = true
      ),
      roles = Nil,
      groups = Nil,
      permissions = Nil,
      poolPerms = Nil,
      claims = Map("dept" -> "fin")
    )

  private def validator(
      reply: Either[String, (Int, String)],
      calls: StringBuilder = StringBuilder()
  ) =
    val post: OpaClient.Post = (url, body, _) =>
      calls.append(url).append('\n').append(body)
      reply
    val auth = new OpaAuthorizer(
      OpaConfig.default,
      new OpaClient(1000, post),
      new OpaDecisionCache(0),
      OpaInstruments.noop
    )
    new OpaValidator(
      auth,
      t => Option.when(t == "acme")(tenant),
      _ => Nil,
      "tpch",
      "main",
      "duckdb",
      false
    )

  private def ctx(sql: String, e: Option[EffectiveSet]) =
    ValidationContext(
      "alice",
      pk.toString,
      sql,
      "c1",
      defaultDatabase = Some("tpch"),
      defaultSchema = Some("main"),
      attachedCatalogs = Set("tpch"),
      effectiveSet = e,
      poolKey = Some(pk),
      edge = "flightsql",
      statementClass = "READ"
    )

  "OpaValidator" should "allow when OPA allows, sending gated accesses and claims" in:
    val calls = StringBuilder()
    validator(Right((200, """{"result":{"allow":true}}""")), calls)
      .validate(ctx("SELECT * FROM orders", Some(eff(true)))) shouldBe Allowed
    calls.toString should include("/v1/data/qod/authz/statement")
    calls.toString should include("\"table\":\"orders\"")
    calls.toString should include("\"dept\":\"fin\"")

  it should "deny with authz_source=opa and decision id meta" in:
    validator(Right((200, """{"decision_id":"d9","result":{"allow":false,"reason":"r"}}""")))
      .validate(ctx("SELECT * FROM orders", Some(eff(true)))) match
      case Denied(reason, refs, meta) =>
        reason should include("r")
        refs.map(_.table.table) shouldBe Set("orders")
        meta shouldBe Map("authz_source" -> "opa", "opa_decision_id" -> "d9")
      case other => fail(other.toString)

  it should "return ValidatorUnavailable on transport error" in:
    validator(Left("ConnectException")).validate(
      ctx("SELECT * FROM orders", Some(eff(true)))
    ) shouldBe a[ValidatorUnavailable]

  it should "bypass superusers and never call OPA" in:
    val calls = StringBuilder()
    validator(Left("must not be called"), calls).validate(
      ctx("SELECT * FROM orders", Some(eff(false)))
    ) shouldBe Allowed
    calls.toString shouldBe ""

  it should "deny without a bound principal, and deny parse errors without calling OPA" in:
    validator(Right((200, """{"result":{"allow":true}}""")))
      .validate(ctx("SELECT * FROM orders", None)) shouldBe a[Denied]
    val calls = StringBuilder()
    validator(Right((200, """{"result":{"allow":true}}""")), calls)
      .validate(ctx("SELECT FROM WHERE (", Some(eff(true)))) shouldBe a[Denied]
    calls.toString shouldBe ""

  it should "admit control flow without calling OPA" in:
    val calls = StringBuilder()
    validator(Left("must not be called"), calls).validate(
      ctx("COMMIT", Some(eff(true)))
    ) shouldBe Allowed
    calls.toString shouldBe ""

  it should "deny a catalog-reaching call even though OPA would allow" in:
    val calls = StringBuilder()
    validator(Right((200, """{"result":{"allow":true}}""")), calls).validate(
      ctx("SELECT * FROM query_table('orders')", Some(eff(true)))
    ) shouldBe a[Denied]
    calls.toString shouldBe ""

  it should "deny a node-escaping call (postgres_query / getenv) before asking OPA" in:
    for sql <- List(
        "SELECT * FROM postgres_query('c', 'SELECT 1')",
        "SELECT * FROM sqlite_query('s', 'SELECT 1')",
        "SELECT getenv('pgPassword')",
        "SET pg_pool_health_check_query = 'SELECT 1'"
      )
    do
      val calls = StringBuilder()
      validator(Right((200, """{"result":{"allow":true}}""")), calls).validate(
        ctx(sql, Some(eff(true)))
      ) shouldBe a[Denied]
      withClue(sql)(calls.toString shouldBe "")

  it should "deny an unknown tenant without calling OPA" in:
    val calls = StringBuilder()
    val other = ctx("SELECT * FROM orders", Some(eff(true)))
      .copy(poolKey = Some(PoolKey("globex", "tpch", "bi")))
    validator(Right((200, """{"result":{"allow":true}}""")), calls).validate(other) shouldBe
      a[Denied]
    calls.toString shouldBe ""

  it should "deny a write outside a token's verb ceiling without asking OPA" in:
    val calls = StringBuilder()
    val v     = validator(Right((200, """{"result":{"allow":true}}""")), calls)
    val ro    = eff(true).copy(verbCeiling = Some(Set(ai.starlake.acl.parser.Verb.Read)))
    v.validate(ctx("INSERT INTO orders VALUES (1)", Some(ro))) match
      case Denied(reason, unauthorized, meta) =>
        reason should include("token verb ceiling")
        unauthorized.map(_.verb) shouldBe Set(ai.starlake.acl.parser.Verb.Write)
        meta.get("authz_source") shouldBe Some("opa")
      case other => fail(s"expected Denied, got $other")
    calls.toString shouldBe ""
    // A read inside the ceiling still goes to OPA.
    v.validate(ctx("SELECT * FROM orders", Some(ro))) shouldBe Allowed
    calls.toString should include("/v1/data/qod/authz/statement")
