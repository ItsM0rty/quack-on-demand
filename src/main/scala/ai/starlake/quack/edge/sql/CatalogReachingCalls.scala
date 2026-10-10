package ai.starlake.quack.edge.sql

/** DuckDB table functions that resolve their target from a STRING at run time: `query('<sql>')`,
  * `query_table('<name>')`, `json_execute_serialized_sql(...)` and the DuckLake extension's
  * `ducklake_*` family (`ducklake_table_insertions('<catalog>', '<schema>', '<table>', ...)`,
  * `ducklake_expire_snapshots('<catalog>')`, ...). The ACL parser only sees a table function call,
  * never the table it reaches, so neither catalog scoping nor the RLS / CLS rewriters can apply to
  * what it reads. Two consumers deny them for a tenant-scoped principal:
  *
  *   - [[PostgresAclValidator]], unconditionally: the `*.*.* ALL` wildcard covers "unsupported
  *     constructs", and admitting these under it lets a tenant admin read (or maintain) a sibling
  *     tenant's catalog that the wildcard's own catalog scoping refuses when named directly.
  *   - [[ai.starlake.quack.edge.policy.ProtectedWriteGuard]], for a principal with column or row
  *     policies, on every statement kind (including a plain SELECT, and with ACL disabled): reading
  *     a protected table through one of these returns unfiltered, unmasked rows.
  *
  * Lexical on purpose, not an AST walk: an unparseable statement reaches the wildcard-coverable
  * parse-error arm with no AST at all. Calls are read from DuckDB 2.0's own tokens by
  * [[FunctionCalls]]: a listed name, bare, double-quoted or backtick-quoted (DuckDB 2.0 runs
  * `` `query_table`('orders') ``), qualified or not, followed past any trivia by an opening
  * parenthesis, compared case-insensitively after unquoting. A name in a comment, or a quoted
  * identifier with no parenthesis after it (a column named `query`), is not a call. String literal
  * bodies are scanned too, so a literal like `'query('` over-denies: the safe direction.
  */
object CatalogReachingCalls:

  private val Fixed = Set("query", "query_table", "json_execute_serialized_sql")

  /** The first catalog-reaching function called by `sql`, lowercased, or None.
    *
    * A string literal nested past [[FunctionCalls.MaxDepth]] is left unscanned without denying. A
    * literal runs as SQL only through a function that takes SQL text. On the node itself those are
    * `query` and `json_execute_serialized_sql`, both listed here and so found at a shallower level.
    * The others (`quack_query`, `quack_query_by_name`, `postgres_query`, `postgres_execute`) send
    * their SQL to the server they name instead of running it here, and this gate makes no claim
    * about what that server runs, nested or not.
    */
  def find(sql: String): Option[String] =
    FunctionCalls.scan(sql).calls.iterator.map(_.name).find(isCatalogReaching)

  private def isCatalogReaching(name: String): Boolean =
    Fixed.contains(name) || name.startsWith("ducklake_")

  def denyReason(fn: String): String =
    s"table function $fn resolves its target at run time, so its access cannot be authorized; " +
      "not available to tenant-scoped principals (deny, fail-closed)"
