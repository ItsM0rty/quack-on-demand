package ai.starlake.quack.edge.sql

import ai.starlake.sql.SqlLexer

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.collection.mutable

/** Function-call sites read from DuckDB 2.0's own tokens ([[ai.starlake.sql.SqlLexer]]), the one
  * reader shared by every screen that asks "does this statement call f?".
  *
  * A call is an identifier token immediately followed, past any trivia (whitespace, line and nested
  * block comments), by an opening parenthesis token. The identifier is bare, double-quoted or
  * backtick-quoted (DuckDB 2.0 accepts all three for a function name, and resolves each case
  * insensitively: `` `READ_TEXT`('/etc/hosts') `` reads the file on the 2.0 CLI), and may be the
  * last part of a qualified name (`` `system`.`main`.`read_text` ``), whose qualifiers do not
  * change which function runs. Its name is unquoted (the delimiter undoubled) and folded to lower
  * case AFTER lexing: DuckDB compares dollar-quote tags case-sensitively, so folding first could
  * end a literal where DuckDB does not.
  *
  * A regex over the text cannot do this: each quoting form it does not spell out is a bypass, and
  * the backtick form arrived with 2.0. Tokens also stop a name inside a comment or a quoted
  * identifier from reading as a call, and a call from hiding behind a comment before its paren.
  *
  * String literal BODIES are scanned too, as SQL in their own right, because `query('...')` runs
  * its argument: `query('SELECT * FROM `read_text`(''/etc/hosts'')')` reads the file. Each body is
  * decoded the way DuckDB decodes it (`''` undoubled; an escape string's backslash escapes,
  * including `\x72` and octal bytes; a dollar-quoted body verbatim) from the characters DuckDB's
  * unicode-space pre-pass kept, never the engine view's stand-ins, and lexed afresh: the fresh lex
  * pre-passes the body again, as DuckDB's parser does when `query()` runs it, so
  * `query('SELECT * FROM<NBSP>read_text(...)')` is a call. This is the over-deny the previous regex
  * had by accident (it never blanked literals), kept on purpose; it is NOT a proof that `query()`
  * is safe, since a string computed at run time (`'read' || '_text(...)'`) carries a call no
  * lexical scan sees. Nesting is followed [[MaxDepth]] levels deep, which bounds the work at
  * `MaxDepth` passes over the input; past it the scan reports `truncated`.
  */
private[quack] object FunctionCalls:

  /** One call: `name` unquoted and lowercased; `text` the text it was found in (the engine view of
    * the statement, or of a decoded literal body); `argsFrom` the index in `text` just past `(`.
    */
  final case class Call(name: String, text: String, argsFrom: Int)

  /** Every call found, top level first; `truncated` when a string literal nested deeper than
    * [[MaxDepth]] was left unscanned.
    */
  final case class Scan(calls: Vector[Call], truncated: Boolean)

  val MaxDepth = 8

  def scan(sql: String): Scan =
    val calls     = Vector.newBuilder[Call]
    val pending   = mutable.Queue((Option(sql).getOrElse(""), 0))
    var truncated = false
    while pending.nonEmpty do
      val (text, depth) = pending.dequeue()
      val view          = SqlLexer.engineView(text)
      // literal values are read from the characters DuckDB's pre-pass kept, not the stand-ins
      val values = SqlLexer.keptSpacesRestored(view)
      val toks   = SqlLexer.tokens(view).filterNot(_.isTrivia)
      var i      = 0
      while i < toks.length do
        val t = toks(i)
        if i + 1 < toks.length && isOpenParen(view, toks(i + 1)) then
          identifier(view, t).foreach(name => calls += Call(name, view, toks(i + 1).end))
        stringBody(values, t).foreach { body =>
          if depth < MaxDepth then pending.enqueue((body, depth + 1))
          else truncated = true
        }
        i += 1
    Scan(calls.result(), truncated)

  /** The unquoted, lowercased name of an identifier token (bare word, double-quoted or
    * backtick-quoted), or None for any other token.
    */
  def identifier(view: String, t: SqlLexer.Token): Option[String] =
    quotedIdentifier(view, t).orElse(
      if t.kind == SqlLexer.Kind.Word then
        Some(view.substring(t.start, t.end).toLowerCase(Locale.ROOT))
      else None
    )

  /** The unquoted, lowercased body of a double-quoted or backtick-quoted identifier token. */
  def quotedIdentifier(view: String, t: SqlLexer.Token): Option[String] =
    val d = view.charAt(t.start)
    if t.kind == SqlLexer.Kind.Literal && (d == '"' || d == '`') then
      Some(undoubled(view, t.start + 1, t.end, d).toLowerCase(Locale.ROOT))
    else None

  private def isOpenParen(view: String, t: SqlLexer.Token): Boolean =
    t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == '('

  /** The decoded body of a string literal token: `'...'` and the `n` / `x` / `b` prefixed forms
    * with `''` undoubled, an escape string `e'...'` decoded, a dollar-quoted body verbatim. None
    * for a quoted identifier or any non-literal token.
    *
    * `values` is the token's text with the pre-pass's kept unicode spaces restored
    * ([[ai.starlake.sql.SqlLexer.keptSpacesRestored]]), position for position the lexed view: a
    * body is the literal's VALUE, which holds each kept space as itself, so that the body's own
    * lex, which pre-passes it afresh as DuckDB does when `query()` runs it, turns the ones outside
    * the body's literals into spaces. Every literal form, the escape string included, reads its
    * body from it.
    */
  private def stringBody(values: String, t: SqlLexer.Token): Option[String] =
    if t.kind != SqlLexer.Kind.Literal then None
    else
      val c = values.charAt(t.start)
      if c == '\'' then Some(undoubled(values, t.start + 1, t.end, '\''))
      else if c == '$' then
        val tagEnd = values.indexOf('$', t.start + 1) + 1
        Some(values.substring(tagEnd, math.max(tagEnd, t.end - (tagEnd - t.start))))
      else if c == 'e' || c == 'E' then Some(escapeDecoded(values, t.start + 2, t.end))
      else if c == '"' || c == '`' then None
      else Some(undoubled(values, t.start + 2, t.end, '\''))

  /** `s[from, end)` up to the closing delimiter `d`, a doubled `d` standing for one. */
  private def undoubled(s: String, from: Int, end: Int, d: Char): String =
    val out    = new StringBuilder
    var i      = from
    var closed = false
    while i < end && !closed do
      val c = s.charAt(i)
      if c == d then
        if i + 1 < end && s.charAt(i + 1) == d then
          out.append(d)
          i += 2
        else closed = true
      else
        out.append(c)
        i += 1
    out.toString

  /** An escape string's body as DuckDB 2.0 decodes it (each rule checked on the 2.0 CLI): `''` is a
    * quote; `\b \f \n \r \t` the usual controls; `\x` with one or two hex digits a byte (with none,
    * an `x`); one to three octal digits a byte; a backslash before anything else that character.
    * The bytes are UTF-8.
    */
  private def escapeDecoded(s: String, from: Int, end: Int): String =
    val bytes         = new ByteArrayOutputStream
    val run           = new StringBuilder
    def flush(): Unit =
      val b = run.toString.getBytes(StandardCharsets.UTF_8)
      bytes.write(b, 0, b.length)
      run.clear()
    def byte(v: Int): Unit =
      flush()
      bytes.write(v & 0xff)
    def isHex(c: Char)   = Character.digit(c, 16) >= 0
    def isOctal(c: Char) = c >= '0' && c <= '7'
    var i                = from
    var closed           = false
    while i < end && !closed do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < end then
        val e = s.charAt(i + 1)
        i += 2
        e match
          case 'b'                                  => byte(8)
          case 'f'                                  => byte(12)
          case 'n'                                  => byte(10)
          case 'r'                                  => byte(13)
          case 't'                                  => byte(9)
          case 'x' if i < end && isHex(s.charAt(i)) =>
            var v = Character.digit(s.charAt(i), 16)
            i += 1
            if i < end && isHex(s.charAt(i)) then
              v = v * 16 + Character.digit(s.charAt(i), 16)
              i += 1
            byte(v)
          case o if isOctal(o) =>
            var v = o - '0'
            var k = 0
            while k < 2 && i < end && isOctal(s.charAt(i)) do
              v = v * 8 + (s.charAt(i) - '0')
              i += 1
              k += 1
            byte(v)
          case other => run.append(other)
      else if c == '\'' then
        if i + 1 < end && s.charAt(i + 1) == '\'' then
          run.append('\'')
          i += 2
        else closed = true
      else
        run.append(c)
        i += 1
    flush()
    new String(bytes.toByteArray, StandardCharsets.UTF_8)
