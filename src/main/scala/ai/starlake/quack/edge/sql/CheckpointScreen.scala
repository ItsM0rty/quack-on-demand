package ai.starlake.quack.edge.sql

/** CHECKPOINT in every form, and DuckLake's destructive maintenance and retention-option calls
  * ([[MaintenanceCalls]]), refused to every caller that is not a superuser, whatever the ACL mode
  * (QoD grants, OPA, ACL off) and the grants, and to every caller on a pool under lockdown: the
  * router runs it beside [[NodeSettingScreen.screenNodeWide]], which reserves FORCE CHECKPOINT the
  * same way.
  *
  * On a DuckLake catalog a checkpoint is not a storage flush: DuckDB 2.0's
  * `DuckLakeTransactionManager::Checkpoint` runs the whole maintenance chain, flush, expire,
  * `merge_adjacent_files`, rewrite, `cleanup_old_files` and `delete_orphaned_files`.
  * `merge_adjacent_files` hard-deletes the merged `ducklake_data_file` rows and schedules their
  * files for deletion, so a later checkpoint deletes files a live branch (whose catalog references
  * the parent's files by path) or time travel still reads. It is QoD's maintenance scheduler's job,
  * which pins every branch's fork snapshot and runs on the node directly, never through the edge.
  * The ACL cannot gate it table by table (it names a catalog, or nothing), and before this screen a
  * `*.*.* ALL` grant admitted it as an unparseable statement.
  *
  * Refused: the statement `CHECKPOINT [catalog]` and `FORCE CHECKPOINT [catalog]`, also as the
  * statement an EXPLAIN or a `PREPARE <name> AS` carries ([[StatementHead]]), and the table
  * functions `checkpoint(...)` / `force_checkpoint(...)` anywhere, any quoting, qualified or not,
  * literal bodies included (`CALL checkpoint('lake')`, `FROM checkpoint()`), read from DuckDB 2.0's
  * tokens ([[FunctionCalls]]). A statement nesting literals too deeply to scan is refused by
  * [[CatalogInternalsScreen]], which runs in the same arm.
  *
  * The same chain, a step at a time, is reachable without CHECKPOINT: the explicit `ducklake_*`
  * maintenance calls, and DuckLake's per-schema macros `merge_adjacent_files()` and
  * `set_option(...)` ([[CatalogReachingCalls.DuckLakeSchemaMacros]]), which run
  * `ducklake_merge_adjacent_files` / `ducklake_set_option` on the catalog they resolve in.
  * `set_option('delete_older_than' | 'expire_older_than', '1 second')` removes the retention wait,
  * so the next merge or cleanup deletes the files at once. With the ACL off they were all admitted
  * ([[CatalogReachingCalls]] gates them only when the ACL validates), and lockdown admits the
  * `ducklake_*` family by name. They are refused here like CHECKPOINT. The read-only DuckLake calls
  * (snapshots, table info, changes, options, settings) and `set_commit_message` stay with
  * [[CatalogReachingCalls]]: gated whenever the ACL validates, admitted with it off.
  */
object CheckpointScreen:

  /** The table functions that checkpoint a catalog (2.0 CLI: both are table functions). */
  val Calls: Set[String] = Set("checkpoint", "force_checkpoint")

  val Refusal: String =
    "CHECKPOINT runs the DuckLake maintenance chain (it can delete files that branches and " +
      "time travel still read) and is reserved to superusers"

  /** DuckLake's calls that merge, rewrite, flush, expire or delete data files and snapshots, or set
    * the options that govern when (`delete_older_than`, `expire_older_than`, ...), each also as the
    * per-schema macro DuckLake 2.0 registers for it (`ducklake_default_functions.cpp`:
    * `merge_adjacent_files`, `set_option`; the others have no bare form). Listed from
    * `duckdb_functions()` on the 2.0 CLI with ducklake loaded. QoD's own maintenance chain
    * (MaintenanceRunner) and branch merge (BranchService) send theirs straight to a node through
    * the adapter, never through the edge, so this refuses no QoD path.
    */
  val MaintenanceCalls: Set[String] = Set(
    "ducklake_merge_adjacent_files",
    "ducklake_rewrite_data_files",
    "ducklake_flush_inlined_data",
    "ducklake_expire_snapshots",
    "ducklake_cleanup_old_files",
    "ducklake_delete_orphaned_files",
    "ducklake_set_option",
    "merge_adjacent_files",
    "set_option"
  )

  def maintenanceRefusal(fn: String): String =
    s"$fn runs DuckLake maintenance or changes its retention options (it can delete files that " +
      "branches and time travel still read) and is reserved to superusers"

  /** Some(refusal) when a statement of `sql` checkpoints a catalog or calls one of
    * [[MaintenanceCalls]].
    */
  def find(sql: String): Option[String] =
    val statement = LockdownScreen.splitStatements(sql).exists { stmt =>
      val h = StatementHead.of(stmt)
      h.word(h.start).contains("checkpoint") ||
      (h.word(h.start).contains("force") && h.word(h.start + 1).contains("checkpoint"))
    }
    val calls = FunctionCalls.scan(sql).calls
    if statement || calls.exists(c => Calls(c.name)) then Some(Refusal)
    else calls.find(c => MaintenanceCalls(c.name)).map(c => maintenanceRefusal(c.name))
