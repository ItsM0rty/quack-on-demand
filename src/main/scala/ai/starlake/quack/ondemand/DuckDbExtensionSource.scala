package ai.starlake.quack.ondemand

import java.util.Properties

/** Where the manager's in-process JDBC DuckDB (the DuckLake catalog initializer, `ducklake`,
  * `postgres_scanner` and the storage extensions; nothing else in the manager opens one since the
  * embedded node client was dropped) gets its extensions. Unset (the default) it INSTALLs them from
  * DuckDB's signed repository, as always. Set to a directory of `.duckdb_extension` files it LOADs
  * them by path and opens the connection with `allow_unsigned_extensions`: the DuckDB 2.0
  * pre-release toolchain (scripts/dev/build-duckdb-prerelease.sh) and air-gapped installs, where no
  * repository serves the engine's version.
  */
final case class DuckDbExtensionSource(dir: Option[String]):

  def loadSql(extension: String): String = dir match
    case None    => s"INSTALL $extension; LOAD $extension;"
    case Some(d) =>
      val path = s"${d.stripSuffix("/")}/$extension.duckdb_extension".replace("'", "''")
      s"LOAD '$path';"

  /** JDBC properties of the manager's in-process DuckDB: the [[InProcessDuckDb]] settings (thread
    * budget, read-only secret directory), plus `allow_unsigned_extensions` when extensions load
    * from a directory.
    */
  def connectionProperties: Properties =
    val p = InProcessDuckDb.properties
    if dir.isDefined then p.setProperty("allow_unsigned_extensions", "true")
    p

object DuckDbExtensionSource:
  val EnvVar = "QOD_DUCKDB_EXTENSION_DIR"

  def from(env: Map[String, String]): DuckDbExtensionSource =
    DuckDbExtensionSource(env.get(EnvVar).map(_.trim).filter(_.nonEmpty))

  lazy val fromEnv: DuckDbExtensionSource = from(sys.env)
