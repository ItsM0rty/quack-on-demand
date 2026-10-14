package ai.starlake.quack.edge.adapter

/** Test-only JNI fixture entry points. These mirror the `QuackNativeBridge` object's loader pattern
  * (its companion class init runs `System.load` on libquackwire) and add the symbols defined in
  * `quackwire.cpp` under the `Java_ai_starlake_quack_edge_adapter_QuackTestFixtures_00024_*` names.
  * The native lib is loaded exactly once at the JVM level, so referencing `QuackNativeBridge` here
  * is enough to guarantee the symbols resolve.
  */
object QuackTestFixtures:
  // Force libquackwire to load. The `_` discard pins the initializer
  // anchor without ever calling it; the side effect of resolving the
  // `loaded` val in QuackNativeBridge is sufficient.
  private val _bridgeLoaded: Int = QuackNativeBridge.smokeAnswer()

  /** Returns the serialized bytes of a CONNECTION_RESPONSE message with the supplied connection id.
    */
  @native def serializeSampleConnectionResponse(connectionId: String): Array[Byte]

  /** Returns the serialized bytes of an ERROR_RESPONSE message carrying the supplied human-readable
    * message.
    */
  @native def serializeSampleErrorResponse(message: String): Array[Byte]

  /** Returns the serialized bytes of a PREPARE_RESPONSE message. When `withOneRowOneColChunk` is
    * true, the message carries a single `INTEGER` column with one row of value `42`. Otherwise the
    * result set is empty (no chunks).
    *
    * `columnName` controls the column name on the wire. Pass `null` (or empty) for the default
    * `"column_0"`. Threaded into the new `extractColumnNames` Task 6 test and into the column-name
    * propagation check in `QuackProtocolSpec`.
    */
  @native def serializeSamplePrepareResponse(
      resultUuid: java.math.BigInteger,
      needsMoreFetch: Boolean,
      withOneRowOneColChunk: Boolean,
      columnName: String
  ): Array[Byte]

  /** Convenience overload preserving the older Task 4/5 call shape. Routes through the same JNI
    * entry with a null column name (which the C++ side defaults to `"column_0"`).
    */
  def serializeSamplePrepareResponse(
      resultUuid: java.math.BigInteger,
      needsMoreFetch: Boolean,
      withOneRowOneColChunk: Boolean
  ): Array[Byte] =
    serializeSamplePrepareResponse(resultUuid, needsMoreFetch, withOneRowOneColChunk, null)

  /** A one-row PREPARE_RESPONSE whose column is declared as DuckDB's `TYPE` (what `get_type(42)`
    * returns), a type with no Arrow mapping. The type is physically VARCHAR, so the node serializes
    * it like any other column: this patches the INTEGER fixture's declared result type id (field
    * 100, `64 00`, then `0d` = INTEGER) to `06` = TYPE. The first match is the result's declared
    * types, which the Arrow schema is built from; the chunk's own copy after it keeps INTEGER so
    * its int32 payload still decodes.
    */
  def serializeTypeColumnPrepareResponse(): Array[Byte] =
    val out    = serializeSamplePrepareResponse(java.math.BigInteger.TWO, false, true)
    val needle = Array[Byte](0x64, 0x00, 0x0d)
    val at     = out.indices.find(i => out.slice(i, i + needle.length).sameElements(needle))
    require(at.isDefined, "the fixture declares no INTEGER type id")
    out(at.get + 2) = 0x06
    out

  /** Returns the serialized bytes of a FETCH_RESPONSE message. When `withOneRowOneColChunk` is
    * true, the message carries a single `INTEGER` column with one row of value `42`. Otherwise the
    * result set is empty (no chunks).
    */
  @native def serializeSampleFetchResponse(withOneRowOneColChunk: Boolean): Array[Byte]
