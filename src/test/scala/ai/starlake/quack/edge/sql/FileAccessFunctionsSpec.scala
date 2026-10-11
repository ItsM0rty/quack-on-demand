package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.InProcessDuckDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.io.Source

/** Pins the derivation of [[FileAccessScreen.FileFunctions]]: every DuckDB 2.0 table function, with
  * every extension a node loads, must be judged for tenant principals, either refused (a file
  * reader, a catalog-reaching or node-escaping call, a node-wide or credential-exposing call) or
  * admitted on purpose ([[FileAccessScreen.NoFileFunctions]]). A function nobody judged is how
  * `sql_auto_complete` (a directory listing) and `shell_history` (the node's init SQL) went
  * unscreened: a new 2.0 table function fails this spec until someone decides.
  */
class FileAccessFunctionsSpec extends AnyFlatSpec with Matchers:

  private def unjudged(names: Iterable[String]): List[String] =
    names.filterNot(FileAccessScreen.judged).toList.distinct.sorted

  "the 2.0 CLI's table functions, every node extension loaded" should "all be judged" in {
    val src   = Source.fromResource("lockdown/duckdb-2.0-table-functions.csv")
    val names =
      try src.getLines().filterNot(l => l.startsWith("#") || l.isBlank).map(_.split(',')(0)).toList
      finally src.close()
    names should contain allOf ("sql_auto_complete", "shell_history", "read_csv")
    unjudged(names) shouldBe Nil
  }

  "the bundled DuckDB's own table functions" should "all be judged" in {
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    try
      val st = conn.createStatement()
      val rs = st.executeQuery(
        "SELECT DISTINCT function_name FROM duckdb_functions() " +
          "WHERE function_type IN ('table', 'table_macro')"
      )
      val names =
        Iterator.continually(rs.next()).takeWhile(identity).map(_ => rs.getString(1)).toList
      rs.close()
      st.close()
      names should not be empty
      unjudged(names) shouldBe Nil
    finally conn.close()
  }

  "sql_auto_complete" should "be refused: it lists node directories" in {
    FileAccessScreen.FileFunctions should contain("sql_auto_complete")
    FileAccessScreen.find("SELECT * FROM sql_auto_complete('SELECT * FROM ''/etc/ho')") shouldBe
      defined
  }
