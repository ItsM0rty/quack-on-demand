package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{InetSocketAddress, Socket}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/** A node spawned by the real script with the pinned DuckDB CLI, queried over its own quack
  * endpoint from another process, the way the manager reaches it. Cancelled without the pinned CLI.
  */
class SpawnScriptLiveNodeSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val script = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString
  private val Token  = "tok-live-node-7c41"

  private lazy val pinnedDuckdb: Option[Path] =
    val abi = BuildVersions.duckdb.split('.').take(3).mkString(".")
    sys.env
      .get("DUCKDB_BIN")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .orElse(sys.env.get("DUCKDB_CACHE_DIR").map(d => Paths.get(d, abi, "bin", "duckdb")))
      .filter(Files.isExecutable(_))

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private def listening(port: Int): Boolean =
    Try {
      val s = new Socket()
      try s.connect(new InetSocketAddress("127.0.0.1", port), 200)
      finally s.close()
    }.isSuccess

  /** Start a `memory` node with `extraSetupSql`, run `body(port)` once it listens, then stop it. */
  private def withNode[A](extraSetupSql: String, env: (String, String)*)(body: Int => A): A =
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    val port    = freePort()
    val secrets = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> Token, "extraSetupSql" -> extraSetupSql),
      Some(tempDir)
    )
    val pb = new java.lang.ProcessBuilder("bash", script, port.toString)
      .directory(Files.createDirectories(tempDir.resolve("start")).toFile)
      .redirectErrorStream(true)
      .redirectOutput(tempDir.resolve("node.log").toFile)
    pb.environment()
      .putAll(
        (Seq(
          "kind"          -> "memory",
          "schemaName"    -> "main",
          "dbName"        -> "acme_mem",
          "DUCKDB_BIN"    -> pinnedDuckdb.get.toString,
          "QOD_NODE_BIND" -> "127.0.0.1",
          // A throwaway HOME, so the node never reads the developer's own ~/.duckdb (extensions
          // resolve the same; before the persistent-secrets fix a real HOME also leaked stored
          // secrets into the node).
          "HOME"                 -> Files.createDirectories(tempDir.resolve("node-home")).toString,
          NodeSecretFiles.DirEnv -> secrets.toString,
          NodeSecretFiles.EphemeralEnv -> "1"
        ) ++ env).toMap.asJava
      )
    val p = pb.start()
    try
      val deadline = System.currentTimeMillis() + 60000
      while !listening(port) && p.isAlive && System.currentTimeMillis() < deadline do
        Thread.sleep(100)
      withClue(Files.readString(tempDir.resolve("node.log")))(listening(port) shouldBe true)
      body(port)
    finally
      p.destroy()
      if !p.waitFor(20, TimeUnit.SECONDS) then p.destroyForcibly()

  /** `sql` run on the node through `quack_query` from a separate DuckDB process; CSV rows. */
  private def onNode(port: Int, sql: String): List[String] =
    val inner = sql.replace("'", "''")
    val out   = new StringBuilder
    val err   = new StringBuilder
    val rc    = Process(
      Seq(
        pinnedDuckdb.get.toString,
        "-csv",
        "-noheader",
        "-c",
        s"SELECT * FROM quack_query('quack:127.0.0.1:$port', '$inner', token := '$Token');"
      ),
      tempDir.toFile,
      "DUCKDB_AGENT_MODE" -> "0",
      "HOME"              -> Files.createDirectories(tempDir.resolve("client-home")).toString
    ) ! ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    withClue(err.toString)(rc shouldBe 0)
    out.toString.linesIterator.toList

  // The DuckDB 2.0 CLI records every statement it reads from stdin in shell_history(), which any
  // session of the node can query; the init SQL carries the node token, the catalog password and the
  // federation / object-store secrets. It must reach duckdb by a path the CLI does not record.
  "a node" should "keep its init SQL, and the credentials in it, out of shell_history()" in {
    val marker = "AKIAINITMARKER7731"
    // The failing statement first: a node carries on past an init error, as it always has.
    withNode(
      "SELECT * FROM no_such_table;\n" +
        s"CREATE OR REPLACE TEMPORARY SECRET fed_marker (TYPE s3, KEY_ID '$marker', SECRET 'x');"
    ) { port =>
      onNode(port, "SELECT count(*) FROM duckdb_secrets() WHERE name = 'fed_marker'") shouldBe
        List("1")
      onNode(port, "SELECT 42") shouldBe List("42")
      val history = onNode(port, "SELECT sql FROM shell_history()").mkString("\n")
      history should not include marker
      history should not include Token
      history should not include "quack_serve"
    }
  }

  // DuckDB loads every persistent secret of the OS user (~/.duckdb/stored_secrets) into a node on
  // first secret use, so an operator's own cloud keys would serve every tenant on the node. The
  // node starts with persistent secrets off and the secret manager already in use, which DuckDB
  // then refuses to switch back.
  it should "not load the OS user's persistent secrets" in {
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    val home = Files.createDirectories(tempDir.resolve("home"))
    Process(
      Seq(
        pinnedDuckdb.get.toString,
        "-c",
        "CREATE PERSISTENT SECRET operator_keys (TYPE s3, KEY_ID 'AKIAOPERATOR', SECRET 's')"
      ),
      tempDir.toFile,
      "HOME"              -> home.toString,
      "DUCKDB_AGENT_MODE" -> "0"
    ).!! should include("true")
    withNode("SELECT 1;", "HOME" -> home.toString) { port =>
      onNode(port, "SELECT count(*) FROM duckdb_secrets()") shouldBe List("0")
      onNode(port, "SELECT current_setting('allow_persistent_secrets')") shouldBe List("false")
    }
  }

  "the node init SQL" should "turn persistent secrets off before anything else" in {
    val dir = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> Token, "pgPassword" -> "p"),
      Some(tempDir)
    )
    val lines = Process(
      Seq("bash", script, "21999"),
      None,
      "QOD_SPAWN_DRY_RUN"          -> "1",
      "kind"                       -> "ducklake",
      "schemaName"                 -> "main",
      "dbName"                     -> "acme_lake",
      "dataPath"                   -> tempDir.resolve("lake").toString,
      "dbInitSql"                  -> "SELECT 'db-init';",
      NodeSecretFiles.DirEnv       -> dir.toString,
      NodeSecretFiles.EphemeralEnv -> "1"
    ).!!.linesIterator.filter(_.trim.nonEmpty).toVector
    lines.take(3) shouldBe Vector(
      ".bail off",
      "SET allow_persistent_secrets = false;",
      "SELECT count(*) AS qod_secret_manager_ready FROM duckdb_secrets();"
    )
  }
