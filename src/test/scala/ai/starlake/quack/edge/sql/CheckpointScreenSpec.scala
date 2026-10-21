package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** F09: on DuckLake a CHECKPOINT runs the whole maintenance chain (merge_adjacent_files
  * hard-deletes data-file rows, cleanup deletes the files a branch still reads), so every form is
  * refused.
  */
class CheckpointScreenSpec extends AnyFlatSpec with Matchers:

  "find" should "refuse every CHECKPOINT form" in
    Seq(
      "CHECKPOINT",
      "checkpoint;",
      "CHECKPOINT lake",
      "CHECKPOINT \"lake\"",
      "FORCE CHECKPOINT",
      "force checkpoint lake",
      "/* c */ CHECKPOINT",
      "SELECT 1; CHECKPOINT",
      "CHECKPOINT; SELECT 1",
      "EXPLAIN CHECKPOINT",
      "EXPLAIN ANALYZE CHECKPOINT lake",
      "EXPLAIN (ANALYZE) CHECKPOINT",
      "PREPARE p AS CHECKPOINT",
      "CALL checkpoint()",
      "CALL checkpoint('lake')",
      "CALL \"CHECKPOINT\"('lake')",
      "CALL `checkpoint`('lake')",
      "CALL system.main.checkpoint('lake')",
      "CALL force_checkpoint('lake')",
      "SELECT * FROM checkpoint('lake')",
      "FROM checkpoint()",
      "SELECT * FROM t, checkpoint('lake')",
      "SELECT * FROM query('CALL checkpoint()')",
      "SELECT 1 FROM t WHERE EXISTS (SELECT * FROM checkpoint())"
    ).foreach(sql =>
      withClue(sql)(CheckpointScreen.find(sql) shouldBe Some(CheckpointScreen.Refusal))
    )

  it should "leave other statements and look-alike names alone" in
    Seq(
      "SELECT 1",
      "SELECT checkpoint FROM t",
      "SELECT \"checkpoint\" FROM t",
      "SELECT 'CHECKPOINT' AS s",
      "SELECT 1 -- CHECKPOINT",
      "CREATE TABLE checkpoints (x INT)",
      "INSERT INTO checkpoint VALUES (1)",
      "SET checkpoint_threshold = '1GB'",
      "SELECT * FROM checkpoints"
    ).foreach(sql => withClue(sql)(CheckpointScreen.find(sql) shouldBe None))

  it should "refuse DuckLake's destructive maintenance and option-setting calls in any spelling" in
    Seq(
      "FROM merge_adjacent_files()"                                     -> "merge_adjacent_files",
      "CALL lake.merge_adjacent_files()"                                -> "merge_adjacent_files",
      "FROM lake.set_option('delete_older_than', '1 second')"           -> "set_option",
      "FROM \"lake\".`main`.\"SET_OPTION\"('expire_older_than', '1 s')" -> "set_option",
      "CALL ducklake_merge_adjacent_files('lake')" -> "ducklake_merge_adjacent_files",
      "CALL ducklake_cleanup_old_files('lake', cleanup_all => true)" -> "ducklake_cleanup_old_files",
      "CALL ducklake_expire_snapshots('lake', versions => [1])" -> "ducklake_expire_snapshots",
      "CALL ducklake_delete_orphaned_files('lake')"             -> "ducklake_delete_orphaned_files",
      "CALL ducklake_rewrite_data_files('lake', 't')"           -> "ducklake_rewrite_data_files",
      "CALL ducklake_flush_inlined_data('lake')"                -> "ducklake_flush_inlined_data",
      "SELECT * FROM ducklake_set_option('lake', 'k', 'v')"     -> "ducklake_set_option",
      "SELECT * FROM system.main.ducklake_merge_adjacent_files('lake')" -> "ducklake_merge_adjacent_files",
      "PREPARE p AS SELECT * FROM ducklake_flush_inlined_data('lake')" -> "ducklake_flush_inlined_data",
      "SELECT * FROM query('CALL ducklake_cleanup_old_files(''lake'')')" -> "ducklake_cleanup_old_files"
    ).foreach { (sql, fn) =>
      withClue(sql)(
        CheckpointScreen.find(sql) shouldBe Some(CheckpointScreen.maintenanceRefusal(fn))
      )
    }

  it should "leave the read-only DuckLake calls and set_commit_message to the ACL" in
    Seq(
      "SELECT * FROM snapshots()",
      "SELECT * FROM ducklake_snapshots('lake')",
      "FROM lake.table_info()",
      "FROM ducklake_table_info('lake')",
      "FROM table_changes('t', 0, 3)",
      "FROM ducklake_table_insertions('lake', 'main', 't', 0, 3)",
      "FROM table_deletions('t', 0, 3)",
      "FROM current_snapshot()",
      "FROM last_committed_snapshot()",
      "FROM lake.options()",
      "FROM ducklake_settings('lake')",
      "CALL set_commit_message('a', 'b')",
      "CALL ducklake_set_commit_message('lake', 'a', 'b')",
      "SELECT set_option FROM t",
      "SELECT * FROM merge_adjacent_files_log"
    ).foreach(sql => withClue(sql)(CheckpointScreen.find(sql) shouldBe None))
