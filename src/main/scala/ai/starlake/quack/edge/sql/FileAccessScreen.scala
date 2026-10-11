package ai.starlake.quack.edge.sql

import ai.starlake.sql.SqlLexer

/** Direct file access, refused to every tenant-scoped principal whenever the ACL validates (QoD
  * grant mode and OPA mode alike), the wildcard `*.*.* ALL` included. Owner ruling (2026-10-11): a
  * tenant user reads and writes data through tables; a file is read or written by a superuser, or
  * reached through a federated source. These were "unsupported constructs" that a `*.*.* ALL` grant
  * covered, so a tenant admin could read any file or object the node's process and secrets reach
  * (another tenant's DuckLake data files, the node's local disk) and write files with COPY.
  *
  * Refused, in every statement of a batch and behind EXPLAIN / PREPARE:
  *
  *   - a call to a file-reading table function ([[FileFunctions]]: `read_parquet`, `read_csv`,
  *     `read_json`, `glob`, `read_text`, `parquet_metadata`, `iceberg_scan`, ...), read by
  *     [[FunctionCalls]] under every quoting DuckDB 2.0 accepts, inside string literal bodies too;
  *   - a string literal in a table position (`FROM 'x.parquet'`, `JOIN 's3://..'`, `SUMMARIZE
  *     '/d/x.csv'`, `MERGE .. USING '..'`), which DuckDB replacement-scans as a file;
  *   - a quoted identifier shaped like a URL or a local path, at any position (`FROM
  *     "s3://b/x.parquet"`, `FROM "/d/x"."csv"`), which DuckDB replacement-scans the same way;
  *   - `COPY` to or from a path, `EXPORT DATABASE` and `IMPORT DATABASE` (`COPY FROM DATABASE a TO
  *     b` copies between attached catalogs and names no file);
  *   - `ATTACH` (a DuckDB, SQLite or DuckLake file is opened by path; a connection string reaches a
  *     caller-chosen server, refused in its function form by [[CatalogReachingCalls]]), and a
  *     `LOAD` / `INSTALL` naming a file, a URL or a repository (`LOAD '/tmp/x.duckdb_extension'`
  *     runs code read from a file). A bare `LOAD httpfs` stays admitted.
  *
  * Not refused: the native front door's client data scan (`scan_data_from_quack_client` reads the
  * client's own stream), the DuckLake catalog functions ([[CatalogReachingCalls]]' concern), and
  * attached sources read by table name (federation, Iceberg catalogs). An unquoted relative file
  * name DuckDB falls back to reading (`FROM x.parquet`, schema x and table parquet not found) is
  * not covered: the owner ruled out a name rule.
  *
  * Lexical on purpose, like [[CatalogReachingCalls]]: an unparseable statement reaches the
  * wildcard-coverable parse-error arm with no AST at all. Same population and consumers:
  * [[PostgresAclValidator]] and the OPA path, both through [[AccessGate]], and
  * [[ai.starlake.quack.edge.policy.ProtectedWriteGuard]]. A superuser reaches none of them.
  */
object FileAccessScreen:

  /** Every table function that opens a caller-named file, path list, glob or URL: the lockdown
    * screen's list (derived from the 2.0 CLI's table functions with every extension a node loads,
    * pinned by LockdownTableFunctionsSpec), plus the readers of extensions a node may install that
    * the 2.0 toolchain lacks (excel, spatial, delta).
    */
  val FileFunctions: Set[String] = LockdownScreen.FileReadFunctions ++ Set(
    "read_xlsx",
    "st_read",
    "st_read_meta",
    "st_readosm",
    "st_readshp",
    "delta_scan"
  )

  /** Some(reason) when `sql` reads or writes a file directly. */
  def find(sql: String): Option[String] =
    if sql == null then None
    else
      val scan = FunctionCalls.scan(sql)
      scan.calls.iterator
        .map(_.name)
        .find(FileFunctions)
        .map(fn => reason(s"$fn reads files by path"))
        .orElse(
          Option.when(scan.truncated)(reason("a statement nesting string literals too deeply"))
        )
        .orElse(LockdownScreen.splitStatements(sql).iterator.flatMap(findOne).nextOption())

  private def findOne(stmt: String): Option[String] =
    statementVerb(stmt)
      .orElse(
        LockdownScreen.replacementScans(
          stmt,
          (_, _) => Some(reason("a file path in a table position reads the file"))
        )
      )
      .orElse(
        LockdownScreen.quotedPathFrom(
          stmt,
          name =>
            Option.when(pathShaped(name))(reason("a quoted path or URL as a table reads the file"))
        )
      )

  /** The statement verbs that read or write files, judged on the statement an EXPLAIN / PREPARE
    * wrapper carries.
    */
  private def statementVerb(stmt: String): Option[String] =
    val head = StatementHead.of(stmt)
    val k    = head.start
    head.word(k) match
      case Some("copy")
          if !(head.word(k + 1).contains("from") && head.word(k + 2).contains("database")) =>
        Some(reason("COPY reads or writes a file"))
      case Some("export") | Some("import") =>
        Some(reason("EXPORT / IMPORT DATABASE reads or writes files"))
      case Some("attach") => Some(reason("ATTACH opens a database file or a connection"))
      case Some("load") | Some("install") => Option.when(namesSource(head, k + 1))(loadReason)
      case Some("force") if head.word(k + 1).contains("install") =>
        Option.when(namesSource(head, k + 2))(loadReason)
      case _ => None

  /** A LOAD / INSTALL that names its extension by a quoted path or URL, or a repository. */
  private def namesSource(head: StatementHead.Head, from: Int): Boolean =
    (from until head.toks.length).exists { i =>
      head.toks(i).kind == SqlLexer.Kind.Literal || head.word(i).contains("from")
    }

  private val loadReason = reason("LOAD / INSTALL from a file, a URL or a repository loads code")

  /** A joined quoted-identifier chain DuckDB would read as a file: a URL, or a local path. */
  private def pathShaped(name: String): Boolean =
    name.contains("://") || LockdownScreen.looksLikePath("'" + name + "'")

  private def reason(what: String): String =
    s"$what; direct file access is not available to tenant-scoped principals (deny, " +
      "fail-closed): a superuser or a federated source must do it"
