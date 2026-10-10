package ai.starlake.quack.edge.sql

import java.util.Locale
import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableAccess, TableExtractor, Verb}

/** What a statement needs authorized, independent of WHO decides (QoD grants or OPA).
  *   - `Refuse`: the statement cannot be authorized table by table. `wildcardCoverable` says
  *     whether QoD's unrestricted `*.*.* ALL` grant may still admit it; OPA always denies.
  *     `covered` names what the wildcard admitted, for the allow log line.
  *   - `NothingGated`: control flow, or every access was answered by the metadata filter.
  *   - `Gated`: the accesses that need a decision (`accesses` is the full set, for logging).
  */
enum GateOutcome:
  case Refuse(message: String, wildcardCoverable: Boolean, covered: String)
  case NothingGated(accesses: Set[TableAccess], catalogFunctions: List[String])
  case Gated(gated: Set[TableAccess], accesses: Set[TableAccess])

/** The gated-access computation shared by every statement validator, so each one authorizes exactly
  * the same `(table, verb)` set. Pure: no logging, no grant lookup.
  */
object AccessGate:

  /** The SQL parser configuration for a session: the pool's metastore catalog / schema fill in
    * unqualified references, falling back to the validator's defaults.
    */
  def parserConfig(
      context: ValidationContext,
      defaultDatabase: String,
      defaultSchema: String,
      dialect: String
  ): Config =
    val db     = context.defaultDatabase.getOrElse(defaultDatabase)
    val schema = context.defaultSchema.getOrElse(defaultSchema)
    if dialect.equalsIgnoreCase("duckdb") then
      Config.forDuckDB(Some(db), Some(schema), context.attachedCatalogs)
    else Config.forGeneric(db, schema)

  /** `sessionCatalog` must be the same catalog `parserConfig` used as its default database, so the
    * filtered-metadata admit and the parser's qualification agree (see below).
    *
    * `clientDataScans` is [[ValidationContext.clientDataScans]]: true only for a statement from the
    * native Quack front door. Required, so no caller can admit the scan by omission.
    */
  def evaluate(
      statement: String,
      config: Config,
      sessionCatalog: String,
      filteredMetadata: Boolean,
      clientDataScans: Boolean
  ): GateOutcome =
    // Ahead of every other arm, and never wildcard-coverable: the target of these calls is
    // a string the parser cannot see, so the wildcard's tenant-catalog scoping cannot apply.
    CatalogReachingCalls.find(statement) match
      case Some(fn) => GateOutcome.Refuse(CatalogReachingCalls.denyReason(fn), false, "")
      case None     =>
        evaluateParsed(statement, config, sessionCatalog, filteredMetadata, clientDataScans)

  private def evaluateParsed(
      statement: String,
      config: Config,
      sessionCatalog: String,
      filteredMetadata: Boolean,
      clientDataScans: Boolean
  ): GateOutcome =
    val extraction = SqlParser.extract(statement, config)

    // Parse errors short-circuit to Denied unless the principal holds an
    // unrestricted ALL grant (preserves prior behavior).
    val parseErrors = extraction.statements.collect {
      case StatementResult.ParseError(_, snippet, msg) => s"parse error: $msg ($snippet)"
    }
    // Constructs the walker could not resolve to a grantable table (table
    // functions like read_parquet, string-literal file refs, unrecognized
    // FROM-item / node types). Fail closed: these escape the tenant-catalog
    // boundary or would otherwise be silently dropped, turning the
    // empty-access fail-open into an allow. Wildcard ALL still covers them.
    //
    // Filtered-metadata carve-out (issue #114): the DuckDB catalog functions the
    // edge rewriter filters are set aside here and admitted below, but only once
    // the statement is known to be a pure read. The marker is the parser's own
    // (TableExtractor.tableFunctionName reads it back), and the call must be
    // exactly `<function>()`: `duckdb_tables('x')`, `main.duckdb_tables()` and the
    // bare-name marker for `FROM duckdb_tables` are not that shape and stay
    // unsupported.
    //
    // A DuckDB 2.0 client's INSERT through an attached catalog reads its rows from
    // scan_data_from_quack_client, which reads only what the same connection sends: as the
    // source of a write it is set aside here, the INSERT target staying a gated Write
    // (TableExtractor.gatedUnsupported). Only on the native front door, the one edge that
    // relays SEND_DATA: from FlightSQL, MCP or `qod sql` nothing can feed the stream, the
    // statement would park on the node forever, so the scan stays unsupported there.
    val allUnsupported = extraction.statements.collect {
      case StatementResult.Extracted(_, _, a, _, u) if u.nonEmpty =>
        TableExtractor.gatedUnsupported(a, u, clientDataScans)
    }.flatten
    val (catalogFunctions, unsupported) =
      if filteredMetadata then
        allUnsupported.partition(u =>
          TableExtractor.tableFunctionName(u).flatMap(TableExtractor.catalogFunctionCall).isDefined
        )
      else (Nil, allUnsupported)
    // Qualification errors were previously ignored, silently DROPPING the
    // offending ref from the access set (fail-open). AmbiguousCatalogRef denies
    // unconditionally: admitting it under the wildcard would reopen the
    // cross-catalog bypass the attached-catalog check exists to close. Other
    // qualification errors mirror the unsupported arm (wildcard ALL covers them).
    val qualErrors = extraction.statements.collect {
      case StatementResult.Extracted(_, _, _, q, _) if q.nonEmpty => q
    }.flatten
    val ambiguous = qualErrors.collect { case a: DenyReason.AmbiguousCatalogRef => a }
    val otherQual = qualErrors.filterNot(_.isInstanceOf[DenyReason.AmbiguousCatalogRef])

    if parseErrors.nonEmpty then
      GateOutcome.Refuse(parseErrors.mkString("; "), true, "unparseable statement")
    else if unsupported.nonEmpty then
      GateOutcome.Refuse(
        s"unsupported constructs (deny, fail-closed): ${unsupported.mkString(", ")}",
        true,
        s"unsupported constructs (${unsupported.mkString(", ")})"
      )
    else if ambiguous.nonEmpty then
      GateOutcome.Refuse(
        ambiguous
          .map(a =>
            s"ambiguous two-part name '${a.tableName}': '${a.catalog}' is an attached " +
              s"catalog; qualify fully as '${a.catalog}.<schema>.<table>'"
          )
          .mkString("; "),
        false,
        ""
      )
    else if otherQual.nonEmpty then
      GateOutcome.Refuse(
        s"unresolvable table references (deny, fail-closed): ${otherQual.mkString("; ")}",
        true,
        s"qualification errors (${otherQual.mkString(", ")})"
      )
    else
      // Collect all (table, verb) tuples from every Extracted statement.
      // ControlFlow statements (COMMIT, ROLLBACK, SET, ...) carry no accesses
      // and contribute nothing.
      val accesses: Set[TableAccess] = extraction.statements
        .collect { case StatementResult.Extracted(_, _, a, _, _) =>
          a
        }
        .flatten
        .toSet

      // Filtered-metadata implicit admit: Read accesses on the session catalog's
      // filterable information_schema tables are answered by the edge metadata
      // filter (same flag), so they need no grant here. Everything else about
      // system schemas (writes, DDL, unlisted tables, cross-catalog refs) still
      // requires a grant. The flag wires from the SAME AclConfig value that
      // mounts the rewriter, so admit-without-filter cannot happen.
      //
      // SESSION catalog only. Never the tenant catalog set: the rewriter filters
      // only session-catalog references, so admitting a SIBLING tenant-db's
      // information_schema here would be admit-without-filter (unfiltered
      // enumeration of a catalog the principal may hold zero grants on).
      // The caller passes `context.defaultDatabase.getOrElse(defaultDatabase)`,
      // the same expression `parserConfig` uses, so the admit and the parser's
      // qualification of an unqualified `information_schema.tables` agree on the
      // catalog by construction.
      //
      // PURE-READ statements only: the metadata rewriter filters Select
      // statements and passes everything else through untouched, so dropping an
      // info-schema Read that rides inside an INSERT / CTAS / MERGE would be
      // admit-without-filter (unfiltered catalog copy into a table the principal
      // owns).
      val pureRead                = accesses.forall(_.verb == Verb.Read)
      val gated: Set[TableAccess] =
        if !filteredMetadata || !pureRead then accesses
        else
          accesses.filterNot { ta =>
            ta.verb == Verb.Read &&
            ta.table.schema.equalsIgnoreCase("information_schema") &&
            ai.starlake.quack.edge.meta.MetadataFilterRewriter.FilterableTables
              .contains(ta.table.table.toLowerCase(Locale.ROOT)) &&
            ta.table.database.equalsIgnoreCase(sessionCatalog)
          }

      if catalogFunctions.nonEmpty && !pureRead then
        // A catalog function riding inside an INSERT / CTAS / MERGE: the rewriter
        // does not filter the read half of a write, so admitting it would copy an
        // unfiltered catalog listing into a table the principal owns.
        GateOutcome.Refuse(
          "catalog functions are admitted in read-only statements only (deny, fail-closed): " +
            catalogFunctions.mkString(", "),
          true,
          s"unsupported constructs (${catalogFunctions.mkString(", ")})"
        )
      else if gated.isEmpty then
        // Pure ControlFlow (or every arm yielded zero refs), or an
        // all-metadata statement whose every access the implicit admit above
        // dropped. Admit unconditionally -- there is nothing left to
        // authorize. The caller's log still names the ORIGINAL accesses so the
        // audit trail shows what was read.
        GateOutcome.NothingGated(accesses, catalogFunctions)
      else GateOutcome.Gated(gated, accesses)
