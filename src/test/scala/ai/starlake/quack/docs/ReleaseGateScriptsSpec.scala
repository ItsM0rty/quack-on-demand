package ai.starlake.quack.docs

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/** Drives the release gates on synthetic trees: the per-binary build-marker check
  * (scripts/release/quackwire-build-info.sh) and `verify_quackwire_binaries` built on it, and the
  * release-pin guard (scripts/release/check-release-pins.sh). The VERSION stamp vouches for the
  * whole binary set, so a stale binary with a correct sidecar passed the old gate; these pin that
  * it no longer does.
  */
class ReleaseGateScriptsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private var tempDir: Path = uninitialized

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("release-gate-scripts-spec")

  override def afterEach(): Unit =
    Files.walk(tempDir).iterator().asScala.toSeq.sortBy(_.toString.length).reverse.foreach {
      Files.deleteIfExists
    }

  private val Pin = "2.0.0-974927a394b1-1"

  private def marker(
      protocol: String = "3",
      duckdb: String = "v2.0.0-alpha46057",
      quack: String = "974927a394b188755284682b73398ed50e86316c"
  ): String =
    s"quackwire-build-info:protocol=$protocol;duckdb=$duckdb;duckdb_source=b15358c371;quack=$quack"

  /** A stand-in binary: the marker surrounded by NUL-padded junk, as in .rodata. */
  private def fakeBinary(path: Path, marker: Option[String]): Path =
    Files.createDirectories(path.getParent)
    val junk = Array.fill[Byte](64)(0x7f) ++ Array.fill[Byte](8)(0)
    Files.write(path, junk ++ marker.fold(Array.emptyByteArray)(_.getBytes("US-ASCII")) ++ junk)

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map("%02x".format(_))
      .mkString

  /** Runs `script` with bash in `cwd`; returns (exit code, stdout + stderr). */
  private def bash(cwd: Path, script: String): (Int, String) =
    val out  = new StringBuilder
    val sink = ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n'))
    val code = Process(Seq("bash", "-c", script), cwd.toFile).!(sink)
    (code, out.toString)

  private val buildInfoScript = Paths.get("scripts/release/quackwire-build-info.sh").toAbsolutePath

  private def checkMarker(file: Path, pin: String = Pin): (Int, String) =
    bash(tempDir, s"""source "$buildInfoScript" && quackwire_check_build_marker "$file" "$pin"""")

  "quackwire_check_build_marker" should "accept a binary whose marker matches the pin" in {
    val f = fakeBinary(tempDir.resolve("ok.so"), Some(marker()))
    checkMarker(f)._1 shouldBe 0
  }

  it should "refuse a binary with no marker as a pre-marker (stale) build" in {
    val (code, out) = checkMarker(fakeBinary(tempDir.resolve("old.so"), None))
    code shouldBe 1
    out should include("predates build markers")
  }

  it should "refuse a binary built against another DuckDB release" in {
    val (code, out) =
      checkMarker(fakeBinary(tempDir.resolve("a.so"), Some(marker(duckdb = "v1.5.6"))))
    code shouldBe 1
    out should include("DuckDB 'v1.5.6'")
  }

  it should "refuse a binary built from another duckdb-quack commit" in {
    val f           = fakeBinary(tempDir.resolve("b.so"), Some(marker(quack = "7e80f7ffcc98d0b3")))
    val (code, out) = checkMarker(f)
    code shouldBe 1
    out should include("duckdb-quack '7e80f7ffcc98'")
  }

  it should "refuse another Quack protocol generation" in {
    val (code, out) = checkMarker(fakeBinary(tempDir.resolve("c.so"), Some(marker(protocol = "1"))))
    code shouldBe 1
    out should include("Quack protocol '1'")
  }

  /** A checkout holding just what `verify_quackwire_binaries` reads, every sidecar correct. */
  private def releaseTree(stale: Set[String]): Path =
    val root = tempDir.resolve("tree")
    Files.createDirectories(root.resolve("scripts/release"))
    Seq("release-lib.sh", "quackwire-build-info.sh").foreach { f =>
      Files.copy(Paths.get(s"scripts/release/$f"), root.resolve(s"scripts/release/$f"))
    }
    Files.writeString(root.resolve("build.sbt"), s"""val libquackwireVersion = "$Pin"\n""")
    val bins = root.resolve("libquackwire/binaries")
    Seq(
      "linux-x86_64/libquackwire.so",
      "linux-aarch64/libquackwire.so",
      "osx-x86_64/libquackwire.dylib",
      "osx-aarch64/libquackwire.dylib",
      "windows-x86_64/quackwire.dll"
    ).foreach { rel =>
      val plat = rel.takeWhile(_ != '/')
      val bin  = fakeBinary(bins.resolve(rel), if stale(plat) then None else Some(marker()))
      Files.writeString(bins.resolve(s"$rel.sha256"), sha256(bin) + "\n")
    }
    Files.writeString(bins.resolve("VERSION"), Pin + "\n")
    root

  private def verify(root: Path): (Int, String) =
    bash(root, "source scripts/release/release-lib.sh && verify_quackwire_binaries")

  "verify_quackwire_binaries" should "pass a binary set whose every marker matches the pin" in {
    val (code, out) = verify(releaseTree(stale = Set.empty))
    withClue(out)(code shouldBe 0)
    out should include(s"phase 1 OK: vendored libquackwire binaries match $Pin")
  }

  it should "refuse a stale binary even when VERSION and its sidecar agree" in {
    val (code, out) = verify(releaseTree(stale = Set("linux-x86_64")))
    code shouldBe 1
    out should include("linux-x86_64/libquackwire.so has no quackwire-build-info marker")
  }

  it should "refuse a stale Windows dll too" in {
    val (code, out) = verify(releaseTree(stale = Set("windows-x86_64")))
    code shouldBe 1
    out should include("windows-x86_64/quackwire.dll")
  }

  private val pinsScript = Paths.get("scripts/release/check-release-pins.sh").toAbsolutePath

  private def pinsTree(jdbc: String, buildSbt: String): Path =
    val root = Files.createDirectories(tempDir.resolve("pins"))
    Files.createDirectories(root.resolve("project"))
    Files.writeString(
      root.resolve("project/Versions.scala"),
      s"""object Versions {\n  val duckdb         = "2.0.0.0"\n  val duckdbJdbc     = $jdbc\n}\n"""
    )
    Files.writeString(root.resolve("build.sbt"), buildSbt)
    root

  private def checkPins(root: Path): (Int, String) = bash(root, s""""$pinsScript" "$root"""")

  "check-release-pins.sh" should "pass the release-day pins: the JDBC from Central, no mavenLocal" in {
    val root = pinsTree("duckdb", "// resolvers += Resolver.mavenLocal (removed on release day)\n")
    val (code, out) = checkPins(root)
    withClue(out)(code shouldBe 0)
  }

  it should "refuse a pre-release JDBC" in {
    val (code, out) = checkPins(pinsTree("\"2.0.0.0-pre-b15358c\"", ""))
    code shouldBe 1
    out should include("pre-release DuckDB JDBC")
  }

  it should "refuse a mavenLocal resolver" in {
    val (code, out) = checkPins(pinsTree("duckdb", "    resolvers += Resolver.mavenLocal,\n"))
    code shouldBe 1
    out should include("build.sbt:1 resolves from ~/.m2")
  }
