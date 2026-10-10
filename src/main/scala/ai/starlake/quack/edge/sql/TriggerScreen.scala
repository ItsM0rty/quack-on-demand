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

  private val TriggerDdl =
    ("^(?:explain\\s+analyze\\s+)?" +
      "(?:create\\s+(?:or\\s+replace\\s+)?(?:temp\\s+|temporary\\s+)?trigger" +
      "|drop\\s+trigger|alter\\s+trigger)\\b").r

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

  private def normalize(stmt: String): String =
    SqlTrivia
      .normalize(
        SqlCommentStripper.stripComments(SqlTrivia.stripLeading(stmt.toLowerCase(Locale.ROOT)))
      )
      .trim
