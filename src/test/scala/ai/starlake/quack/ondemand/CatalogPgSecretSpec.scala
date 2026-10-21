package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[CatalogPgSecret]]: the catalog Postgres password rides a temporary DuckDB secret, never an
  * ATTACH string (which `duckdb_databases().path` and connection errors print verbatim).
  */
class CatalogPgSecretSpec extends AnyFlatSpec with Matchers:

  "createSql" should "create a temporary postgres secret holding only the password" in {
    CatalogPgSecret.createSql(CatalogPgSecret.Name, "Pw-1") shouldBe
      "CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD 'Pw-1')"
  }

  it should "escape the password as a DuckDB literal" in {
    CatalogPgSecret.createSql(CatalogPgSecret.Name, "a'b c\\d") should include(
      "PASSWORD 'a''b c\\d'"
    )
  }

  "dropSql" should "drop the secret tolerating its absence" in {
    CatalogPgSecret.dropSql(CatalogPgSecret.BranchName) shouldBe
      "DROP SECRET IF EXISTS qod_branch_pg"
  }

  "metadataParameters" should "name the secret for DuckLake's metadata attach" in {
    CatalogPgSecret.metadataParameters(CatalogPgSecret.Name) shouldBe
      "METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'}"
  }

  "connstr" should "carry host, port, dbname and user but never a password" in {
    val c = CatalogPgSecret.connstr("h", "5432", "d", "u")
    c shouldBe "ducklake:postgres:host='h' port='5432' dbname='d' user='u'"
    c.toLowerCase should not include "password"
  }

  it should "escape libpq values" in {
    CatalogPgSecret.connstr("h", "5432", "d", "o'neil") should include("user='o\\'neil'")
  }

  "a secret name" should "be a plain identifier" in {
    an[IllegalArgumentException] should be thrownBy CatalogPgSecret.dropSql("x; DROP")
  }
