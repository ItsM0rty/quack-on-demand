package ai.starlake.quack.model

/** Refuses DuckDB CLI dot-commands in SQL a node runs at boot.
  *
  * A node's init SQL reaches the DuckDB CLI through `-init` (a FIFO on Unix; on Windows a file the
  * outer init `.read`s), and the CLI runs a line starting with `.` as a meta-command: `.shell` and
  * `.system` run an OS command on the node host as the node's OS user (the manager's user on the
  * local backend). Boot SQL runs before any ACL, lockdown or policy is in effect, so the screen
  * sits at the trust boundary instead: every tenant-authored value (database `initSql`, pool
  * `initSql`, a `sql` federated source's `setupSql`) is refused at write time, and the resolved
  * boot SQL of every [[NodeSpec]] is refused again right before a backend starts it, which covers
  * rows stored before this screen and `{{secret.NAME}}` values spliced in at spawn. The spawn
  * scripts repeat the check.
  *
  * The CLI only recognizes a dot at column 0; this screen refuses a dot after any leading
  * whitespace too, and splits lines on every line terminator (`\n`, `\r` and the Unicode ones), so
  * it is a superset of what the CLI would run. No legitimate SQL statement starts a line with `.`.
  */
object InitSqlScreen:

  // `\r\n` first, so a CRLF file numbers its lines like an LF one.
  private val LineBreak = "\r\n|[\n\r\u0085\u2028\u2029]".r

  // Control characters, every Java whitespace / space separator, and a byte-order mark.
  private def isBlank(c: Char): Boolean =
    c <= ' ' || c == '\uFEFF' || Character.isWhitespace(c) || Character.isSpaceChar(c)

  /** The 1-based number of the first line of `sql` whose first non-blank character is `.`. */
  def dotCommandLine(sql: String): Option[Int] =
    LineBreak
      .split(Option(sql).getOrElse(""))
      .iterator
      .zipWithIndex
      .collectFirst { case (line, i) if line.dropWhile(isBlank).startsWith(".") => i + 1 }

  /** Some(error) when `sql` holds a dot-command line. `field` names the value in the message; the
    * offending text itself is never echoed (a resolved value can carry a secret).
    */
  def error(field: String, sql: String): Option[String] =
    dotCommandLine(sql).map { n =>
      s"invalid $field: line $n starts with '.', which the DuckDB CLI runs as a dot-command " +
        "(.shell / .system run OS commands); dot-commands are not allowed in init SQL"
    }

  /** Some(error) when any boot SQL of `spec` (the values the spawn scripts append to the node's
    * init) holds a dot-command line.
    */
  def nodeSpecError(spec: NodeSpec): Option[String] =
    error("dbInitSql", spec.dbInitSql)
      .orElse(error("extraSetupSql (pool initSql + federation SQL)", spec.extraSetupSql))
      .orElse(error("objectStoreSql", spec.objectStoreSql))
      .orElse(error("lockdownSql", spec.lockdownSql))
      .map(msg => s"refusing to start node ${spec.nodeId} of ${spec.poolKey}: $msg")
