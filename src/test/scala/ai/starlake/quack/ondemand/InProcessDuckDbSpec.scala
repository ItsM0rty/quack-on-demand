package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
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
    InProcessDuckDb.url() shouldBe
      "jdbc:duckdb:;threads=1;async_threads=1;allow_persistent_secrets=false"
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

  // DuckDB loads every persistent secret of the OS user (~/.duckdb/stored_secrets) into an instance
  // on first secret use, so an operator's own cloud keys would serve the manager's statements.
  // `secret_directory` stands in for that home directory here.
  "An in-process instance" should "not load persistent secrets" in {
    val dir   = Files.createTempDirectory("qod-inproc-secrets")
    val plain = new java.util.Properties()
    plain.setProperty("secret_directory", dir.toString)
    val maker = DriverManager.getConnection("jdbc:duckdb:", plain)
    try
      val st = maker.createStatement()
      try
        st.execute("CREATE PERSISTENT SECRET operator_keys (TYPE s3, KEY_ID 'k', SECRET 's')")
        val rs = st.executeQuery("SELECT count(*) FROM duckdb_secrets()")
        rs.next(); rs.getInt(1) shouldBe 1
      finally st.close()
    finally maker.close()
    val p = DuckDbExtensionSource(None).connectionProperties
    p.setProperty("secret_directory", dir.toString)
    val c = DriverManager.getConnection("jdbc:duckdb:", p)
    try
      val st = c.createStatement()
      try
        val rs = st.executeQuery("SELECT count(*) FROM duckdb_secrets()")
        rs.next(); rs.getInt(1) shouldBe 0
      finally st.close()
    finally
      c.close()
      Files.list(dir).iterator().asScala.foreach(Files.deleteIfExists)
      Files.deleteIfExists(dir)
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
