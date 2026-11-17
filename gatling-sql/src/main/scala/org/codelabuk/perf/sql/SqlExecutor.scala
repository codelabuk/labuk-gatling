package org.codelabuk.perf.sql

import java.sql.{CallableStatement, Connection, PreparedStatement, ResultSet, Types}

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/**
 * Plain JDBC execution of one `sql` step - no Gatling types, so it is unit-testable against any database.
 * Runs on the SQL thread pool, never on Gatling's event loops (JDBC blocks).
 */
object SqlExecutor {

  /** What a statement produced: rows (column names lower-cased), update count, OUT values by saveAs. */
  final case class Outcome(rows: Vector[Map[String, Any]], updateCount: Option[Int], outs: Map[String, Any])

  /** Bound value for one IN parameter (already resolved from the session), or an OUT registration. */
  sealed trait Bound
  final case class BoundIn(value: Any, sqlType: Option[String]) extends Bound
  final case class BoundOut(sqlType: String, saveAs: String) extends Bound

  /**
   * Executes `step` on `conn` with resolved parameters, then applies checks and extractions.
   * @param expected column-check values resolved from the session, in check order
   * @return Right(variables to save) or Left(error message for the report)
   */
  def run(conn: Connection, step: SqlStep, bound: Seq[Bound], expected: Seq[String], fetchSize: Option[Int],
          batchRows: Seq[Seq[Bound]] = Nil): Either[String, Map[String, Any]] =
    try {
      val outcome =
        if (step.rollback) {
          val auto = conn.getAutoCommit
          conn.setAutoCommit(false)
          try execute(conn, step, bound, fetchSize, batchRows)
          finally { conn.rollback(); conn.setAutoCommit(auto) }
        } else execute(conn, step, bound, fetchSize, batchRows)
      verify(step, outcome, expected).toLeft(()).flatMap(_ => extract(step, outcome))
    } catch {
      case e: java.sql.SQLException => Left(sqlMessage(e))
      case NonFatal(e)              => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  /** Short, stable error text - the report groups errors by message. */
  def sqlMessage(e: java.sql.SQLException): String = {
    val state = Option(e.getSQLState).map(s => s" [SQLState $s]").getOrElse("")
    s"${Option(e.getMessage).map(_.linesIterator.next().trim).getOrElse(e.getClass.getSimpleName)}$state"
  }

  private def execute(conn: Connection, step: SqlStep, bound: Seq[Bound], fetchSize: Option[Int], batchRows: Seq[Seq[Bound]]): Outcome =
    step.mode match {
      case "query" =>
        val ps = conn.prepareStatement(step.statement)
        try {
          prepare(ps, step, fetchSize); bindAll(ps, bound)
          val rs = ps.executeQuery()
          try Outcome(readRows(rs, step.maxRows), None, Map.empty) finally rs.close()
        } finally ps.close()

      case "update" =>
        val ps = conn.prepareStatement(step.statement)
        try {
          prepare(ps, step, fetchSize); bindAll(ps, bound)
          Outcome(Vector.empty, Some(ps.executeUpdate()), Map.empty)
        } finally ps.close()

      case "batch" =>
        if (batchRows.isEmpty) Outcome(Vector.empty, Some(0), Map.empty)
        else {
          val ps = conn.prepareStatement(step.statement)
          try {
            prepare(ps, step, fetchSize)
            batchRows.foreach { row => bindAll(ps, row); ps.addBatch() }
            // drivers may answer SUCCESS_NO_INFO (-2) per row: count those rows as 1
            val counts = ps.executeBatch()
            Outcome(Vector.empty, Some(counts.map(c => if (c == java.sql.Statement.SUCCESS_NO_INFO) 1 else math.max(c, 0)).sum), Map.empty)
          } finally ps.close()
        }

      case "call" =>
        val cs = conn.prepareCall(step.statement)
        try {
          prepare(cs, step, fetchSize); bindAll(cs, bound)
          val hasRows = cs.execute()
          // first result set (procedures with dynamic result sets), else the update count
          var rows = Vector.empty[Map[String, Any]]
          var count: Option[Int] = None
          if (hasRows) { val rs = cs.getResultSet; try rows = readRows(rs, step.maxRows) finally rs.close() }
          else { val c = cs.getUpdateCount; if (c >= 0) count = Some(c) }
          val outs = bound.zipWithIndex.collect { case (BoundOut(t, saveAs), i) => saveAs -> readOut(cs, i + 1, t, step.maxRows) }.toMap
          Outcome(rows, count, outs)
        } finally cs.close()
    }

  private def prepare(ps: PreparedStatement, step: SqlStep, fetchSize: Option[Int]): Unit = {
    step.timeout.foreach(t => ps.setQueryTimeout(math.max(1, t.toSeconds.toInt)))
    if (step.mode != "update") ps.setMaxRows(step.maxRows)
    fetchSize.foreach(ps.setFetchSize)
  }

  private def bindAll(ps: PreparedStatement, bound: Seq[Bound]): Unit =
    bound.zipWithIndex.foreach {
      case (BoundIn(v, t), i)     => bindIn(ps, i + 1, v, t)
      case (BoundOut(t, _), i) =>
        ps match {
          case cs: CallableStatement => cs.registerOutParameter(i + 1, jdbcType(t))
          case _                     => throw new IllegalStateException("OUT parameter outside a call")
        }
    }

  private def bindIn(ps: PreparedStatement, i: Int, v: Any, t: Option[String]): Unit = {
    val s = Option(v).map(_.toString).orNull
    t match {
      case Some("null")      => ps.setNull(i, Types.VARCHAR)
      case _ if v == null    => ps.setNull(i, t.map(jdbcType).getOrElse(Types.VARCHAR))
      case Some("string")    => ps.setString(i, s)
      case Some("int")       => ps.setInt(i, s.trim.toInt)
      case Some("long")      => ps.setLong(i, s.trim.toLong)
      case Some("decimal")   => ps.setBigDecimal(i, new java.math.BigDecimal(s.trim))
      case Some("double")    => ps.setDouble(i, s.trim.toDouble)
      case Some("boolean")   => ps.setBoolean(i, s.trim.toBoolean)
      case Some("date")      => ps.setDate(i, java.sql.Date.valueOf(s.trim))           // yyyy-mm-dd
      case Some("timestamp") => ps.setTimestamp(i, timestamp(s.trim))
      case _ =>
        v match {
          case b: BigDecimal => ps.setBigDecimal(i, b.bigDecimal)
          case other         => ps.setObject(i, other)
        }
    }
  }

  /** yyyy-mm-dd hh:mm:ss[.fff], ISO-8601 instant, or epoch millis. */
  private def timestamp(s: String): java.sql.Timestamp =
    s.toLongOption.map(new java.sql.Timestamp(_))
      .orElse(scala.util.Try(java.sql.Timestamp.from(java.time.Instant.parse(s))).toOption)
      .getOrElse(java.sql.Timestamp.valueOf(s.replace('T', ' ')))

  private def jdbcType(t: String): Int = t match {
    case "string"             => Types.VARCHAR
    case "int"                => Types.INTEGER
    case "long"               => Types.BIGINT
    case "decimal" | "number" => Types.NUMERIC
    case "double"             => Types.DOUBLE
    case "boolean"            => Types.BOOLEAN
    case "date"               => Types.DATE
    case "timestamp"          => Types.TIMESTAMP
    case "cursor"             => Types.REF_CURSOR
    case _                    => Types.VARCHAR
  }

  private def readOut(cs: CallableStatement, i: Int, t: String, maxRows: Int): Any =
    if (t == "cursor") {
      val rs = cs.getObject(i, classOf[ResultSet])
      try readRows(rs, maxRows) finally if (rs != null) rs.close()
    } else plain(cs.getObject(i))

  private def readRows(rs: ResultSet, maxRows: Int): Vector[Map[String, Any]] = {
    if (rs == null) return Vector.empty
    val md = rs.getMetaData
    val cols = (1 to md.getColumnCount).map(c => md.getColumnLabel(c).toLowerCase)
    val out = new ArrayBuffer[Map[String, Any]]
    while (out.size < maxRows && rs.next())
      out += cols.zipWithIndex.map { case (c, i) => c -> plain(rs.getObject(i + 1)) }.toMap
    out.toVector
  }

  /** Large objects become text/bytes so they survive the connection being returned to the pool. */
  private def plain(v: Any): Any = v match {
    case c: java.sql.Clob => c.getSubString(1, math.min(c.length, Int.MaxValue.toLong).toInt)
    case b: java.sql.Blob => b.getBytes(1, math.min(b.length, Int.MaxValue.toLong).toInt)
    case other            => other
  }

  // ---------------------------------------------------------------- checks and extraction

  private def compare(actual: Long, op: String, expected: Long): Boolean = op match {
    case "==" => actual == expected
    case "!=" => actual != expected
    case "<"  => actual < expected
    case "<=" => actual <= expected
    case ">"  => actual > expected
    case ">=" => actual >= expected
  }

  private def column(row: Map[String, Any], name: String): Either[String, Any] =
    row.get(name.toLowerCase).toRight(s"column '$name' not in result (columns: ${row.keys.toList.sorted.mkString(", ")})")

  private def verify(step: SqlStep, o: Outcome, expected: Seq[String]): Option[String] =
    step.checks.zip(expected).iterator.map {
      case (SqlCheck("rowCount", _, op, n), _) =>
        Option.when(!compare(o.rows.size.toLong, op, n.toLong))(s"rowCount ${o.rows.size} is not $op $n")
      case (SqlCheck("updateCount", _, op, n), _) =>
        val c = o.updateCount.getOrElse(-1)
        Option.when(!compare(c.toLong, op, n.toLong))(s"updateCount $c is not $op $n")
      case (SqlCheck(_, Some(col), _, _), exp) =>
        o.rows.headOption match {
          case None => Some(s"column '$col': no rows returned")
          case Some(row) =>
            column(row, col) match {
              case Left(err)                                   => Some(err)
              case Right(v) if String.valueOf(v) == exp        => None
              case Right(v)                                    => Some(s"column '$col' is '$v', expected '$exp'")
            }
        }
      case _ => None
    }.collectFirst { case Some(err) => err }

  private def extract(step: SqlStep, o: Outcome): Either[String, Map[String, Any]] = {
    val saved = Map.newBuilder[String, Any] ++= o.outs
    step.extract.foreach { e =>
      val value: Either[String, Option[Any]] = e.kind match {
        case "rows"  => Right(Some(o.rows))
        case "count" => Right(Some(o.updateCount.getOrElse(o.rows.size)))
        case "columnAll" =>
          val col = e.column.get
          if (o.rows.isEmpty) Right(Some(Vector.empty))
          else o.rows.foldLeft[Either[String, Vector[Any]]](Right(Vector.empty)) {
            case (Right(acc), row) => column(row, col).map(acc :+ _)
            case (left, _)         => left
          }.map(Some(_))
        case _ =>
          o.rows.headOption match {
            case None if e.optional => Right(None)
            case None               => Left(s"extract '${e.saveAs}': no rows returned")
            case Some(row)          => column(row, e.column.get).map(v => Option(v))
          }
      }
      value match {
        case Left(err)        => return Left(err)
        case Right(Some(v))   => saved += e.saveAs -> v
        case Right(None)      => ()
      }
    }
    Right(saved.result())
  }
}
