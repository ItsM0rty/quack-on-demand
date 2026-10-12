package ai.starlake.quack.ondemand.federation.rdbms

import ai.starlake.quack.model.{FederatedSourceType, Names}
import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*

/** The relational database a typed federated source attaches, with the DuckDB facts that differ
  * between the two: the extension, the default port, the secret option spelling of the TLS mode and
  * the values that option accepts.
  *
  * `sslModes` are the values each extension forwards to its client library: libpq's `sslmode` for
  * postgres (verified on the 2.0 CLI: `SSLMODE 'require'` against a server without TLS fails the
  * ATTACH with "server does not support SSL, but SSL was required"), and the mysql extension's
  * `ssl_mode` secret parameter (duckdb-mysql `SetMySQLSecretParameters`; the extension is not in
  * the 2.0 toolchain, so that one is read from source, not run).
  */
enum RdbmsKind(
    val sourceType: FederatedSourceType,
    val extension: String,
    val defaultPort: Int,
    val secretPrefix: String,
    val sslModeOption: String,
    val sslModes: List[String]
):
  case Postgres
      extends RdbmsKind(
        FederatedSourceType.Postgres,
        "postgres",
        5432,
        "qod_pg_",
        "SSLMODE",
        List("disable", "allow", "prefer", "require", "verify-ca", "verify-full")
      )
  case Mysql
      extends RdbmsKind(
        FederatedSourceType.Mysql,
        "mysql",
        3306,
        "qod_mysql_",
        "SSL_MODE",
        List("disabled", "preferred", "required", "verify_ca", "verify_identity")
      )

  def wire: String = sourceType.wire

object RdbmsKind:
  def of(t: FederatedSourceType): Option[RdbmsKind] = values.find(_.sourceType == t)

/** Typed declaration of one external Postgres or MySQL database, persisted as JSON in
  * `qodstate_federated_source.config`. Which of the two is decided by the row's `sourceType`, not
  * by anything in here: the two share this shape, and `schema` is postgres-only.
  *
  * `password` is the one credential and must be a `{{secret.NAME}}` placeholder, resolved at node
  * spawn by [[ai.starlake.quack.ondemand.federation.FederationBlobBuilder]] against
  * `qodstate_federated_secret` (inline value or externalRef), exactly as an Iceberg `clientSecret`
  * is. That is why this type is safe to return over REST and to export in a manifest unredacted:
  * the secret's own value is what the read paths redact.
  *
  * The password is REQUIRED, not merely allowed. With no password on the secret, libpq and the
  * MySQL client fall back to the node process's own defaults (`PGPASSWORD`, `~/.pgpass`,
  * `~/.my.cnf`), i.e. to credentials the node holds for some other purpose, under a user name the
  * tenant chose. For the same reason `host` must be a host name or an IP address: a path would make
  * libpq connect over a Unix socket on the node's own host, where peer authentication needs no
  * password at all.
  */
final case class RdbmsConfig(
    host: String = "",
    port: Option[Int] = None,
    database: String = "",
    user: String = "",
    password: String = "",
    schema: Option[String] = None,
    sslMode: Option[String] = None
):

  def toJson: String = this.asJson.noSpaces

  /** The config exactly as it will be rendered: every string trimmed, blank optionals dropped and
    * the default port filled in. Validation runs on THIS, never on the raw input, so a value cannot
    * pass a check and then change shape on its way into the SQL.
    */
  def normalized(kind: RdbmsKind): RdbmsConfig =
    RdbmsConfig(
      host = host.trim,
      port = Some(port.getOrElse(kind.defaultPort)),
      database = database.trim,
      user = user.trim,
      password = password.trim,
      schema = schema.map(_.trim).filter(_.nonEmpty),
      sslMode = sslMode.map(_.trim).filter(_.nonEmpty)
    )

  /** Empty list when this (already normalized) config is usable. Every rule here is one the manager
    * can check before a node ever runs the SQL; a bad source takes down the whole node's federation
    * blob, not just its own catalog.
    */
  def validate(kind: RdbmsKind, alias: String, reservedAliases: Set[String]): List[String] =
    val errs = List.newBuilder[String]

    if !Names.isValid(alias) then
      errs += s"alias '$alias' must be a plain SQL identifier of 1..${Names.MaxLength} chars " +
        "(letters, digits, underscore; not starting with a digit)"
    if (reservedAliases ++ RdbmsConfig.ReservedAliases).exists(_.equalsIgnoreCase(alias)) then
      errs += s"alias '$alias' is reserved"

    if host.isEmpty then errs += "host is required"
    else if !RdbmsConfig.isHost(host) then
      errs += s"host '$host' must be a host name or an IP address (a socket path is not accepted)"

    port.foreach { p =>
      if p < 1 || p > 65535 then errs += s"port $p is out of range (1..65535)"
    }

    if database.isEmpty then errs += "database is required"
    if user.isEmpty then errs += "user is required"

    if password.isEmpty then
      errs += "password is required: a {{secret.NAME}} placeholder naming one of this source's " +
        "secrets (without one the client library falls back to the node's own credentials)"
    else if !RdbmsConfig.SecretPlaceholder.matches(password) then
      errs += "password must be a secret placeholder of the form {{secret.NAME}}, not a literal " +
        "value"

    if schema.isDefined && kind != RdbmsKind.Postgres then
      errs += s"schema applies only to sourceType 'postgres'"

    sslMode.foreach { m =>
      if !kind.sslModes.contains(m) then
        errs += s"sslMode '$m' is not valid for sourceType '${kind.wire}' (expected one of: " +
          s"${kind.sslModes.mkString(", ")})"
    }

    // Every non-credential field is rendered as a literal and must not carry a placeholder or a
    // control character: FederationBlobBuilder substitutes `{{secret.NAME}}` anywhere in the
    // rendered SQL, so a brace here would either resolve a secret into a field the read paths
    // show in the clear or trip its stray-placeholder check and fail the whole blob.
    List(
      "host"     -> Some(host),
      "database" -> Some(database),
      "user"     -> Some(user),
      "schema"   -> schema,
      "sslMode"  -> sslMode
    ).foreach { (name, value) =>
      value.foreach { v =>
        if v.contains("{{") then errs += s"$name must not contain '{{' (only password is a secret)"
        if v.exists(RdbmsConfig.isControl) then errs += s"$name must not contain control characters"
      }
    }

    errs.result()

/** An [[RdbmsConfig]] that passed [[RdbmsConfig.validated]]: NORMALIZED config, its kind and the
  * normalized alias. The constructor is private to the package, so [[RdbmsSetupSql.render]] cannot
  * be handed anything that skipped validation.
  */
final case class ValidatedRdbmsConfig private[rdbms] (
    kind: RdbmsKind,
    config: RdbmsConfig,
    alias: String
)

object RdbmsConfig:
  /** DuckDB's built-in catalogs. An alias colliding with one of these would shadow it. */
  val ReservedAliases: Set[String] = ai.starlake.quack.model.DuckDbCatalogs.Builtins

  private[rdbms] val SecretPlaceholder = "\\{\\{secret\\.[A-Za-z0-9_]+\\}\\}".r

  private val Hostname = "[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?".r
  private val Ipv6     = "[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}".r

  private def isHost(h: String): Boolean = Hostname.matches(h) || Ipv6.matches(h)

  private def isControl(c: Char): Boolean = c < ' ' || c == '\u007f'

  // Absent optional wire fields fall back to the case-class defaults, as for IcebergRestConfig.
  private given Configuration = Configuration.default.withDefaults

  given Codec[RdbmsConfig] = ConfiguredCodec.derived

  def fromJson(s: String): Either[String, RdbmsConfig] =
    io.circe.parser.decode[RdbmsConfig](s).left.map(_.getMessage)

  /** Normalize the alias (Names rule) and the config, validate the normalized forms, then render
    * and check the RENDERED text too: the only `{{` it may carry is the password placeholder, which
    * is exactly what FederationBlobBuilder will look for. Left carries every error at once.
    *
    * `extraReserved` is the set `IcebergRestConfig.validated` takes: the tenant-db's own catalog
    * alias plus every sibling federated alias, so a second ATTACH under a name the node already
    * holds is refused here rather than half-applied on the node. Required, with no default: an
    * empty default would compile at a forgetful call site and silently drop the collision check.
    */
  def validated(
      kind: RdbmsKind,
      cfg: RdbmsConfig,
      alias: String,
      extraReserved: Set[String]
  ): Either[List[String], ValidatedRdbmsConfig] =
    Names.normalizeOrError(alias, "alias") match
      case Left(err)   => Left(List(err))
      case Right(norm) =>
        val n    = cfg.normalized(kind)
        val errs = n.validate(kind, norm, ReservedAliases ++ extraReserved)
        if errs.nonEmpty then Left(errs)
        else
          val v        = ValidatedRdbmsConfig(kind, n, norm)
          val leftover =
            SecretPlaceholder.replaceAllIn(RdbmsSetupSql.render(v, readOnly = true), "")
          if leftover.contains("{{") then
            Left(List("rendered setup SQL carries a placeholder other than the password"))
          else Right(v)
