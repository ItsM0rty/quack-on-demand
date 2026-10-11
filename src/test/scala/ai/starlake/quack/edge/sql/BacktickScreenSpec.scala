package ai.starlake.quack.edge.sql

import ai.starlake.quack.BuildVersions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Each refused form below is accepted by DuckDB 1.5.6 once a macro named with a backtick exists,
  * and the text between the pair then runs (checked on the 1.5.6 CLI): see BacktickScreen.
  */
class BacktickScreenSpec extends AnyFlatSpec with Matchers:

  private val B = "`"

  "BacktickScreen" should "be active for DuckDB 1.x and inert for 2.x and later" in:
    BacktickScreen.activeFor("1.5.6.0") shouldBe true
    BacktickScreen.activeFor("1.4.0") shouldBe true
    BacktickScreen.activeFor("2.0.0.0") shouldBe false
    BacktickScreen.activeFor("2.1.0") shouldBe false
    BacktickScreen.activeFor("10.0.0") shouldBe false

  it should "stay active when the version cannot be read" in:
    BacktickScreen.activeFor("") shouldBe true
    BacktickScreen.activeFor("snapshot") shouldBe true

  it should "follow the pinned engine version" in:
    BacktickScreen.active shouldBe BacktickScreen.activeFor(BuildVersions.duckdb)

  it should "refuse a backtick pair hiding separators, a pair hiding a call, and a lone one" in:
    List(
      s"SELECT 1 $B 2; SET search_path='db.other'; SELECT 3 $B 4",
      s"SELECT 1 $B 2 AS a, * FROM query_table('t') WHERE 3 $B 4 = 7",
      s"SELECT 1 $B 2",
      s"SELECT ${B}a$B FROM t"
    ).foreach(sql => withClue(sql)(BacktickScreen.findFor(sql, active = true) shouldBe defined))

  it should "admit a backtick inside a string literal, a quoted identifier or a comment" in:
    List(
      s"SELECT '$B;$B' AS a",
      s"SELECT e'\\'$B' AS a",
      s"SELECT $$$$$B;$B$$$$ AS a",
      "SELECT 1 AS \"a" + B + "b\"",
      s"SELECT 1 /* $B; $B */",
      s"SELECT 1 -- $B"
    ).foreach(sql => withClue(sql)(BacktickScreen.findFor(sql, active = true) shouldBe None))

  it should "admit everything when inert" in:
    BacktickScreen.findFor(s"SELECT 1 $B 2; SELECT 3 $B 4", active = false) shouldBe None

  "AccessGate" should "refuse a backtick pair as not wildcard-coverable while active" in:
    assume(BacktickScreen.active, "backtick screen is inert on this DuckDB pin")
    val cfg = ai.starlake.acl.model.Config.forDuckDB(Some("db"), Some("def"), Set("db"))
    AccessGate.evaluate(s"SELECT 1 $B 2; SELECT 3 $B 4", cfg, "db", false) match
      case GateOutcome.Refuse(m, coverable, _) =>
        m should include("backtick")
        coverable shouldBe false
      case other => fail(s"expected Refuse, got $other")

  "LockdownScreen" should "refuse a backtick pair hiding a call while active" in:
    assume(BacktickScreen.active, "backtick screen is inert on this DuckDB pin")
    LockdownScreen
      .screen(s"SELECT 1 $B 2 AS a, * FROM read_csv('/tmp/x.csv') WHERE 3 $B 4 = 7", Set.empty)
      .getOrElse("") should include("backtick")
