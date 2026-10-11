package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
  private def dryRun(tmp: Path, target: String): (Int, String, String) =
    assume(pwsh.nonEmpty, "no PowerShell on this host")
    val secrets =
      NodeSecretFiles.writePrivateDir(Map(NodeSecretFiles.TokenKey -> "tok"), Some(tempDir))
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = Process(
      Seq(pwsh.get, "-NoProfile", "-File", ps1, "21999"),
      Files.createDirectories(tempDir.resolve("start")).toFile,
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
    ) ! ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    (rc, out.toString, err.toString)

  private def dotLines(out: String): Vector[String] =
    out.linesIterator.filter(_.startsWith(".")).toVector

  "the Windows outer init" should "single-quote every dot-command argument, with forward slashes for .read" in {
    val tmp            = Files.createDirectories(tempDir.resolve("my temp"))
    val (rc, out, err) = dryRun(tmp, "windows")
    withClue(err)(rc shouldBe 0)
    val dots = dotLines(out)
    dots.head shouldBe ".bail off"
    val read = dots.find(_.startsWith(".read ")).getOrElse(fail(out))
    read should fullyMatch regex """\.read '[^'\\]*my temp[^'\\]*/secrets\.sql'"""
    val del = dots.find(_.contains(" del ")).getOrElse(fail(out))
    del should fullyMatch regex """\.shell 'cmd /c del /f /q "[^'"]*my temp[^'"]*\\secrets\.sql"'"""
    val keep = dots.find(_.contains("Wait-Process")).getOrElse(fail(out))
    keep should fullyMatch regex """\.shell 'powershell -NoProfile -Command "Wait-Process -Id \d+ -ErrorAction SilentlyContinue"'"""
    dots.last shouldBe ".exit"
    // No dot-command argument is double-quoted: that is what mangled the Windows paths.
    dots.foreach(l => withClue(l)(l should not startWith ".read \""))
    dots.filter(_.startsWith(".shell")).foreach(l => withClue(l)(l should startWith(".shell '")))
  }

  it should "keep the credentials out of the outer init" in {
    val (rc, out, _) = dryRun(Files.createDirectories(tempDir.resolve("t")), "windows")
    rc shouldBe 0
    dotLines(out).foreach(l => withClue(l)(l should not include "tok"))
  }

  it should "refuse a node directory whose path holds a single quote" in {
    val tmp            = Files.createDirectories(tempDir.resolve("o'brien"))
    val (rc, out, err) = dryRun(tmp, "windows")
    rc should not be 0
    err should include("single quote")
    out should not include ".read"
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
