package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** TDD coverage for [[DuckLakeInitializer.attachSql]] and
  * [[DuckLakeInitializer.encryptionMismatch]], the two pure helpers extracted from the
  * catalog-creating ATTACH so its option list and the mismatch guard's messages are assertable
  * without a live Postgres.
  */
class DuckLakeInitializerSqlSpec extends AnyFlatSpec with Matchers:

  private val connstr = CatalogPgSecret.connstr("h", "5432", "d", "u")

  "attachSql" should "carry DATA_PATH and AUTOMATIC_MIGRATION when encryption is off" in {
    val sql = DuckLakeInitializer.attachSql(connstr, "acme_lake", "/var/lake", encrypted = false)
    sql should include("DATA_PATH '/var/lake', AUTOMATIC_MIGRATION TRUE")
    sql should endWith(
      "(DATA_PATH '/var/lake', AUTOMATIC_MIGRATION TRUE, " +
        "METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'})"
    )
    sql should not include "ENCRYPTED"
  }

  it should "read the catalog password from the temporary secret, never from the ATTACH string" in {
    val sql = DuckLakeInitializer.attachSql(connstr, "acme_lake", "/var/lake", encrypted = true)
    sql.toLowerCase should not include "password"
    sql should include("METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'}")
  }

  it should "carry ENCRYPTED alongside DATA_PATH when encryption is on" in {
    val sql = DuckLakeInitializer.attachSql(connstr, "acme_lake", "/var/lake", encrypted = true)
    sql should include("DATA_PATH '/var/lake'")
    sql should include("ENCRYPTED")
    // Pin the separator, not just the two tokens: DuckLake parses the parenthesized list as
    // comma-separated options, so a missing or wrong separator is a syntax error at the one ATTACH
    // that decides a catalog's encryption forever.
    sql should include("DATA_PATH '/var/lake', ENCRYPTED")
    sql should endWith(
      "(DATA_PATH '/var/lake', ENCRYPTED, AUTOMATIC_MIGRATION TRUE, " +
        "METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'})"
    )
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
