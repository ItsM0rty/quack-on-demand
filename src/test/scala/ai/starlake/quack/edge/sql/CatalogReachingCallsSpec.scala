package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CatalogReachingCallsSpec extends AnyFlatSpec with Matchers:

  "CatalogReachingCalls" should "see a call after a dollar quote that follows a Unicode space" in:
    // DuckDB opens the dollar quote after the space character, so the `--` sits inside the string
    // and the call after it runs; read as a comment it would hide the call from this screen.
    CatalogReachingCalls.find("SELECT\u00a0$$--$$, * FROM query_table('t')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT\u3000$$--$$, * FROM query('SELECT 1')") shouldBe
      Some("query")

  it should "ignore a call that only appears inside a comment" in:
    CatalogReachingCalls.find("SELECT 1 -- query_table('t')") shouldBe None

  it should "read a dollar sign after a soft hyphen as part of an identifier" in:
    // DuckDB names the column `AS<U+00AD>$$` and reads the rest of the line as a comment: the call
    // never runs (run through the 1.5.6 CLI against a missing table, which it did not resolve).
    CatalogReachingCalls.find("SELECT 1 AS\u00ad$$ -- $$, * FROM query_table('t')") shouldBe None
    CatalogReachingCalls.find("SELECT 1 AS\u00a0$$ -- $$, * FROM query_table('t')") shouldBe
      Some("query_table")
