package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.rdbms.RdbmsConfig
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A `postgres` federated source end to end: typed config -> `FederationBlobBuilder` -> the real
  * spawn script -> a live 2.0 node -> the test Postgres. Nothing in the node's federation SQL is
  * hand written.
  *
  * The remote side is built to be hostile to quoting: the role name, its password and the schema
  * name all carry a single quote (the password also a backslash and a double quote), so a query
  * that comes back proves every value crossed render, secret substitution and DuckDB's own parsing
  * unchanged. The role can only read, as an operator should configure it; the write refusals
  * asserted below are DuckDB's own read-only-mode errors, which fire before the remote is asked.
  *
  * Also the evidence for dropping the secret right after the ATTACH: once every backend the catalog
  * held is terminated from outside, the next query opens a NEW backend with no secret left on the
  * node.
  *
  * Cancelled without the pinned DuckDB CLI or a reachable test Postgres (SL_TEST_PG_*).
  */
class PostgresFederationLiveSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val suffix   = java.lang.Long.toHexString(System.nanoTime())
  private val database = s"qodfedpg_$suffix"
  private val role     = s"qod_fed'ro_$suffix"
  private val password = "p'w\\x\"y-" + suffix
  private val schema   = "sa'les"
  private val alias    = "shop_pg"

  private var tempDir: Path                         = null
  private var node: Option[RdbmsFederationLiveNode] = None

  private def available: Boolean =
    RdbmsFederationLiveNode.pinnedDuckdb.nonEmpty && TestPostgres.reachable

  private def requireLive(): RdbmsFederationLiveNode =
    assume(RdbmsFederationLiveNode.pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    assume(TestPostgres.reachable, "the test Postgres (SL_TEST_PG_*) is not reachable")
    node.getOrElse(fail("node did not start"))

  private def ident(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
  private def lit(s: String)   = "'" + s.replace("'", "''") + "'"

  private def adminExec(db: String, sqls: String*): Unit =
    val c =
      DriverManager.getConnection(TestPostgres.dbUrl(db), TestPostgres.pgUser, TestPostgres.pgPass)
    try sqls.foreach(s => c.createStatement().execute(s))
    finally c.close()

  /** Backend pids the role currently holds on the test database. */
  private def backends(): Set[Int] =
    val c =
      DriverManager.getConnection(TestPostgres.adminUrl, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val ps = c.prepareStatement(
        "SELECT pid FROM pg_stat_activity WHERE datname = ? AND usename = ?"
      )
      ps.setString(1, database)
      ps.setString(2, role)
      val rs  = ps.executeQuery()
      val out = Set.newBuilder[Int]
      while rs.next() do out += rs.getInt(1)
      out.result()
    finally c.close()

  private def terminateBackends(): Unit =
    adminExec(
      "postgres",
      s"SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
        s"WHERE datname = ${lit(database)} AND usename = ${lit(role)}"
    )

  private def source(cfg: RdbmsConfig, readOnly: Boolean) = FederatedSource(
    id = "fs-" + alias,
    tenantDbId = "td-live",
    alias = alias,
    sourceType = FederatedSourceType.Postgres,
    config = Some(cfg.toJson),
    readOnly = readOnly
  )

  override def beforeAll(): Unit =
    if available then
      adminExec(
        "postgres",
        s"CREATE DATABASE ${ident(database)}",
        s"CREATE ROLE ${ident(role)} LOGIN PASSWORD ${lit(password)}"
      )
      adminExec(
        database,
        s"CREATE SCHEMA ${ident(schema)}",
        s"CREATE TABLE ${ident(schema)}.orders (id int, amount numeric(10,2))",
        s"INSERT INTO ${ident(schema)}.orders VALUES (1, 10.50), (2, 20.00)",
        s"CREATE TABLE public.misc (x int)",
        s"GRANT CONNECT ON DATABASE ${ident(database)} TO ${ident(role)}",
        s"GRANT USAGE ON SCHEMA ${ident(schema)} TO ${ident(role)}",
        s"GRANT SELECT ON ALL TABLES IN SCHEMA ${ident(schema)} TO ${ident(role)}"
      )
      tempDir = Files.createTempDirectory("pg-federation-live")
      val cfg = RdbmsConfig(
        host = TestPostgres.pgHost,
        port = Some(TestPostgres.pgPort),
        database = database,
        user = role,
        password = "{{secret.PG_PWD}}",
        schema = Some(schema),
        sslMode = Some("disable")
      )
      val src = source(cfg, readOnly = true)
      val n   = new RdbmsFederationLiveNode(tempDir)
      n.start(
        n.blob(List(src), List(FederatedSecret("sec-pw", src.id, "PG_PWD", Some(password), None)))
      )
      node = Some(n)

  override def afterAll(): Unit =
    node.foreach(_.stop())
    if available then
      Try(adminExec("postgres", s"DROP DATABASE IF EXISTS ${ident(database)} WITH (FORCE)"))
      Try(adminExec("postgres", s"DROP ROLE IF EXISTS ${ident(role)}"))
    Option(tempDir).foreach { d =>
      Try(Files.walk(d).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists))
    }

  private def rows(sql: String): List[String] =
    val n            = requireLive()
    val (rc, out, e) = n.run(sql)
    withClue(s"$e\n--- node log ---\n${n.log}")(rc shouldBe 0)
    out

  "a postgres source" should "serve the remote table through its alias" in {
    rows(s"SELECT sum(amount)::VARCHAR FROM $alias.${ident(schema)}.orders") shouldBe List("30.50")
  }

  it should "attach only the configured schema" in {
    // Compared in SQL: the CSV client output quotes a value holding a quote.
    rows(
      s"SELECT count(*) FILTER (WHERE schema_name = ${lit(schema)}) || '/' || count(*) " +
        s"FROM duckdb_tables() WHERE database_name = '$alias'"
    ) shouldBe List("1/1")
  }

  it should "refuse a write with DuckDB's read-only error, before the remote is asked" in {
    val n            = requireLive()
    val (rc, _, err) = n.run(s"INSERT INTO $alias.${ident(schema)}.orders VALUES (3, 1)")
    rc should not be 0
    err should include("attached in read-only mode")
    rows(s"SELECT count(*) FROM $alias.${ident(schema)}.orders") shouldBe List("2")
  }

  it should "keep the password out of duckdb_databases().path and leave no secret behind" in {
    rows(s"SELECT path FROM duckdb_databases() WHERE database_name = '$alias'") shouldBe List("")
    rows(
      "SELECT string_agg(path, '|') FROM duckdb_databases()"
    ).mkString should not include password
    rows("SELECT count(*) FROM duckdb_secrets()") shouldBe List("0")
  }

  it should "not let a session re-attach the server through the dropped secret" in {
    val n            = requireLive()
    val (rc, _, err) = n.run(
      s"ATTACH '' AS pg_w (TYPE postgres, SECRET ${ident("qod_pg_" + alias)})"
    )
    rc should not be 0
    err.toLowerCase should include("secret")
    rows("SELECT count(*) FROM duckdb_databases() WHERE database_name = 'pg_w'") shouldBe List("0")
  }

  it should "reconnect with a NEW backend after every backend was terminated, secret long gone" in {
    rows(s"SELECT count(*) FROM $alias.${ident(schema)}.orders") shouldBe List("2")
    val before = backends()
    before should not be empty
    terminateBackends()
    val deadline = System.currentTimeMillis() + 10000
    while backends().nonEmpty && System.currentTimeMillis() < deadline do Thread.sleep(100)
    backends() shouldBe empty
    // The first query after the kill may hit a dead pooled connection; the extension retries on a
    // fresh one. Either way, a query must succeed again without any secret on the node.
    val again = (1 to 3).iterator
      .map(_ => requireLive().run(s"SELECT count(*) FROM $alias.${ident(schema)}.orders"))
      .find(_._1 == 0)
    withClue(requireLive().log)(again.map(_._2) shouldBe Some(List("2")))
    val after = backends()
    after should not be empty
    (after intersect before) shouldBe empty
    rows("SELECT count(*) FROM duckdb_secrets()") shouldBe List("0")
  }

  "a hostile config" should "be refused before it reaches any node" in {
    val bad = RdbmsConfig(
      host = "/var/run/postgresql",
      database = database,
      user = role,
      password = "{{secret.PG_PWD}}"
    )
    val n = new RdbmsFederationLiveNode(Files.createTempDirectory("pg-federation-bad"))
    val e = intercept[RuntimeException](n.blob(List(source(bad, readOnly = true)), Nil))
    e.getMessage should include("socket path is not accepted")
  }
