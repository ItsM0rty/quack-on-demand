package ai.starlake.quack.ondemand

import java.nio.file.attribute.{
  AclEntry,
  AclEntryPermission,
  AclEntryType,
  AclFileAttributeView,
  PosixFileAttributeView,
  PosixFilePermissions
}
import java.nio.file.{Files, Path}
import java.util.Properties

/** The fixed thread budget of every in-process DuckDB the manager JVM opens through JDBC (the
  * DuckLake catalog initializer, via [[DuckDbExtensionSource.connectionProperties]]; tests reuse
  * the same settings).
  *
  * DuckDB 2.0 sizes each instance at one worker per core (`threads`) plus an async I/O pool
  * (`async_threads`, 4 x cores capped at 256): ~79 native threads for one in-memory instance on a
  * 16-core host, against ~15 on 1.5.6. The manager's instances run a handful of short catalog
  * statements, so they get one worker and one async thread each, which costs under one extra thread
  * per instance. `async_threads=0` is accepted too (the engine then runs async I/O inline on the
  * caller), but that is a separate code path in the engine and its extensions, so the budget keeps
  * one.
  *
  * libquackwire's process-wide helper instance (`shared_client_properties` in quackwire.cpp)
  * applies the same two thread settings through `DBConfig`.
  *
  * `secret_directory`: DuckDB loads every persistent secret of its secret directory on first secret
  * use, and the default is the OS user's `~/.duckdb/stored_secrets`, so an operator's own cloud
  * keys would serve the manager's statements in place of the configured ones. Every instance gets
  * [[secretDirectory]] instead, an empty read-only directory: nothing loads and CREATE PERSISTENT
  * SECRET fails. Not `allow_persistent_secrets = false`: on DuckDB 2.0 that refuses every postgres
  * ATTACH naming no SECRET ("Unknown secret storage found"), inline credentials included.
  * libquackwire's helper instance keeps `allow_persistent_secrets = false`: it never runs SQL, so
  * no ATTACH can trip over it.
  */
object InProcessDuckDb:

  /** The secret directory of every in-process instance: created once per JVM under
    * `java.io.tmpdir`, empty, readable and listable by the JVM's user only and writable by nobody
    * (0500 on POSIX, an owner-only read-and-delete ACL elsewhere), and removed when the JVM exits.
    * Settings are applied when an instance is created, before its first secret use.
    */
  lazy val secretDirectory: Path =
    val dir = Files.createTempDirectory("qod-duckdb-secrets")
    Option(Files.getFileAttributeView(dir, classOf[PosixFileAttributeView])) match
      case Some(posix) => posix.setPermissions(PosixFilePermissions.fromString("r-x------"))
      case None        =>
        val acl = Option(Files.getFileAttributeView(dir, classOf[AclFileAttributeView]))
          .getOrElse(throw new IllegalStateException(s"cannot make $dir read-only"))
        val readOnly = AclEntry
          .newBuilder()
          .setType(AclEntryType.ALLOW)
          .setPrincipal(acl.getOwner)
          .setPermissions(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE,
            AclEntryPermission.DELETE
          )
          .build()
        acl.setAcl(java.util.List.of(readOnly))
    dir.toFile.deleteOnExit()
    dir

  /** Engine settings applied when an instance is created. All are global (per instance): DuckDB
    * refuses a second connection to the same database file with a different configuration, so every
    * connection to one file must carry them.
    */
  lazy val Settings: List[(String, String)] =
    List(
      "threads"          -> "1",
      "async_threads"    -> "1",
      "secret_directory" -> secretDirectory.toString
    )

  /** [[Settings]] as JDBC connection properties. A fresh object per call: callers add their own. */
  def properties: Properties =
    val p = new Properties()
    Settings.foreach((k, v) => p.setProperty(k, v))
    p

  /** A DuckDB JDBC URL for `path` (empty = a private in-memory database) carrying [[Settings]] as
    * URL options, for callers that can only hand over a URL (a HikariCP pool).
    */
  def url(path: String = ""): String =
    s"jdbc:duckdb:$path" + Settings.map((k, v) => s";$k=$v").mkString
