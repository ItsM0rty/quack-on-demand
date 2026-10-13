package ai.starlake.quack.edge.adapter

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class LibDuckDbPreloadSpec extends AnyFunSpec with Matchers:

  private val lib = System.mapLibraryName("duckdb")

  describe("abiVersion"):
    it("takes the DuckDB segment of the libquackwire version stamp") {
      LibDuckDbPreload.abiVersion("1.5.6-7e80f7ffcc98-1\n") shouldBe Some("1.5.6")
    }
    it("refuses a stamp that does not start with a DuckDB version") {
      LibDuckDbPreload.abiVersion("") shouldBe None
      LibDuckDbPreload.abiVersion("snapshot-7e80f7f") shouldBe None
      LibDuckDbPreload.abiVersion("../../etc-x-1") shouldBe None
    }

  describe("the bundled version stamp"):
    it("ships next to the natives and names a DuckDB version") {
      LibDuckDbPreload.bundledAbi shouldBe defined
    }
    it("names the version directory the qod CLI provisions libduckdb into") {
      // The CLI lays its cache out <root>/<DUCKDB_CLI_VERSION>/lib and exports DUCKDB_CACHE_DIR at
      // <root>; the preload looks under <root>/<abi>/lib, so the two must be the same string.
      val launcher = Files.readString(Path.of("cli/src/qod_cli/launcher.py"))
      val pin      = """DUCKDB_CLI_VERSION = "([^"]+)"""".r
        .findFirstMatchIn(launcher)
        .map(_.group(1))
        .getOrElse(fail("could not parse DUCKDB_CLI_VERSION from cli/src/qod_cli/launcher.py"))
      LibDuckDbPreload.bundledAbi shouldBe Some(pin)
    }

  describe("candidates"):
    val cwd = Path.of("/work/repo")
    it("looks under DUCKDB_CACHE_DIR first, then the working directory's .duckdb cache") {
      LibDuckDbPreload.candidates("1.5.6", Map("DUCKDB_CACHE_DIR" -> "/cache"), cwd) shouldBe List(
        Path.of("/cache", "1.5.6", "lib", lib),
        Path.of("/work/repo/.duckdb/1.5.6/lib", lib)
      )
    }
    it("uses only the working directory without DUCKDB_CACHE_DIR, or with it blank") {
      val only = List(Path.of("/work/repo/.duckdb/1.5.6/lib", lib))
      LibDuckDbPreload.candidates("1.5.6", Map.empty, cwd) shouldBe only
      LibDuckDbPreload.candidates("1.5.6", Map("DUCKDB_CACHE_DIR" -> "  "), cwd) shouldBe only
    }

  describe("preload"):
    def cacheWith(abi: String): Path =
      val root = Files.createTempDirectory("qod-duckdb-cache")
      val dir  = Files.createDirectories(root.resolve(abi).resolve("lib"))
      Files.writeString(dir.resolve(lib), "not a real library")
      root

    it("loads the first candidate that exists and reports it") {
      val cache  = cacheWith("1.5.6")
      val loaded = List.newBuilder[String]
      val got    = LibDuckDbPreload.preload(
        abi = Some("1.5.6"),
        env = Map("DUCKDB_CACHE_DIR" -> cache.toString),
        cwd = Path.of("/nonexistent"),
        load = p => loaded += p
      )
      got shouldBe Some(cache.resolve("1.5.6").resolve("lib").resolve(lib))
      loaded.result() shouldBe List(got.get.toString)
      LibDuckDbPreload.lastLoaded shouldBe got
    }

    it("records nothing loaded after a miss or a failed load") {
      val cache = cacheWith("1.5.6")
      val env   = Map("DUCKDB_CACHE_DIR" -> cache.toString)
      LibDuckDbPreload.preload(Some("1.5.6"), env, Path.of("/nonexistent"), _ => ())
      LibDuckDbPreload.preload(Some("1.5.6"), Map.empty, Path.of("/nonexistent"), _ => ())
      LibDuckDbPreload.lastLoaded shouldBe None
      LibDuckDbPreload.preload(Some("1.5.6"), env, Path.of("/nonexistent"), _ => ())
      LibDuckDbPreload.preload(
        Some("1.5.6"),
        env,
        Path.of("/nonexistent"),
        _ => throw new UnsatisfiedLinkError("wrong architecture")
      )
      LibDuckDbPreload.lastLoaded shouldBe None
    }

    it("loads nothing when no candidate exists, leaving resolution to the loader path") {
      var calls = 0
      LibDuckDbPreload.preload(
        Some("1.5.6"),
        Map.empty,
        Path.of("/nonexistent"),
        _ => calls += 1
      ) shouldBe
        None
      calls shouldBe 0
      LibDuckDbPreload.lastMiss.get should include("/nonexistent/.duckdb/1.5.6/lib")
    }

    it("clears the recorded miss once a candidate is found") {
      LibDuckDbPreload.preload(Some("1.5.6"), Map.empty, Path.of("/nonexistent"), _ => ())
      val cache = cacheWith("1.5.6")
      LibDuckDbPreload.preload(
        Some("1.5.6"),
        Map("DUCKDB_CACHE_DIR" -> cache.toString),
        Path.of("/nonexistent"),
        _ => ()
      )
      LibDuckDbPreload.lastMiss shouldBe None
    }

    it("loads nothing without a bundled ABI version") {
      val cache = cacheWith("1.5.6")
      var calls = 0
      LibDuckDbPreload.preload(
        None,
        Map("DUCKDB_CACHE_DIR" -> cache.toString),
        cache,
        _ => calls += 1
      ) shouldBe
        None
      calls shouldBe 0
    }

    it("never lets a failing load escape: the libquackwire load still gets its turn") {
      val cache = cacheWith("1.5.6")
      LibDuckDbPreload.preload(
        abi = Some("1.5.6"),
        env = Map("DUCKDB_CACHE_DIR" -> cache.toString),
        cwd = Path.of("/nonexistent"),
        load = _ => throw new UnsatisfiedLinkError("wrong architecture")
      ) shouldBe None
    }

    it("prefers the DUCKDB_CACHE_DIR copy over the working directory's .duckdb cache") {
      val cache = cacheWith("1.5.6")
      val cwd   = Files.createTempDirectory("qod-cwd")
      Files.move(cacheWith("1.5.6"), cwd.resolve(".duckdb"))
      LibDuckDbPreload.preload(
        abi = Some("1.5.6"),
        env = Map("DUCKDB_CACHE_DIR" -> cache.toString),
        cwd = cwd,
        load = _ => ()
      ) shouldBe Some(cache.resolve("1.5.6").resolve("lib").resolve(lib))
    }

  describe("the warning when nothing is found"):
    it("names the ABI, every path tried and how to provision the cache") {
      val tried = LibDuckDbPreload.candidates(
        "1.5.6",
        Map("DUCKDB_CACHE_DIR" -> "/cache"),
        Path.of("/work/repo")
      )
      val msg = LibDuckDbPreload.notFoundMessage("1.5.6", tried)
      msg should include("1.5.6")
      tried.foreach(p => msg should include(p.toString))
      msg should include("scripts/run-jar.sh")
      msg should include("DUCKDB_CACHE_DIR")
      msg should include("qod/duckdb/1.5.6/lib")
      msg should include("DUCKDB_VERSION")
    }
