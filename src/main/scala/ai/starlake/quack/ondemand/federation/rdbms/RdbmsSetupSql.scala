package ai.starlake.quack.ondemand.federation.rdbms

import ai.starlake.quack.model.SqlLiterals.{duckdbIdent as ident, duckdbLiteral as lit}

/** Authors the `INSTALL` + `CREATE TEMPORARY SECRET` + `ATTACH` + `DROP SECRET` block for one
  * Postgres or MySQL source, the single source of truth in the same spirit as `IcebergSetupSql`.
  *
  * Output carries the `{{secret.NAME}}` password placeholder verbatim: this runs BEFORE
  * [[ai.starlake.quack.ondemand.federation.FederationBlobBuilder]] substitutes and SQL-escapes the
  * resolved value, so nothing here may resolve a secret.
  *
  * The credentials ride a named temporary secret and never the ATTACH string. DuckDB prints an
  * attach path verbatim in `duckdb_databases().path` and in connection errors, readable by any SQL
  * user of the node; with every connection field on the secret the path holds only
  * `RdbmsKind.attachPath` (`connect_timeout=10` for postgres, `''` for mysql), never a credential
  * (verified on the 2.0 CLI against Postgres 17, including a failed ATTACH: "Unable to connect to
  * Postgres at "": ... password authentication failed for user ...").
  *
  * The secret is DROPPED right after the ATTACH, as the node's own DuckLake catalog secret is
  * (`CatalogPgSecret`). Evidence, 2.0 CLI against Postgres 17: the postgres extension resolves the
  * secret into the attached catalog's connection string at ATTACH time, so after the drop the
  * catalog still serves queries, and still does once every backend it held was terminated from
  * outside with `pg_terminate_backend` (the next query opened a NEW backend, a different pid, with
  * no secret left). The duckdb-mysql source does the same (`MySQLCatalog` keeps the connection
  * string built from the secret at attach). Keeping the secret would be a hole, not a convenience:
  * any node SQL user who may run ATTACH could `ATTACH '' AS w (TYPE postgres, SECRET <name>)`
  * WITHOUT `READ_ONLY` and write through the operator's credentials, which the CLI did.
  *
  * The piped node init runs with `.bail off`, so a failed ATTACH (an unreachable database) still
  * reaches the DROP. A path that runs this block as ONE batch, where a failure stops the batch,
  * must run [[cleanupSql]] itself afterwards; the attach verifier does. The secret is therefore
  * live for exactly as long as the ATTACH runs. At spawn that is before `quack_serve`, so no
  * session exists; on the verifier's re-attach the node is serving, and a session that may run
  * ATTACH could name the secret in that window with a path of its own, and the path's keys WIN over
  * the secret's (2.0 CLI: `ATTACH 'host=127.0.0.2' ... (SECRET s)` dialled 127.0.0.2), i.e.
  * redirect the password. `connect_timeout` bounds that window for postgres.
  *
  * One statement per line group, never two on one line: when a statement fails the CLI skips the
  * rest of that line (CLAUDE.md).
  */
object RdbmsSetupSql:

  /** Every `CREATE SECRET` option whose VALUE is a credential, declared once so
    * `AttachErrorRedactor` can widen its parser from it, as it does from
    * `IcebergSetupSql.CredentialOption`.
    */
  enum CredentialOption(val optionName: String):
    case Password extends CredentialOption("PASSWORD")

  /** Per-alias secret name, prefixed per kind. The alias arrives normalized (lowercase) and is
    * unique within the tenant-db, so the name is unique among the node's federated secrets, and the
    * kind prefix keeps it apart from the Iceberg (`qod_ice_`) and catalog (`qod_catalog_pg`) ones.
    */
  def secretName(kind: RdbmsKind, alias: String): String = kind.secretPrefix + alias

  /** `readOnly` carries no default on purpose: a forgotten argument would attach a source the
    * operator marked read-only as writable. When true the ATTACH carries `READ_ONLY`, which the
    * postgres extension enforces (2.0 CLI: INSERT refused with "attached in read-only mode", and
    * `postgres_execute` writes refused by the remote's READ ONLY transaction); see
    * `FederatedSource.readOnly` for the `postgres_execute` residual.
    */
  def render(v: ValidatedRdbmsConfig, readOnly: Boolean): String =
    val cfg        = v.config
    val kind       = v.kind
    val secret     = ident(secretName(kind, v.alias))
    val secretOpts = List(
      Some(s"TYPE ${kind.extension}"),
      Some(s"HOST ${lit(cfg.host)}"),
      cfg.port.map(p => s"PORT ${portValue(kind, p)}"),
      Some(s"DATABASE ${lit(cfg.database)}"),
      Some(s"USER ${lit(cfg.user)}"),
      Some(s"${CredentialOption.Password.optionName} ${lit(cfg.password)}"),
      cfg.sslMode.map(m => s"${kind.sslModeOption} ${lit(m)}")
    ).flatten
    val attachOpts = List(
      Some(s"TYPE ${kind.extension}"),
      Some(s"SECRET $secret"),
      cfg.schema.map(s => s"SCHEMA ${lit(s)}"),
      Option.when(readOnly)("READ_ONLY")
    ).flatten
    s"INSTALL ${kind.extension};\nLOAD ${kind.extension};\n" +
      s"CREATE OR REPLACE TEMPORARY SECRET $secret (\n  " + secretOpts.mkString(
        ",\n  "
      ) + "\n);\n" +
      s"ATTACH ${lit(kind.attachPath)} AS ${ident(v.alias)} (\n  " + attachOpts.mkString(",\n  ") +
      "\n);\n" +
      cleanupSql(kind, v.alias)

  /** Drops the source's secret. The tail of [[render]], and what a caller that ran the block as one
    * batch runs on its own once the batch failed part way.
    */
  def cleanupSql(kind: RdbmsKind, alias: String): String =
    s"DROP SECRET IF EXISTS ${ident(secretName(kind, alias))};"

  /** The postgres secret takes an integer port; the mysql extension declares `port` as VARCHAR
    * (`SetMySQLSecretParameters`), so it gets the string form. Either way the value is an `Int`
    * that passed the range check, never operator text.
    */
  private def portValue(kind: RdbmsKind, port: Int): String = kind match
    case RdbmsKind.Postgres => port.toString
    case RdbmsKind.Mysql    => lit(port.toString)
