package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** TDD coverage for the pure helpers extracted from the initializer's ATTACH
  * ([[DuckLakeInitializer.attachSql]], [[DuckLakeInitializer.encryptionMismatch]],
  * [[DuckLakeInitializer.migrationPlan]], [[DuckLakeInitializer.initializerConnstr]],
  * [[DuckLakeInitializer.isLockTimeout]]), so its option list, the mismatch guard's messages, the
  * migration decision and the lock-timeout classification are assertable without a live Postgres.
  */
class DuckLakeInitializerSqlSpec extends AnyFlatSpec with Matchers:

  private val connstr = CatalogPgSecret.connstr("h", "5432", "d", "u")

  "attachSql" should "carry DATA_PATH and no AUTOMATIC_MIGRATION when the plan does not migrate" in {
    val sql = DuckLakeInitializer.attachSql(
      connstr,
      "acme_lake",
      "/var/lake",
      encrypted = false,
      migrate = false
    )
    sql should endWith(
      "(DATA_PATH '/var/lake', METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'})"
    )
    sql should not include "ENCRYPTED"
    sql should not include "AUTOMATIC_MIGRATION"
  }

  it should "carry AUTOMATIC_MIGRATION only when the plan migrates" in {
    val sql = DuckLakeInitializer.attachSql(
      connstr,
      "acme_lake",
      "/var/lake",
      encrypted = false,
      migrate = true
    )
    sql should endWith(
      "(DATA_PATH '/var/lake', AUTOMATIC_MIGRATION TRUE, " +
        "METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'})"
    )
  }

  it should "read the catalog password from the temporary secret, never from the ATTACH string" in {
    val sql = DuckLakeInitializer.attachSql(
      connstr,
      "acme_lake",
      "/var/lake",
      encrypted = true,
      migrate = true
    )
    sql.toLowerCase should not include "password"
    sql should include("METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'}")
  }

  it should "carry ENCRYPTED alongside DATA_PATH when encryption is on" in {
    val sql = DuckLakeInitializer.attachSql(
      connstr,
      "acme_lake",
      "/var/lake",
      encrypted = true,
      migrate = false
    )
    // Pin the separator, not just the two tokens: DuckLake parses the parenthesized list as
    // comma-separated options, so a missing or wrong separator is a syntax error at the one ATTACH
    // that decides a catalog's encryption forever.
    sql should endWith(
      "(DATA_PATH '/var/lake', ENCRYPTED, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'})"
    )
    DuckLakeInitializer.attachOptions("/var/lake", encrypted = true, migrate = true) shouldBe
      "DATA_PATH '/var/lake', ENCRYPTED, AUTOMATIC_MIGRATION TRUE"
  }

  // ---------- initializerConnstr: the lock_timeout rides the initializer's connstr only ----------

  "initializerConnstr" should "add a libpq lock_timeout option to the plain catalog connstr" in {
    val c = DuckLakeInitializer.initializerConnstr("h", "5432", "d", "u")
    c shouldBe s"$connstr options='-c lock_timeout=${DuckLakeInitializer.CatalogLockTimeoutMs}'"
    DuckLakeInitializer.CatalogLockTimeoutMs shouldBe 5000
    // The node path keeps the plain connstr: a node's attach runs no DDL.
    connstr should not include "lock_timeout"
  }

  it should "survive the DuckDB literal the ATTACH wraps it in" in {
    val c   = DuckLakeInitializer.initializerConnstr("h", "5432", "d", "u")
    val sql = DuckLakeInitializer.attachSql(c, "d", "/var/lake", encrypted = false, migrate = false)
    sql should startWith(
      "ATTACH 'ducklake:postgres:host=''h'' port=''5432'' dbname=''d'' user=''u'' " +
        "options=''-c lock_timeout=5000''' AS \"d\""
    )
  }

  // ---------- migrationPlan: AUTOMATIC_MIGRATION only when the catalog needs it ----------

  import DuckLakeInitializer.{migrationPlan, MetadataRead, MigrationPlan}

  private val build  = Some("duckdb=b15358c371 ducklake=e8924cfc9c")
  private val latest = "1.1-dev1"

  "migrationPlan" should "create a catalog that does not exist or is not established yet" in {
    migrationPlan(MetadataRead.NoTable, established = false, None, build, latest) shouldBe
      MigrationPlan.Create
    migrationPlan(MetadataRead.NoRow, established = false, None, build, latest) shouldBe
      MigrationPlan.Create
    migrationPlan(MetadataRead.Value("1.0"), established = false, None, build, latest) shouldBe
      MigrationPlan.Create
  }

  it should "migrate a catalog older than the engine's latest version" in
    List("0.1", "0.2", "0.3-dev1", "0.3", "0.4-dev1", "0.4", "1.0", "1.1-dev0").foreach { v =>
      withClue(v) {
        migrationPlan(MetadataRead.Value(v), established = true, build, build, latest) shouldBe
          MigrationPlan.Migrate
      }
    }

  it should "attach a dev catalog as-is when the current engine build last migrated it" in {
    migrationPlan(MetadataRead.Value(latest), established = true, build, build, latest) shouldBe
      MigrationPlan.AsIs
  }

  it should "re-migrate a dev catalog last migrated by another engine build, or by none recorded" in {
    val other = Some("duckdb=b15358c371 ducklake=0000000000")
    migrationPlan(MetadataRead.Value(latest), established = true, other, build, latest) shouldBe
      MigrationPlan.Migrate
    migrationPlan(MetadataRead.Value(latest), established = true, None, build, latest) shouldBe
      MigrationPlan.Migrate
  }

  it should "always migrate a dev catalog when the current engine build is unknown" in {
    migrationPlan(MetadataRead.Value(latest), established = true, None, None, latest) shouldBe
      MigrationPlan.Migrate
  }

  it should "attach a catalog at a released latest version as-is, whatever the stamp" in {
    migrationPlan(MetadataRead.Value("1.1"), established = true, None, build, "1.1") shouldBe
      MigrationPlan.AsIs
  }

  it should "never flag a catalog newer than the engine" in {
    migrationPlan(MetadataRead.Value("1.1"), established = true, None, build, latest) shouldBe
      MigrationPlan.AsIs
    migrationPlan(MetadataRead.Value("2.0"), established = true, None, build, latest) shouldBe
      MigrationPlan.AsIs
  }

  it should "migrate when the recorded version is missing or does not parse" in {
    migrationPlan(MetadataRead.NoRow, established = true, build, build, latest) shouldBe
      MigrationPlan.Migrate
    migrationPlan(MetadataRead.Value("v1-weird"), established = true, build, build, latest) shouldBe
      MigrationPlan.Migrate
  }

  "compareVersions" should "order dev builds before their release" in {
    DuckLakeInitializer.compareVersions("1.1-dev1", "1.1").map(_.sign) shouldBe Some(-1)
    DuckLakeInitializer.compareVersions("1.1-dev1", "1.1-dev2").map(_.sign) shouldBe Some(-1)
    DuckLakeInitializer.compareVersions("1.0", "1.1-dev1").map(_.sign) shouldBe Some(-1)
    DuckLakeInitializer.compareVersions("0.4", "0.10").map(_.sign) shouldBe Some(-1)
    DuckLakeInitializer.compareVersions("1.1-dev1", "1.1-dev1") shouldBe Some(0)
    DuckLakeInitializer.compareVersions("nope", "1.0") shouldBe None
  }

  // ---------- isLockTimeout: a lock timeout is retry-later, never a mismatch ----------

  "isLockTimeout" should "recognize Postgres's lock timeout through DuckDB's relayed message" in {
    val relayed = new java.sql.SQLException(
      "IO Error: Failed to execute query \"ALTER TABLE ducklake_data_file ADD COLUMN IF NOT " +
        "EXISTS row_group_count BIGINT\": ERROR:  canceling statement due to lock timeout"
    )
    DuckLakeInitializer.isLockTimeout(relayed) shouldBe true
    DuckLakeInitializer.isLockTimeout(new RuntimeException("wrapped", relayed)) shouldBe true
    DuckLakeInitializer.isLockTimeout(new java.sql.SQLException("x", "55P03")) shouldBe true
  }

  it should "not mistake other failures for a lock timeout" in {
    DuckLakeInitializer.isLockTimeout(new java.sql.SQLException("connection refused")) shouldBe
      false
    DuckLakeInitializer.isLockTimeout(
      DuckLakeInitializer.DataPathMismatchException("tenant-db d: dataPath mismatch")
    ) shouldBe false
  }

  "CatalogLockTimeoutException" should "not be a PreInitMismatchException" in {
    val e: Throwable =
      DuckLakeInitializer.CatalogLockTimeoutException("lock", new RuntimeException("lock timeout"))
    e.isInstanceOf[DuckLakeInitializer.PreInitMismatchException] shouldBe false
  }

  "encryptionMismatch" should "pass when the catalog has no recorded value yet" in {
    DuckLakeInitializer.encryptionMismatch(None, wanted = true) shouldBe None
  }

  it should "pass when the recorded value agrees" in {
    DuckLakeInitializer.encryptionMismatch(Some("true"), wanted = true) shouldBe None
    DuckLakeInitializer.encryptionMismatch(Some("false"), wanted = false) shouldBe None
  }

  it should "name the cause when the catalog was created unencrypted" in {
    DuckLakeInitializer.encryptionMismatch(Some("false"), wanted = true) shouldBe Some(
      "this DuckLake catalog was created unencrypted (ducklake_metadata.encrypted='false') but " +
        "the tenant-db row asks for encryption. Encryption cannot be enabled on an existing " +
        "catalog: create a new database with encrypted=true and copy the data."
    )
  }

  it should "name the cause when the catalog was created encrypted" in {
    DuckLakeInitializer.encryptionMismatch(Some("true"), wanted = false) shouldBe Some(
      "this DuckLake catalog was created encrypted (ducklake_metadata.encrypted='true') but the " +
        "tenant-db row asks for no encryption. Encryption cannot be removed from an existing " +
        "catalog: create a new database with encrypted=false and copy the data."
    )
  }

  "storageSqlFor" should "keep INSTALL/LOAD for object stores when no extension dir is set" in {
    val none = DuckDbExtensionSource(None)
    DuckLakeInitializer.storageSqlFor("s3://b/p/", none).head shouldBe
      "INSTALL httpfs; LOAD httpfs;"
    DuckLakeInitializer.storageSqlFor("az://c/p/", none).head shouldBe
      "INSTALL azure; LOAD azure;"
    DuckLakeInitializer.storageSqlFor("/var/lake", none) shouldBe Nil
  }

  it should "LOAD object-store extensions by path under an extension dir, never INSTALL" in {
    val src = DuckDbExtensionSource(Some("/opt/ext"))
    val s3  = DuckLakeInitializer.storageSqlFor("s3://b/p/", src)
    s3.head shouldBe "LOAD '/opt/ext/httpfs.duckdb_extension';"
    val az = DuckLakeInitializer.storageSqlFor("abfss://c/p/", src)
    az.head shouldBe "LOAD '/opt/ext/azure.duckdb_extension';"
    (s3 ++ az).exists(_.contains("INSTALL")) shouldBe false
  }
