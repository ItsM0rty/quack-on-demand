package ai.starlake.quack.ondemand

import ai.starlake.quack.model.SqlLiterals

/** How a DuckLake catalog's Postgres password reaches DuckDB without ever riding an ATTACH string.
  *
  * DuckDB keeps an ATTACH path verbatim: `duckdb_databases().path` prints it to any SQL user of the
  * node, and the postgres extension repeats it in connection errors. A `password=` in a
  * `ducklake:postgres:` path therefore hands the catalog credential (and with it every per-file
  * encryption key of an encrypted database) to whoever can query the node. Instead the password
  * goes into a TEMPORARY `TYPE postgres` secret, the attach path carries only host, port, dbname
  * and user, and the ATTACH names the secret: `SECRET <name>` on a postgres attach,
  * `METADATA_PARAMETERS MAP {'SECRET': '<name>'}` on a DuckLake one. `duckdb_secrets()` redacts the
  * password (and `allow_unredacted_secrets` is refused at the edge).
  *
  * The secret is dropped right after the ATTACH, before any federation SQL or the quack server, so
  * no session can reuse it to attach the catalog again under a name the edge's screens do not know.
  * Verified on DuckDB 2.0 against a real Postgres: the attached catalog keeps the resolved
  * connection string, so new pool connections, a cleared connection cache and a Postgres restart
  * all reconnect after the drop; the reconnect error names the password-free path.
  *
  * Mirrored by the spawn scripts (`scripts/spawn-quack-node.{sh,ps1}`), which render the same three
  * statements with the same names.
  */
object CatalogPgSecret:

  /** The node bootstrap's and the manager initializer's secret for the node's own catalog. */
  val Name: String = "qod_catalog_pg"

  /** The merge node's secret for the branch catalog, created after [[Name]] is already dropped. */
  val BranchName: String = "qod_branch_pg"

  private val SafeName = "[a-z_][a-z0-9_]*".r

  private def checked(name: String): String =
    require(SafeName.matches(name), s"secret name must be a plain identifier: $name")
    name

  def createSql(name: String, password: String): String =
    s"CREATE OR REPLACE TEMPORARY SECRET ${checked(name)} " +
      s"(TYPE postgres, PASSWORD ${SqlLiterals.duckdbLiteral(password)})"

  def dropSql(name: String): String = s"DROP SECRET IF EXISTS ${checked(name)}"

  def metadataParameters(name: String): String =
    s"METADATA_PARAMETERS MAP {'SECRET': '${checked(name)}'}"

  /** The DuckLake attach path: libpq keyword=value pairs, each value quoted and escaped, and no
    * password key.
    */
  def connstr(host: String, port: String, dbName: String, user: String): String =
    s"ducklake:postgres:host=${libpqValue(host)} port=${libpqValue(port)} " +
      s"dbname=${libpqValue(dbName)} user=${libpqValue(user)}"

  /** libpq keyword-value value: always wrap in `'...'`, escape `\` as `\\` then `'` as `\'` (the
    * backslash escape first, or the backslash added for `\'` would be doubled).
    */
  def libpqValue(v: String): String =
    "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
