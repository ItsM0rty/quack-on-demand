package ai.starlake.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Invariants of the shared lexer that its three consumers (stripper, splitter, classifier) rely
  * on. Behaviour against DuckDB is pinned through those consumers' own specs; non-ASCII is written
  * as escapes.
  */
class SqlLexerSpec extends AnyFlatSpec with Matchers:

  private val samples = List(
    "SELECT 1",
    "SELECT $q$a;b$q$, 'x''y', e'\\'', \"a\"\"b\", `c``d` FROM t -- c\nWHERE /* a /* b */ c */ 1",
    "SELECT 1e'x', .5, 1_000, 1.e5, $1, $, a$q$, a\u20AC$q$ /* x",
    "SELECT 1 /* ' */ AS a\u00A0$q$; ATTACH 'x.db' AS y; SELECT 1 AS b\u00A0$q$ /* ' */",
    "x::int := 1 <> 2 ||/*c*/3--d\r4"
  )

  "tokens" should "cover the input exactly, in order" in:
    samples.foreach { s =>
      val toks = SqlLexer.tokens(s)
      toks.map(_.start) shouldBe (0 +: toks.map(_.end).init)
      toks.last.end shouldBe s.length
      toks.foreach(t => t.end should be > t.start)
    }

  "engineView" should "turn a unicode space into a space only where DuckDB's pre-pass does" in:
    // Outside any quote the pre-pass converts it; after the `'` in the comment it believes it is
    // inside a literal and keeps it (pinned to a stand-in that is not a space).
    val v = SqlLexer.engineView("SELECT\u00A01 /* ' */ AS a\u00A0$q$")
    v.charAt(6) shouldBe ' '
    v.charAt(v.indexOf("$q$") - 1) should not be ' '
    v.length shouldBe "SELECT\u00A01 /* ' */ AS a\u00A0$q$".length

  it should "be a fixpoint on itself and on every slice, so a cut statement lexes as it did in place" in:
    samples.foreach { s =>
      val v = SqlLexer.engineView(s)
      SqlLexer.engineView(v) shouldBe v
      for i <- 0 to v.length do SqlLexer.engineView(v.substring(i)) shouldBe v.substring(i)
    }
