package org.codelabuk.perf.sql

import scala.concurrent.duration._

import org.codelabuk.perf.load.Node
import org.codelabuk.perf.model.{DataSourceDef, TestPlan}

/** A database connection pool definition: `dataSources: { name: { type: jdbc, url: ... } }`. */
final case class JdbcConnection(
    name: String,
    url: String,
    user: Option[String],
    password: Option[String],
    driver: Option[String],
    poolSize: Int,
    connectionTimeout: FiniteDuration,
    properties: Map[String, String],
    allowWrites: Boolean,
    fetchSize: Option[Int],
    maxSessions: Option[Int]
)

/** A query that feeds test data to virtual users: `{ type: jdbc, datasource: db, query: ... }`. */
final case class JdbcFeeder(name: String, connection: String, query: String, params: List[SqlParam.In])

sealed trait SqlParam
object SqlParam {
  /** An input value: a literal or #{...} expression, optionally with an explicit SQL type. */
  final case class In(expr: String, literal: Option[Any], sqlType: Option[String]) extends SqlParam
  /** An OUT parameter of a stored procedure, saved into the session. */
  final case class Out(sqlType: String, saveAs: String) extends SqlParam
}

/** kind: rowCount | updateCount | column ; op: == != < <= > >= */
final case class SqlCheck(kind: String, column: Option[String], op: String, expected: String)

/** kind: column (first row) | columnAll | rows (all rows as maps) | count (rows or update count) */
final case class SqlExtract(saveAs: String, kind: String, column: Option[String], optional: Boolean)

final case class SqlStep(
    name: String,
    datasource: String,
    mode: String, // query | update | call | batch
    statement: String,
    params: List[SqlParam],
    checks: List[SqlCheck],
    extract: List[SqlExtract],
    timeout: Option[FiniteDuration],
    maxRows: Int,
    rollback: Boolean,
    reportConnectionWait: Boolean,
    batch: Option[BatchRows] = None
)

/** Rows of a `batch:` statement - written in the YAML, or a list variable resolved at run time. */
sealed trait BatchRows
object BatchRows {
  final case class Literal(rows: List[List[SqlParam.In]]) extends BatchRows
  /** Each item of the list: a list of values, a map read in `columns` order, or a single value. */
  final case class FromVariable(el: String, columns: List[String]) extends BatchRows
}

/** Reads the YAML for connections, feeders and `sql` steps - shared by validation and build. */
object SqlModel {

  val InTypes: Set[String] = Set("string", "int", "long", "decimal", "double", "boolean", "date", "timestamp", "null")
  val OutTypes: Set[String] = Set("string", "int", "long", "decimal", "number", "double", "boolean", "date", "timestamp", "cursor")
  private val ReadOnlyStart = Set("select", "with", "values")

  def isFeeder(n: Node): Boolean = n.has("query")

  def connection(name: String, n: Node): JdbcConnection = {
    n.allowOnly("type", "url", "user", "password", "driver", "pool", "properties", "allowWrites", "fetchSize", "maxSessions")
    val pool = n.opt("pool").map(_.allowOnly("size", "connectionTimeout"))
    val size = pool.flatMap(_.optInt("size")).getOrElse(10)
    if (size < 1) n.get("pool").get("size").fail("must be at least 1")
    JdbcConnection(
      name = name,
      url = n.str("url"),
      user = n.optStr("user"),
      password = n.optStr("password"),
      driver = n.optStr("driver"),
      poolSize = size,
      connectionTimeout = pool.flatMap(_.optDuration("connectionTimeout")).getOrElse(10.seconds),
      properties = n.optStringMap("properties"),
      allowWrites = n.optBool("allowWrites").getOrElse(false),
      fetchSize = n.optInt("fetchSize"),
      maxSessions = n.optInt("maxSessions")
    )
  }

  def feeder(name: String, n: Node, plan: TestPlan): JdbcFeeder = {
    n.allowOnly("type", "datasource", "query", "params", "strategy", "splitAcrossWorkers")
    val ds = n.str("datasource")
    connectionNode(plan, ds, n.get("datasource"))
    val q = n.str("query")
    if (!readOnly(q)) n.get("query").fail("a feeder query must be a SELECT")
    val params = n.optList("params").map(param).map {
      case p: SqlParam.In if !p.expr.contains("#{") => p
      case _ => n.get("params").fail("feeder params must be literal values or ${...} - there is no virtual user yet to take #{...} from")
    }
    JdbcFeeder(name, ds, q, params)
  }

  /** The node of connection `name`, failing (at `at`) if it is missing or not a connection. */
  def connectionNode(plan: TestPlan, name: String, at: Node): Node =
    plan.dataSources.get(name) match {
      case Some(DataSourceDef.Plugin(_, "jdbc", n)) if !isFeeder(n) => n
      case Some(_) => at.fail(s"'$name' is not a jdbc connection (a `type: jdbc` data source with a `url`)")
      case None =>
        val known = plan.dataSources.collect { case (k, DataSourceDef.Plugin(_, "jdbc", n)) if !isFeeder(n) => k }
        at.fail(s"unknown jdbc connection '$name' - defined: ${if (known.isEmpty) "none" else known.mkString(", ")}")
    }

  private def readOnly(sql: String): Boolean =
    ReadOnlyStart.contains(sql.trim.takeWhile(c => c.isLetter).toLowerCase)

  def param(n: Node): SqlParam =
    if (n.isObject && n.has("out")) {
      n.allowOnly("out", "saveAs")
      val t = n.str("out").toLowerCase
      if (!OutTypes(t)) n.get("out").fail(s"unknown OUT type '$t' - use ${OutTypes.toList.sorted.mkString(", ")}")
      SqlParam.Out(t, n.str("saveAs"))
    } else if (n.isObject) {
      n.allowOnly("value", "type")
      val t = n.optStr("type").map(_.toLowerCase)
      t.foreach(x => if (!InTypes(x)) n.get("type").fail(s"unknown type '$x' - use ${InTypes.toList.sorted.mkString(", ")}"))
      val v = if (n.has("value")) n.get("value") else if (t.contains("null")) n else n.fail("'value' is required (or type: null)")
      SqlParam.In(if (t.contains("null")) "" else v.text, if (t.contains("null")) None else literal(v), t)
    } else SqlParam.In(n.text, literal(n), None)

  /** YAML numbers and booleans keep their type when bound; text and #{...} are resolved at run time. */
  private def literal(n: Node): Option[Any] =
    if (n.raw.isIntegralNumber) Some(n.raw.asLong())
    else if (n.raw.isNumber) Some(n.raw.decimalValue())
    else if (n.raw.isBoolean) Some(n.raw.asBoolean())
    else None

  private val CheckOp = """^\s*(==|!=|<=|>=|<|>)?\s*(-?\d+)\s*$""".r

  /** Report name of a step: its `name`, or "sql <datasource>". */
  def stepName(v: Node): String = v.optStr("name").getOrElse(s"sql ${v.optStr("datasource").getOrElse("?")}")

  def step(n: Node, plan: TestPlan): SqlStep = {
    val v = n.get("sql")
    v.allowOnly("datasource", "name", "query", "update", "call", "batch", "rows", "columns", "params", "checks", "extract", "timeout",
      "maxRows", "rollback", "reportConnectionWait")
    val ds = v.str("datasource")
    val conn = connection(ds, connectionNode(plan, ds, v.get("datasource")))
    val modes = List("query", "update", "call", "batch").filter(v.has)
    val mode = modes match {
      case List(m) => m
      case Nil     => v.fail("needs one of: query (SELECT), update (INSERT/UPDATE/DELETE/DDL), call (stored procedure) or batch (many rows)")
      case many    => v.fail(s"use only one of ${many.mkString(", ")}")
    }
    val statement = v.str(mode)
    if (mode == "query" && !readOnly(statement))
      v.get("query").fail("`query` is for SELECT statements - use `update:` for INSERT/UPDATE/DELETE or `call:` for procedures")
    if (mode != "query" && !conn.allowWrites)
      v.get(mode).fail(s"connection '$ds' is read-only - set `allowWrites: true` on it to run $mode statements")
    val placeholders = statement.replaceAll("'[^']*'", "").count(_ == '?') // ignore ? inside string literals

    val batch: Option[BatchRows] =
      if (mode != "batch") {
        if (v.has("rows") || v.has("columns")) v.fail("`rows` / `columns` are only for `batch:`")
        None
      } else {
        if (v.has("params")) v.get("params").fail("a batch takes its values from `rows`, not `params`")
        val rows = v.get("rows")
        if (rows.isArray) {
          if (v.has("columns")) v.get("columns").fail("`columns` is only needed when `rows` is a variable of maps")
          val literal = rows.list.map { r =>
            val values = if (r.isArray) r.list else List(r)
            if (values.size != placeholders) r.fail(s"this row has ${values.size} value(s) but the statement has $placeholders '?' placeholder(s)")
            values.map(param).map {
              case in: SqlParam.In => in
              case _               => r.fail("OUT parameters are not allowed in a batch")
            }
          }
          if (literal.isEmpty) rows.fail("at least one row is required")
          Some(BatchRows.Literal(literal))
        } else {
          val el = rows.text.trim.stripPrefix("#{").stripSuffix("}").trim
          val columns = v.opt("columns").map(_.stringList).getOrElse(Nil)
          if (columns.nonEmpty && columns.size != placeholders)
            v.get("columns").fail(s"${columns.size} column(s) listed but the statement has $placeholders '?' placeholder(s)")
          Some(BatchRows.FromVariable(el, columns))
        }
      }

    val params = if (mode == "batch") Nil else v.optList("params").map(param)
    if (mode != "call" && params.exists(_.isInstanceOf[SqlParam.Out])) v.get("params").fail("OUT parameters are only for `call:`")
    if (mode != "batch" && placeholders != params.size)
      v.fail(s"the statement has $placeholders '?' placeholder(s) but ${params.size} param(s) are given")

    val checks = v.optList("checks").map { c =>
      c.allowOnly("rowCount", "updateCount", "column", "equals")
      if (c.has("column")) {
        if (!c.has("equals")) c.fail("a column check needs `equals:`")
        SqlCheck("column", Some(c.str("column")), "==", c.str("equals"))
      } else {
        val kind = List("rowCount", "updateCount").find(c.has).getOrElse(c.fail("expected rowCount, updateCount or column + equals"))
        if (kind == "updateCount" && mode == "query") c.fail("updateCount is for update/call statements - use rowCount for queries")
        if (kind == "rowCount" && (mode == "update" || mode == "batch")) c.fail(s"rowCount is for queries - use updateCount for $mode statements")
        c.str(kind) match {
          case CheckOp(op, num) => SqlCheck(kind, None, Option(op).getOrElse("=="), num)
          case other            => c.get(kind).fail(s"expected a number with an optional operator, e.g. '>= 1' - got '$other'")
        }
      }
    }
    val extract = v.opt("extract").map(_.entries).getOrElse(Nil).map { case (saveAs, e) =>
      if (e.isText) SqlExtract(saveAs, "column", Some(e.text), optional = false)
      else {
        e.allowOnly("column", "all", "rows", "count", "optional")
        val optional = e.optBool("optional").getOrElse(false)
        if (e.optBool("rows").contains(true)) SqlExtract(saveAs, "rows", None, optional)
        else if (e.optBool("count").contains(true)) SqlExtract(saveAs, "count", None, optional)
        else if (e.has("column")) SqlExtract(saveAs, if (e.optBool("all").contains(true)) "columnAll" else "column", Some(e.str("column")), optional)
        else e.fail("expected column (+ all: true), rows: true or count: true")
      }
    }
    SqlStep(
      name = stepName(v),
      datasource = ds,
      mode = mode,
      statement = statement,
      params = params,
      checks = checks,
      extract = extract,
      timeout = v.optDuration("timeout"),
      maxRows = v.optInt("maxRows").getOrElse(1000),
      rollback = v.optBool("rollback").getOrElse(false),
      reportConnectionWait = v.optBool("reportConnectionWait").getOrElse(false),
      batch = batch
    )
  }
}
