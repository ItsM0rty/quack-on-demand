package ai.starlake.quack.edge.meta

import ai.starlake.acl.parser.TableExtractor
import ai.starlake.quack.edge.cls.SchemaContext
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Pins the DuckDB quack client's catalog-sync queries to what the metadata filter can rewrite
  * (issue #114). On `ATTACH ... (TYPE quack)` the client loads the remote catalog through the
  * `GetLoadQuery` statements of the vendored extension (`native/quackwire/thirdparty/duckdb-quack`,
  * a git submodule); every `duckdb_%` table function and every `information_schema` table those
  * statements name must be in [[TableExtractor.DuckDbCatalogFunctions]] /
  * [[MetadataFilterRewriter.FilterableTables]], or the attach is denied again for every principal
  * without a wildcard ALL grant, with the exact error the issue reported.
  *
  * The scan is textual over the client's storage sources: coarser than parsing each query, but it
  * cannot under-count, and a name it catches outside a sync query only ever widens what must be
  * filterable. Cancelled when the submodule is not checked out (a plain clone without
  * `--recursive`, or a CI job that does not fetch it), never passed vacuously: the scan must find
  * the three functions the sync is known to use.
  *
  * Since duckdb-quack 974927a (upstream 15ab79b, nested schemas) the schema sync reads
  * `duckdb_schemas()` through a recursive CTE instead of `information_schema.schemata`, and the
  * table sync keys tables by `schema_oid`; the client names no `information_schema` table at all.
  * The scans below pin the same property against that shape, and the last case runs each
  * `GetLoadQuery` text through the filter itself, so a sync query shape the filter cannot rewrite
  * fails here rather than as a denied ATTACH.
  */
class QuackClientSyncFunctionsSpec extends AnyFlatSpec with Matchers:

  private val storageDir: Path =
    Paths.get("native", "quackwire", "thirdparty", "duckdb-quack", "src", "storage")

  private val FunctionRe   = """\bduckdb_[a-z_]+(?=\s*\()""".r
  private val InfoSchemaRe = """\binformation_schema\.([a-z_]+)""".r

  /** The raw-string body of each `GetLoadQuery()` definition. */
  private val LoadQueryRe = """(?s)::GetLoadQuery\(\)\s*\{.*?R"\((.*?)\)";""".r

  private def clientSources: List[Path] =
    Files
      .walk(storageDir)
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".cpp") || p.toString.endsWith(".hpp"))
      .toList

  "the quack client's catalog sync" should "use only catalog functions the metadata filter rewrites" in {
    assume(Files.isDirectory(storageDir), s"duckdb-quack submodule not checked out at $storageDir")
    val text      = clientSources.map(Files.readString).mkString("\n")
    val functions = FunctionRe.findAllIn(text).toSet
    withClue("scan found nothing: the client's load queries moved, update storageDir") {
      functions should contain allOf ("duckdb_tables", "duckdb_views", "duckdb_schemas")
    }
    withClue(
      "the client syncs through a catalog function the filter does not rewrite; add it to " +
        "TableExtractor.DuckDbCatalogFunctions AND give functionPredicateFor its column names"
    ) {
      functions diff TableExtractor.DuckDbCatalogFunctions shouldBe empty
    }
  }

  it should "use only information_schema tables the metadata filter rewrites" in {
    assume(Files.isDirectory(storageDir), s"duckdb-quack submodule not checked out at $storageDir")
    val text   = clientSources.map(Files.readString).mkString("\n")
    val tables = InfoSchemaRe.findAllMatchIn(text).map(_.group(1)).toSet
    // Not vacuous: the schema sync must be found in one of its two known spellings
    // (`information_schema.schemata` up to 7e80f7f, `duckdb_schemas()` since 974927a).
    val schemaSync =
      tables.contains("schemata") || FunctionRe.findAllIn(text).contains("duckdb_schemas")
    withClue("the scan found no schema sync query: the client's load queries moved") {
      schemaSync shouldBe true
    }
    tables diff MetadataFilterRewriter.FilterableTables shouldBe empty
  }

  it should "send load queries the metadata filter rewrites rather than denies" in {
    assume(Files.isDirectory(storageDir), s"duckdb-quack submodule not checked out at $storageDir")
    val queries = clientSources.flatMap { p =>
      LoadQueryRe.findAllMatchIn(Files.readString(p)).map(m => p.getFileName.toString -> m.group(1))
    }
    withClue("found no GetLoadQuery bodies: the client's load queries moved") {
      queries.map(_._1).toSet should contain allOf ("quack_schema.cpp", "quack_table.cpp")
    }
    val user = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
    val eff  = EffectiveSet(
      user,
      Nil,
      Nil,
      List(RolePermission("rp-1", "r-1", "acme_tpch", "tpch1", "customer", "RO")),
      Nil
    )
    val ctx = SchemaContext(
      defaultDatabase = Some("acme_tpch"),
      defaultSchema = Some("tpch1"),
      attachedCatalogs = Set.empty
    )
    queries.foreach { (file, sql) =>
      withClue(s"$file load query:\n$sql\n") {
        new MetadataFilterRewriter(enabled = true).rewrite(sql, eff, ctx) match
          case MetadataFilterOutcome.Rewritten(_) => succeed
          case other                              => fail(s"expected Rewritten, got $other")
      }
    }
  }
