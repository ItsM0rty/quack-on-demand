package ai.starlake.quack.model

/** Discriminator for [[FederatedSource]]. `Sql` is the original free-form form, where the operator
  * writes the INSTALL / CREATE SECRET / ATTACH themselves. The others are declared through typed
  * fields instead, and QoD renders the SQL: `IcebergRest` through
  * `ai.starlake.quack.ondemand.federation.iceberg.IcebergSetupSql`, `Postgres` and `Mysql` through
  * `ai.starlake.quack.ondemand.federation.rdbms.RdbmsSetupSql`.
  *
  * This enum lives in `model` (a leaf package that must not import `ondemand`), which is also why
  * [[FederatedSource.config]] is raw JSON text rather than a typed config object.
  */
enum FederatedSourceType(val wire: String):
  case Sql         extends FederatedSourceType("sql")
  case IcebergRest extends FederatedSourceType("iceberg_rest")
  case Postgres    extends FederatedSourceType("postgres")
  case Mysql       extends FederatedSourceType("mysql")

  /** True when QoD renders this source's ATTACH from typed config, so the rendered SQL is QoD's
    * own: the attach verifier re-issues it, and `readOnly` reaches the engine as `READ_ONLY`.
    */
  def rendered: Boolean = this != Sql

  /** True for the relational database types whose config is
    * `ai.starlake.quack.ondemand.federation.rdbms.RdbmsConfig`.
    */
  def rdbms: Boolean = this == Postgres || this == Mysql

object FederatedSourceType:
  def fromWire(s: String): Option[FederatedSourceType] =
    val t = Option(s).getOrElse("").trim
    values.find(_.wire.equalsIgnoreCase(t))

  /** Every wire value, for error messages that list the accepted ones. */
  def wireList: String = values.map(_.wire).mkString(", ")

  /** Lenient read path: an unknown or NULL `source_type` column reads as `Sql`, which is what every
    * pre-0038 row is.
    */
  def fromWireOrSql(s: String): FederatedSourceType = fromWire(s).getOrElse(Sql)
