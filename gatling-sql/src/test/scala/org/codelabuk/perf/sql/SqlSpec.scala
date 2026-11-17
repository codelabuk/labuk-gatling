package org.codelabuk.perf.sql

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.codelabuk.perf.load.{PlanException, PlanLoader}
import org.codelabuk.perf.sql.SqlExecutor.{BoundIn, BoundOut}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

class SqlSpec extends AnyFunSuite with BeforeAndAfterAll {

  override def beforeAll(): Unit = DerbyFixture.init()

  private def step(mode: String, sql: String, checks: List[SqlCheck] = Nil, extract: List[SqlExtract] = Nil, rollback: Boolean = false) =
    SqlStep("t", "db", mode, sql, Nil, checks, extract, None, 1000, rollback, reportConnectionWait = false)

  private def run(s: SqlStep, bound: Seq[SqlExecutor.Bound], expected: Seq[String] = Nil) = {
    val c = DerbyFixture.connect()
    try SqlExecutor.run(c, s, bound, if (expected.isEmpty) s.checks.map(_ => "") else expected, None) finally c.close()
  }

  // ---------------------------------------------------------------- execution

  test("query: checks and extraction (first value, all values, all rows, count)") {
    val s = step("query", "SELECT id, name FROM customers WHERE status = ? ORDER BY id",
      checks = List(SqlCheck("rowCount", None, "==", "40")),
      extract = List(SqlExtract("firstId", "column", Some("ID"), optional = false), SqlExtract("ids", "columnAll", Some("id"), optional = false),
        SqlExtract("rows", "rows", None, optional = false), SqlExtract("n", "count", None, optional = false)))
    val saved = run(s, Seq(BoundIn("ACTIVE", None))).toOption.get
    assert(saved("firstId") == 1)
    assert(saved("ids").asInstanceOf[Seq[Any]].size == 40)
    assert(saved("rows").asInstanceOf[Seq[Map[String, Any]]].head == Map("id" -> 1, "name" -> "Customer 01"))
    assert(saved("n") == 40)
  }

  test("checks fail with readable messages") {
    assert(run(step("query", "SELECT id FROM customers WHERE id = ?", checks = List(SqlCheck("rowCount", None, ">=", "1"))), Seq(BoundIn(999, None)))
      == Left("rowCount 0 is not >= 1"))
    assert(run(step("query", "SELECT status FROM customers WHERE id = ?", checks = List(SqlCheck("column", Some("status"), "==", ""))),
      Seq(BoundIn(45, None)), Seq("ACTIVE")) == Left("column 'status' is 'INACTIVE', expected 'ACTIVE'"))
    assert(run(step("query", "SELECT id FROM customers WHERE id = ?", extract = List(SqlExtract("x", "column", Some("nope"), optional = false))),
      Seq(BoundIn(1, None))).swap.toOption.get.contains("column 'nope' not in result"))
    assert(run(step("query", "SELECT id FROM customers WHERE id = ?", extract = List(SqlExtract("x", "column", Some("id"), optional = true))),
      Seq(BoundIn(999, None))) == Right(Map.empty))
    val bad = run(step("query", "SELECT nope FROM customers"), Nil)
    assert(bad.isLeft && bad.swap.toOption.get.contains("SQLState"), bad)
  }

  test("call: OUT parameter, then a procedure returning rows") {
    val place = step("call", "{call PLACE_ORDER(?, ?, ?)}")
    val id = run(place, Seq(BoundIn(7, None), BoundIn("12.50", Some("decimal")), BoundOut("int", "orderId"))).toOption.get("orderId")
    assert(id.isInstanceOf[Integer])
    val history = step("call", "{call CUSTOMER_ORDERS(?)}", checks = List(SqlCheck("rowCount", None, ">=", "1")),
      extract = List(SqlExtract("amount", "column", Some("amount"), optional = false)))
    assert(run(history, Seq(BoundIn(7, None))).toOption.get("amount") == new java.math.BigDecimal("12.50"))
  }

  test("update with rollback: measured, counted, but nothing is kept") {
    val before = run(step("query", "SELECT COUNT(*) AS n FROM audit", extract = List(SqlExtract("n", "column", Some("n"), optional = false))), Nil)
    val upd = step("update", "INSERT INTO audit (sr, note) VALUES (?, ?)", checks = List(SqlCheck("updateCount", None, "==", "1")), rollback = true)
    assert(run(upd, Seq(BoundIn("SR-1", None), BoundIn(null, Some("string")))) == Right(Map.empty))
    val after = run(step("query", "SELECT COUNT(*) AS n FROM audit", extract = List(SqlExtract("n", "column", Some("n"), optional = false))), Nil)
    assert(before == after)
  }

  test("batch: many rows in one round-trip, update count is the total") {
    val s = step("batch", "INSERT INTO audit (sr, note) VALUES (?, ?)", checks = List(SqlCheck("updateCount", None, "==", "3")))
    val rows = Seq("a", "b", "c").map(x => Seq(BoundIn("SR-B", None), BoundIn(s"note $x", None)))
    val c = DerbyFixture.connect()
    try {
      assert(SqlExecutor.run(c, s, Nil, Seq(""), None, rows) == Right(Map.empty))
      val count = c.createStatement().executeQuery("SELECT COUNT(*) FROM audit WHERE sr = 'SR-B'")
      count.next(); assert(count.getInt(1) == 3)
      // a failing check is reported
      assert(SqlExecutor.run(c, s, Nil, Seq(""), None, rows.take(1)) == Left("updateCount 1 is not == 3"))
    } finally c.close()
  }

  // ---------------------------------------------------------------- YAML validation

  private val dir = Paths.get(getClass.getResource("/plans/sql-and-api.yaml").toURI).getParent

  private def planError(dataSources: String, steps: String): String = {
    val f = dir.resolve(s"tmp-${System.nanoTime()}.yaml")
    Files.write(f, s"""name: t
                      |baseUrl: http://x
                      |dataSources:
                      |$dataSources
                      |scenarios:
                      |  - name: s
                      |    steps:
                      |$steps
                      |    load: { profile: [ { atOnceUsers: 1 } ] }
                      |""".stripMargin.getBytes(StandardCharsets.UTF_8))
    try intercept[PlanException](PlanLoader.load(f)).getMessage finally Files.deleteIfExists(f)
  }

  private val ro = "  db: { type: jdbc, url: \"jdbc:derby:memory:perf\" }"
  private val rw = "  db: { type: jdbc, url: \"jdbc:derby:memory:perf\", allowWrites: true }"

  test("validation: safety and mistakes are caught before any load") {
    assert(planError(ro, "      - sql: { datasource: db, update: \"DELETE FROM orders\" }").contains("read-only - set `allowWrites: true`"))
    assert(planError(rw, "      - sql: { datasource: db, query: \"DELETE FROM orders\" }").contains("`query` is for SELECT"))
    assert(planError(rw, "      - sql: { datasource: nope, query: \"SELECT 1 FROM orders\" }").contains("unknown jdbc connection 'nope'"))
    assert(planError(rw, "      - sql: { datasource: db, query: \"SELECT * FROM orders WHERE id = ?\" }").contains("1 '?' placeholder(s) but 0 param(s)"))
    assert(planError(rw, "      - sql: { datasource: db, query: \"SELECT * FROM orders WHERE id = ?\", params: [ { out: int, saveAs: x } ] }")
      .contains("OUT parameters are only for `call:`"))
    assert(planError(rw, "      - sql: { datasource: db, query: \"SELECT 1 FROM orders\", maxRow: 5 }").contains("unknown key 'maxRow'"))
    assert(planError(rw, "      - sql: { datasource: db, query: \"SELECT 1 FROM orders\", checks: [ { updateCount: 1 } ] }").contains("use rowCount"))
    // a connection is not test data
    assert(planError(rw, "      - feed: db").contains("feed 'db' is not a data source or setup share"))
    // batch rows must fit the statement
    assert(planError(rw, "      - sql: { datasource: db, batch: \"INSERT INTO audit (sr, note) VALUES (?, ?)\", rows: [ [ a ] ] }")
      .contains("this row has 1 value(s) but the statement has 2"))
    assert(planError(ro, "      - sql: { datasource: db, batch: \"INSERT INTO audit (sr) VALUES (?)\", rows: [ a ] }").contains("read-only"))
    assert(planError(rw, "      - sql: { datasource: db, update: \"DELETE FROM audit\", rows: [ a ] }").contains("only for `batch:`"))
  }

  test("validation: the sample plan with API + SQL loads") {
    val plan = PlanLoader.load(dir.resolve("sql-and-api.yaml"))
    assert(plan.scenarios.map(_.name) == List("db-only", "api-then-db"))
  }
}
