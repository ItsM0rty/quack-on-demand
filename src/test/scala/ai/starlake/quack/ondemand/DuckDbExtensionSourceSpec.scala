package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

class DuckDbExtensionSourceSpec extends AnyFlatSpec with Matchers:

  "unset" should "install from the official repository, signed only" in {
    val s = DuckDbExtensionSource(None)
    s.loadSql("ducklake") shouldBe "INSTALL ducklake; LOAD ducklake;"
    s.loadSql("postgres_scanner") shouldBe "INSTALL postgres_scanner; LOAD postgres_scanner;"
    // Only the in-process thread budget: no unsigned extensions without a directory.
    s.connectionProperties.asScala.toMap shouldBe InProcessDuckDb.Settings.toMap
  }

  "a directory" should "LOAD the file by path and allow unsigned extensions" in {
    val s = DuckDbExtensionSource(Some("/opt/x/ext"))
    s.loadSql("ducklake") shouldBe "LOAD '/opt/x/ext/ducklake.duckdb_extension';"
    s.connectionProperties.getProperty("allow_unsigned_extensions") shouldBe "true"
  }

  it should "escape a quote in the path" in {
    DuckDbExtensionSource(Some("/o'x")).loadSql("ducklake") shouldBe
      "LOAD '/o''x/ducklake.duckdb_extension';"
  }

  it should "treat a blank value as unset" in {
    DuckDbExtensionSource.from(Map(DuckDbExtensionSource.EnvVar -> "  ")).dir shouldBe None
  }
