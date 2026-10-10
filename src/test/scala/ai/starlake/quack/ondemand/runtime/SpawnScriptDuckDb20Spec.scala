package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.sys.process.*

class SpawnScriptDuckDb20Spec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private def initSql(env: (String, String)*): String =
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake"
    )
    Process(Seq("bash", "scripts/spawn-quack-node.sh", "21999", "t"), None, (base ++ env)*).!!

  private def ducklake(extra: (String, String)*): String =
    initSql(
      (Seq(
        "kind"       -> "ducklake",
        "dataPath"   -> tempDir.resolve("lake").toString,
        "pgHost"     -> "h",
        "pgPort"     -> "5432",
        "pgUser"     -> "u",
        "pgPassword" -> "p"
      ) ++ extra)*
    )

  "the node init SQL" should "serve plain HTTP (disable_ssl)" in {
    ducklake() should include("disable_ssl := true")
  }

  it should "hold one statement per line (a failing INSTALL must not skip a LOAD)" in
    ducklake("encrypted" -> "true").linesIterator.foreach { line =>
      withClue(line)((line.contains("INSTALL ") && line.contains("LOAD ")) shouldBe false)
    }

  it should "NOT carry AUTOMATIC_MIGRATION on the node ATTACH" in {
    ducklake() should not include "AUTOMATIC_MIGRATION"
  }
