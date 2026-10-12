package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, DriverManager}
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/** Regression for the DuckDB 2.0 thread blow-up: an in-memory 2.0 instance with default settings
  * costs ~79 native threads (`threads` = cores plus an `async_threads` pool of 4 x cores, capped at
  * 256), so tests that kept instances open drove the forked test JVM into macOS's 16384
  * threads-per-process limit. Every in-process instance goes through [[InProcessDuckDb]]; this spec
  * opens many through the production helper and bounds the process's native thread growth.
  */
class InProcessDuckDbSpec extends AnyFlatSpec with Matchers:

  Class.forName("org.duckdb.DuckDBDriver")

  /** Native threads of this process, counted by the OS: `/proc/self/task` on Linux, `ps -M` on
    * macOS (one line per thread after the header). JVM APIs (`Thread.getAllStackTraces`,
    * ThreadMXBean) only see threads attached to the JVM, not DuckDB's own pools. None elsewhere.
    */
  private def nativeThreads(): Option[Int] =
    val task = Paths.get("/proc/self/task")
    if Files.isDirectory(task) then
      val s = Files.list(task)
      try Some(s.iterator().asScala.size)
      finally s.close()
    else if sys.props.getOrElse("os.name", "").toLowerCase.contains("mac") then
      val pid = ProcessHandle.current().pid()
      Try(Seq("ps", "-M", "-p", pid.toString).!!.linesIterator.count(_.trim.nonEmpty) - 1).toOption
    else None

  private def settingsOf(c: Connection): (String, String) =
    val st = c.createStatement()
    try
      val rs = st.executeQuery(
        "SELECT current_setting('threads')::VARCHAR, current_setting('async_threads')::VARCHAR"
      )
      try
        rs.next()
        (rs.getString(1), rs.getString(2))
      finally rs.close()
    finally st.close()

  private def closeAll(cs: Iterable[Connection]): Unit =
    cs.foreach(c => Try(c.close()))

  "DuckDbExtensionSource.connectionProperties" should "carry the in-process thread budget" in {
    val p = DuckDbExtensionSource(None).connectionProperties
    p.getProperty("threads") shouldBe "1"
    p.getProperty("async_threads") shouldBe "1"
    val withDir = DuckDbExtensionSource(Some("/x")).connectionProperties
    withDir.getProperty("threads") shouldBe "1"
    withDir.getProperty("async_threads") shouldBe "1"
  }

  it should "open an instance whose engine settings are the budget" in {
    val c =
      DriverManager.getConnection("jdbc:duckdb:", DuckDbExtensionSource(None).connectionProperties)
    try settingsOf(c) shouldBe ("1", "1")
    finally c.close()
  }

  "InProcessDuckDb.url" should "carry the budget as URL options, in memory and on a file" in {
    InProcessDuckDb.url() should startWith(
      "jdbc:duckdb:;threads=1;async_threads=1;secret_directory="
    )
    InProcessDuckDb.url() should not include "allow_persistent_secrets"
    val mem = DriverManager.getConnection(InProcessDuckDb.url())
    try settingsOf(mem) shouldBe ("1", "1")
    finally mem.close()
    val dir  = Files.createTempDirectory("qod-inproc-duckdb")
    val file = dir.resolve("t.duckdb")
    val c    = DriverManager.getConnection(InProcessDuckDb.url(file.toAbsolutePath.toString))
    try settingsOf(c) shouldBe ("1", "1")
    finally
      c.close()
      Files.list(dir).iterator().asScala.foreach(Files.deleteIfExists)
      Files.deleteIfExists(dir)
  }

  /** Runs [[InProcessSecretProbe]] in a child JVM whose HOME is `home`: DuckDB derives its default
    * secret directory (`~/.duckdb/stored_secrets`) from the HOME environment variable, which a JVM
    * cannot change for itself (`home_directory` does not move it). The probe's last output line.
    */
  private def probeWithHome(home: Path, mode: String): String =
    val java = Paths.get(sys.props("java.home"), "bin", "java").toString
    val out  = new StringBuilder
    val rc   = Process(
      Seq(
        java,
        "-cp",
        sys.props("java.class.path"),
        InProcessSecretProbe.getClass.getName.stripSuffix("$"),
        mode
      ),
      None,
      "HOME" -> home.toString
    ) ! ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n'))
    withClue(out.toString)(rc shouldBe 0)
    out.toString.linesIterator.filter(_.nonEmpty).toList.last

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val s = Files.walk(root)
      try
        s.sorted(java.util.Comparator.reverseOrder[Path]()).iterator().asScala.foreach { p =>
          Try(p.toFile.setWritable(true, true))
          Files.deleteIfExists(p)
        }
      finally s.close()

  private def query1(c: Connection, sql: String): String =
    val st = c.createStatement()
    try
      val rs = st.executeQuery(sql)
      try
        rs.next()
        rs.getString(1)
      finally rs.close()
    finally st.close()

  // DuckDB loads every persistent secret of the OS user (~/.duckdb/stored_secrets) into an instance
  // on first secret use, so an operator's own cloud keys would serve the manager's statements.
  "An in-process instance" should "not load the OS user's persistent secrets" in {
    val home = Files.createTempDirectory("qod-inproc-home")
    try
      // Seeded through an explicit secret_directory, so nothing here ever resolves the real HOME.
      val seed = new java.util.Properties()
      seed.setProperty("secret_directory", home.resolve(".duckdb/stored_secrets").toString)
      val maker = DriverManager.getConnection("jdbc:duckdb:", seed)
      try
        val st = maker.createStatement()
        try st.execute("CREATE PERSISTENT SECRET operator_keys (TYPE s3, KEY_ID 'k', SECRET 's')")
        finally st.close()
      finally maker.close()
      // Control: a plain instance under that HOME does load it, so the probe really reads HOME.
      probeWithHome(home, "plain") shouldBe "secrets=1"
      probeWithHome(home, "qod") shouldBe "secrets=0"
    finally deleteTree(home)
  }

  /** The marker file the in-process secret directory points below, and the directory holding it. */
  private def secretLayout(c: Connection): (Path, Path) =
    val configured = Paths.get(query1(c, "SELECT current_setting('secret_directory')"))
    // Checked first: the real ~/.duckdb is never inspected.
    configured.toAbsolutePath.startsWith(Paths.get(sys.props("user.home"))) shouldBe false
    configured shouldBe InProcessDuckDb.secretDirectory
    (configured.getParent, configured.getParent.getParent)

  private def entries(dir: Path): List[String] =
    val s = Files.list(dir)
    try s.iterator().asScala.map(_.getFileName.toString).toList.sorted
    finally s.close()

  private def refusePersistentCreate(c: Connection): Unit =
    val st = c.createStatement()
    try
      val e = intercept[java.sql.SQLException](
        st.execute("CREATE PERSISTENT SECRET tenant_keys (TYPE s3, KEY_ID 'k', SECRET 's')")
      )
      e.getMessage should include("File exists")
    finally st.close()

  // A path below a regular file of a read-only directory: it can never exist as a directory, so
  // nothing loads and CREATE PERSISTENT SECRET fails, and the marker cannot be replaced.
  it should "point its secret directory below a read-only marker file" in {
    val c =
      DriverManager.getConnection("jdbc:duckdb:", DuckDbExtensionSource(None).connectionProperties)
    try
      val (marker, dir) = secretLayout(c)
      Files.isRegularFile(marker) shouldBe true
      Files.size(marker) shouldBe 0
      if Files.getFileStore(dir).supportsFileAttributeView("posix") then
        PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "r-x------"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(marker)) shouldBe "r--------"
      refusePersistentCreate(c)
      query1(c, "SELECT count(*) FROM duckdb_secrets()") shouldBe "0"
      entries(dir) shouldBe List("none")
      Files.size(marker) shouldBe 0
    finally c.close()
  }

  // What a root process with CAP_DAC_OVERRIDE sees: the directory is writable. The marker alone
  // still makes the configured path impossible, so a persistent create fails all the same.
  it should "refuse CREATE PERSISTENT SECRET even when its directory is writable" in {
    val c =
      DriverManager.getConnection("jdbc:duckdb:", DuckDbExtensionSource(None).connectionProperties)
    try
      val (_, dir) = secretLayout(c)
      assume(Files.getFileStore(dir).supportsFileAttributeView("posix"), "POSIX permissions only")
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
      try
        refusePersistentCreate(c)
        entries(dir) shouldBe List("none")
      finally Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
    finally c.close()
  }

  it should "refuse a change of secret_directory once the secret manager is in use" in {
    val c =
      DriverManager.getConnection("jdbc:duckdb:", DuckDbExtensionSource(None).connectionProperties)
    try
      query1(c, "SELECT count(*) FROM duckdb_secrets()") shouldBe "0"
      val st = c.createStatement()
      try
        val other = Files.createTempDirectory("qod-inproc-other")
        try
          val e = intercept[java.sql.SQLException](
            st.execute(s"SET secret_directory = '${other.toString.replace("'", "''")}'")
          )
          e.getMessage should include("after the secret manager is used")
        finally deleteTree(other)
      finally st.close()
    finally c.close()
  }

  // On DuckDB 2.0 `allow_persistent_secrets = false` broke every postgres ATTACH that names no
  // SECRET ("Unknown secret storage found: local_file"), inline credentials included.
  it should "attach Postgres with an inline password" in {
    TestPostgres.ensureReachable()
    val ext = DuckDbExtensionSource.fromEnv
    val c   = DriverManager.getConnection("jdbc:duckdb:", ext.connectionProperties)
    try
      val st = c.createStatement()
      try
        st.execute(ext.loadSql("postgres_scanner"))
        st.execute(
          s"ATTACH 'host=${TestPostgres.pgHost} port=${TestPostgres.pgPort} dbname=postgres " +
            s"user=${TestPostgres.pgUser} password=${TestPostgres.pgPass}' AS pg (TYPE postgres, READ_ONLY)"
        )
      finally st.close()
      query1(c, "SELECT * FROM postgres_query('pg', 'SELECT 41 + 1')") shouldBe "42"
    finally c.close()
  }

  "Many open in-process instances" should "cost a small fixed number of native threads" in {
    val before = nativeThreads()
    assume(before.isDefined, "no OS thread count on this platform")
    // Uncapped, 30 instances cost ~2400 threads on a 16-core host and ~300 on 2 cores; capped,
    // under one each. The bound leaves room for unrelated JVM threads coming and going.
    val n  = 30
    val cs = scala.collection.mutable.ListBuffer.empty[Connection]
    try
      (1 to n).foreach { _ =>
        val c = DriverManager.getConnection(
          "jdbc:duckdb:",
          DuckDbExtensionSource(None).connectionProperties
        )
        cs += c
        // A query per instance, so any lazily started pool is up when we count.
        settingsOf(c) shouldBe ("1", "1")
      }
      val grown = nativeThreads().get - before.get
      withClue(s"$n open instances grew the process by $grown native threads: ") {
        grown should be < 2 * n
      }
    finally closeAll(cs)
  }

/** Child-JVM side of [[InProcessDuckDbSpec]]'s HOME case: opens one instance, `plain` (no settings)
  * or `qod` (the manager's [[InProcessDuckDb.properties]]), and prints how many secrets it holds.
  */
object InProcessSecretProbe:
  def main(args: Array[String]): Unit =
    Class.forName("org.duckdb.DuckDBDriver")
    val p =
      if args.headOption.contains("qod") then InProcessDuckDb.properties
      else new java.util.Properties()
    val c = DriverManager.getConnection("jdbc:duckdb:", p)
    try
      val rs = c.createStatement().executeQuery("SELECT count(*) FROM duckdb_secrets()")
      rs.next()
      println(s"secrets=${rs.getInt(1)}")
    finally c.close()
