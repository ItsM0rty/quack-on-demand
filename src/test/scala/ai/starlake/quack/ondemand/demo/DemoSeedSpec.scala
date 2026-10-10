package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DemoSeedSpec extends AnyFlatSpec with Matchers:

  "DemoSeed.buildInitSql" should "attach the DuckLake-over-Postgres catalog and dbgen at the SF" in {
    val pg  = PgCoords("localhost", 54321, "postgres", "")
    val sql = DemoSeed.buildInitSql(pg, "acme_tpch", "tpch1", "/demo/ducklake", 0.1)

    sql should include("INSTALL ducklake;\nLOAD ducklake;\n")
    sql should include("INSTALL postgres;\nLOAD postgres;\n")
    sql should include("INSTALL tpch;\nLOAD tpch;\n")
    sql should include("(DATA_PATH '/demo/ducklake');")
    sql should include("INSTALL postgres")
    sql should include("INSTALL tpch")
    sql should include("host=localhost port=54321 dbname=acme_tpch user=postgres")
    sql should include("DATA_PATH '/demo/ducklake'")
    sql should include("CREATE SCHEMA IF NOT EXISTS acme_tpch.tpch1")
    sql should include("CALL dbgen(sf = 0.1)")
  }

  it should "CTAS each of the 8 TPC-H tables from the in-memory dbgen output into the DuckLake schema" in {
    val pg  = PgCoords("localhost", 54321, "postgres", "")
    val sql = DemoSeed.buildInitSql(pg, "acme_tpch", "tpch1", "/demo/ducklake", 0.1)

    for table <-
        List("region", "nation", "customer", "supplier", "part", "partsupp", "orders", "lineitem")
    do
      sql should include(s"CREATE TABLE acme_tpch.tpch1.$table AS SELECT * FROM memory.main.$table")
  }

  "DemoSeed.run" should "start the duckdb CLI with DuckDB 2.0's agent mode off" in {
    assume(!scala.util.Properties.isWin, "the fake duckdb is a POSIX shell script")
    val dir  = java.nio.file.Files.createTempDirectory("qod-demo-seed-spec")
    val fake = dir.resolve("duckdb")
    java.nio.file.Files
      .writeString(fake, "#!/bin/sh\necho \"agent=$DUCKDB_AGENT_MODE\" >&2\nexit 1\n")
    fake.toFile.setExecutable(true)
    val home = DemoHome(dir, dir.resolve("pg"), dir.resolve("ducklake"), dir.resolve("native"))
    val pg   = PgCoords("localhost", 54321, "postgres", "")
    val out  = DemoSeed.run(pg, "acme_tpch", "tpch1", home, 0.1, fake.toString, maxAttempts = 1)
    out.left.toOption.getOrElse("") should include("agent=0")
  }
