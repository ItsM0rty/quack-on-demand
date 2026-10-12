package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import ai.starlake.quack.ondemand.DuckDbExtensionSource
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{InetSocketAddress, Socket}
import java.nio.file.attribute.PosixFilePermissions
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

  /** The pid of the duckdb node process listening on `port` (the lsof -t of that port), if any. */
  private def duckdbPidOn(port: Int): Option[Long] =
    Try(Process(Seq("lsof", "-nP", "-tiTCP:" + port, "-sTCP:LISTEN")).!!.trim).toOption
      .filter(_.nonEmpty)
      .flatMap(_.linesIterator.find(_.nonEmpty))
      .flatMap(s => Try(s.toLong).toOption)

  private def alive(pid: Long): Boolean =
    java.lang.ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)

  /** SIGKILL the wrapper and every duckdb it started, so a test can never leave an orphan. */
  private def hardStop(p: java.lang.Process): Unit =
    Try(p.descendants().forEach(d => d.destroyForcibly()))
    p.destroyForcibly()
    p.waitFor(20, TimeUnit.SECONDS)

  /** Start a node (default kind `memory`) and wait until it listens. Returns the wrapper process
    * and its port; the caller stops it. Credentials ride the secrets dir, as in production.
    */
  private def startNode(
      extraSetupSql: String = "",
      env: Seq[(String, String)] = Nil
  ): (java.lang.Process, Int) =
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
          "HOME" -> Files.createDirectories(tempDir.resolve("node-home")).toString,
          // The node directory (and the secret directory in it) lands where the test can see it.
          "TMPDIR"                     -> nodeTmp.toString,
          NodeSecretFiles.DirEnv       -> secrets.toString,
          NodeSecretFiles.EphemeralEnv -> "1"
        ) ++ env).toMap.asJava
      )
    val p        = pb.start()
    val deadline = System.currentTimeMillis() + 60000
    while !listening(port) && p.isAlive && System.currentTimeMillis() < deadline do
      Thread.sleep(100)
    withClue(Files.readString(tempDir.resolve("node.log")))(listening(port) shouldBe true)
    (p, port)

  /** Where the script creates its per-node directory (its TMPDIR). */
  private def nodeTmp: Path = Files.createDirectories(tempDir.resolve("node-tmp"))

  /** Start a `memory` node with `extraSetupSql`, run `body(port)` once it listens, then stop it. */
  private def withNode[A](extraSetupSql: String, env: (String, String)*)(body: Int => A): A =
    val (p, port) = startNode(extraSetupSql, env)
    try body(port)
    finally hardStop(p)

  /** `sql` run on the node through `quack_query` from a separate DuckDB process; CSV rows. */
  private def onNode(port: Int, sql: String): List[String] =
    val (rc, out, err) = onNodeResult(port, sql)
    withClue(err)(rc shouldBe 0)
    out.linesIterator.toList

  /** [[onNode]] without the success check: (exit code, stdout, stderr). */
  private def onNodeResult(port: Int, sql: String): (Int, String, String) =
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
    (rc, out.toString, err.toString)

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

  // DuckDB loads every persistent secret of its secret directory into a node on first secret use,
  // and the default is the OS user's ~/.duckdb/stored_secrets, so an operator's own cloud keys would
  // serve every tenant on the node. The node's secret directory is an empty read-only one of its
  // own, and the secret manager is already in use when it serves, so DuckDB refuses to move it.
  it should "not load the OS user's persistent secrets, nor store one of its own" in {
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
    Files.list(home.resolve(".duckdb/stored_secrets")).count() shouldBe 1
    val (p, port) = startNode("SELECT 1;", Seq("HOME" -> home.toString))
    try
      onNode(port, "SELECT count(*) FROM duckdb_secrets()") shouldBe List("0")
      val dir = Paths.get(onNode(port, "SELECT current_setting('secret_directory')").head)
      dir.startsWith(nodeTmp) shouldBe true
      dir.getFileName.toString shouldBe "secrets"
      PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "r-x------"
      val (rc, _, err) = onNodeResult(
        port,
        "CREATE PERSISTENT SECRET tenant_keys (TYPE s3, KEY_ID 'AKIATENANT', SECRET 's')"
      )
      rc should not be 0
      err should include("IO Error")
      val moved          = home.resolve(".duckdb/stored_secrets").toString.replace("'", "''")
      val (rc2, _, err2) = onNodeResult(port, s"SET secret_directory = '$moved'")
      rc2 should not be 0
      err2 should include("after the secret manager is used")
      onNode(port, "SELECT count(*) FROM duckdb_secrets()") shouldBe List("0")
      val left = Files.list(dir)
      try left.iterator().asScala.toList shouldBe empty
      finally left.close()
      // A normal stop (SIGTERM) removes the node directory, read-only secret directory included.
      p.destroy()
      p.waitFor(20, TimeUnit.SECONDS) shouldBe true
      Files.exists(dir) shouldBe false
    finally hardStop(p)
  }

  // On DuckDB 2.0, `allow_persistent_secrets = false` refused every postgres ATTACH that names no
  // SECRET ("Unknown secret storage found: local_file"), so operator federation SQL carrying inline
  // credentials broke. A read-only secret directory keeps that working.
  it should "accept an inline-password postgres ATTACH in the operator's init SQL" in {
    TestPostgres.ensureReachable()
    val attach =
      s"ATTACH 'host=${TestPostgres.pgHost} port=${TestPostgres.pgPort} dbname=postgres " +
        s"user=${TestPostgres.pgUser} password=${TestPostgres.pgPass}' AS fedpg (TYPE postgres, READ_ONLY);"
    withNode(DuckDbExtensionSource.fromEnv.loadSql("postgres_scanner") + "\n" + attach) { port =>
      onNode(port, "SELECT * FROM postgres_query('fedpg', 'SELECT 41 + 1')") shouldBe List("42")
    }
  }

  "the node init SQL" should "move the secret directory before anything else" in {
    val dir = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> Token, "pgPassword" -> "p"),
      Some(tempDir)
    )
    val lines = Process(
      Seq("bash", script, "21999"),
      None,
      "QOD_SPAWN_DRY_RUN"          -> "1",
      "TMPDIR"                     -> nodeTmp.toString,
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
      lines(1),
      "SELECT count(*) AS qod_secret_manager_ready FROM duckdb_secrets();"
    )
    // An absolute path in the node's own directory, under the script's TMPDIR.
    val tmp = nodeTmp.toString.replace("'", "''")
    lines(1) should fullyMatch regex
      ("""SET secret_directory = '\Q""" + tmp + """\E/quack-node\.[^/']+/secrets';""")
  }

  // The node is kept alive by a `.shell` dot-command that waits on the wrapper's pid, not by a
  // query (which would pin a transaction and could spin a core) and not by an open stdin FIFO (a
  // write channel). These pin that contract on a live node.
  "a running node" should "have duckdb's stdin on the null device" in {
    val (p, port) = startNode()
    try
      val pid = duckdbPidOn(port).getOrElse(fail("no duckdb on the node port"))
      val fd0 = Process(Seq("lsof", "-nP", "-p", pid.toString, "-a", "-d", "0", "-Fn")).!!
      withClue(fd0)(fd0 should include("/dev/null"))
    finally hardStop(p)
  }

  it should "die within a few seconds of a SIGKILL of its wrapper (no orphan)" in {
    val (p, port) = startNode()
    try
      val pid = duckdbPidOn(port).getOrElse(fail("no duckdb on the node port"))
      // SIGKILL the wrapper ALONE (not its children), the case the FIFO used to handle and a bare
      // blocking query would not: the keep-alive sees the wrapper gone and lets duckdb exit.
      java.lang.ProcessHandle.of(p.pid()).ifPresent(_.destroyForcibly())
      val deadline = System.currentTimeMillis() + 15000
      while alive(pid) && System.currentTimeMillis() < deadline do Thread.sleep(200)
      withClue(s"duckdb pid $pid still alive after the wrapper was killed")(
        alive(pid) shouldBe false
      )
    finally hardStop(p)
  }

  it should "sit near-idle on the CPU" in {
    val (p, port) = startNode()
    try
      val pid = duckdbPidOn(port).getOrElse(fail("no duckdb on the node port"))
      Thread.sleep(4000) // let any boot work settle
      val pcpu = Process(Seq("ps", "-o", "%cpu=", "-p", pid.toString)).!!.trim.toDouble
      // A busy-spin keep-alive pinned a core (~100%); a dot-command wait costs ~0. Generous bound.
      withClue(s"idle duckdb used $pcpu% CPU")(pcpu should be < 50.0)
    finally hardStop(p)
  }

  "a duckdb-file node" should "CHECKPOINT (the keep-alive holds no write transaction)" in {
    val dbFile    = tempDir.resolve("node.duckdb").toString
    val (p, port) = startNode(
      env = Seq(
        "kind"       -> "duckdb-file",
        "dbName"     -> "acme_f",
        "schemaName" -> "main",
        "dataPath"   -> dbFile
      )
    )
    try
      onNode(port, "CREATE TABLE acme_f.main.t AS SELECT range i FROM range(200000)")
      onNode(port, "INSERT INTO acme_f.main.t SELECT range FROM range(200000)")
      // Would fail "there are other write transactions active" if the keep-alive held one.
      onNode(port, "CHECKPOINT acme_f")
      onNode(port, "SELECT count(*) FROM acme_f.main.t") shouldBe List("400000")
    finally hardStop(p)
  }

  "a COPY to a relative path" should "reach no CLI stdin (the keep-alive is no write channel)" in
    withNode("") { port =>
      val marker = tempDir.resolve("start").resolve("PWNED").toString
      // If '../in' were a live FIFO on the CLI's stdin, this line would be read as input.
      Try(
        onNode(
          port,
          s"COPY (SELECT '.shell touch $marker' AS c) TO '../in' (FORMAT csv, HEADER false, QUOTE '')"
        )
      )
      Thread.sleep(1000)
      Files.exists(Paths.get(marker)) shouldBe false
    }
