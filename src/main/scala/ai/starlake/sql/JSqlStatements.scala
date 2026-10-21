package ai.starlake.sql

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.{CCJSqlParser, CCJSqlParserUtil}
import net.sf.jsqlparser.statement.{Statement, Statements, UnsupportedStatement}

/** JSqlParser reads of a submission, split into its statements the way DuckDB splits them.
  *
  * Up to jsqlparser 5.4.15, `CCJSqlParserUtil.parse` returned the first statement of a batch and
  * ignored the rest, and `parseStatements` with `allowUnsupportedStatements` turned a statement it
  * could not parse into an `UnsupportedStatement` and went on with the next one. From 5.4.16x the
  * first throws on any batch, and the second fails the whole batch when one of its statements
  * breaks off in the middle (`USE mem.main`), so a gate reading either would lose the statements
  * around it: `USE mem.main; SHOW TABLES` reached the metadata filter as unparseable text.
  *
  * Both readings here split first, at every `;` DuckDB's lexer treats as a separator
  * ([[SqlCommentStripper.statementSpans]]), and parse each statement on its own, so the parser
  * never decides where a statement ends.
  */
object JSqlStatements:

  /** The text of the first statement of `sql` (all of `sql` when it holds none), for an API that
    * parses its input itself, as `JSQLColumResolver.getResultSetMetaData` does.
    */
  def firstText(sql: String): String =
    SqlCommentStripper.statementSpans(sql) match
      case (from, until) :: _ => sql.substring(from, until)
      case Nil                => sql

  /** The first statement of `sql`, parsed alone: what `CCJSqlParserUtil.parse` returned for a batch
    * up to jsqlparser 5.4.15. Throws as `parse` does when that statement does not parse.
    */
  def parseFirst(sql: String): Statement =
    CCJSqlParserUtil.parse(firstText(sql))

  /** Every statement of `sql`, each parsed on its own with `configure`, in order. A statement that
    * does not parse becomes an `UnsupportedStatement` holding its text, which is what
    * `allowUnsupportedStatements` produced for it up to jsqlparser 5.4.15, so a gate sees one node
    * per statement either way.
    */
  def parseBatch(sql: String, configure: java.util.function.Consumer[CCJSqlParser]): Statements =
    val out = new Statements()
    SqlCommentStripper.statementSpans(sql).foreach { (from, until) =>
      val text = sql.substring(from, until)
      try CCJSqlParserUtil.parseStatements(text, configure).forEach(out.add(_): Unit)
      catch
        case _: JSQLParserException =>
          out.add(new UnsupportedStatement(java.util.List.of(text.trim))): Unit
    }
    out
