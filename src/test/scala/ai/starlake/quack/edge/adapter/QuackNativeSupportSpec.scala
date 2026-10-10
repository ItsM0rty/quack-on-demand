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
      osArch = "amd64"
    )
    val msg = got.left.getOrElse(fail("expected a boot refusal"))
    msg should include("os.name=Linux")
    msg should include("os.arch=amd64")
    msg should include("libquackwire/binaries/linux-x86_64/libquackwire.so")
    msg should include("boom")
    msg should include("DUCKDB_CACHE_DIR")
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
    val got  = QuackNativeSupport.probe(() => throw new ExceptionInInitializerError(link))
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
