package ai.starlake.quack.edge.sql

import ai.starlake.quack.edge.sql.DuckDbSettingScopes.Scope
import ai.starlake.quack.ondemand.InProcessDuckDb
import org.duckdb.DuckDBConnection
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager, SQLException}
import java.util.Locale
import scala.util.Using

/** Pins [[DuckDbSettingScopes]] (generated from the engine source by
  * `scripts/dev/gen-duckdb-setting-scopes.py`) to the DuckDB the manager bundles, so the next pin
  * bump fails here until the table is regenerated: every setting the engine lists is classified,
  * the aliases match, and each core setting's scope is the one its SET / SET SESSION / SET GLOBAL
  * behaviour shows. [[NodeSettingScreen]] judges node-wide settings from that table.
  */
class DuckDbSettingScopesSpec extends AnyFlatSpec with Matchers:

  private def open(): DuckDBConnection =
    Class.forName("org.duckdb.DuckDBDriver")
    DriverManager
      .getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
      .asInstanceOf[DuckDBConnection]

  private def rows(c: Connection, sql: String): List[List[String]] =
    Using.resource(c.createStatement()) { st =>
      Using.resource(st.executeQuery(sql)) { rs =>
        val n = rs.getMetaData.getColumnCount
        Iterator
          .continually(rs.next())
          .takeWhile(identity)
          .map(_ => (1 to n).map(i => rs.getString(i)).toList)
          .toList
      }
    }

  // Registered by the JDBC driver itself (duckdb-java's JNI layer), never present on a node.
  private val JdbcDriverOptions = Set("jdbc_stream_results")

  // duckdb_settings() hides the debug and the deprecated settings unless asked for them.
  private val AllSettings =
    List("", "debug := true", "deprecated := true", "debug := true, deprecated := true")
      .map(a => s"SELECT name, scope, array_to_string(aliases, ',') FROM duckdb_settings($a)")
      .mkString(" UNION ALL ")

  "the generated table" should "be read from the engine the manager bundles" in
    Using.resource(open()) { c =>
      rows(c, "SELECT source_id FROM pragma_version()") shouldBe
        List(List(DuckDbSettingScopes.EngineSourceId))
    }

  it should "classify every setting the bundled engine lists, debug and deprecated included" in
    Using.resource(open()) { c =>
      val listed = rows(c, AllSettings).map(_.head.toLowerCase(Locale.ROOT)).toSet
      listed.size should be > 150
      listed should contain("debug_force_mbedtls_unsafe")
      (listed -- DuckDbSettingScopes.Settings.keySet -- DuckDbSettingScopes.Aliases.keySet --
        JdbcDriverOptions) shouldBe empty
    }

  it should "hold exactly the engine's aliases" in
    Using.resource(open()) { c =>
      val engine = rows(c, AllSettings).flatMap { r =>
        Option(r(2)).filter(_.nonEmpty).toList.flatMap(_.split(',')).map { a =>
          a.toLowerCase(Locale.ROOT) -> r.head.toLowerCase(Locale.ROOT)
        }
      }.toMap
      engine shouldBe DuckDbSettingScopes.Aliases
    }

  /** The scope `name` shows on the bundled engine: where a bare SET of its current value lands
    * (`duckdb_settings().scope` right after it, LOCAL when it went to the session), and whether SET
    * SESSION / SET GLOBAL are accepted. None when a probe fails for another reason (a startup-only
    * or read-only setting, a value that does not round-trip), since that says nothing about scope.
    */
  private def observedScope(db: DuckDBConnection, name: String): Option[Scope] =
    val v = s"current_setting('$name')"
    def probe(sql: String, read: Option[String] = None): Either[String, Option[String]] =
      Using.resource(db.duplicate()) { c =>
        try
          Using.resource(c.createStatement())(_.execute(sql))
          Right(read.flatMap(q => rows(c, q).headOption.map(_.head)))
        catch case e: SQLException => Left(Option(e.getMessage).getOrElse(""))
      }
    val ScopeError =
      "can only be set globally|cannot be set locally|can only be set per session|" +
        "cannot be set globally|cannot be set as a (session|global) variable"
    def scopeRefused(r: Either[String, ?]): Option[Boolean] = r match
      case Right(_)                                         => Some(false)
      case Left(m) if s"(?s).*($ScopeError).*".r.matches(m) => Some(true)
      case Left(_)                                          => None
    val read =
      s"SELECT scope FROM ($AllSettings) WHERE lower(name) = '$name'"
    for
      bare           <- probe(s"SET \"$name\" = $v", Some(read)).toOption.flatten
      sessionRefused <- scopeRefused(probe(s"SET SESSION \"$name\" = $v"))
      globalRefused  <- scopeRefused(probe(s"SET GLOBAL \"$name\" = $v"))
    yield
      if bare == "GLOBAL" then if sessionRefused then Scope.GlobalOnly else Scope.GlobalDefault
      else if globalRefused then Scope.LocalOnly
      else Scope.LocalDefault

  it should "give each core setting the scope the engine applies" in
    Using.resource(open()) { db =>
      val core     = DuckDbSettingScopes.Settings.filter(_._2.origin == "core")
      val observed = core.keys.toList.sorted.flatMap(n => observedScope(db, n).map(n -> _))
      // The rest are startup-only or read-only at run time, or take no round-trippable value.
      observed.size should be > 150
      observed.filter((n, s) => core(n).scope != s) shouldBe empty
    }

  // physical_set.cpp: an unscoped SET of a GLOBAL-default setting writes the shared DBConfig, which
  // every other connection of the instance (a node's sessions) reads.
  "a bare SET of a GLOBAL-default setting" should "reach every other session of the instance" in
    Using.resource(open()) { db =>
      Using.resource(db.duplicate()) { a =>
        Using.resource(db.duplicate()) { b =>
          def current(c: Connection, n: String) = rows(c, s"SELECT current_setting('$n')").head.head
          DuckDbSettingScopes.Settings("default_collation").scope shouldBe Scope.GlobalDefault
          Using.resource(a.createStatement())(_.execute("SET SESSION default_order = 'DESC'"))
          current(b, "default_order").toLowerCase(Locale.ROOT) should startWith("asc")
          Using.resource(a.createStatement())(_.execute("SET default_collation = 'nocase'"))
          current(b, "default_collation") shouldBe "nocase"
          DuckDbSettingScopes.Settings("errors_as_json").scope shouldBe Scope.LocalDefault
          Using.resource(a.createStatement())(_.execute("SET errors_as_json = true"))
          current(b, "errors_as_json") shouldBe "false"
        }
      }
    }
