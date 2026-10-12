package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.rdbms.RdbmsConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The MySQL twin of [[PostgresFederationLiveSpec]]: a `mysql` source rendered by the real builder,
  * attached by a live node, read through its alias, writes refused, no secret left behind.
  *
  * CANCELLED unless all of these hold, and today they do not: the DuckDB 2.0 pre-release toolchain
  * ships no mysql extension (the CLI cannot `LOAD mysql`), and no MySQL server is provisioned for
  * the suite. Point it at one with SL_TEST_MYSQL_HOST / _PORT / _USER / _PASSWORD / _DATABASE; the
  * database must hold a table `qod_fed_probe` with exactly one row, created by the operator, since
  * the source under test attaches read-only.
  */
class MysqlFederationLiveSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val env   = sys.env
  private val alias = "shop_my"

  private lazy val mysqlEnv: Option[(String, Int, String, String, String)] =
    for
      host <- env.get("SL_TEST_MYSQL_HOST").filter(_.nonEmpty)
      user <- env.get("SL_TEST_MYSQL_USER").filter(_.nonEmpty)
      pass <- env.get("SL_TEST_MYSQL_PASSWORD").filter(_.nonEmpty)
      db   <- env.get("SL_TEST_MYSQL_DATABASE").filter(_.nonEmpty)
    yield (
      host,
      env.get("SL_TEST_MYSQL_PORT").flatMap(_.toIntOption).getOrElse(3306),
      user,
      pass,
      db
    )

  private lazy val available: Boolean =
    mysqlEnv.nonEmpty && RdbmsFederationLiveNode.canLoad("mysql")

  private var tempDir: Path                         = null
  private var node: Option[RdbmsFederationLiveNode] = None

  private def requireLive(): RdbmsFederationLiveNode =
    assume(RdbmsFederationLiveNode.pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    assume(mysqlEnv.nonEmpty, "no MySQL server configured (SL_TEST_MYSQL_*)")
    assume(
      RdbmsFederationLiveNode.canLoad("mysql"),
      "the pinned DuckDB CLI cannot load the mysql extension (absent from the 2.0 toolchain)"
    )
    node.getOrElse(fail("node did not start"))

  override def beforeAll(): Unit =
    if available then
      val (host, port, user, pass, db) = mysqlEnv.get
      val src                          = FederatedSource(
        id = "fs-" + alias,
        tenantDbId = "td-live",
        alias = alias,
        sourceType = FederatedSourceType.Mysql,
        config = Some(
          RdbmsConfig(
            host = host,
            port = Some(port),
            database = db,
            user = user,
            password = "{{secret.MY_PWD}}"
          ).toJson
        ),
        readOnly = true
      )
      tempDir = Files.createTempDirectory("mysql-federation-live")
      val n = new RdbmsFederationLiveNode(tempDir)
      n.start(
        n.blob(List(src), List(FederatedSecret("sec-pw", src.id, "MY_PWD", Some(pass), None)))
      )
      node = Some(n)

  override def afterAll(): Unit =
    node.foreach(_.stop())
    Option(tempDir).foreach { d =>
      Try(Files.walk(d).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists))
    }

  private def db: String = mysqlEnv.map(_._5).getOrElse("")

  "a mysql source" should "serve a remote table through its alias" in {
    val n            = requireLive()
    val (rc, out, e) = n.run(s"SELECT count(*) FROM $alias.$db.qod_fed_probe")
    withClue(s"$e\n${n.log}")(rc shouldBe 0)
    out shouldBe List("1")
  }

  it should "refuse a write with DuckDB's read-only error" in {
    val n            = requireLive()
    val (rc, _, err) = n.run(s"DELETE FROM $alias.$db.qod_fed_probe")
    rc should not be 0
    err should include("attached in read-only mode")
  }

  it should "keep the password out of duckdb_databases().path and leave no secret behind" in {
    val n = requireLive()
    n.run(s"SELECT path FROM duckdb_databases() WHERE database_name = '$alias'")._2 shouldBe
      List("")
    n.run("SELECT count(*) FROM duckdb_secrets()")._2 shouldBe List("0")
  }
