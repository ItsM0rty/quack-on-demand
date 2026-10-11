package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DuckDB 2.0 replacement-scans a table name it cannot resolve as a file:
  * `FROM "s3://b/x.parquet"`, `` FROM `file:///d/x.csv` `` and `FROM "/d/x"."csv"` (the parts
  * joined with dots) read the object or file exactly as the string literal
  * `FROM 's3://b/x.parquet'` does, with the node's credentials (checked on the 2.0 CLI). JSqlParser
  * reads the quoted name as a table, so a wildcard read grant (`*.*.* RO`, `<db>.*.* RO`) admitted
  * a raw read of any object the node's secrets reach, the pool's DuckLake data files included. A
  * name holding a URL scheme, a glob character or a path separator is now the file reference the
  * literal form is: an unsupported construct only a wildcard ALL grant covers, in every table
  * position.
  */
class QuotedFileReferenceSpec extends AnyFlatSpec with Matchers:

  private val B = "`"

  private val fileReads = List(
    "SELECT * FROM \"file:///data/x.csv\"",
    "SELECT * FROM \"s3://bucket/*/main/customer/*.parquet\"",
    s"SELECT * FROM ${B}s3://bucket/acme_tpch-1a2b3c4d/main/customer/*.parquet$B",
    s"SELECT * FROM ${B}file:///data/x.csv$B",
    "SELECT * FROM \"s3://bucket/data\"",
    "SELECT * FROM \"s3://bucket/x\".\"parquet\"",
    "SELECT * FROM \"/data/x\".\"csv\"",
    "SELECT * FROM \"/data/x\".csv",
    "SELECT * FROM \"sub/x\".parquet",
    "SELECT * FROM \"data_*\"",
    "SELECT * FROM \"data_?\"",
    "SELECT * FROM \"data_{a,b}\"",
    "SELECT * FROM t JOIN \"s3://bucket/x.parquet\" ON true",
    "SELECT (SELECT max(v) FROM \"s3://bucket/x.parquet\")",
    "WITH c AS (SELECT * FROM \"file:///data/x.csv\") SELECT * FROM c",
    "DESCRIBE \"s3://bucket/x.parquet\"",
    "SUMMARIZE \"s3://bucket/x.parquet\""
  )

  private val fileWrites = List(
    "INSERT INTO \"s3://bucket/t\" VALUES (1)",
    "INSERT INTO t SELECT * FROM \"file:///data/x.csv\"",
    "UPDATE \"s3://bucket/t\" SET a = 1",
    "DELETE FROM \"s3://bucket/t\"",
    "MERGE INTO \"s3://bucket/t\" t USING s ON t.id = s.id WHEN MATCHED THEN DELETE",
    "TRUNCATE \"s3://bucket/t\"",
    "CREATE TABLE \"s3://bucket/t\" (a INT)",
    "CREATE TABLE \"s3://bucket/t\" AS SELECT 1",
    "CREATE TABLE t AS SELECT * FROM \"s3://bucket/x.parquet\"",
    "CREATE VIEW v AS SELECT * FROM \"s3://bucket/x.parquet\"",
    "ALTER TABLE \"s3://bucket/t\" ADD COLUMN y INT",
    "DROP TABLE \"s3://bucket/t\"",
    s"DROP TABLE ${B}data_*$B"
  )

  private val cfg = Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch"))

  "AccessGate" should "refuse a quoted URL, glob or path name as a file reference" in
    (fileReads ++ fileWrites).foreach { sql =>
      withClue(sql) {
        AccessGate.evaluate(
          sql,
          cfg,
          "tpch",
          filteredMetadata = true,
          clientDataScans = false
        ) match
          case GateOutcome.Refuse(reason, coverable, _) =>
            // `"/d/x".csv` is a JSqlParser parse error, refused the same way
            reason should (include("file reference") or include("parse error"))
            coverable shouldBe true
          case other => fail(s"expected Refuse, got $other")
      }
    }

  it should "still gate ordinary quoted names as tables" in
    List(
      "SELECT * FROM \"orders\"",
      "SELECT * FROM \"main\".\"orders\"",
      "SELECT * FROM \"order items\"",
      "SELECT * FROM \"secret#x\""
    ).foreach { sql =>
      withClue(sql) {
        AccessGate.evaluate(
          sql,
          cfg,
          "tpch",
          filteredMetadata = true,
          clientDataScans = false
        ) shouldBe
          a[GateOutcome.Gated]
      }
    }

  private def perm(c: String, s: String, t: String, v: String) =
    RolePermission(s"rp-$c-$s-$t-$v", "role-1", c, s, t, v, None)
  private def eff(ps: RolePermission*) =
    EffectiveSet(RbacUser("u-1", Some("t-1"), "alice", "user"), Nil, Nil, ps.toList, Nil)
  private val validator =
    new PostgresAclValidator(tenantCatalogs = _ => Set("tpch"), filteredMetadata = true)
  private def validate(sql: String, e: EffectiveSet) =
    validator.validate(
      ValidationContext(
        username = "alice",
        database = "t-1/td-1/p-1",
        statement = sql,
        peer = "conn-1",
        defaultDatabase = Some("tpch"),
        defaultSchema = Some("main"),
        effectiveSet = Some(e)
      )
    )

  "PostgresAclValidator" should "deny it under a wildcard read or write grant" in {
    for
      e <- List(
        eff(perm("*", "*", "*", "RO")),
        eff(perm("tpch", "*", "*", "RO")),
        eff(perm("*", "*", "*", "RW")),
        eff(perm("tpch", "*", "*", "ALL"))
      )
      sql <- fileReads ++ fileWrites
    do withClue(sql)(validate(sql, e) shouldBe a[Denied])
  }

  it should "admit it under the wildcard ALL grant, like the string-literal form" in
    (fileReads ++ fileWrites).foreach { sql =>
      withClue(sql)(validate(sql, eff(perm("*", "*", "*", "ALL"))) shouldBe Allowed)
    }

  "LockdownScreen" should "judge a quoted URL name exactly as the string literal" in {
    for (quoted, literal) <- List(
        "SELECT * FROM \"file:///data/x.csv\""     -> "SELECT * FROM 'file:///data/x.csv'",
        "SELECT * FROM \"s3://lakebkt/x.parquet\"" -> "SELECT * FROM 's3://lakebkt/x.parquet'",
        "SELECT * FROM \"s3://other/x.parquet\""   -> "SELECT * FROM 's3://other/x.parquet'",
        "SELECT * FROM \"/data/x\".\"csv\""        -> "SELECT * FROM '/data/x.csv'"
      )
    do
      withClue(quoted) {
        LockdownScreen.screen(quoted, Set("lakebkt")).isDefined shouldBe
          LockdownScreen.screen(literal, Set("lakebkt")).isDefined
      }
    LockdownScreen.screen("SELECT * FROM \"s3://lakebkt/x.parquet\"", Set("lakebkt")) shouldBe
      defined
    LockdownScreen.screen("SELECT * FROM \"file:///data/x.csv\"", Set.empty) shouldBe defined
  }

  // No quoting needed: DuckDB 2.0 replacement-scans `x.csv` (schema x, table csv, neither found)
  // as the relative file x.csv under the node's working directory, `sub.x.parquet` as
  // sub.x.parquet, `"x"."csv"` likewise (checked on the 2.0 CLI). A name whose last part is a
  // replacement-scan extension is a file reference too.
  private val extensionReads = List(
    "SELECT * FROM x.csv",
    "SELECT * FROM \"x\".\"csv\"",
    "SELECT * FROM sub.x.parquet",
    "SELECT * FROM x.JSON",
    "SELECT * FROM x.ndjson",
    "SELECT * FROM x.duckdb",
    "SELECT * FROM x.csv.gz",
    "SELECT * FROM t JOIN x.parquet ON true",
    "INSERT INTO t SELECT * FROM x.csv",
    "DESCRIBE x.csv",
    "CREATE TABLE x.csv (a INT)",
    "DROP TABLE x.parquet"
  )

  "AccessGate" should "refuse a name ending in a replacement-scan extension as a file reference" in
    extensionReads.foreach { sql =>
      withClue(sql) {
        AccessGate.evaluate(
          sql,
          cfg,
          "tpch",
          filteredMetadata = true,
          clientDataScans = false
        ) match
          case GateOutcome.Refuse(reason, coverable, _) =>
            // `csv` is a JSqlParser keyword: `x.csv` is a parse error, refused the same way
            reason should (include("file reference") or include("parse error"))
            coverable shouldBe true
          case other => fail(s"expected Refuse, got $other")
        validate(sql, eff(perm("*", "*", "*", "RO"))) shouldBe a[Denied]
      }
    }

  it should "gate a table whose name only looks like one" in
    List("SELECT * FROM parquet", "SELECT * FROM main.csv_data", "SELECT * FROM main.json_events")
      .foreach { sql =>
        withClue(sql) {
          AccessGate.evaluate(sql, cfg, "tpch", true, clientDataScans = false) shouldBe
            a[GateOutcome.Gated]
        }
      }
