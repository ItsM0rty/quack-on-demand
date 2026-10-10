package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.sql.{SqlCommentStripper, SqlTrivia}

import java.util.Locale

/** Trigger DDL is reserved to superusers and to admins of the pool's own tenant.
  *
  * The ACL checks the statement a user sends, never what a trigger runs later: a trigger body runs
  * with no QoD identity on every matching write by anyone and is not rewritten by RLS/CLS, so a DDL
  * grant on one table would otherwise become a read and write path into every table of the
  * database. A tenant admin gains nothing new (they can grant themselves any table of their
  * tenant). Token-based like [[LockdownScreen]], so it never depends on JSqlParser parsing the
  * trigger, and it applies whatever the ACL mode and the lockdown flag.
  */
object TriggerScreen:

  val RefusalMessage = "CREATE TRIGGER is restricted to tenant administrators"

  /** The statement shapes that create, replace, drop or alter a trigger. `PERSISTENT` is DuckDB
    * 2.0's third spelling of the CREATE persistence slot (`Temporary <- PERSISTENT / TEMP /
    * TEMPORARY` in its grammar), next to the two temporary ones.
    */
  private val TriggerVerb =
    "(?:create\\s+(?:or\\s+replace\\s+)?(?:(?:temp|temporary|persistent)\\s+)?trigger" +
      "|drop\\s+trigger|alter\\s+trigger)\\b"

  /** Trigger DDL as the statement itself, or anywhere behind EXPLAIN. `EXPLAIN` takes an optional
    * ANALYZE / ANALYSE keyword and an optional parenthesized option list whose options carry
    * ARBITRARY expressions, so no fixed prefix can name everything that may sit between EXPLAIN and
    * the explained statement. Fail closed: the verb anywhere after EXPLAIN refuses, which also
    * refuses an EXPLAIN whose string literal spells a trigger verb, a shape nobody needs.
    */
  private val TriggerDdl = ("(?s)^(?:explain\\b.*\\b)?" + TriggerVerb).r

  /** `Some(RefusalMessage)` when any statement of `sql` is trigger DDL. */
  def screen(sql: String): Option[String] =
    LockdownScreen
      .splitStatements(sql)
      .iterator
      .map(normalize)
      .find(s => TriggerDdl.findFirstIn(s).isDefined)
      .map(_ => RefusalMessage)

  /** Superuser, or `kind == "admin"` in the pool's own tenant (same test as
    * `AdminStatementExecutor`). A missing effective set is a regular user: fail closed.
    */
  def mayManageTriggers(effectiveSet: Option[EffectiveSet], poolTenantId: Option[String]): Boolean =
    effectiveSet.exists { e =>
      e.user.tenant.isEmpty ||
      (e.user.kind == "admin" && poolTenantId.exists(id => e.user.tenant.contains(id)))
    }

  /** Lexes the ORIGINAL statement and folds case only afterwards. DuckDB compares dollar-quote tags
    * case-sensitively (`$A$` is not closed by `$a$`), so lowercasing first let the lexer end a
    * literal where DuckDB does not and read the trigger DDL behind it as a comment.
    */
  private def normalize(stmt: String): String =
    SqlTrivia
      .normalize(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(stmt)))
      .toLowerCase(Locale.ROOT)
      .trim
