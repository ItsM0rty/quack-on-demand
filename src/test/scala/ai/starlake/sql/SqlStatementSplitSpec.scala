package ai.starlake.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `SqlCommentStripper.splitStatements`: a `;` separates two statements exactly where DuckDB's own
  * lexer says it does. Every case below that hides a `;` inside a quoting form, or exposes one a
  * naive quote tracker would swallow, was run against a real DuckDB 1.5.6 CLI first: the batches
  * the splitter counts as three really did run three statements there.
  */
class SqlStatementSplitSpec extends AnyFlatSpec with Matchers:

  private def split(sql: String) = SqlCommentStripper.splitStatements(sql)

  "splitStatements" should "split plain top-level statements and keep each fragment verbatim" in:
    split("SET search_path='db.other'; SELECT * FROM t") shouldBe List(
      "SET search_path='db.other'",
      " SELECT * FROM t"
    )

  it should "drop empty fragments and fragments holding only comments or trivia" in:
    split("USE db.other;") shouldBe List("USE db.other")
    split("USE db.other; -- done") shouldBe List("USE db.other")
    split("USE db.other; /* done */ ;\u00a0;") shouldBe List("USE db.other")
    split("") shouldBe Nil
    split(" ; ") shouldBe Nil

  it should "not split on a semicolon inside a plain string, an identifier or a comment" in:
    split("SELECT 'a;USE x'") should have size 1
    split("SELECT \"a;USE x\" FROM t") should have size 1
    split("SELECT 1 -- ; USE x") should have size 1
    split("SELECT 1 /* ; USE x */") should have size 1

  it should "not split on a semicolon inside a dollar-quoted string" in:
    split("SELECT $$; USE x$$") should have size 1
    split("SELECT $tag$; USE x$tag$") should have size 1

  it should "split after a dollar-quoted string that holds a lone single quote" in:
    // A tracker that only knows `'...'` opens a string at the quote inside `$$'$$` and swallows the
    // two statements after it. DuckDB runs three statements here.
    split("SELECT $$'$$; USE db.other; SELECT * FROM t") should have size 3

  it should "split after an escape string whose quote is backslash-escaped" in:
    // e'\'' is the one-character string `'`. Reading it as a plain string leaves a quote open that
    // swallows the rest of the batch. DuckDB runs three statements here.
    split("SELECT e'\\''; USE db.other; SELECT * FROM t") should have size 3

  it should "track nested block comments so an inner close does not expose a separator" in:
    split("SELECT 1 /* a /* b */ ; c */; USE db.other; SELECT * FROM t") should have size 3

  it should "end a line comment at a bare carriage return" in:
    split("SELECT 1 -- c\rUSE db.other; SELECT * FROM t") should have size 2

  // DuckDB's lexer continues an identifier through `$` (ASCII letters, digits, `_`, `$` and every
  // non-ASCII character continue one), so `$$` glued to an identifier is part of its name, not a
  // dollar-quote opener. Each identifier below ran all three statements on DuckDB 1.5.6 and 2.0.
  private val trailer = "; USE db.other; SELECT * FROM t; -- $$"

  it should "not open a dollar quote at a dollar sign that continues an identifier" in:
    List("x$$", "x$a$", "x_$$", "\u00e9$$", "\u20acx$$", "x\u20ac$$", "0x1$$").foreach { ident =>
      val sql = "SELECT 1 AS " + ident + trailer
      withClue(sql)(split(sql) should have size 3)
    }

  it should "open a dollar quote after a number or a parameter" in:
    // DuckDB 1.5.6 reads each `$$` here as the opener of a string running to the `$$` in the
    // comment (its syntax error names that whole string), and rejects the submission.
    List("1$$", "1.5$$", "1e5$$", "1_000$$", "$1$$").foreach { lit =>
      val sql = "SELECT " + lit + trailer
      withClue(sql)(split(sql) should have size 1)
    }

  it should "still open a dollar quote after a non-identifier character" in:
    split("SELECT $$; USE x$$; SELECT 1") should have size 2
    split("SELECT ($$; USE x$$); SELECT 1") should have size 2
    split("SELECT 'a'||$$; USE x$$; SELECT 1") should have size 2

  it should "accept non-ASCII and underscore tags, and digits after the first tag character" in:
    List("\u00e9", "\u20ac", "a1", "_").foreach { tag =>
      val q   = "$" + tag + "$"
      val sql = "SELECT " + q + "; USE other" + q + "; SELECT 1"
      withClue(sql)(split(sql) should have size 2)
    }

  "stripComments" should "strip a comment that follows an identifier ending in a dollar sign" in:
    val out = SqlCommentStripper.stripComments("SELECT 1 AS x$$ /* c */ FROM t -- $$")
    out should not include "/*"
    out should not include "--"
    out should include("FROM t")

  // ---- parity with the DuckDB 1.5.6 engine, character class by character class ----
  //
  // Every expectation below comes from the DuckDB 1.5.6 engine: each submission was run through
  // the 1.5.6 CLI on `:memory:` and its result sets counted (for the stripComments cases, the
  // column name it returned was read). DuckDB's pre-pass turns the 18 characters of
  // `duckDbSpaces` into ASCII spaces, so each ends a token; the characters of `identifierChars`
  // are whitespace or format characters to Java but identifier characters to DuckDB.
  private val duckDbSpaces: List[Char] =
    List('\u00a0') ++ ('\u2000' to '\u200b') ++
      List('\u202f', '\u205f', '\u2060', '\u3000', '\ufeff')

  private val identifierChars: List[Char] =
    List('\u00ad', '\u200c', '\u200d', '\u200e', '\u200f', '\u2028', '\u2029', '\u1680') ++
      List('\u180e') ++ ('\u2061' to '\u2064') ++ ('\u2066' to '\u206f')

  private def hex(c: Char): String = f"U+${c.toInt}%04X"

  "the character lists" should "hold DuckDB's 18 space characters and 23 identifier characters" in:
    duckDbSpaces.distinct should have size 18
    identifierChars.distinct should have size 23
    (duckDbSpaces intersect identifierChars) shouldBe empty

  "splitStatements" should "open a dollar quote after each DuckDB space character" in:
    duckDbSpaces.foreach { c =>
      val sql = "SELECT 1 AS" + c + "$$; SELECT 2; SELECT 3; -- $$"
      withClue(hex(c))(split(sql) should have size 1)
    }

  it should "continue an identifier through each other whitespace or format character" in:
    identifierChars.foreach { c =>
      val sql = "SELECT 1 AS" + c + "$$; SELECT 2; SELECT 3; -- $$"
      withClue(hex(c))(split(sql) should have size 3)
    }

  it should "read a line comment and a separator after a character of either class" in:
    (duckDbSpaces ++ identifierChars).foreach { c =>
      withClue(hex(c)) {
        split("SELECT 1 AS x" + c + "-- ; SELECT 2\n; SELECT 3") should have size 2
        split("SELECT 1 AS x" + c + "; SELECT 2; SELECT 3") should have size 3
      }
    }

  "stripComments" should "keep a comment marker inside a dollar quote after a DuckDB space" in:
    // DuckDB names the column ` -- `: the marker is in the string.
    duckDbSpaces.foreach { c =>
      val sql = "SELECT 1 AS" + c + "$$ -- $$"
      withClue(hex(c))(SqlCommentStripper.stripComments(sql) shouldBe sql)
    }

  it should "strip a comment after an identifier ending in an identifier character and $$" in:
    // DuckDB names the column `AS<char>$$`: the rest of the line is a comment.
    identifierChars.foreach { c =>
      val sql = "SELECT 1 AS" + c + "$$ -- $$"
      withClue(hex(c))(SqlCommentStripper.stripComments(sql) shouldBe "SELECT 1 AS" + c + "$$ ")
    }

  it should "strip a line comment after a character of either class" in:
    (duckDbSpaces ++ identifierChars).foreach { c =>
      withClue(hex(c)) {
        SqlCommentStripper.stripComments("SELECT 1 AS x" + c + "-- c") shouldBe "SELECT 1 AS x" + c
      }
    }

  // DuckDB's pre-pass keeps a space character it believes sits inside a literal, and its quote
  // scan is simpler than the tokenizer's: the kept no-break space then continues the identifier.
  "splitStatements" should "follow the pre-pass when a block comment holds a quote" in:
    split("SELECT 1 /* ' */ AS\u00a0$$; SELECT 2; SELECT 3; -- ' $$") should have size 3
    split("SELECT 1 AS\u00a0$$; SELECT 2; SELECT 3; -- ' $$") should have size 1

  it should "follow the pre-pass when an identifier holds $$" in:
    split("SELECT 1 AS x$$, 2 AS\u00a0$$; SELECT 2; SELECT 3; -- $$") should have size 3
    split("SELECT 1 AS x, 2 AS\u00a0$$; SELECT 2; SELECT 3; -- $$") should have size 1

  it should "follow the pre-pass when an escape string holds a backslash-escaped quote" in:
    split("SELECT e'\\'' AS a, 1 AS\u00a0$$; SELECT 2; SELECT 3; -- ' $$") should have size 3
    split("SELECT 'a' AS a, 1 AS\u00a0$$; SELECT 2; SELECT 3; -- ' $$") should have size 1

  it should "keep original offsets when a space character converts" in:
    val sql = "SELECT\u00a01;\u3000SELECT\u20002"
    SqlCommentStripper.statementSpans(sql) shouldBe List((0, 8), (9, sql.length))
    split(sql) shouldBe List("SELECT\u00a01", "\u3000SELECT\u20002")

  // The lexer follows DuckDB 2.0, which accepts both forms below. DuckDB 1.5.6 rejects each
  // submission as a syntax error, so nothing runs there whatever the count.
  it should "count backtick identifiers and control characters in identifiers the 2.0 way" in:
    split("SELECT 1 AS `a;b`; SELECT 2") should have size 2
    split("SELECT 1 AS a\u0001$$; SELECT 2; -- $$") should have size 2
