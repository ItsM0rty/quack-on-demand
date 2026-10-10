package ai.starlake.quack.edge.adapter

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.Using

object QuackNativeBridge:
  @native def smokeAnswer(): Int

  /** Serializes a `CONNECTION_REQUEST` message with the supplied auth token. The returned bytes are
    * the binary `QuackMessage` ready to POST to a Quack node's `/quack` endpoint.
    *
    * `heartbeatTimeoutSec` (Quack v3) is the connection lease the node grants: every request renews
    * it, and once it lapses the node interrupts the running statement.
    */
  @native def serializeConnectionRequest(token: String, heartbeatTimeoutSec: Long): Array[Byte]

  /** Serializes a `PREPARE_REQUEST` message binding `sql` to an existing connection identified by
    * `connectionId`.
    */
  @native def serializePrepareRequest(connectionId: String, sql: String): Array[Byte]

  /** Serializes a `FETCH_REQUEST` message that asks the node to deliver the next batch for the
    * prepared result identified by `resultUuid`.
    *
    * `resultUuid` is the 128-bit identifier threaded through from the `PREPARE_RESPONSE`; pass it
    * as a `java.math.BigInteger` so the JNI layer can unpack it into the native
    * `hugeint_t {upper:int64, lower:uint64}` pair without sign-mangling on the boundary.
    *
    * `batchIndex` (Quack v3) is the 1-based index of the batch asked for, counted after the batches
    * the PREPARE_RESPONSE carried inline; the request also acks every batch before it.
    */
  @native def serializeFetchRequest(
      connectionId: String,
      resultUuid: java.math.BigInteger,
      batchIndex: Long
  ): Array[Byte]

  /** Serializes a `HEARTBEAT_REQUEST` that renews the connection's lease (Quack v3). */
  @native def serializeHeartbeat(connectionId: String): Array[Byte]

  /** Serializes a `DISCONNECT_MESSAGE` that releases server-side state for the supplied connection.
    */
  @native def serializeDisconnect(connectionId: String): Array[Byte]

  /** Parses the serialized `QuackMessage` in `bytes` and returns the ordinal of its `MessageType`.
    * Matches [[MessageType.ordinal]] for the upstream `duckdb::MessageType` enum (non-sequential --
    * `FETCH_REQUEST` is 7, `ERROR_RESPONSE` is 100, etc).
    *
    * Raises `RuntimeException` if `bytes` is null or cannot be parsed as a `QuackMessage`.
    */
  @native def parseMessageType(bytes: Array[Byte]): Int

  /** Parses `bytes` as a `CONNECTION_RESPONSE` and returns the server-issued connection id. Raises
    * `RuntimeException` if the message is a different type.
    */
  @native def extractConnectionId(bytes: Array[Byte]): String

  /** Parses `bytes` as an `ERROR_RESPONSE` and returns the server's human-readable error message.
    * Raises `RuntimeException` if the message is a different type.
    */
  @native def extractErrorMessage(bytes: Array[Byte]): String

  /** Parses `bytes` as a `PREPARE_RESPONSE` and returns its `needs_more_fetch` flag. Only
    * `PREPARE_RESPONSE` carries this flag in the current submodule pin; passing a `FETCH_RESPONSE`
    * raises a `RuntimeException`.
    */
  @native def needsMoreFetch(bytes: Array[Byte]): Boolean

  /** Parses `bytes` as a `PREPARE_RESPONSE` and returns its 128-bit `result_uuid` as a
    * `java.math.BigInteger`. The returned BigInteger is always non-negative -- the JNI layer
    * prepends a 0x00 sign byte before invoking `BigInteger(byte[])` so the upper byte's high bit
    * does not flip the sign. Pair with [[serializeFetchRequest]] to thread the UUID into subsequent
    * FETCH requests.
    *
    * Raises `RuntimeException` if the message is a different type.
    */
  @native def extractResultUuid(bytes: Array[Byte]): java.math.BigInteger

  /** Parses `bytes` as a `PREPARE_RESPONSE` or `FETCH_RESPONSE`, transfers the embedded
    * `DataChunkWrapper`s into a heap-allocated Arrow C-data `ArrowArrayStream*`, and returns the
    * raw pointer as a `Long`. Ownership transfers to the caller: import it with
    * [[QuackArrowImport.importStream]], which delegates to
    * `org.apache.arrow.c.Data.importArrayStream`. The Arrow Java importer invokes the stream's
    * `release` callback when the returned `ArrowReader` is closed, freeing the underlying chunks.
    *
    * Raises `RuntimeException` for any non-response message type or a malformed buffer. Returns `0`
    * if a Java exception is pending (the caller must check `ExceptionCheck` semantics handled for
    * you by the JVM throwing on return).
    */
  @native def extractArrowStream(bytes: Array[Byte]): Long

  /** Parses `bytes` as a `FETCH_RESPONSE` and returns the number of `DataChunkWrapper` entries it
    * carries. The driver uses `0` as the end-of-FETCH-loop signal, matching the upstream
    * Quack-extension scan code (`duckdb-quack/src/quack_scan.cpp:331`, which guards the loop with
    * `if (fetch_response->MutableResults().empty()) { ... return; }`).
    *
    * Raises `RuntimeException` if the message is a different type (e.g. PREPARE_RESPONSE, which is
    * handled by [[needsMoreFetch]] and the initial Arrow stream).
    */
  @native def fetchResponseChunkCount(bytes: Array[Byte]): Int

  /** Parses `bytes` as a `PREPARE_RESPONSE` and returns the column names declared by the server.
    * Mirrors `duckdb::PrepareResponseMessage::Names()` (upstream `quack_message.hpp:157-159`,
    * backing field `result_names: vector<string>` at line 181). FETCH_RESPONSE carries no schema
    * and therefore no names; the driver pulls names once from the PREPARE_RESPONSE and reuses them
    * for every subsequent FETCH on the same connection.
    *
    * Note: [[extractArrowStream]] already threads the same names into the Arrow C-data schema
    * returned for a PREPARE_RESPONSE -- this method is exposed as a standalone primitive so callers
    * (logging, tests, future column-pruning) can peek at the names without going through Arrow.
    *
    * Raises `RuntimeException` if the message is a different type.
    */
  @native def extractColumnNames(bytes: Array[Byte]): Array[String]

  // Side-effecting init anchor: forces NativeLoader.loadFromResources to run
  // exactly once at object init. The Boolean is never read by callers.
  @scala.annotation.unused
  private val loaded: Boolean =
    val osArch  = NativeLoader.platformDir()
    val libName = System.mapLibraryName("quackwire")
    NativeLoader.loadFromResources(s"/native/$osArch/$libName")
    true

  // Referencing the module object forces its <clinit> (and therefore the
  // `loaded` init above, and the underlying System.load) to run right here,
  // instead of lazily at the first native call. Used by
  // QuackNativeSupport.effectiveNativeClient to probe loadability at boot.
  private[adapter] def forceInit(): Unit =
    val _ = QuackNativeBridge
    ()

/** Classpath probe for the bundled libquackwire native. Deliberately separate from
  * [[QuackNativeBridge]]: touching that object triggers the JNI load at init, which is exactly what
  * must NOT happen on a platform with no bundled binary (Windows on ARM64 - quackwire.dll is built
  * x86_64-only). Main consults [[effectiveNativeClient]] before constructing the client so such
  * platforms degrade to the embedded HTTP path instead of crashing.
  *
  * On a platform WITH a bundled binary, presence alone does not mean the native actually loads
  * (e.g. Windows missing the MSVC runtime, or the ABI-pinned duckdb.dll not resolvable on PATH).
  * [[effectiveNativeClient]] also attempts the real load once here, at boot, so a broken native
  * degrades a single time with a warning instead of poisoning every query with a failed <clinit>.
  */
object QuackNativeSupport extends com.typesafe.scalalogging.LazyLogging:

  def available(platform: String, libFile: String = System.mapLibraryName("quackwire")): Boolean =
    val stream = getClass.getResourceAsStream(s"/native/$platform/$libFile")
    if stream == null then false
    else
      stream.close()
      true

  /** True when a native for the RUNNING platform is bundled. False also for platforms
    * [[NativeLoader.platformDir]] does not know about.
    */
  def availableForThisPlatform: Boolean =
    scala.util.Try(NativeLoader.platformDir()).toOption.exists(available(_))

  /** The native-client setting Main should actually use: the configured value, forced to `false`
    * (with a warning) when no native is bundled for this platform, or when a bundled native fails
    * to actually load. `tryLoad` is injectable for tests; in production it triggers the real JNI
    * load via [[QuackNativeBridge.forceInit]] exactly once, here at boot -- never lazily at the
    * first query.
    */
  def effectiveNativeClient(
      configured: Boolean,
      nativeBundled: Boolean = availableForThisPlatform,
      tryLoad: () => Unit = () => requireLoaded()
  ): Boolean =
    if configured && !nativeBundled then
      logger.warn(
        "nativeClient=true but no libquackwire native is bundled for this platform " +
          "(e.g. Windows on ARM64, where quackwire.dll is x86_64-only); " +
          "falling back to the embedded HTTP client."
      )
      false
    else if configured && nativeBundled then
      // UnsatisfiedLinkError (and the ExceptionInInitializerError that wraps it when the
      // failure surfaces via object init) is a java.lang.Error, not an Exception -- NonFatal
      // (and therefore scala.util.Try) does not catch it. Catch Throwable explicitly.
      try
        tryLoad()
        true
      catch
        case t: Throwable =>
          val cause = if t.getCause != null then t.getCause else t
          logger.warn(
            "nativeClient=true and quackwire is bundled for this platform, but loading it " +
              s"failed: ${cause.getMessage}. Falling back to the embedded HTTP client. On Windows " +
              "this is commonly a missing Microsoft Visual C++ runtime or the ABI-pinned " +
              "duckdb.dll not being resolvable on PATH."
          )
          false
    else configured

  /** Attempts `load` and returns None when it succeeds, else a NON-fatal exception carrying the
    * original cause. A failed [[QuackNativeBridge]] init surfaces as `ExceptionInInitializerError`
    * on the first touch and `NoClassDefFoundError` on every later one, both `LinkageError`s that
    * cats-effect treats as fatal: thrown inside an IO they kill the runtime and leave any
    * `unsafeRunSync` caller waiting forever. Unwrapping to the `UnsatisfiedLinkError` underneath
    * keeps the message actionable.
    */
  private[adapter] def probe(
      load: () => Unit,
      preloadMiss: => Option[String] = LibDuckDbPreload.lastMiss
  ): Option[IllegalStateException] =
    try
      load()
      None
    catch
      case t: Throwable =>
        val root = initCause(t)
        val hint = preloadMiss.getOrElse(
          "It links the pinned libduckdb dynamically: provision .duckdb/<abi>/lib " +
            "(scripts/run-jar.sh does) or point DUCKDB_CACHE_DIR at a cache holding it."
        )
        Some(
          new IllegalStateException(
            s"libquackwire native failed to load: ${root.getMessage}. $hint",
            root
          )
        )

  @scala.annotation.tailrec
  private def initCause(t: Throwable): Throwable = t match
    case _: ExceptionInInitializerError | _: NoClassDefFoundError if t.getCause != null =>
      initCause(t.getCause)
    case _ => t

  /** The JNI load, attempted once per JVM and shared by [[effectiveNativeClient]] and
    * [[requireLoaded]].
    */
  private lazy val loadFailure: Option[IllegalStateException] =
    probe(() => QuackNativeBridge.forceInit())

  /** Throws a non-fatal `IllegalStateException` naming the root cause when the native cannot load.
    * Entry points call it inside their IO before the first native call, so a broken native fails
    * the statement instead of hanging the caller.
    */
  def requireLoaded(): Unit =
    loadFailure.foreach(e => throw new IllegalStateException(e.getMessage, e.getCause))

private object NativeLoader:
  def platformDir(): String =
    val os    = sys.props("os.name").toLowerCase(Locale.ROOT)
    val arch  = sys.props("os.arch").toLowerCase(Locale.ROOT)
    val osTag =
      if os.contains("mac") then "osx"
      else if os.contains("linux") then "linux"
      else if os.contains("win") then "windows"
      else sys.error(s"unsupported OS for libquackwire: $os")
    val archTag = arch match
      case "x86_64" | "amd64"  => "x86_64"
      case "aarch64" | "arm64" => "aarch64"
      case other               => sys.error(s"unsupported arch for libquackwire: $other")
    s"$osTag-$archTag"

  def loadFromResources(resourcePath: String): Unit =
    // Resolved before the preload: a platform with no bundled native (Windows on ARM64) has
    // nothing to preload libduckdb for.
    val resource = Option(getClass.getResourceAsStream(resourcePath))
      .getOrElse(sys.error(s"libquackwire resource not found: $resourcePath"))
    // libquackwire links libduckdb dynamically. Loading the pinned one first lets the dynamic
    // loader satisfy that dependency from the image already in the process, instead of from the
    // library path baked in at build time (see LibDuckDbPreload).
    LibDuckDbPreload.preload()
    val tmp =
      java.nio.file.Files.createTempFile("libquackwire-", System.mapLibraryName("quackwire"))
    Using.resource(resource) { in =>
      Using.resource(java.nio.file.Files.newOutputStream(tmp)) { fos =>
        in.transferTo(fos)
      }
    }
    tmp.toFile.deleteOnExit()
    System.load(tmp.toAbsolutePath.toString)

/** Loads the pinned libduckdb into the process before libquackwire, from the `.duckdb/<abi>/lib`
  * cache the launchers provision.
  *
  * libquackwire depends on `@rpath/libduckdb.dylib` (`libduckdb.so`, `duckdb.dll`), and the only
  * rpath baked into a vendored binary is the build machine's cache directory, which exists nowhere
  * else. `run-jar.sh` and `qod start` / `qod serve` cover that by putting the cache on the loader
  * path, but a JVM started through a bash script on macOS never sees `DYLD_LIBRARY_PATH` (System
  * Integrity Protection strips `DYLD_*` when a protected binary such as `/bin/bash` runs), which is
  * exactly how `sbt test` forks. An image already loaded under the same install name (macOS),
  * soname (Linux) or module name (Windows) satisfies the dependency, so a preload makes the baked
  * rpath irrelevant.
  *
  * Best effort by design: finding nothing, or failing to load what it found, leaves resolution to
  * the loader path exactly as before, and [[QuackNativeSupport.effectiveNativeClient]] still
  * decides whether the native client is usable.
  */
private[adapter] object LibDuckDbPreload extends com.typesafe.scalalogging.LazyLogging:

  /** `libquackwire/binaries/VERSION`, bundled by build.sbt next to the natives. */
  private val VersionResource = "/native/VERSION"

  private val DuckDbVersion = "(\\d+\\.\\d+\\.\\d+)(?:-.*)?".r

  /** The DuckDB release a libquackwire version stamp (`<abi>-<quack sha>-<rev>`) links against. */
  def abiVersion(stamp: String): Option[String] =
    stamp.trim match
      case DuckDbVersion(v) => Some(v)
      case _                => None

  def bundledAbi: Option[String] =
    Option(getClass.getResourceAsStream(VersionResource)).flatMap { in =>
      scala.util.Using.resource(in)(s => abiVersion(new String(s.readAllBytes(), "UTF-8")))
    }

  /** Where the launchers put libduckdb, in the order they are tried: `$DUCKDB_CACHE_DIR/<abi>/lib`
    * (run-jar.sh's and the qod launcher's air-gap layout), then `<cwd>/.duckdb/<abi>/lib` (run-jar
    * .sh's default, and a repo-root `sbt test`).
    */
  def candidates(abi: String, env: Map[String, String], cwd: Path): List[Path] =
    val lib = System.mapLibraryName("duckdb")
    (env.get("DUCKDB_CACHE_DIR").map(_.trim).filter(_.nonEmpty).map(Path.of(_)).toList :+
      cwd.resolve(".duckdb")).map(_.resolve(abi).resolve("lib").resolve(lib)).distinct

  /** The [[notFoundMessage]] of the last [[preload]] that found no candidate, None after one that
    * did. Only logged at DEBUG there: a miss is the normal case wherever libduckdb comes from the
    * loader path (the Docker image, `qod start`, Windows PATH), so the paths are named only when
    * the libquackwire load really fails ([[QuackNativeSupport.probe]]).
    */
  @volatile private[adapter] var lastMiss: Option[String] = None

  /** What [[preload]] records when an ABI is known but no candidate holds libduckdb. */
  def notFoundMessage(abi: String, tried: List[Path]): String =
    s"no libduckdb $abi to preload; tried ${tried.mkString(", ")}. libquackwire falls back to " +
      s"the dynamic loader path, which fails unless a launcher put the cache on it. Provision " +
      s".duckdb/$abi/ (scripts/run-jar.sh populates it) or set DUCKDB_CACHE_DIR to a cache " +
      "holding it."

  /** Loads the first existing candidate and returns it; None when there was nothing to load or the
    * load failed. Never throws: an `UnsatisfiedLinkError` is a `java.lang.Error`, hence Throwable.
    */
  def preload(
      abi: Option[String] = bundledAbi,
      env: Map[String, String] = sys.env,
      cwd: Path = Path.of("").toAbsolutePath,
      load: String => Unit = System.load
  ): Option[Path] =
    val found = abi.flatMap { a =>
      val tried = candidates(a, env, cwd)
      val hit   = tried.find(Files.isRegularFile(_))
      lastMiss = if hit.isEmpty then Some(notFoundMessage(a, tried)) else None
      lastMiss.foreach(m => logger.debug(m))
      hit
    }
    found.flatMap { lib =>
      try
        load(lib.toAbsolutePath.toString)
        logger.debug(s"preloaded libduckdb from $lib")
        Some(lib)
      catch
        case t: Throwable =>
          logger.warn(
            s"could not preload libduckdb from $lib (${t.getMessage}); libquackwire falls back " +
              "to the dynamic loader path"
          )
          None
    }
