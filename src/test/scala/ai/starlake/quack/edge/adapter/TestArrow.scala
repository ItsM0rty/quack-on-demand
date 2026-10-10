package ai.starlake.quack.edge.adapter

import ai.starlake.quack.ondemand.InProcessDuckDb
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BigIntVector, BitVector, IntVector, VarCharVector}
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}

import java.sql.{Connection, DriverManager, ResultSet, Types}
import scala.jdk.CollectionConverters.*

/** Builds throw-away ArrowReader instances backed by an in-process DuckDB so tests can stub out
  * [[QuackHttpClient.query]] without standing up a real Quack node.
  */
object TestArrow:

  // One root allocator for the whole test JVM. Plenty of headroom - readers
  // built here are tiny.
  val sharedAllocator = new RootAllocator()

  Class.forName("org.duckdb.DuckDBDriver")

  /** Reader yielding a single row `SELECT 1 AS x`. */
  def oneRowReader(): ArrowReader =
    readerFor("SELECT 1 AS x")

  /** Reader yielding zero rows but a non-empty schema, useful for "empty result" paths without
    * losing schema information.
    */
  def emptyReader(): ArrowReader =
    readerFor("SELECT 1 AS x WHERE FALSE")

  /** Reader for arbitrary SQL evaluated against the in-process DuckDB. The rows are read through
    * plain JDBC and re-encoded with Arrow Java vectors (DuckDB 2.0's JDBC no longer supports
    * `arrowExportStream`). Batches hold at most 1024 rows, as the old export did.
    */
  def readerFor(sql: String): ArrowReader =
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    try fromResultSet(conn, conn.createStatement().executeQuery(sql))
    catch
      case e: Throwable =>
        conn.close()
        throw e

  /** Wrap an open JDBC result as an ArrowReader; closing the reader closes `conn`. */
  def fromResultSet(conn: Connection, rs: ResultSet): ArrowReader =
    val md         = rs.getMetaData
    val arrowTypes = (1 to md.getColumnCount).map { i =>
      md.getColumnType(i) match
        case Types.INTEGER | Types.SMALLINT | Types.TINYINT => new ArrowType.Int(32, true)
        case Types.BIGINT                                   => new ArrowType.Int(64, true)
        case Types.BOOLEAN | Types.BIT                      => ArrowType.Bool.INSTANCE
        case _                                              => ArrowType.Utf8.INSTANCE
    }
    val fields = (1 to md.getColumnCount).map { i =>
      new Field(md.getColumnLabel(i), FieldType.nullable(arrowTypes(i - 1)), null)
    }
    val schema = new Schema(fields.asJava)
    new ArrowReader(sharedAllocator):
      private var exhausted = false

      override def loadNextBatch(): Boolean =
        if exhausted then false
        else
          val root = getVectorSchemaRoot
          root.allocateNew()
          var n = 0
          while n < 1024 && rs.next() do
            for c <- 1 to arrowTypes.length do
              val v   = root.getVector(c - 1)
              val raw = rs.getObject(c)
              if raw == null then () // stays null
              else
                v match
                  case x: IntVector     => x.setSafe(n, raw.asInstanceOf[Number].intValue)
                  case x: BigIntVector  => x.setSafe(n, raw.asInstanceOf[Number].longValue)
                  case x: BitVector     => x.setSafe(n, if raw.asInstanceOf[Boolean] then 1 else 0)
                  case x: VarCharVector => x.setSafe(n, raw.toString.getBytes("UTF-8"))
                  case _                => ()
            n += 1
          root.setRowCount(n)
          if n < 1024 then exhausted = true
          n > 0 || !exhausted

      override def bytesRead(): Long = 0L

      override def closeReadSource(): Unit =
        try rs.close()
        finally conn.close()

      override def readSchema(): Schema = schema

  /** A success response with a small reader and a no-op close. */
  def okResponse(latencyMs: Long = 5L): QuackResponse =
    QuackResponse.Ok(oneRowReader(), latencyMs, () => ())
