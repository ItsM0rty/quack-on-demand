package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.util.{Failure, Success, Try}

/** The initializer's migration decision against the pinned DuckLake, through the manager's own
  * in-process JDBC DuckDB and a DuckDB-file metadata catalog, so no Postgres is involved.
  *
  * Pins two facts [[DuckLakeInitializer.migrationPlan]] stands on: the engine's latest catalog
  * version is [[DuckLakeInitializer.LatestDuckLakeVersion]] (a pin bump that moves it fails here),
  * and on a current catalog an attach without `AUTOMATIC_MIGRATION` runs no migration DDL while a
  * flagged one re-runs it. The Postgres side (the lock_timeout, the stamp, the retry) is
  * `DuckLakeInitializerLockTimeoutSpec`.
  */
class DuckLakeInitializerMigrationSpec extends AnyFlatSpec with Matchers:

  Class.forName("org.duckdb.DuckDBDriver")

  private val ext = DuckDbExtensionSource.fromEnv

  /** Same rule as `DuckLakeInitializerRaceSpec`: an extension the machine cannot fetch is "DuckDB
    * unavailable", anything else is a real failure.
    */
  private def extensionUnavailable(t: Throwable): Boolean =
    val msg = Option(t.getMessage).getOrElse("").toLowerCase
    msg.contains("failed to download extension") ||
    msg.contains("unable to connect to extension repository") ||
    (msg.contains("extension") && msg.contains("not found"))

  /** A fresh in-process DuckDB with ducklake loaded, as the initializer opens it. */
  private def withDuckDb[A](f: Connection => A): A =
    val conn = DriverManager.getConnection("jdbc:duckdb:", ext.connectionProperties)
    try
      Try(exec(conn, ext.loadSql("ducklake"))) match
        case Success(_)                            => ()
        case Failure(t) if extensionUnavailable(t) =>
          cancel(s"DuckDB extensions unavailable (${t.getMessage}); skipping")
        case Failure(t) => throw t
      f(conn)
    finally conn.close()

  private def exec(conn: Connection, sql: String): Unit =
    val st = conn.createStatement()
    try st.execute(sql)
    finally st.close()

  private def scalar(conn: Connection, sql: String): String =
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(sql)
      try
        rs.next() shouldBe true
        rs.getString(1)
      finally rs.close()
    finally st.close()

  private def lake(): (Path, Path) =
    val dir = Files.createTempDirectory("qod-ducklake-migration")
    (dir.resolve("meta.ducklake"), Files.createDirectories(dir.resolve("data")))

  private def attach(conn: Connection, meta: Path, data: Path, migrate: Boolean): Unit =
    val opts = DuckLakeInitializer.attachOptions(s"$data/", encrypted = false, migrate = migrate)
    exec(conn, s"ATTACH ${DuckLakeInitializer.duckdbLiteral(s"ducklake:$meta")} AS lake ($opts)")

  /** Migration statements DuckLake ran on this connection since logging was enabled. */
  private def migrationDdl(conn: Connection): (Int, Int, Int) =
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(
        "SELECT " +
          "count(*) FILTER (WHERE message ILIKE '%ADD COLUMN IF NOT EXISTS%'), " +
          "count(*) FILTER (WHERE message ILIKE '%CREATE TABLE IF NOT EXISTS%'), " +
          "count(*) FILTER (WHERE message ILIKE 'UPDATE %ducklake_metadata%') " +
          "FROM duckdb_logs WHERE type = 'QueryLog'"
      )
      try
        rs.next()
        (rs.getInt(1), rs.getInt(2), rs.getInt(3))
      finally rs.close()
    finally st.close()

  "LatestDuckLakeVersion" should "be the version the pinned DuckLake stamps on a fresh catalog" in {
    val (meta, data) = lake()
    withDuckDb { conn =>
      attach(conn, meta, data, migrate = false)
      scalar(
        conn,
        "SELECT value FROM __ducklake_metadata_lake.ducklake_metadata WHERE key = 'version'"
      ) shouldBe DuckLakeInitializer.LatestDuckLakeVersion
    }
  }

  "engineBuild" should "name the engine and the loaded ducklake extension" in
    withDuckDb { conn =>
      val build = DuckLakeInitializer.engineBuild(conn)
      build.isDefined shouldBe true
      build.get should fullyMatch regex "duckdb=\\S+ ducklake=\\S+"
    }

  "a second boot of a current catalog" should "run no migration DDL" in {
    val (meta, data) = lake()
    // Boot 1 creates the catalog; the stamp it records is this build.
    val build = withDuckDb { conn =>
      attach(conn, meta, data, migrate = false)
      exec(conn, "CREATE TABLE lake.t AS SELECT range AS i FROM range(10)")
      DuckLakeInitializer.engineBuild(conn)
    }
    // Boot 2: the decision the initializer makes from what the catalog recorded.
    withDuckDb { conn =>
      exec(conn, s"ATTACH ${DuckLakeInitializer.duckdbLiteral(meta.toString)} AS m (READ_ONLY)")
      val version = scalar(conn, "SELECT value FROM m.ducklake_metadata WHERE key = 'version'")
      exec(conn, "DETACH m")
      val plan = DuckLakeInitializer.migrationPlan(
        DuckLakeInitializer.MetadataRead.Value(version),
        established = true,
        stamp = build,
        build = DuckLakeInitializer.engineBuild(conn),
        latest = DuckLakeInitializer.LatestDuckLakeVersion
      )
      plan shouldBe DuckLakeInitializer.MigrationPlan.AsIs

      exec(conn, "CALL enable_logging('QueryLog')")
      attach(conn, meta, data, migrate = plan == DuckLakeInitializer.MigrationPlan.Migrate)
      migrationDdl(conn) shouldBe (0, 0, 0)
      scalar(conn, "SELECT sum(i) FROM lake.t") shouldBe "45"
    }
  }

  /** The reason the flag must not ride every boot: on the pinned DuckLake a flagged attach of a
    * catalog already at the latest dev version re-runs the dev migration. If a pin bump stops
    * re-running it, this fails as a reminder that the stamp is no longer needed, not as a defect.
    */
  "a flagged attach of a current dev catalog" should "re-run the migration DDL on the pinned DuckLake" in {
    val (meta, data) = lake()
    withDuckDb(conn => attach(conn, meta, data, migrate = false))
    withDuckDb { conn =>
      exec(conn, "CALL enable_logging('QueryLog')")
      attach(conn, meta, data, migrate = true)
      val (alters, creates, updates) = migrationDdl(conn)
      withClue("ALTER TABLE ... ADD COLUMN IF NOT EXISTS statements: ") {
        alters should be > 0
      }
      (creates + updates) should be > 0
    }
  }
