package ai.starlake.quack.edge.sql

import ai.starlake.quack.BuildVersions
import ai.starlake.sql.SqlLexer

/** Refuses a statement holding a backtick outside every string literal, quoted identifier and
  * comment, while the pinned engine is DuckDB 1.x.
  *
  * `SqlLexer` follows DuckDB 2.0, where a backtick pair quotes an identifier. On DuckDB 1.5 a
  * backtick is a binary OPERATOR, resolved as a scalar function or macro named with a backtick, so
  * the text between two backticks is ordinary SQL there: once such a macro exists, `SELECT 1 ` +
  * backtick + ` 2; SET search_path = 'x'; SELECT 3 ` + backtick + ` 4` runs three statements and a
  * call between the pair runs too (both checked on the 1.5.6 CLI), while the lexer reads one quoted
  * identifier and every screen built on it (statement split, SessionScopeScreen,
  * CatalogReachingCalls, LockdownScreen, CatalogWriteScreen, and StatementClassifier, whose kind
  * feeds OPA's statement class and the protected-write guard) sees neither the separators nor the
  * call. Refusing the backtick closes that for the callers the screens protect; identifiers take
  * double quotes. It runs first in AccessGate (QoD and OPA validators), LockdownScreen,
  * ProtectedWriteGuard and CatalogWriteScreen, so the classifier's reading of a backtick statement
  * never decides anything on those paths.
  *
  * Up to the first backtick token the 1.5 and 2.0 lexers agree, so a statement this screen admits
  * holds no backtick token on either engine. Inert once the pin is DuckDB 2 or later, where the
  * lexer and the engine agree: [[active]] follows `Versions.duckdb`, so the 2.0 bump needs no edit.
  */
object BacktickScreen:

  /** True when an engine of this version reads a backtick as an operator (any major below 2). A
    * version whose major cannot be read keeps the screen on.
    */
  def activeFor(duckdbVersion: String): Boolean =
    duckdbVersion.trim.takeWhile(_.isDigit).toIntOption.forall(_ < 2)

  /** Whether the screen applies to this build's pinned engine. Fixed at object init from a
    * compile-time constant, so specs cannot flip it: refusal specs `assume(active)` and cancel on a
    * 2.x pin.
    */
  val active: Boolean = activeFor(BuildVersions.duckdb)

  /** Some(reason) when `sql` must be refused on this build's engine. */
  def find(sql: String): Option[String] = findFor(sql, active)

  private[sql] def findFor(sql: String, active: Boolean): Option[String] =
    if !active || sql == null || sql.indexOf('`') < 0 then None
    else if SqlLexer
        .tokens(sql)
        .exists(t => t.kind == SqlLexer.Kind.Literal && sql.charAt(t.start) == '`')
    then Some(Reason)
    else None

  val Reason: String =
    s"a backtick is not supported on this DuckDB version (${BuildVersions.duckdb}): it is an " +
      "operator there, not an identifier quote; quote identifiers with double quotes"
