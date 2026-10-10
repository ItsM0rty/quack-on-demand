package ai.starlake.quack.route

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.{SqlCommentStripper, SqlLexer, SqlTrivia}

/** Per-bucket keyword sets used to classify a statement by its first non-blank token. Sets are
  * uppercased on construction so matching is case-insensitive without per-call allocation.
  *
  * Operators tune this through `quack-on-demand.statementClassifier.*` (or the matching
  * `QOD_CLASSIFIER_*` env vars). Each conf key takes a comma-separated keyword list; whitespace is
  * trimmed. Values REPLACE the built-in defaults: to add a single keyword, copy the default list
  * and append.
  */
final case class StatementClassifierConfig(
    select: Set[String],
    dml: Set[String],
    ddl: Set[String],
    begin: Set[String],
    commit: Set[String],
    rollback: Set[String]
):
  /** Uppercased copy so the classifier matches case-insensitively without per-call allocation.
    * `normalized` is idempotent.
    */
  lazy val normalized: StatementClassifierConfig = copy(
    select = select.map(_.toUpperCase(Locale.ROOT)),
    dml = dml.map(_.toUpperCase(Locale.ROOT)),
    ddl = ddl.map(_.toUpperCase(Locale.ROOT)),
    begin = begin.map(_.toUpperCase(Locale.ROOT)),
    commit = commit.map(_.toUpperCase(Locale.ROOT)),
    rollback = rollback.map(_.toUpperCase(Locale.ROOT))
  )

object StatementClassifierConfig:

  /** Parse a comma-separated keyword list. Whitespace around each entry is trimmed. Empty entries
    * (consecutive commas, trailing comma) are dropped silently. A null or whitespace-only input
    * yields an empty set -- which collapses the matching arm to "never matches", making
    * misconfiguration fail-closed rather than fail-open.
    */
  def parseCsv(raw: String): Set[String] =
    if raw == null || raw.trim.isEmpty then Set.empty
    else raw.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet

  /** Built-in defaults baked into source. Edited by operators via the conf keys; tests pass these
    * directly.
    *
    * Buckets:
    *   - `select` - read-side verbs. `WITH` covers CTE-prefixed selects. `FROM` covers DuckDB /
    *     BigQuery FROM-first shorthand. `EXPLAIN` is read-side by convention (an EXPLAIN of an
    *     INSERT still doesn't mutate).
    *   - `dml` - write-side verbs. `COPY` covers DuckDB's data-load / data-export. `UPSERT` /
    *     `REPLACE` are dialect variants of INSERT-with-conflict-resolution.
    *   - `ddl` - schema-mutating. `ATTACH` / `DETACH` are DuckDB catalog manipulation;
    *     `COMMENT ON ...` is metadata DDL; `GRANT` / `REVOKE` mutate ACL state.
    *   - `begin` - transaction start. `START TRANSACTION` is ANSI.
    *   - `commit` - transaction end. `END` is the Postgres alias.
    *   - `rollback` - transaction abort. `ABORT` is the Postgres alias.
    *
    * Anything not listed falls through to `StatementKind.Other`, which `RoleMatcher` routes like a
    * read.
    */
  val Defaults: StatementClassifierConfig = StatementClassifierConfig(
    select = Set("SELECT", "WITH", "VALUES", "SHOW", "DESCRIBE", "EXPLAIN", "FROM"),
    dml = Set("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "COPY"),
    ddl =
      Set("CREATE", "DROP", "ALTER", "TRUNCATE", "ATTACH", "DETACH", "COMMENT", "GRANT", "REVOKE"),
    begin = Set("BEGIN", "START"),
    commit = Set("COMMIT", "END"),
    rollback = Set("ROLLBACK", "ABORT")
  )

/** Cheap keyword-based classification of a SQL statement, used by the routing layer to pick a Quack
  * node role (READONLY / WRITEONLY / DUAL). Does NOT involve JSQLParser -- routing wants speed and
  * a coarse three-bucket answer; authorization runs `SqlParser.extract` separately and consumes its
  * own per-`TableAccess` `Verb` enum.
  *
  * SQL comments (`--`, `/* */`) are stripped, and every trivia character (whitespace, BOM,
  * zero-width space, non-breaking space, and other invisible `Character.FORMAT` / `SPACE_SEPARATOR`
  * characters `String.trim` and `Character.isWhitespace` don't treat as blank -- see
  * `ai.starlake.sql.SqlTrivia`) is normalized to an ASCII space ACROSS THE WHOLE STATEMENT, not
  * just its head, before the first-token match. A leading OR an interior trivia character (e.g.
  * `INSERT<NBSP>INTO t VALUES (1)`, which DuckDB executes identically to `INSERT INTO ...`) can
  * otherwise make a write look like `Other`, which `RoleMatcher` routes to a reader node and lets
  * skip `ProtectedWriteGuard` and the write audit path (the same class of bypass the `WITH` special
  * case below documents, reached here through a hidden first or verb token instead of a misleading
  * one). The normalized copy is used ONLY for classification -- `sql` itself is what every other
  * consumer, and the node, receives.
  */
final class StatementClassifier(
    config: StatementClassifierConfig = StatementClassifierConfig.Defaults
):

  private val cfg = config.normalized

  // Leading trivia/comments are stripped BEFORE stripComments, not after: `SqlTrivia.stripLeading`
  // tracks comment-nesting depth correctly, `SqlCommentStripper.stripComments` does not (it closes
  // on the FIRST `*/` it sees, however deep). DuckDB nests block comments to arbitrary depth
  // (verified against a real DuckDB 1.5.4: a leading `/* a /* b */ c */ INSERT INTO t VALUES (1)`
  // executes and writes the row), so stripping comments before leading trivia on a nested leading
  // comment exposes the literal text between the inner and outer close (`c` above) as if it were
  // the real first token -- a misclassification to `Other`, the same bucket `RoleMatcher` treats as
  // read-shaped, not merely a missed normalization. See `SqlTrivia.firstToken`'s scaladoc for the
  // identical reasoning; this entry point needs its own copy because `classifyStripped` below
  // receives already-comment-stripped text and reuses it for more than just the first token (the
  // `WITH`/`EXPLAIN` arms slice into it directly).
  //
  // `original` rides along untouched for the WITH arm, which lexes the WHOLE statement as DuckDB
  // does instead of the comment-stripped, trivia-normalized copy: `SqlTrivia.normalize` turns a
  // NBSP inside a dollar tag into a space (`$<NBSP>$` stops being a tag) and stripping a comment
  // changes what DuckDB's unicode-space pre-pass keeps (see `SqlLexer`), so either copy can open a
  // literal DuckDB does not and hide a DML verb behind it.
  def classify(sql: String): StatementKind =
    classifyStripped(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(sql)), sql)

  private def classifyStripped(raw: String, original: String): StatementKind =
    val sql = SqlTrivia.normalize(raw)
    firstToken(sql).map(_.toUpperCase(Locale.ROOT)) match
      // A WITH prefix says nothing about what the statement DOES: the verb after the
      // CTE list decides. First-token classification put WITH ... INSERT in the select
      // bucket, which routed the write to a reader node and let it skip
      // ProtectedWriteGuard, the RLS/CLS rewriters, author stamping, and the
      // data-write audit record. Only special-cased while WITH sits in the select
      // bucket, so an operator who reassigns it keeps full control.
      case Some("WITH") if cfg.select.contains("WITH") =>
        // DuckDB 2.0 allows DML inside a CTE body (WITH w AS (DELETE ... RETURNING *) SELECT ...),
        // so the verb after the list alone would misroute it as a read.
        val toks = wordTokens(original)
        if hasDmlKeyword(toks) then StatementKind.Dml
        else
          verbAfterWithClause(toks) match
            case Some(tok) =>
              kindOf(tok) match
                case StatementKind.Other => StatementKind.Select // unknown verb: legacy bucket
                case k                   => k
            case None => StatementKind.Select
      // DuckDB's EXPLAIN ANALYZE EXECUTES the inner statement; plain EXPLAIN only
      // plans it. Classify the executed statement, recursively so
      // EXPLAIN ANALYZE WITH ... INSERT resolves too.
      case Some("EXPLAIN") if cfg.select.contains("EXPLAIN") =>
        val rest = sql.trim.drop("EXPLAIN".length)
        firstToken(rest).map(_.toUpperCase(Locale.ROOT)) match
          case Some("ANALYZE") =>
            classifyStripped(rest.trim.drop("ANALYZE".length), original)
          case _ => StatementKind.Select
      case Some(tok) => kindOf(tok)
      case None      => StatementKind.Other

  private def kindOf(tok: String): StatementKind =
    if cfg.select.contains(tok) then StatementKind.Select
    else if cfg.dml.contains(tok) then StatementKind.Dml
    else if cfg.ddl.contains(tok) then StatementKind.Ddl
    else if cfg.begin.contains(tok) then StatementKind.Begin
    else if cfg.commit.contains(tok) then StatementKind.Commit
    else if cfg.rollback.contains(tok) then StatementKind.Rollback
    else StatementKind.Other

  // Routes through the one shared reader (`SqlTrivia.firstToken`) rather than a hand-rolled
  // takeWhile of its own -- see that method's scaladoc for why a sibling reader is exactly how
  // this class of bug regenerates. `sql` here is already comment-stripped and whole-string
  // normalized by `classifyStripped`, so `firstToken`'s own stripComments/stripLeading/normalize
  // passes are idempotent no-ops on it; kept anyway so this method stays correct even if a future
  // caller passes it raw SQL directly. `.takeWhile(_ != ';')` and `.dropWhile(_ == '(')` are this
  // call site's own extras (a bare `COMMIT;` or a WITH-list's opening paren), not universal to the
  // shared primitive.
  private def firstToken(sql: String): Option[String] =
    val tok = SqlTrivia.firstToken(sql).takeWhile(_ != ';').dropWhile(_ == '(')
    Option.when(tok.nonEmpty)(tok)

  /** The first keyword token at the leading WITH's depth after its CTE list, uppercased: the
    * statement's real verb. Read through [[wordTokens]], so parens or verbs inside a CTE body's
    * literals cannot desync it. The scan starts at the first `WITH` word (the statement's own, or
    * the one after `EXPLAIN ANALYZE`). CTE names, AS, RECURSIVE, MATERIALIZED and column lists are
    * all either non-keyword words or parenthesized groups, so the first token that matches a
    * configured bucket IS the verb (reserved words cannot be unquoted CTE names). None when the
    * scan runs out, which the caller maps to the legacy select bucket.
    */
  private def verbAfterWithClause(toks: Vector[StatementClassifier.WordToken]): Option[String] =
    // depth relative to the leading WITH, which a parenthesized statement opens at depth 1
    val fromWith = toks.dropWhile(_.upper != "WITH")
    fromWith.headOption.flatMap(w =>
      fromWith.iterator
        .drop(1)
        .find(t => t.depth == w.depth && kindOf(t.upper) != StatementKind.Other)
        .map(_.upper)
    )

  /** True when any word of a WITH statement, outside literals, quoted identifiers and comments and
    * at any paren depth, is a configured DML keyword or `TRUNCATE`: a data-modifying CTE (DuckDB
    * 2.0, `WITH w AS (DELETE ... RETURNING *) SELECT ...`), including one behind a CTE body's own
    * nested WITH, is a write whatever verb follows the CTE list. `TRUNCATE` joins the configured
    * DML set here because DuckDB 2.0 accepts it as a CTE body and runs it as a DELETE (`WITH w AS
    * (TRUNCATE t) SELECT 1` empties `t`); the rest of the DDL bucket stays out (`comment` is a
    * common column name, and CREATE / DROP / CHECKPOINT / MERGE bodies are parse errors on 2.0).
    * Deliberately fail closed: it does not try to tell a CTE body's verb from the same word used
    * elsewhere, so a read that carries one of these keywords as a bare word (a column named `copy`,
    * `update`, `delete` or `truncate`, say) is a false positive. A false positive is not free: the
    * read routes to a writer, is author-stamped, switches prepare to SkipExecute and is refused for
    * a branch-only token on a main pool. The one carve-out is `REPLACE` immediately followed (after
    * optional whitespace or comments) by `(`, which is DuckDB's string function (and the `SELECT *
    * REPLACE (...)` star modifier), not a write verb. `COPY (SELECT ...) TO ...` still counts.
    */
  private def hasDmlKeyword(toks: Vector[StatementClassifier.WordToken]): Boolean =
    toks.exists(t =>
      (cfg.dml.contains(t.upper) || t.upper == "TRUNCATE") &&
        !(t.upper == "REPLACE" && t.beforeParen)
    )

  /** The word tokens of `sql` (the ORIGINAL statement, comments and trivia intact), uppercased,
    * each with its paren depth and whether the next token past whitespace and comments is `(`.
    * Every boundary comes from `ai.starlake.sql.SqlLexer`, the one reader shared with
    * `SqlCommentStripper` and `LockdownScreen.splitStatements` and ported from DuckDB 2.0's own
    * front end; this class keeps no quote rules or character classes of its own, because a sibling
    * reader with its own is how a literal one reader honours and another does not hides a verb.
    * Literals, quoted identifiers, comments and numbers are not words.
    */
  private def wordTokens(sql: String): Vector[StatementClassifier.WordToken] =
    val toks  = SqlLexer.tokens(sql)
    val out   = Vector.newBuilder[StatementClassifier.WordToken]
    var depth = 0
    var k     = 0
    while k < toks.length do
      val t = toks(k)
      if t.kind == SqlLexer.Kind.Punct && sql.charAt(t.start) == '(' then depth += 1
      else if t.kind == SqlLexer.Kind.Punct && sql.charAt(t.start) == ')' then depth -= 1
      else if t.kind == SqlLexer.Kind.Word then
        var m = k + 1
        while m < toks.length && toks(m).isTrivia do m += 1
        val beforeParen =
          m < toks.length && toks(m).kind == SqlLexer.Kind.Punct && sql.charAt(toks(m).start) == '('
        out += StatementClassifier.WordToken(
          sql.substring(t.start, t.end).toUpperCase(Locale.ROOT),
          depth,
          beforeParen
        )
      k += 1
    out.result()

object StatementClassifier:

  /** One word of a statement outside literals: uppercased text, paren depth, and whether the next
    * non-blank character is `(`.
    */
  private final case class WordToken(upper: String, depth: Int, beforeParen: Boolean)

  /** Default classifier instance backed by [[StatementClassifierConfig.Defaults]]. Used by tests
    * and by call sites that haven't been threaded with a config-driven instance.
    */
  val default: StatementClassifier = new StatementClassifier(StatementClassifierConfig.Defaults)

  /** Convenience delegating to [[default]] so static-method call sites
    * (`StatementClassifier.classify(sql)`) keep working. Production wiring should construct a
    * config-driven instance via `Main.scala` and pass it through `FlightSqlRouter` to use the
    * operator's tuned keyword lists.
    */
  def classify(sql: String): StatementKind = default.classify(sql)
