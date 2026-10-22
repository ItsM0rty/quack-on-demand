package ai.starlake.quack.docs

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** Pins the version-pinned download sites OUTSIDE the build to `Versions.duckdb` (pin-bump
  * checklist item 6). These fallbacks self-install the duckdb CLI/libduckdb for spawned nodes; a
  * drifted one ships an ABI-mismatched engine that links fine and breaks at first node spawn.
  */
class DuckDbPinParitySpec extends AnyFlatSpec with Matchers:

  /** e.g. "1.5.5" from `val duckdb = "1.5.5.0"` in project/Versions.scala. */
  private val enginePin: String =
    val versions = Files.readString(Paths.get("project/Versions.scala"))
    val re       = """val duckdb\s*=\s*"(\d+)\.(\d+)\.(\d+)\.\d+"""".r
    re.findFirstMatchIn(versions)
      .map(m => s"${m.group(1)}.${m.group(2)}.${m.group(3)}")
      .getOrElse(fail("could not parse `val duckdb` from project/Versions.scala"))

  private def fileMustContain(path: String, needle: String, what: String): Unit =
    val content = Files.readString(Paths.get(path))
    withClue(
      s"$path: $what is not pinned to DuckDB $enginePin " +
        s"(expected to contain `$needle`); bump it together with Versions.duckdb " +
        s"per docs/duckdb-pin-bump-checklist.md item 6. "
    ) {
      content should include(needle)
    }

  "scripts/run-jar.sh" should "fall back to the pinned DuckDB version" in
    fileMustContain("scripts/run-jar.sh", s"""version="$${version:-$enginePin}"""", "the fallback")

  "scripts/run-jar.ps1" should "fall back to the pinned DuckDB version" in
    fileMustContain("scripts/run-jar.ps1", s"return '$enginePin'", "the fallback")

  "cli launcher.py" should "pin DUCKDB_CLI_VERSION to the engine version" in
    fileMustContain(
      "cli/src/qod_cli/launcher.py",
      s"""DUCKDB_CLI_VERSION = "$enginePin"""",
      "DUCKDB_CLI_VERSION"
    )

  "Dockerfile" should "bake the pinned DuckDB CLI and libduckdb into the manager image" in
    fileMustContain("Dockerfile", s"ARG DUCKDB_VERSION=$enginePin", "ARG DUCKDB_VERSION")

  "docker/quack-node/Dockerfile" should "bake the pinned DuckDB CLI into the node image" in
    fileMustContain(
      "docker/quack-node/Dockerfile",
      s"ARG DUCKDB_VERSION=$enginePin",
      "ARG DUCKDB_VERSION"
    )

  "docker/fleet-worker/Dockerfile" should "bake the pinned DuckDB CLI into the worker image" in
    fileMustContain(
      "docker/fleet-worker/Dockerfile",
      s"ARG DUCKDB_VERSION=$enginePin",
      "ARG DUCKDB_VERSION"
    )

  private lazy val libquackwireVersion: String =
    val buildSbt = Files.readString(Paths.get("build.sbt"))
    val re       = """val libquackwireVersion\s*=\s*"([^"]+)"""".r
    re.findFirstMatchIn(buildSbt)
      .map(_.group(1))
      .getOrElse(fail("could not parse libquackwireVersion from build.sbt"))

  "build.sbt libquackwireVersion" should "carry the same DuckDB ABI segment" in {
    val coord = libquackwireVersion
    withClue(
      s"libquackwireVersion `$coord` links a different DuckDB ABI than Versions.duckdb " +
        s"($enginePin); nodes and the native client would disagree at runtime. "
    ) {
      coord should startWith(s"$enginePin-")
    }
  }

  // quackwire.yml builds the natives the refresh script vendors. It derives the DuckDB release from
  // project/Versions.scala instead of carrying a copy, so the parity to guard is that it still does
  // and that no literal release tag crept back in (a hard-coded v1.5.6 is how CI kept producing
  // 1.5.6 natives under a 2.0 stamp).
  ".github/workflows/quackwire.yml" should "build against the DuckDB release read from the pin" in {
    val path = ".github/workflows/quackwire.yml"
    val yml  = Files.readString(Paths.get(path))
    withClue(s"$path must read `val duckdb` from project/Versions.scala: ") {
      yml should include("project/Versions.scala")
      yml should include("DUCKDB_VERSION: ${{ needs.pin.outputs.duckdb }}")
    }
    val literalTag = """(releases/download/|--branch\s+)v\d+\.\d+\.\d+""".r
    withClue(s"$path hard-codes a DuckDB release tag instead of using $$DUCKDB_VERSION: ") {
      literalTag.findAllIn(yml).toList shouldBe Nil
    }
  }

  // The Windows shim copies DuckDB internals by hand; naming the pin it was derived at makes a bump
  // that forgets to revisit it fail here instead of at a Windows link (or silently on the wire).
  "native/quackwire/src/win_duckdb_compat.cpp" should "name the libquackwire pin it was derived at" in {
    val pinNoRev = libquackwireVersion.split('-').take(2).mkString("-")
    fileMustContain(
      "native/quackwire/src/win_duckdb_compat.cpp",
      s"\"$pinNoRev-*\"",
      "the re-derivation header"
    )
  }
