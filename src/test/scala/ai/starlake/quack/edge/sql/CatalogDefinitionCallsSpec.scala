package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source

/** `pg_get_viewdef(oid)` and `pg_get_constraintdef(oid)` are DuckDB 2.0 scalar macros over
  * `duckdb_views()` / `duckdb_constraints()`, outside the metadata filter: on the 2.0 CLI, `SELECT
  * pg_get_viewdef(o) FROM range(100000) ..` printed the SQL of every view of the node, and
  * `pg_get_constraintdef(..)` every CHECK expression. A principal holding a grant on one table read
  * the definition of objects it holds no grant on. They are now unsupported constructs: only the
  * unrestricted `*.*.* ALL` grant covers them, like a direct `duckdb_views()` read.
  */
class CatalogDefinitionCallsSpec extends AnyFlatSpec with Matchers:

  private def perm(c: String, s: String, t: String, v: String) =
    RolePermission(s"rp-$c-$s-$t-$v", "role-1", c, s, t, v, None)
  private def eff(ps: RolePermission*) =
    EffectiveSet(RbacUser("u-1", Some("t-1"), "alice", "user"), Nil, Nil, ps.toList, Nil)
  private val narrow    = eff(perm("tpch", "main", "orders", "RO"))
  private val all       = eff(perm("*", "*", "*", "ALL"))
  private val validator =
    new PostgresAclValidator(tenantCatalogs = _ => Set("tpch"), filteredMetadata = true)
  private def validate(sql: String, e: EffectiveSet) =
    validator.validate(
      ValidationContext(
        username = "alice",
        database = "t-1/td-1/p-1",
        statement = sql,
        peer = "conn-1",
        defaultDatabase = Some("tpch"),
        defaultSchema = Some("main"),
        effectiveSet = Some(e)
      )
    )

  private val definitionReads = List(
    "SELECT pg_get_viewdef(1234)",
    "SELECT pg_get_viewdef(o_id) FROM orders",
    "SELECT pg_catalog.pg_get_viewdef(o_id) FROM orders",
    "SELECT \"pg_get_viewdef\"(1)",
    "SELECT pg_get_constraintdef(o_id) FROM orders",
    "SELECT o_id FROM orders WHERE pg_get_viewdef(o_id) LIKE '%SECRET%'",
    "SELECT (SELECT pg_get_constraintdef(1))"
  )

  "a definition-returning catalog function" should "be denied to a narrow grant" in
    definitionReads.foreach(sql => withClue(sql)(validate(sql, narrow) shouldBe a[Denied]))

  it should "be covered by the unrestricted *.*.* ALL grant" in
    definitionReads.foreach(sql => withClue(sql)(validate(sql, all) shouldBe Allowed))

  it should "leave the constant and type-name macros alone" in
    List(
      "SELECT format_type(23, -1)",
      "SELECT obj_description(1), col_description(1, 1), pg_get_expr('x', 1)",
      "SELECT o_id FROM orders"
    ).foreach(sql => withClue(sql)(validate(sql, narrow) shouldBe Allowed))

  // Derived from the pinned 2.0 macro bodies: a scalar macro whose body reads a catalog-object
  // listing (tables, views, columns, constraints, indexes, sequences, schemas, triggers,
  // dependencies) returns another object's definition outside the metadata filter, so it must be
  // gated here; a new or changed one fails until judged.
  "every 2.0 scalar macro reading a catalog-object listing" should "be gated" in {
    val listings = Set(
      "duckdb_tables",
      "duckdb_views",
      "duckdb_columns",
      "duckdb_constraints",
      "duckdb_indexes",
      "duckdb_sequences",
      "duckdb_schemas",
      "duckdb_triggers",
      "duckdb_dependencies"
    )
    val src     = Source.fromResource("lockdown/duckdb-2.0-macros.csv")
    val readers =
      try
        src
          .getLines()
          .filterNot(l => l.startsWith("#") || l.isBlank)
          .map(_.split(','))
          .collect {
            case cols if cols(0) == "macro" && cols(2).split('|').exists(listings) => cols(1)
          }
          .toSet
      finally src.close()
    readers shouldBe Set("pg_get_viewdef", "pg_get_constraintdef")
    readers.foreach(r => withClue(r)(CatalogDefinitionCalls.Names should contain(r)))
  }
