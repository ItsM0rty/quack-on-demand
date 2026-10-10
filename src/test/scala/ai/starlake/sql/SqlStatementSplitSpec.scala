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
    split("USE db.other; /* done */ ; ;") shouldBe List("USE db.other")
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
