package ai.starlake.quack.ondemand

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
  * applies the same two settings through `DBConfig`.
  */
object InProcessDuckDb:

  /** Engine settings applied when an instance is created. Both are global (per instance): DuckDB
    * refuses a second connection to the same database file with a different configuration, so every
    * connection to one file must carry them.
    */
  val Settings: List[(String, String)] = List("threads" -> "1", "async_threads" -> "1")

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
