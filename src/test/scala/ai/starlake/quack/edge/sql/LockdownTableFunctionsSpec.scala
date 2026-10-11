package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.InProcessDuckDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.io.Source

/** Pins the derivation of the lockdown's table-function lists: every DuckDB 2.0 table function
  * whose first argument can be a string (a path, a name, SQL) must be judged by [[LockdownScreen]],
  * either denied or admitted on purpose. A function nobody judged is how `read_avro` and the
  * `iceberg_*` readers, autoloadable on a node, went unscreened.
  */
class LockdownTableFunctionsSpec extends AnyFlatSpec with Matchers:

  private val StringLike = Set("VARCHAR", "VARCHAR[]", "ANY", "ANY[]", "VARIANT", "JSON")

  private def unjudged(rows: Iterable[(String, Set[String])]): List[String] =
    rows
      .collect {
        case (name, types) if types.exists(StringLike) && !LockdownScreen.judged(name) => name
      }
      .toList
      .sorted

  "the 2.0 CLI's table functions, every node extension loaded" should "all be judged" in {
    val src  = Source.fromResource("lockdown/duckdb-2.0-table-functions.csv")
    val rows =
      try
        src.getLines().filterNot(l => l.startsWith("#") || l.isBlank).toList.map { line =>
          val cols = line.split(',')
          cols(0) -> cols(1).split('|').toSet
        }
      finally src.close()
    rows.map(_._1) should contain allOf ("read_avro", "iceberg_scan", "read_csv")
    unjudged(rows) shouldBe Nil
  }

  "the bundled DuckDB's own table functions" should "all be judged" in {
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    try
      val st = conn.createStatement()
      val rs = st.executeQuery(
        "SELECT function_name, coalesce(parameter_types[1]::VARCHAR, '-') FROM duckdb_functions() " +
          "WHERE function_type IN ('table', 'table_macro')"
      )
      val rows = Iterator
        .continually(rs.next())
        .takeWhile(identity)
        .map(_ => rs.getString(1) -> rs.getString(2))
        .toList
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toList
      rs.close()
      st.close()
      rows should not be empty
      unjudged(rows) shouldBe Nil
    finally conn.close()
  }
