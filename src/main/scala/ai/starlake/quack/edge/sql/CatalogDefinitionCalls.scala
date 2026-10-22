package ai.starlake.quack.edge.sql

/** Scalar functions that return another catalog object's definition by oid, read from the node's
  * catalog listings outside the metadata filter. In DuckDB 2.0 they are macros:
  * `pg_get_viewdef(oid)` is `(SELECT sql FROM duckdb_views() WHERE view_oid = oid)` and
  * `pg_get_constraintdef(oid)` reads `duckdb_constraints()`. A caller enumerating oids (`SELECT
  * pg_get_viewdef(o) FROM range(100000) ..`) read the SQL of every view and every CHECK expression
  * of the node (2.0 CLI), whatever its grants.
  *
  * Gated as unsupported constructs ([[AccessGate]]): refused unless the principal holds the
  * unrestricted `*.*.* ALL` grant, which already covers a direct `duckdb_views()` /
  * `duckdb_constraints()` read; under OPA, refused (every OPA refusal denies). Superusers never
  * reach the gate. Not routed through the metadata filter: that would mean rewriting a scalar call,
  * in every spelling and position, into a correlated read of a filtered listing, and the filter
  * does not narrow constraints at all; the refusal is lexical and complete.
  *
  * The other catalog-flavoured 2.0 macros return no other object's definition: `format_type` reads
  * `duckdb_types()` for a type name, `col_description` / `obj_description` / `shobj_description`
  * return NULL, `pg_get_expr` returns its argument, the `pg_*_is_visible` / `has_*_privilege`
  * family return constants. CatalogDefinitionCallsSpec derives the list from the pinned 2.0 macro
  * bodies: a scalar macro reading a catalog-object listing fails it until gated here.
  */
object CatalogDefinitionCalls:

  val Names: Set[String] = Set("pg_get_viewdef", "pg_get_constraintdef")

  /** The `unsupported` markers of the definition-returning calls of `sql`, any quoting, qualified,
    * inside literal bodies too ([[FunctionCalls]]).
    */
  def markers(sql: String): List[String] =
    if sql == null then Nil
    else
      FunctionCalls
        .scan(sql)
        .calls
        .map(_.name)
        .filter(Names)
        .distinct
        .map(n => s"catalog definition function $n")
        .toList
