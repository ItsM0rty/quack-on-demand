package ai.starlake.sql

import ai.starlake.quack.ondemand.InProcessDuckDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager

/** The lexer-canonical parser input: what JSqlParser reads in place of the raw statement. Two
  * properties are pinned. The rendering puts token boundaries where DuckDB puts them (every string
  * literal a plain `'...'`, comments gone, `//` no longer a JSqlParser comment), and an exact
  * rendering IS the caller's statement, checked by running both on the bundled DuckDB.
  */
class ParserInputSpec extends AnyFlatSpec with Matchers:

  private def text(sql: String): String =
    ParserInput.render(sql) match
      case Right(r) => r.text
      case Left(e)  => fail(s"refused: $e")

  private def exact(sql: String): Boolean =
    ParserInput.render(sql).fold(e => fail(s"refused: $e"), _.exact)

  "render" should "turn an escape string into a plain literal of the same value" in:
    text("SELECT E'\\'' AS a") shouldBe "SELECT '''' AS a"
    text("SELECT e'a\\\\b' AS a") shouldBe "SELECT 'a\\b' AS a"
    text("SELECT E'x\\ny' AS a") shouldBe "SELECT 'x\ny' AS a"

  it should "turn a dollar-quoted string into a plain literal of the same value" in:
    text("SELECT $x$'$x$ AS a") shouldBe "SELECT '''' AS a"
    text("SELECT $$it's$$ AS a") shouldBe "SELECT 'it''s' AS a"

  it should "replace comments with a space and keep double-quoted identifiers" in:
    text("SELECT 1 /* a /* b */ c */ AS \"x--y\" -- tail") shouldBe "SELECT 1   AS \"x--y\"  "
    text("SELECT \"a\"\"b\" FROM t") shouldBe "SELECT \"a\"\"b\" FROM t"

  it should "render a backtick-quoted identifier as the double-quoted one DuckDB 2.0 reads" in:
    // The ACL (getUnquotedName) strips backticks, every policy match unquotes double quotes:
    // one spelling for both, or `FROM \`orders\`` meets no row filter and no column mask.
    text("SELECT `email` FROM `orders`") shouldBe "SELECT \"email\" FROM \"orders\""
    text("SELECT `a``b` FROM t") shouldBe "SELECT \"a`b\" FROM t"
    // JSqlParser does not undouble `""`: the ACL would read `a""b`, so a backtick name holding a
    // double quote keeps its backticks (the policy matchers unquote both delimiters)
    text("SELECT `a\"b` FROM t") shouldBe "SELECT `a\"b` FROM t"
    text("SELECT `s`.`t`.`c` FROM `s`.`t`") shouldBe "SELECT \"s\".\"t\".\"c\" FROM \"s\".\"t\""
    text("SELECT '`x`' AS \"`y`\"") shouldBe "SELECT '`x`' AS \"`y`\""
    exact("SELECT `email` FROM `orders`") shouldBe true

  it should "set a string literal off from a preceding word" in:
    // JSqlParser reads q'{...}' as an Oracle quote; DuckDB reads the word q, then a literal
    text("SELECT q'{' AS x") shouldBe "SELECT q '{' AS x"
    text("SELECT 1e'x'") shouldBe "SELECT 1 'x'"

  it should "keep the prefix of a national, hex or bit literal" in:
    text("SELECT N'a''b', X'4142', b'01'") shouldBe "SELECT N'a''b', X'4142', b'01'"

  it should "fold DuckDB's newline continuations and nothing else" in:
    text("SELECT 'a'\n'b' AS c") shouldBe "SELECT 'ab' AS c"
    text("SELECT 'a' -- c\n'b' AS c") shouldBe "SELECT 'ab' AS c"
    // a block comment, a missing newline or a non-plain second literal stops the fold, and the
    // rendering must not introduce one (a newline between two literals would)
    text("SELECT 'a' /* c */\n'b' AS c") shouldBe "SELECT 'a' 'b' AS c"
    text("SELECT 'a' 'b' AS c") shouldBe "SELECT 'a' 'b' AS c"
    text("SELECT 'a'\nE'b' AS c") shouldBe "SELECT 'a' 'b' AS c"

  it should "quote a word holding a dollar or a non-ASCII character" in:
    text("SELECT a$q$ FROM café") shouldBe "SELECT \"a$q$\" FROM \"café\""
    text("SELECT $1, $name FROM t") shouldBe "SELECT $1, $name FROM t"

  it should "collapse // and mark the rendering inexact" in:
    text("SELECT 7 // 2") shouldBe "SELECT 7 / 2"
    exact("SELECT 7 // 2") shouldBe false
    exact("SELECT E'\\'' AS a, $$x$$ AS b /* c */") shouldBe true

  it should "refuse input DuckDB rejects outright" in:
    ParserInput.render("SELECT 'abc") shouldBe a[Left[?, ?]]
    ParserInput.render("SELECT E'abc\\'") shouldBe a[Left[?, ?]]
    ParserInput.render("SELECT \"abc") shouldBe a[Left[?, ?]]
    ParserInput.render("SELECT 1 /* a /* b */") shouldBe a[Left[?, ?]]
    ParserInput.render("SELECT $$abc") shouldBe a[Left[?, ?]]
    ParserInput.render("SELECT E'\\0'") shouldBe a[Left[?, ?]]
    ParserInput.render("SELECT E'\\377'") shouldBe a[Left[?, ?]]

  "decodeEscapes" should "follow DuckDB's escape-string rules" in:
    ParserInput.decodeEscapes("a\\nb") shouldBe Right("a\nb")
    ParserInput.decodeEscapes("\\x41\\101\\u0041\\q\\\\") shouldBe Right("AAu0041q\\")
    ParserInput.decodeEscapes("\\xg") shouldBe Right("xg")
    ParserInput.decodeEscapes("\\303\\251") shouldBe Right("é")
    ParserInput.decodeEscapes("trailing\\") shouldBe Right("trailing\\")

  /** Each statement and its rendering return the same row, under the same column names, on DuckDB
    * 2.0.
    */
  "an exact rendering" should "be the same statement on DuckDB" in:
    val corpus = List(
      "SELECT E'\\'' AS a, E'x\\ny' AS b, e'\\x41\\101\\u0041\\q\\\\' AS c",
      "SELECT E'\\303\\251t\\303\\251' AS a, E'tab\\there' AS b",
      "SELECT $x$'$x$ AS a, $$it's$$ AS b, $q$a$$b$q$ AS c",
      "SELECT 'a'\n'b' AS a, 'c' -- comment\n'd' AS b, E'e\\\\'\n'\\n' AS c",
      "SELECT 'a' /* c */ AS a, N'n''x' AS b, X'4142' AS c, B'0101' AS d",
      "SELECT 1 /* a /* nested */ */ + 2 AS a, 'x--y' AS b",
      "SELECT 'back\\slash' AS a, '\\' AS b",
      "SELECT a$q$ FROM (SELECT 7 AS a$q$) t",
      "SELECT café FROM (SELECT 3 AS café) t",
      "SELECT 'nbsp in' AS a, 1 + 1 AS b",
      // implicit column names, which a client reads as much as the values
      "SELECT E'x', $$y$$, N'n', 'a'\n'b', 1 + 1, café FROM (SELECT 3 AS café) t",
      // a backtick-quoted identifier is the double-quoted one, embedded quotes and case included
      "SELECT `a``b`, `C\"d`, `MiXed` FROM (SELECT 1 AS \"a`b\", 2 AS \"C\"\"d\", 3 AS mixed) `t`",
      "SELECT `T`.`x` FROM (SELECT 4 AS x) AS t"
    )
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    def row(sql: String): List[String] =
      val st = conn.createStatement()
      try
        val rs = st.executeQuery(sql)
        try
          rs.next() shouldBe true
          val md = rs.getMetaData
          (1 to md.getColumnCount).toList.map(i =>
            s"${md.getColumnLabel(i)}=${String.valueOf(rs.getString(i))}"
          )
        finally rs.close()
      finally st.close()
    try
      corpus.foreach { sql =>
        val r = ParserInput.render(sql).fold(e => fail(s"$sql refused: $e"), identity)
        withClue(s"$sql => ${r.text}: ") {
          r.exact shouldBe true
          row(r.text) shouldBe row(sql)
        }
      }
    finally conn.close()
