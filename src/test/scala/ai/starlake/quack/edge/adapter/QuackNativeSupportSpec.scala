package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the native-client boot gate. The native client is the manager's only client to its nodes,
  * so a platform with no bundled libquackwire, or one whose bundled native cannot load, refuses to
  * boot with an actionable message instead of failing at the first query.
  */
class QuackNativeSupportSpec extends AnyFlatSpec with Matchers:

  "QuackNativeSupport.available" should "see the bundled native for this platform" in {
    // The four Unix platform binaries are vendored under libquackwire/binaries/
    // and bundled into resources at build time; dev machines and CI are all
    // Unix, so the current platform's native must be present.
    QuackNativeSupport.availableForThisPlatform shouldBe true
  }

  it should "not see a native for a platform with no bundled binary" in {
    QuackNativeSupport.available("windows-aarch64", "quackwire.dll") shouldBe false
  }

  "QuackNativeSupport.bootCheck" should "pass when the native is bundled and loads" in {
    var invoked = false
    QuackNativeSupport.bootCheck(bundled = true, load = () => invoked = true) shouldBe Right(())
    invoked shouldBe true
  }

  it should "pass in this JVM, where the bundled native really loads" in {
    QuackNativeSupport.bootCheck() shouldBe Right(())
  }

  it should "refuse boot when no native is bundled, naming the platform, the binaries path and DUCKDB_CACHE_DIR" in {
    var invoked = false
    val got     = QuackNativeSupport.bootCheck(
      bundled = false,
      load = () => invoked = true,
      osName = "Windows 11",
      osArch = "aarch64"
    )
    invoked shouldBe false // nothing to load: touching the bridge would only add a link error
    val msg = got.left.getOrElse(fail("expected a boot refusal"))
    msg should include("os.name=Windows 11")
    msg should include("os.arch=aarch64")
    msg should include("libquackwire/binaries/windows-aarch64/quackwire.dll")
    msg should include("DUCKDB_CACHE_DIR")
    msg should include("no fallback")
  }

  it should "refuse boot when the bundled native fails to load, keeping the root cause" in {
    val got = QuackNativeSupport.bootCheck(
      bundled = true,
      load = () => throw new IllegalStateException("libquackwire native failed to load: boom"),
      osName = "Linux",
      osArch = "amd64",
      preloaded = None
    )
    val msg = got.left.getOrElse(fail("expected a boot refusal"))
    msg should include("os.name=Linux")
    msg should include("os.arch=amd64")
    msg should include("libquackwire/binaries/linux-x86_64/libquackwire.so")
    msg should include("boom")
    msg should include("DUCKDB_CACHE_DIR")
    // CLI users never ran run-jar.sh: the hint names the qod cache first.
    msg should include("qod/duckdb/")
  }

  it should "not blame provisioning when libduckdb was preloaded and libquackwire still fails" in {
    val got = QuackNativeSupport.bootCheck(
      bundled = true,
      load = () => throw new UnsatisfiedLinkError("Symbol not found: __ZN6duckdb10StringUtil"),
      osName = "Mac OS X",
      osArch = "aarch64",
      preloaded = Some(java.nio.file.Path.of("/cache/2.0.0/lib/libduckdb.dylib"))
    )
    val msg = got.left.getOrElse(fail("expected a boot refusal"))
    msg should include("Symbol not found")
    msg should not include "DUCKDB_CACHE_DIR"
    msg should not include "run-jar.sh"
  }

  it should "survive a link error thrown by the load (a java.lang.Error, not an Exception)" in {
    val got = QuackNativeSupport.bootCheck(
      bundled = true,
      load = () => throw new ExceptionInInitializerError(new UnsatisfiedLinkError("GLIBC_2.32")),
      osName = "Mac OS X",
      osArch = "aarch64"
    )
    val msg = got.left.getOrElse(fail("expected a boot refusal"))
    msg should include("GLIBC_2.32")
    msg should include("libquackwire/binaries/osx-aarch64/libquackwire.dylib")
  }

  it should "name the platform even when libquackwire has no build for its OS or arch" in {
    val got = QuackNativeSupport.bootCheck(bundled = false, osName = "SunOS", osArch = "sparcv9")
    val msg = got.left.getOrElse(fail("expected a boot refusal"))
    msg should include("os.name=SunOS")
    msg should include("os.arch=sparcv9")
    msg should include("libquackwire/binaries/")
  }

  "QuackNativeSupport.probe" should "report nothing when the load succeeds" in {
    QuackNativeSupport.probe(() => ()) shouldBe None
  }

  it should "unwrap a failed object init to the original link error, as a non-fatal exception" in {
    val link = new UnsatisfiedLinkError("boom: Library not loaded: @rpath/libduckdb.dylib")
    val got  = QuackNativeSupport.probe(
      () => throw new ExceptionInInitializerError(link),
      preloadMiss = None,
      preloaded = None
    )
    got shouldBe defined
    val e = got.get
    scala.util.control.NonFatal(e) shouldBe true
    e.getMessage should include("boom")
    e.getMessage should include("DUCKDB_CACHE_DIR")
    e.getCause shouldBe link
  }

  it should "unwrap the NoClassDefFoundError a second touch of a failed object raises" in {
    val link   = new UnsatisfiedLinkError("boom")
    val second = new NoClassDefFoundError("Could not initialize class QuackNativeBridge$")
    second.initCause(new ExceptionInInitializerError(link))
    val e = QuackNativeSupport.probe(() => throw second).get
    scala.util.control.NonFatal(e) shouldBe true
    e.getCause shouldBe link
    e.getMessage should include("boom")
  }

  "QuackNativeSupport.requireLoaded" should "pass when the bundled native loads in this JVM" in {
    noException should be thrownBy QuackNativeSupport.requireLoaded()
  }

  "QuackNativeSupport.probe" should "name the preload paths tried when libduckdb was not found" in {
    val e = QuackNativeSupport
      .probe(
        () => throw new UnsatisfiedLinkError("boom"),
        preloadMiss = Some("no libduckdb 1.5.6 to preload; tried /cache/1.5.6/lib/libduckdb.dylib")
      )
      .get
    e.getMessage should include("boom")
    e.getMessage should include("/cache/1.5.6/lib/libduckdb.dylib")
  }

  it should "say provisioning worked when libduckdb was preloaded" in {
    val e = QuackNativeSupport
      .probe(
        () => throw new UnsatisfiedLinkError("mach-o file, but is an incompatible architecture"),
        preloadMiss = None,
        preloaded = Some(java.nio.file.Path.of("/cache/2.0.0/lib/libduckdb.dylib"))
      )
      .get
    e.getMessage should include("incompatible architecture")
    e.getMessage should include("preloaded from /cache/2.0.0/lib/libduckdb.dylib")
    e.getMessage should not include "DUCKDB_CACHE_DIR"
  }

  "QuackNativeSupport.probe" should "report a missing symbol as a stale native, not a provisioning problem" in {
    val link = new UnsatisfiedLinkError(
      "dlopen(/tmp/libquackwire.dylib, 0x0001): Symbol not found: __ZN6duckdb10StringUtil10StartsWith"
    )
    val e = QuackNativeSupport.probe(() => throw new ExceptionInInitializerError(link)).get
    e shouldBe a[StaleNativeException]
    e.getMessage should startWith(StaleNativeException.Marker)
    e.getMessage should include("Symbol not found")
    e.getMessage should include("refresh-quackwire-binaries.sh")
    e.getMessage should not include "DUCKDB_CACHE_DIR"
    e.getCause shouldBe link
  }

  it should "recognise the Linux and Windows missing-symbol messages too" in {
    NativeBuildInfo.isMissingSymbol(
      "/tmp/libquackwire.so: undefined symbol: _ZN6duckdb26SerializationCompatibility7DefaultEv"
    ) shouldBe true
    NativeBuildInfo.isMissingSymbol(
      "C:\\tmp\\quackwire.dll: The specified procedure could not be found"
    ) shouldBe true
    NativeBuildInfo.isMissingSymbol("libduckdb.so: cannot open shared object file") shouldBe false
  }

  "QuackNativeSupport.verifyBuild" should "call a native without the build-info export stale" in {
    val e = QuackNativeSupport
      .verifyBuild(
        buildInfo = () =>
          throw new UnsatisfiedLinkError(
            "'java.lang.String ai.starlake.quack.edge.adapter.QuackNativeBridge$.buildInfo()'"
          ),
        linkedDuckDb = () => fail("must not be reached"),
        stamp = Some("2.0.0-974927a394b1-1")
      )
      .get
    e shouldBe a[StaleNativeException]
    e.getMessage should include("no build-info export")
    e.getMessage should not include "DUCKDB_CACHE_DIR"
  }

  it should "pass a marker that matches the stamp and the loaded libduckdb" in {
    QuackNativeSupport.verifyBuild(
      buildInfo = () => Built,
      linkedDuckDb = () => Linked,
      stamp = Some(Pin)
    ) shouldBe None
  }

  it should "pass the native bundled in this jar" in {
    QuackNativeSupport.requireLoaded()
    QuackNativeSupport.verifyBuild() shouldBe None
  }

  "QuackNativeSupport.bootCheck" should "refuse a stale native without blaming libduckdb provisioning" in {
    val got = QuackNativeSupport.bootCheck(
      bundled = true,
      load = () => throw StaleNativeException("built against DuckDB v1.5.6"),
      osName = "Linux",
      osArch = "amd64"
    )
    val msg = got.left.getOrElse(fail("expected a boot refusal"))
    msg should include(StaleNativeException.Marker)
    msg should include("v1.5.6")
    msg should include("libquackwire/binaries/linux-x86_64/libquackwire.so")
    msg should not include "DUCKDB_CACHE_DIR"
  }

  private val Pin   = "2.0.0-974927a394b1-1"
  private val Built =
    "quackwire-build-info:protocol=3;duckdb=v2.0.0-alpha46057;duckdb_source=b15358c371;" +
      "quack=974927a394b188755284682b73398ed50e86316c"
  private val Linked = "duckdb=v2.0.0-alpha46057;duckdb_source=b15358c371"

  "NativeBuildInfo.mismatch" should "accept a pre-release build of the pinned DuckDB release" in {
    NativeBuildInfo.mismatch(Built, Linked, Some(Pin + "\n")) shouldBe None
  }

  it should "refuse another Quack protocol generation" in {
    val got = NativeBuildInfo.mismatch(Built.replace("protocol=3", "protocol=1"), Linked, Some(Pin))
    got.get should include("Quack protocol 1")
  }

  it should "refuse a native built against another DuckDB release than the stamp" in {
    val built = Built.replace("v2.0.0-alpha46057", "v1.5.6")
    val got   = NativeBuildInfo.mismatch(built, "duckdb=v1.5.6;duckdb_source=b15358c371", Some(Pin))
    got.get should include("DuckDB v1.5.6")
    got.get should include(Pin)
  }

  it should "refuse a native built from another duckdb-quack commit than the stamp" in {
    val got = NativeBuildInfo.mismatch(Built, Linked, Some("2.0.0-7e80f7ffcc98-1"))
    got.get should include("duckdb-quack 974927a394b1")
    got.get should include("duckdb-quack 7e80f7ffcc98")
  }

  it should "refuse a loaded libduckdb that is not the one the native was built against" in {
    val got = NativeBuildInfo.mismatch(Built, "duckdb=v2.0.0;duckdb_source=0123456789", Some(Pin))
    got.get should include("loaded libduckdb v2.0.0 (0123456789)")
  }

  it should "refuse a missing or malformed stamp and an incomplete marker" in {
    NativeBuildInfo.mismatch(Built, Linked, None).get should include("/native/VERSION")
    NativeBuildInfo.mismatch(Built, Linked, Some("snapshot")).get should include("snapshot")
    NativeBuildInfo
      .mismatch("quackwire-build-info:protocol=3", Linked, Some(Pin))
      .get should include("incomplete")
  }

  "scripts/release/quackwire-build-info.sh" should "check the protocol generation the boot gate checks" in {
    val script = java.nio.file.Files
      .readString(java.nio.file.Paths.get("scripts/release/quackwire-build-info.sh"))
    script should include(s"QUACKWIRE_PROTOCOL=${NativeBuildInfo.ProtocolVersion}\n")
  }
