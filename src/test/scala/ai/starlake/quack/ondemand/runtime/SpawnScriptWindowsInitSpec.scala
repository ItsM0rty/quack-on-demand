package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*
import scala.util.Try

/** The Windows node init is split: an outer `-init` file `.read`s a secrets file, has DuckDB delete
  * it, then blocks on a keep-alive. Every dot-command argument must be SINGLE-quoted: both CLIs
  * resolve backslash escapes inside a double-quoted argument, so `.read "C:\Users\bob\..."` opened
  * a mangled path, the payload never ran and the secrets file stayed on disk. The rendering is
  * checked through the script's dry run with `QOD_SPAWN_TARGET_OS=windows` (pwsh runs it on any
  * OS), and the quoting rule itself against the real CLI. Cancelled without pwsh / the pinned CLI.
  */
class SpawnScriptWindowsInitSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val ps1 = Paths.get("scripts/spawn-quack-node.ps1").toAbsolutePath.toString

  private lazy val pwsh: Option[String] =
    Seq("pwsh", "powershell.exe").find(c =>
      Try(Process(Seq(c, "-NoProfile", "-Command", "1")).!!).isSuccess
    )

  private lazy val pinnedDuckdb: Option[Path] =
    val abi = BuildVersions.duckdb.split('.').take(3).mkString(".")
    sys.env
      .get("DUCKDB_BIN")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .orElse(sys.env.get("DUCKDB_CACHE_DIR").map(d => Paths.get(d, abi, "bin", "duckdb")))
      .filter(Files.isExecutable(_))

  /** Dry run of the ps1 with its node directories under `tmp`; returns (exit code, stdout, stderr).
    */
  private def dryRun(
      tmp: Path,
      target: String,
      extraEnv: Seq[(String, String)] = Nil
  ): (Int, String, String) =
    assume(pwsh.nonEmpty, "no PowerShell on this host")
    val secrets =
      NodeSecretFiles.writePrivateDir(Map(NodeSecretFiles.TokenKey -> "tok"), Some(tempDir))
    val out = new StringBuilder
    val err = new StringBuilder
    val env = Seq(
      "QOD_SPAWN_DRY_RUN"          -> "1",
      "QOD_SPAWN_TARGET_OS"        -> target,
      "TMPDIR"                     -> tmp.toString,
      "TMP"                        -> tmp.toString,
      "TEMP"                       -> tmp.toString,
      "kind"                       -> "memory",
      "dbName"                     -> "m",
      "schemaName"                 -> "main",
      NodeSecretFiles.DirEnv       -> secrets.toString,
      NodeSecretFiles.EphemeralEnv -> "1"
    ) ++ extraEnv
    val rc = Process(
      Seq(pwsh.get, "-NoProfile", "-File", ps1, "21999"),
      Files.createDirectories(tempDir.resolve("start")).toFile,
      env*
    ) ! ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    (rc, out.toString, err.toString)

  private def deadPid(): Long =
    val dead = new java.lang.ProcessBuilder("true").start()
    dead.waitFor()
    dead.pid()

  private def dotLines(out: String): Vector[String] =
    out.linesIterator.filter(_.startsWith(".")).toVector

  // DuckDB's secret directory moves below the read-only marker file of the node's own `secrets`
  // directory before anything else, under `.bail on` (the outer init turned bail off), so the OS
  // user's ~/.duckdb/stored_secrets never loads. The ACL that makes the directory read-only on
  // Windows only runs there; elsewhere the script applies 0500 / 0400.
  "the PowerShell node payload" should "move the secret directory below the marker first" in {
    val tmp            = Files.createDirectories(tempDir.resolve("t"))
    val (rc, out, err) = dryRun(tmp, "windows")
    withClue(err)(rc shouldBe 0)
    val lines = out.linesIterator.filter(_.trim.nonEmpty).toVector
    lines.take(4) shouldBe Vector(
      ".bail on",
      lines(1),
      "SELECT count(*) AS qod_secret_manager_ready FROM duckdb_secrets();",
      ".bail off"
    )
    lines(1) should startWith("SET secret_directory = '")
    val path = Paths.get(lines(1).stripPrefix("SET secret_directory = '").stripSuffix("';"))
    path.isAbsolute shouldBe true
    path.getFileName.toString shouldBe "s"
    path.getParent.getFileName.toString shouldBe "none"
    val nodeDir = path.getParent.getParent.getParent
    path.getParent.getParent.getFileName.toString shouldBe "secrets"
    nodeDir.getFileName.toString should startWith("quack-node.")
    path.toString should startWith(tmp.toString)
    // The dry run removes its node directory, read-only secret directory and marker included.
    Files.exists(nodeDir) shouldBe false
  }

  // A node whose script was killed leaves its read-only secret directory; the next spawn's sweep
  // gives it back to the current user before removing it (chmod 700 here, the ACL on Windows), and
  // says so on stderr when a node directory cannot be removed.
  "a later PowerShell spawn" should "sweep a dead node's read-only secret directory" in {
    assume(!scala.util.Properties.isWin, "checks the POSIX branch; the ACL branch runs on Windows")
    val tmp  = Files.createDirectories(tempDir.resolve("t"))
    val dead = new java.lang.ProcessBuilder("true").start()
    dead.waitFor()
    val stale = Files.createDirectories(tmp.resolve("quack-node.deadbeef0001"))
    val sec   = Files.createDirectories(stale.resolve("secrets"))
    Files.writeString(stale.resolve("owner.pid"), s"${dead.pid()} 0")
    Files.createFile(sec.resolve("none"))
    Files.setPosixFilePermissions(sec.resolve("none"), PosixFilePermissions.fromString("r--------"))
    Files.setPosixFilePermissions(sec, PosixFilePermissions.fromString("r-x------"))
    val (rc, _, err) = dryRun(tmp, "windows")
    withClue(err)(rc shouldBe 0)
    Files.exists(stale) shouldBe false
    err should not include "could not remove"
  }

  // F01: a line starting with `.` is a CLI dot-command in the `.read` payload (`.shell` runs an OS
  // command); the script refuses one in any value it did not author, before writing anything.
  "the PowerShell spawn" should "refuse a dot-command line in the init SQL" in {
    val tmp = Files.createDirectories(tempDir.resolve("t"))
    List("SET a = 1;\n  .shell id", "SET a = 1;\r.system id").foreach { sql =>
      val (rc, out, err) = dryRun(tmp, "windows", Seq("dbInitSql" -> sql))
      withClue(err)(rc should not be 0)
      err should include("dbInitSql holds a line starting with '.'")
      out shouldBe ""
    }
    val (rc, out, err) = dryRun(tmp, "windows", Seq("HTTP_PROXY" -> "proxy:3128\n.shell id"))
    withClue(err)(rc should not be 0)
    err should include("the assembled init SQL holds a line starting with '.'")
    out shouldBe ""
    Files.list(tmp).count() shouldBe 0
  }

  // F06: off Windows the temp root is often the shared /tmp, so the sweep must not follow a planted
  // symlink nor delete a spill path a node directory merely names.
  "a later PowerShell spawn" should "sweep only real directories and spill directories it made" in {
    assume(!scala.util.Properties.isWin, "symlinks need privileges on Windows")
    val tmp       = Files.createDirectories(tempDir.resolve("t"))
    val spillRoot = Files.createDirectories(tempDir.resolve("start").resolve(".tmp"))
    // Spill paths are written physical, as the script itself writes them (its starting directory is
    // the process's getcwd, e.g. /private/var/... for a macOS temp dir).
    // A symlinked entry pointing at a directory that looks stale, naming a real spill directory.
    val victim = Files.createDirectories(tempDir.resolve("victim"))
    Files.writeString(victim.resolve("owner.pid"), s"${deadPid()} 0")
    val linkedSpill = Files.createDirectories(spillRoot.resolve("qod-node.aaaaaaaaaaaa"))
    Files.writeString(victim.resolve("spill.path"), linkedSpill.toRealPath().toString)
    Files.createSymbolicLink(tmp.resolve("quack-node.deadbeef0002"), victim)
    // A stale directory naming a path outside every spill root.
    val planted = Files.createDirectories(tmp.resolve("quack-node.deadbeef0003"))
    Files.writeString(planted.resolve("owner.pid"), s"${deadPid()} 0")
    val outside = Files.createDirectories(tempDir.resolve("data").resolve("qod-node.bbbbbbbbbbbb"))
    Files.writeString(planted.resolve("spill.path"), outside.toRealPath().toString)
    // A stale directory of this host with its own spill directory.
    val real = Files.createDirectories(tmp.resolve("quack-node.deadbeef0004"))
    Files.writeString(real.resolve("owner.pid"), s"${deadPid()} 0")
    val realSpill = Files.createDirectories(spillRoot.resolve("qod-node.cccccccccccc"))
    Files.writeString(real.resolve("spill.path"), realSpill.toRealPath().toString)
    val (rc, _, err) = dryRun(tmp, "windows")
    withClue(err)(rc shouldBe 0)
    Files.exists(victim.resolve("owner.pid")) shouldBe true
    Files.exists(linkedSpill) shouldBe true
    Files.exists(planted) shouldBe false
    Files.exists(outside) shouldBe true
    Files.exists(real) shouldBe false
    Files.exists(realSpill) shouldBe false
  }

  "the Windows outer init" should "hand the CLI only relative, single-quoted paths" in {
    val tmp            = Files.createDirectories(tempDir.resolve("my temp"))
    val (rc, out, err) = dryRun(tmp, "windows")
    withClue(err)(rc shouldBe 0)
    // The outer init's five lines come last (the payload, printed first, has its own `.bail`
    // lines).
    val dots = dotLines(out).takeRight(5)
    dots shouldBe Vector(
      ".bail off",
      ".read '../secrets.sql'",
      ".shell 'cmd /c del /f /q ..\\secrets.sql'",
      dots(3),
      ".exit"
    )
    dots(3) should fullyMatch regex
      """\.shell 'powershell -NoProfile -Command "Wait-Process -Id \d+ -ErrorAction SilentlyContinue"'"""
    out should include("-- init: ../init.sql")
  }

  // The CLI opens -init / .read with fopen() and runs .shell through system(), which read paths in
  // the ANSI code page on Windows: an absolute path under a non-ASCII %TEMP% would never open. So no
  // absolute and no non-ASCII path may reach it, whatever the temp directory is called (a quote, a
  // space, an accent).
  it should "pass no absolute or non-ASCII path to the CLI, whatever the temp directory" in {
    val tmp            = Files.createDirectories(tempDir.resolve("Hélène o'brien t€mp"))
    val (rc, out, err) = dryRun(tmp, "windows")
    withClue(err)(rc shouldBe 0)
    val cliLines = dotLines(out) ++ out.linesIterator.filter(_.startsWith("-- init:")).toVector
    cliLines.foreach { l =>
      withClue(l) {
        l.forall(_ < 128) shouldBe true
        l should not include tmp.toString
        l should not include "Hélène"
        l should not include ":/"
        l should not include ":\\"
      }
    }
    cliLines should contain(".read '../secrets.sql'")
    cliLines should contain("-- init: ../init.sql")
  }

  it should "keep the credentials out of the outer init" in {
    val (rc, out, _) = dryRun(Files.createDirectories(tempDir.resolve("t")), "windows")
    rc shouldBe 0
    dotLines(out).foreach(l => withClue(l)(l should not include "tok"))
  }

  // The rule the rendering relies on, against the real CLI: a single-quoted argument is literal (a
  // path with a backslash and a space is read and deleted), a double-quoted one is not.
  "the duckdb CLI" should "take single-quoted dot-command arguments literally" in {
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    val dir     = Files.createDirectories(tempDir.resolve("a\\b c"))
    val secrets = dir.resolve("secrets.sql")
    Files.writeString(secrets, "SELECT 'payload-ran' AS p;\n")
    val outer = tempDir.resolve("outer.sql")
    Files.writeString(
      outer,
      s".bail off\n.read '$secrets'\n.shell 'rm -f \"$secrets\"'\n.exit\n"
    )
    val out = Process(
      Seq(pinnedDuckdb.get.toString, "-init", outer.toString),
      tempDir.toFile,
      "DUCKDB_AGENT_MODE" -> "0",
      "HOME"              -> Files.createDirectories(tempDir.resolve("home")).toString
    ).#<(new java.io.ByteArrayInputStream(Array.emptyByteArray)).!!
    out should include("payload-ran")
    Files.exists(secrets) shouldBe false
  }

  it should "mangle a double-quoted backslash path (why the rendering single-quotes)" in {
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    val dir     = Files.createDirectories(tempDir.resolve("x\\y"))
    val secrets = dir.resolve("secrets.sql")
    Files.writeString(secrets, "SELECT 'payload-ran' AS p;\n")
    val outer = tempDir.resolve("outer-dq.sql")
    Files.writeString(outer, s".bail off\n.read \"$secrets\"\n.exit\n")
    val out = new StringBuilder
    Process(
      Seq(pinnedDuckdb.get.toString, "-init", outer.toString),
      tempDir.toFile,
      "DUCKDB_AGENT_MODE" -> "0",
      "HOME"              -> Files.createDirectories(tempDir.resolve("home")).toString
    ).#<(new java.io.ByteArrayInputStream(Array.emptyByteArray))
      .!(ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n')))
    out.toString should not include "payload-ran"
  }
