package ai.starlake.quack.edge.sql

import java.util.Locale
import ai.starlake.acl.parser.{TableAccess, Verb}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RolePermission
import com.typesafe.scalalogging.LazyLogging

/** Per-statement ACL gate backed by the cached [[ai.starlake.quack.ondemand.rbac.EffectiveSet]]
  * pinned on [[ai.starlake.quack.edge.ConnectionContext]] at handshake time. Reads a tenant-scoped
  * principal's table permissions in-memory; superusers (`effectiveSet.user.tenant.isEmpty`) bypass.
  *
  * Decision rule per statement:
  *   1. No `effectiveSet` on the validation context -> deny (the handshake never bound an RBAC
  *      principal, so we err on the safe side).
  *   2. Superuser principal -> allow.
  *   3. Parse the statement with [[SqlParser.extract]] to enumerate every `(table, verb)` tuple.
  *      Parse errors short-circuit to Denied unless the principal holds an unrestricted ALL grant.
  *   4. `ControlFlow` statements (COMMIT, ROLLBACK, SET, ...) carry no table refs and are admitted
  *      unconditionally.
  *   5. For each `TableAccess(table, verb)` check that at least one [[RolePermission]] in the
  *      effective set covers it: `verbCovers(p.verb, verb)` AND wildcard-or-literal match on
  *      catalog / schema / table.
  *
  * Catalog/schema defaults: when a referenced table is unqualified, the SQL parser fills them in
  * from the pool's metastore via [[ValidationContext.defaultDatabase]] / `defaultSchema`.
  */
/** `tenantCatalogs(tenantId)` returns the catalog names (= tenant-db names) the given tenant owns.
  * Used to scope wildcard catalog grants (`*.*.* ALL`) so a tenant admin cannot wildcard-match a
  * sibling tenant's catalog. An empty set or an unknown tenant id collapses to "no catalogs
  * admissible via wildcard for this session"; explicit (non-wildcard) catalog grants are honored
  * regardless of the session's tenant. Default is a no-op lookup that returns `Set.empty`, which
  * makes wildcard catalog matches fail-closed: callers that don't wire a real lookup get the safer
  * behavior.
  *
  * `filteredMetadata` mirrors `quack-flightsql.acl.filteredMetadata` (QOD_ACL_FILTERED_METADATA):
  * when on, a PURE-READ statement's Read accesses on the SESSION catalog's filterable
  * `information_schema` tables, and its unqualified no-argument calls of DuckDB's catalog functions
  * (`duckdb_tables()` and the others in
  * [[ai.starlake.quack.edge.meta.MetadataFilterRewriter.FilterableFunctions]]), are admitted
  * without a grant, because the edge [[ai.starlake.quack.edge.meta.MetadataFilterRewriter]]
  * (mounted from the same flag) narrows those rows to the principal's granted objects. Defaults to
  * `false` so a caller that constructs the validator without the rewriter keeps the grant-required
  * posture.
  */
final class PostgresAclValidator(
    defaultDatabase: String = "",
    defaultSchema: String = "main",
    dialect: String = "duckdb",
    tenantCatalogs: String => Set[String] = _ => Set.empty,
    filteredMetadata: Boolean = false
) extends StatementValidator,
      LazyLogging:

  override def validate(context: ValidationContext): ValidationResult =
    context.effectiveSet match
      case None =>
        // Defensive: no handshake state -> deny. In practice the FlightSQL
        // handshake always pins an EffectiveSet (even an empty one for
        // superusers), so reaching here means the wiring is broken.
        val msg = "no RBAC principal bound to session; deny"
        logger.warn(s"ACL DENIED: user=${context.username}: $msg")
        Denied(msg)

      case Some(eff) if eff.user.tenant.isEmpty =>
        // Superuser bypass. Logged at debug only so admin smoke tests
        // don't spam the info channel.
        logger.debug(s"ACL ALLOWED (superuser): user=${context.username}")
        Allowed

      case Some(eff) =>
        val config = AccessGate.parserConfig(context, defaultDatabase, defaultSchema, dialect)
        val sessionCatalog = context.defaultDatabase.getOrElse(defaultDatabase)
        AccessGate.evaluate(
          context.statement,
          config,
          sessionCatalog,
          filteredMetadata,
          context.clientDataScans
        ) match
          case GateOutcome.Refuse(msg, true, covered) =>
            denyUnlessWildcardAll(eff, context.username, covered, msg)
          case GateOutcome.Refuse(msg, false, _) =>
            logger.warn(s"ACL DENIED: user=${context.username}: $msg")
            Denied(msg)
          case GateOutcome.NothingGated(accesses, catalogFunctions) =>
            logger.info(
              s"ACL ALLOWED: user=${context.username} no gated table refs" +
                (if accesses.isEmpty then ""
                 else
                   s" accesses=${accesses.map(a => s"${a.table.canonical}:${a.verb}").mkString(",")}") +
                (if catalogFunctions.isEmpty then ""
                 else s" filtered=${catalogFunctions.mkString(",")}")
            )
            Allowed
          case GateOutcome.Gated(gated, accesses) =>
            matchGrants(context, eff, gated, accesses)

  /** Deny `msg` unless the principal holds an unrestricted `*.*.* ALL` grant, which covers
    * statements the parser could not fully resolve (unparseable / unsupported constructs /
    * qualification errors). `covered` names what the wildcard admitted in the allow log line.
    */
  private def denyUnlessWildcardAll(
      eff: EffectiveSet,
      username: String,
      covered: String,
      msg: String
  ): ValidationResult =
    if hasWildcardAll(eff) then
      logger.info(s"ACL: wildcard ALL covers $covered for user=$username")
      Allowed
    else
      logger.warn(s"ACL DENIED: user=$username: $msg")
      Denied(msg)

  /** Match every gated access against the principal's role permissions. `accesses` is the full set,
    * named in the allow log line.
    */
  private def matchGrants(
      context: ValidationContext,
      eff: EffectiveSet,
      gated: Set[TableAccess],
      accesses: Set[TableAccess]
  ): ValidationResult =
    // Pre-compute the catalogs admissible via wildcard for this session.
    // Used below to scope `*` catalog matches to the user's tenant; an
    // explicit (non-wildcard) catalog grant still bypasses this check, so
    // operators can deliberately grant cross-tenant access by naming a
    // sibling tenant's catalog.
    val sessionCatalogs: Set[String] =
      eff.user.tenant.map(tenantCatalogs).getOrElse(Set.empty)

    val unauthorized = gated.filterNot { ta =>
      eff.permissions.exists(p =>
        verbCovers(p.verb, ta.verb) &&
          catalogMatch(p.catalogName, ta.table.database, sessionCatalogs) &&
          wildcardMatch(p.schemaName, ta.table.schema) &&
          wildcardMatch(p.tableName, ta.table.table)
      )
    }

    if unauthorized.isEmpty then
      logger.info(
        s"ACL ALLOWED: user=${context.username} " +
          s"roles=${eff.roles.map(_.name).mkString(",")} " +
          s"accesses=${accesses.map(a => s"${a.table.canonical}:${a.verb}").mkString(",")}"
      )
      Allowed
    else
      // Superusers never reach this method (validate() short-circuits
      // them), so the principal is always the tenant-scoped user.
      val msg =
        s"user:${context.username} lacks grants on ${unauthorized
            .map(a => s"${a.table.canonical}:${a.verb}")
            .mkString(", ")}"
      logger.warn(s"ACL DENIED: $msg")
      Denied(msg, unauthorized)

  /** Whether a role-permission verb covers a parser-emitted access verb. Grant verbs are the
    * canonical (RO / RW / DDL / ALL) set; the parser emits the collapsed
    * `Verb.Read | Verb.Write | Verb.Ddl` per access. `RW` is the only multi-cover grant (Read +
    * Write); DDL stays separate because CREATE/DROP/ALTER are deliberately higher-privilege.
    */
  private def verbCovers(grantVerb: String, access: Verb): Boolean =
    val gu = grantVerb.toUpperCase(Locale.ROOT)
    if gu == "ALL" then true
    else
      access match
        case Verb.Read  => gu == "RO" || gu == "RW"
        case Verb.Write => gu == "RW"
        case Verb.Ddl   => gu == "DDL"

  private def wildcardMatch(grant: String, ref: String): Boolean =
    grant == RolePermission.Wildcard || grant.equalsIgnoreCase(ref)

  /** Catalog-specific wildcard match: the literal-equal arm behaves like `wildcardMatch`, but the
    * `*` arm additionally requires the referenced catalog to be in the session's allowed-catalog
    * set. This closes the cross-tenant wildcard leak (a tenant admin with `*.*.* ALL` could
    * otherwise SELECT from a sibling tenant's catalog -- see project memory
    * `project-catalog-wildcard-cross-tenant`).
    *
    * An empty `sessionCatalogs` means no catalog matches via wildcard for this session. Operators
    * who want a tenant admin to retain cross-tenant read access can still grant an explicit catalog
    * (`otherdb.*.* ALL`), which bypasses the wildcard arm via the literal-equal check below.
    */
  private def catalogMatch(
      grant: String,
      ref: String,
      sessionCatalogs: Set[String]
  ): Boolean =
    if grant == RolePermission.Wildcard then sessionCatalogs.exists(_.equalsIgnoreCase(ref))
    else grant.equalsIgnoreCase(ref)

  private def hasWildcardAll(eff: EffectiveSet): Boolean =
    eff.permissions.exists(p =>
      p.verb.equalsIgnoreCase("ALL") &&
        p.catalogName == RolePermission.Wildcard &&
        p.schemaName == RolePermission.Wildcard &&
        p.tableName == RolePermission.Wildcard
    )
