package ai.starlake.quack.ondemand.federation.rdbms

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RdbmsSetupSqlSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val base = RdbmsConfig(
    host = "pg.example.com",
    database = "warehouse",
    user = "svc_reader",
    password = "{{secret.PG_PWD}}"
  )

  private def render(
      kind: RdbmsKind,
      cfg: RdbmsConfig = base,
      alias: String = "sales",
      readOnly: Boolean = true
  ): String =
    RdbmsSetupSql.render(RdbmsConfig.validated(kind, cfg, alias, Set.empty).value, readOnly)

  "RdbmsSetupSql.render" should "emit the full postgres block, credentials on a dropped secret" in {
    render(RdbmsKind.Postgres) shouldBe
      """INSTALL postgres;
        |LOAD postgres;
        |CREATE OR REPLACE TEMPORARY SECRET "qod_pg_sales" (
        |  TYPE postgres,
        |  HOST 'pg.example.com',
        |  PORT 5432,
        |  DATABASE 'warehouse',
        |  USER 'svc_reader',
        |  PASSWORD '{{secret.PG_PWD}}'
        |);
        |ATTACH '' AS "sales" (
        |  TYPE postgres,
        |  SECRET "qod_pg_sales",
        |  READ_ONLY
        |);
        |DROP SECRET IF EXISTS "qod_pg_sales";""".stripMargin
  }

  it should "emit the mysql block with its own extension, port form and TLS option" in {
    render(RdbmsKind.Mysql, base.copy(port = Some(3307), sslMode = Some("required"))) shouldBe
      """INSTALL mysql;
        |LOAD mysql;
        |CREATE OR REPLACE TEMPORARY SECRET "qod_mysql_sales" (
        |  TYPE mysql,
        |  HOST 'pg.example.com',
        |  PORT '3307',
        |  DATABASE 'warehouse',
        |  USER 'svc_reader',
        |  PASSWORD '{{secret.PG_PWD}}',
        |  SSL_MODE 'required'
        |);
        |ATTACH '' AS "sales" (
        |  TYPE mysql,
        |  SECRET "qod_mysql_sales",
        |  READ_ONLY
        |);
        |DROP SECRET IF EXISTS "qod_mysql_sales";""".stripMargin
  }

  it should "carry SSLMODE on the postgres secret and SCHEMA on the ATTACH" in {
    val sql =
      render(RdbmsKind.Postgres, base.copy(sslMode = Some("verify-full"), schema = Some("s")))
    sql should include("  SSLMODE 'verify-full'\n);")
    sql should include("  SECRET \"qod_pg_sales\",\n  SCHEMA 's',\n  READ_ONLY\n);")
  }

  it should "omit READ_ONLY only when readOnly is false" in {
    render(RdbmsKind.Postgres, readOnly = false) should not include "READ_ONLY"
    render(RdbmsKind.Mysql, readOnly = false) should not include "READ_ONLY"
    render(RdbmsKind.Postgres, readOnly = true) should include("READ_ONLY")
  }

  it should "never put a credential or a connection field in the ATTACH string" in {
    val sql = render(RdbmsKind.Postgres)
    sql should include("ATTACH '' AS")
    val attach = sql.substring(sql.indexOf("ATTACH"))
    attach should not include "PG_PWD"
    attach should not include "pg.example.com"
  }

  it should "drop the secret after the ATTACH, as the last statement" in {
    val sql = render(RdbmsKind.Postgres)
    sql.indexOf("DROP SECRET") should be > sql.indexOf("ATTACH")
    sql.trim should endWith(RdbmsSetupSql.cleanupSql(RdbmsKind.Postgres, "sales"))
  }

  it should "put every statement on its own lines, never two on one line" in {
    for kind <- RdbmsKind.values do
      render(kind).linesIterator.foreach { line =>
        withClue(line)(line.count(_ == ';') should be <= 1)
      }
  }

  it should "quote hostile literal values so they stay inside their literal" in {
    val hostile = base.copy(
      database = "x'); DROP TABLE t; --",
      user = "a\\'b\"c",
      schema = Some("s'; ATTACH 'evil")
    )
    val sql = render(RdbmsKind.Postgres, hostile)
    sql should include("DATABASE 'x''); DROP TABLE t; --'")
    sql should include("USER 'a\\''b\"c'")
    sql should include("SCHEMA 's''; ATTACH ''evil'")
    // Only the generated statements end a line with ';'.
    sql.linesIterator.count(_.trim.endsWith(";")) shouldBe 5
  }

  it should "pass unicode through unchanged" in {
    val sql = render(RdbmsKind.Postgres, base.copy(database = "entrepôt", user = "用户"))
    sql should include("DATABASE 'entrepôt'")
    sql should include("USER '用户'")
  }

  it should "quote the alias and secret as identifiers" in {
    val sql = render(RdbmsKind.Postgres, alias = "Mixed_Case")
    sql should include("ATTACH '' AS \"mixed_case\" (")
    sql should include("SECRET \"qod_pg_mixed_case\"")
  }

  "RdbmsSetupSql.secretName" should "be unique per alias and per kind" in {
    val names = for
      kind  <- RdbmsKind.values.toList
      alias <- List("a", "b", "sales", "sales_2")
    yield RdbmsSetupSql.secretName(kind, alias)
    names.distinct.size shouldBe names.size
    RdbmsSetupSql.secretName(RdbmsKind.Postgres, "catalog") should not be "qod_catalog_pg"
  }

  "RdbmsSetupSql.CredentialOption" should "name PASSWORD, the one credential the block renders" in {
    RdbmsSetupSql.CredentialOption.values.map(_.optionName).toList shouldBe List("PASSWORD")
    render(RdbmsKind.Postgres) should include("PASSWORD '{{secret.PG_PWD}}'")
  }
