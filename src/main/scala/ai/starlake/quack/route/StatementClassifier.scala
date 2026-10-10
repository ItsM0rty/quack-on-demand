package ai.starlake.quack.route

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.{SqlCommentStripper, SqlTrivia}

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
  def classify(sql: String): StatementKind =
    classifyStripped(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(sql)))

  private def classifyStripped(raw: String): StatementKind =
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
        val toks = wordTokens(sql)
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
          case Some("ANALYZE") => classifyStripped(rest.trim.drop("ANALYZE".length))
          case _               => StatementKind.Select
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
    * literals cannot desync it. CTE names, AS, RECURSIVE, MATERIALIZED and column lists are all
    * either non-keyword words or parenthesized groups, so the first token that matches a configured
    * bucket IS the verb (reserved words cannot be unquoted CTE names). None when the scan runs out,
    * which the caller maps to the legacy select bucket.
    */
  private def verbAfterWithClause(toks: Vector[StatementClassifier.WordToken]): Option[String] =
    // depth relative to the leading WITH, which a parenthesized statement opens at depth 1
    toks.headOption.flatMap(w =>
      toks.iterator
        .drop(1)
        .find(t => t.depth == w.depth && kindOf(t.upper) != StatementKind.Other)
        .map(_.upper)
    )

  /** True when any word of a WITH statement, outside literals and quoted identifiers and at any
    * paren depth, is a configured DML keyword: a data-modifying CTE (DuckDB 2.0,
    * `WITH w AS (DELETE ... RETURNING *) SELECT ...`), including one behind a CTE body's own nested
    * WITH, is a write whatever verb follows the CTE list. Deliberately fail closed: it does not try
    * to tell a CTE body's verb from the same word used elsewhere, so a read that carries a DML
    * keyword as a bare word (a column named `copy`, `update` or `delete`, say) is a false positive.
    * A false positive is not free: the read routes to a writer, is author-stamped, switches prepare
    * to SkipExecute and is refused for a branch-only token on a main pool. The one carve-out is
    * `REPLACE` immediately followed (after optional whitespace) by `(`, which is DuckDB's string
    * function (and the `SELECT * REPLACE (...)` star modifier), not a write verb. `COPY (SELECT
    * ...) TO ...` still counts.
    */
  private def hasDmlKeyword(toks: Vector[StatementClassifier.WordToken]): Boolean =
    toks.exists(t => cfg.dml.contains(t.upper) && !(t.upper == "REPLACE" && t.beforeParen))

  /** The word tokens of `sql` (already comment-stripped and normalized) outside every literal and
    * quoted identifier, uppercased, each with its paren depth and whether the next non-blank
    * character is `(`. The ONE quote-aware reader of this class; a sibling scanner with its own
    * quote rules is how a literal that one reader honours and another does not hides a verb.
    *
    *   - `'...'` with `''` doubling;
    *   - `E'...'` / `e'...'` (the prefix being a whole word, i.e. not preceded by a word character)
    *     additionally with backslash escapes, so `E'\''` does not close early;
    *   - `"..."` identifiers with `""` doubling;
    *   - `$tag$...$tag$` (the tag empty or starting with a letter or `_`) only when the `$` starts
    *     a token: a word consumes `$` as an identifier character the way DuckDB's lexer does, so
    *     `a$q$` is one identifier, never an opener.
    *
    * An unterminated literal runs to the end of the statement (DuckDB rejects the statement).
    */
  private def wordTokens(sql: String): Vector[StatementClassifier.WordToken] =
    val s                   = sql.trim
    val n                   = s.length
    def isWordChar(c: Char) = c.isLetterOrDigit || c == '_'
    // from points at the opening quote; returns the index just past the closing one
    def skipQuoted(from: Int, q: Char, backslash: Boolean): Int =
      var p    = from + 1
      var done = false
      while p < n && !done do
        if backslash && s(p) == '\\' then p += 2
        else if s(p) == q then
          if p + 1 < n && s(p + 1) == q then p += 2
          else
            p += 1
            done = true
        else p += 1
      math.min(p, n)
    // from points at a token-initial '$'; a lone '$' (or `$1`) is stepped over
    def skipDollar(from: Int): Int =
      var t = from + 1
      if t < n && (s(t).isLetter || s(t) == '_') then while t < n && isWordChar(s(t)) do t += 1
      if t >= n || s(t) != '$' then from + 1
      else
        val tag   = s.substring(from, t + 1)
        val close = s.indexOf(tag, t + 1)
        if close < 0 then n else close + tag.length
    val out   = Vector.newBuilder[StatementClassifier.WordToken]
    var depth = 0
    var i     = 0
    while i < n do
      val c = s(i)
      if c == '\'' then i = skipQuoted(i, '\'', backslash = false)
      else if c == '"' then i = skipQuoted(i, '"', backslash = false)
      else if c == '$' then i = skipDollar(i)
      else if c == '(' then
        depth += 1
        i += 1
      else if c == ')' then
        depth -= 1
        i += 1
      else if isWordChar(c) then
        var w = i
        while w < n && (isWordChar(s(w)) || s(w) == '$') do w += 1
        if w - i == 1 && (c == 'e' || c == 'E') && w < n && s(w) == '\'' then
          i = skipQuoted(w, '\'', backslash = true)
        else
          var p = w
          while p < n && s(p).isWhitespace do p += 1
          out += StatementClassifier.WordToken(
            s.substring(i, w).toUpperCase(Locale.ROOT),
            depth,
            p < n && s(p) == '('
          )
          i = w
      else i += 1
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
