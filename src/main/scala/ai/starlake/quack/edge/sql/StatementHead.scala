package ai.starlake.quack.edge.sql

import ai.starlake.sql.{SqlCommentStripper, SqlLexer, SqlTrivia}

import java.util.Locale

/** The lexing primitives the statement screens share: the screening text of one statement, and
  * where the statement an EXPLAIN carries begins. Every input is ONE statement, as
  * [[LockdownScreen.splitStatements]] cuts it.
  */
private[sql] object StatementHead:

  /** A statement behind EXPLAIN, at ANY distance: EXPLAIN takes ANALYZE / ANALYSE and an option
    * list whose options carry arbitrary expressions. For a screen that refuses a verb wherever it
    * follows EXPLAIN ([[TriggerScreen]]), accepting the over-deny of a literal spelling it.
    */
  val ExplainPrefix = "(?s)^(?:explain\\b.*\\b)?"

  /** The screening text of `stmt`: leading trivia deleted, interior comments replaced by a space,
    * remaining trivia normalized, case folded LAST. The ORIGINAL statement is lexed first because
    * DuckDB compares dollar-quote tags case-sensitively (`$A$` is not closed by `$a$`): folding
    * first let the lexer end a literal where DuckDB does not and read the text behind it as a
    * comment.
    */
  def normalize(stmt: String): String =
    SqlTrivia
      .normalize(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(stmt)))
      .toLowerCase(Locale.ROOT)
      .trim

  /** The non-trivia tokens of `stmt` read from DuckDB 2.0's own tokenizer, over `view` (the
    * statement's engine view, which the token offsets index), and the index of the first token of
    * the statement a wrapper carries: past a leading run of EXPLAIN / ANALYZE / ANALYSE keywords
    * and parenthesized option lists (balanced over paren TOKENS, so a paren inside a literal or a
    * comment never counts), and then past a `PREPARE <name> [(<types>)] AS` head. 0 when the
    * statement wraps nothing; `toks.length` when nothing follows. `EXPLAIN ANALYZE <stmt>`,
    * `EXPLAIN (ANALYZE) <stmt>` and `EXPLAIN ANALYZE (FORMAT json) <stmt>` all EXECUTE the
    * statement on 2.0 (SET, PRAGMA, RESET, COPY and LOAD verified), and `PREPARE p AS <stmt>` +
    * `EXECUTE p` executes `<stmt>` (only SELECT / INSERT / UPDATE / DELETE / COPY are preparable on
    * 2.0, so `PREPARE p AS COPY (...) TO '<local>'` is the write carrier the lockdown first-token
    * rules must see through). The EXPLAIN run is wider than the grammar (any order, repeated) so an
    * unusual prefix only ever moves the head further in.
    */
  final case class Head(view: String, toks: Vector[SqlLexer.Token], start: Int):

    /** The lowercased text of the word token at `k`, None for any other token or past the end. */
    def word(k: Int): Option[String] =
      if k < toks.length && toks(k).kind == SqlLexer.Kind.Word then
        Some(view.substring(toks(k).start, toks(k).end).toLowerCase(Locale.ROOT))
      else None

    /** The unquoted, lowercased identifier at `k` (bare, double-quoted or backtick-quoted). */
    def identifier(k: Int): Option[String] =
      if k < toks.length then FunctionCalls.identifier(view, toks(k)) else None

    /** The engine view of the carried statement, from its first token (empty when none). */
    def text: String = if start < toks.length then view.substring(toks(start).start) else ""

  def of(stmt: String): Head =
    val view         = SqlLexer.engineView(Option(stmt).getOrElse(""))
    val toks         = SqlLexer.tokens(view).filterNot(_.isTrivia)
    val h            = Head(view, toks, 0)
    val afterExplain =
      if !h.word(0).contains("explain") then 0
      else
        var i = 0
        while i < toks.length && (h.word(i).exists(ExplainWords) || isPunct(view, toks(i), '(')) do
          if isPunct(view, toks(i), '(') then i = closingParen(view, toks, i) + 1
          else i += 1
        i
    Head(view, toks, math.min(skipPrepareHead(h, afterExplain), toks.length))

  /** Past a `PREPARE <name> [(<types>)] AS` head at `from`, to the first token of the prepared
    * statement; `from` unchanged when there is no such head or no `AS` is found.
    */
  private def skipPrepareHead(h: Head, from: Int): Int =
    if !h.word(from).contains("prepare") || h.identifier(from + 1).isEmpty then from
    else
      var i = from + 2 // past PREPARE and the statement name
      if i < h.toks.length && isPunct(h.view, h.toks(i), '(') then
        i = closingParen(h.view, h.toks, i) + 1
      if h.word(i).contains("as") then i + 1 else from

  private val ExplainWords = Set("explain", "analyze", "analyse")

  private def isPunct(view: String, t: SqlLexer.Token, c: Char): Boolean =
    t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == c

  /** Index of the paren token closing the one at `open`, or the last index when unbalanced. */
  private def closingParen(view: String, toks: Vector[SqlLexer.Token], open: Int): Int =
    var depth = 0
    var i     = open
    var close = -1
    while i < toks.length && close < 0 do
      if isPunct(view, toks(i), '(') then depth += 1
      else if isPunct(view, toks(i), ')') then
        depth -= 1
        if depth == 0 then close = i
      i += 1
    if close < 0 then toks.length - 1 else close
