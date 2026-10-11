package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{ExtractionResult, SqlParser, StatementResult, TableExtractor}
import ai.starlake.sql.SqlLexer

/** Direct file access, refused to every tenant-scoped principal whenever the ACL validates (QoD
  * grant mode and OPA mode alike), the wildcard `*.*.* ALL` included. Owner ruling (2026-10-11): a
  * tenant user reads and writes data through tables; a file is read or written by a superuser, or
  * reached through a federated source. These were "unsupported constructs" that a `*.*.* ALL` grant
  * covered, so a tenant admin could read any file or object the node's process and secrets reach
  * (another tenant's DuckLake data files, the node's local disk) and write files with COPY.
  *
  * Refused, in every statement of a batch and behind EXPLAIN / PREPARE, read from DuckDB 2.0's own
  * tokens whether the statement parses or not:
  *
  *   - a call to a file-reading table function ([[FileFunctions]]: `read_parquet`, `read_csv`,
  *     `read_json`, `glob`, `read_text`, `parquet_metadata`, `iceberg_scan`, ...), read by
  *     [[FunctionCalls]] under every quoting DuckDB 2.0 accepts, inside string literal bodies too;
  *   - `COPY` to or from a path, `EXPORT DATABASE` and `IMPORT DATABASE` (`COPY FROM DATABASE a TO
  *     b` copies between attached catalogs and names no file);
  *   - `ATTACH` (a DuckDB, SQLite or DuckLake file is opened by path; a connection string reaches a
  *     caller-chosen server, refused in its function form by [[CatalogReachingCalls]]), and a
  *     `LOAD` / `INSTALL` naming a file, a URL or a repository (`LOAD '/tmp/x.duckdb_extension'`
  *     runs code read from a file). A bare `LOAD httpfs` stays admitted.
  *
  * A file in table position, which DuckDB replacement-scans (`FROM 'x.parquet'`, `JOIN 's3://..'`,
  * `SUMMARIZE '/d/x.csv'`, `FROM "s3://b/x.parquet"`, `FROM "/d/x"."csv"`, `FROM "*".csv`), is
  * decided in two tiers:
  *
  *   - When JSqlParser reads every statement completely, from the access check's own walk: a
  *     `file reference` marker (`DuckDBDialectMapper.isFileReference`: a string literal, or a name
  *     part holding a path separator, `://` or a glob character) in any read, DESCRIBE / SHOW /
  *     SUMMARIZE, write or DDL position. Precise: `x IS DISTINCT FROM 'y'` is a value, and a
  *     keyword used as an alias (`FROM range(1) AS values, '/etc/x.csv'`) cannot hide the join
  *     after it.
  *   - Otherwise (a statement JSqlParser cannot parse, or a walk that met a node it cannot follow),
  *     a lexical scan that only over-denies: any string literal right after FROM, JOIN, USING,
  *     TABLE, a statement keyword that reads a table (SUMMARIZE, DESCRIBE, SHOW, PIVOT, ...), a
  *     comma, or an opening parenthesis in such a position, at any depth and with no notion of
  *     where a FROM list ends; and any quoted identifier chain holding `://`, a glob character or a
  *     local path shape.
  *
  * Not refused: the native front door's client data scan (`scan_data_from_quack_client` reads the
  * client's own stream), the DuckLake catalog functions ([[CatalogReachingCalls]]' concern), and
  * attached sources read by table name (federation, Iceberg catalogs). An unquoted relative file
  * name DuckDB falls back to reading (`FROM x.parquet`, schema x and table parquet not found) is
  * not covered: the owner ruled out a name rule.
  *
  * Never wildcard-coverable, like [[CatalogReachingCalls]], and with the same population and
  * consumers: [[PostgresAclValidator]] and the OPA path, both through [[AccessGate]] (which hands
  * over the extraction it parsed anyway), and
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
    find(sql, SqlParser.extract(sql, Config.forDuckDB(None, None, Set.empty)))

  /** [[find]] over the access check's own `extraction` of `sql`, evaluated only when the lexical
    * checks find nothing (file references do not depend on the qualification config).
    */
  def find(sql: String, extraction: => ExtractionResult): Option[String] =
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
        .orElse(LockdownScreen.splitStatements(sql).iterator.flatMap(statementVerb).nextOption())
        .orElse(tablePositionFile(sql, extraction))

  /** A file in table position: from the walk when it is complete, else the lexical scan. */
  private def tablePositionFile(sql: String, extraction: ExtractionResult): Option[String] =
    if incomplete(extraction) then
      LockdownScreen.splitStatements(sql).iterator.flatMap(lexicalTableFile).nextOption()
    else
      extraction.statements
        .collect { case StatementResult.Extracted(_, _, _, _, unsupported) => unsupported }
        .flatten
        .find(_.startsWith(TableExtractor.FileReferencePrefix))
        .map(m =>
          reason(
            s"${m.stripPrefix(TableExtractor.FileReferencePrefix)} in a table " +
              "position reads a file"
          )
        )

  /** The walk did not read the whole statement: a parse error, or a node it could not follow. */
  private def incomplete(extraction: ExtractionResult): Boolean =
    extraction.statements.exists {
      case _: StatementResult.ParseError                  => true
      case StatementResult.Extracted(_, _, _, _, markers) => markers.exists(IncompleteMarkers)
      case _                                              => false
    }

  private def IncompleteMarkers(marker: String): Boolean =
    List("unrecognized ", "unwalked ", "unreadable ", "data-modifying ").exists(marker.startsWith)

  // The words after which DuckDB 2.0 reads a table name: FROM, JOIN, USING, TABLE and the statement
  // keywords that take one (LockdownScreen.TableKeywords' set).
  private val TableWords = Set(
    "from",
    "join",
    "using",
    "table",
    "summarize",
    "describe",
    "desc",
    "show",
    "pivot",
    "pivot_wider",
    "pivot_longer",
    "unpivot"
  )

  /** The conservative scan of a statement the walk could not read: a string literal right after a
    * table word, a comma, or a `(` itself in such a position; or a quoted identifier chain shaped
    * like a URL, a glob or a local path.
    */
  private def lexicalTableFile(stmt: String): Option[String] =
    val view                       = SqlLexer.engineView(stmt)
    val toks                       = SqlLexer.tokens(view).filterNot(_.isTrivia)
    def text(i: Int)               = view.substring(toks(i).start, toks(i).end)
    def isWord(i: Int)             = toks(i).kind == SqlLexer.Kind.Word
    def isPunct(i: Int, c: String) = toks(i).kind == SqlLexer.Kind.Punct && text(i) == c
    def isString(i: Int)           =
      toks(i).kind == SqlLexer.Kind.Literal && view.charAt(toks(i).start) != '"' &&
        view.charAt(toks(i).start) != '`'
    // opens(i): token i puts the next token in a table position
    val opens = Array.fill(toks.length)(false)
    var hit   = false
    var i     = 0
    while i < toks.length && !hit do
      val after = i > 0 && opens(i - 1)
      if isString(i) && after then hit = true
      else
        opens(i) = (isWord(i) && TableWords(text(i).toLowerCase(java.util.Locale.ROOT))) ||
          isPunct(i, ",") || (isPunct(i, "(") && after)
      i += 1
    if hit then Some(reason("a file path in a table position reads the file"))
    else
      LockdownScreen.quotedPathFrom(
        stmt,
        name =>
          Option.when(pathShaped(name))(reason("a quoted path, URL or glob as a table reads files"))
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
