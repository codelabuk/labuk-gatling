package org.codelabuk.perf.unit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.concurrent.duration._

import org.codelabuk.perf.build.Jwt
import org.codelabuk.perf.data.{CsvReader, RecordSource, Worker}
import org.codelabuk.perf.expr.{Condition, Durations}
import org.codelabuk.perf.load.{PlanException, PlanLoader}
import org.codelabuk.perf.model._
import org.scalatest.funsuite.AnyFunSuite

class CoreSpec extends AnyFunSuite {

  // ---------------------------------------------------------------- durations

  test("durations: units, bare seconds, compound and ranges") {
    assert(Durations.parse("500ms") == 500.millis)
    assert(Durations.parse("2s") == 2.seconds)
    assert(Durations.parse("1.5s") == 1500.millis)
    assert(Durations.parse("30") == 30.seconds)
    assert(Durations.parse("1m30s") == 90.seconds)
    assert(Durations.parse("1h") == 1.hour)
    (1 to 50).foreach { _ =>
      val d = Durations.parseOrRange("1s..3s"); assert(d >= 1.second && d <= 3.seconds)
    }
    assert(!Durations.isValid("abc"))
    assert(!Durations.isValid("3s..1s"))
  }

  // ---------------------------------------------------------------- conditions

  private def eval(c: String, vars: (String, Any)*): Boolean = Condition.parse(c).eval(vars.toMap.get)

  test("conditions: comparisons on strings and numbers") {
    assert(eval("#{status} != 'PENDING'", "status" -> "COMPLETED"))
    assert(!eval("#{status} != 'PENDING'", "status" -> "PENDING"))
    assert(eval("#{n} < 10", "n" -> "9"))
    assert(!eval("#{n} < 10", "n" -> "10"))
    assert(eval("#{n} >= 2.5", "n" -> 3))
    assert(eval("#{a} == #{b}", "a" -> "x", "b" -> "x"))
  }

  test("conditions: and / or / not / parentheses / exists / contains / truthy") {
    assert(eval("#{a} == 1 and #{b} == 2", "a" -> 1, "b" -> 2))
    assert(eval("#{a} == 9 || #{b} == 2", "a" -> 1, "b" -> 2))
    assert(eval("not (#{a} == 9 or #{b} == 9)", "a" -> 1, "b" -> 2))
    assert(eval("exists #{token}", "token" -> "t"))
    assert(!eval("exists #{token}"))
    assert(eval("#{roles} contains 'admin'", "roles" -> Seq("user", "admin")))
    assert(eval("#{msg} contains 'ok'", "msg" -> "all ok"))
    assert(eval("#{done}", "done" -> true))
    assert(!eval("#{done}", "done" -> "false"))
    assert(!eval("#{missing} == 'x'"))
    assert(eval("#{missing} != 'x'"))
  }

  test("conditions: bad syntax is reported") {
    intercept[IllegalArgumentException](Condition.parse("#{a} == "))
    intercept[IllegalArgumentException](Condition.parse("(#{a} == 1"))
    intercept[IllegalArgumentException](Condition.parse("#{a"))
  }

  // ---------------------------------------------------------------- workers

  test("workers: users and data split without overlap") {
    val workers = (0 until 3).map(Worker(_, 3))
    assert(workers.map(_.share(10)) == Seq(4, 3, 3))
    assert(workers.map(_.share(10)).sum == 10)
    val items = (1 to 10).toIndexedSeq
    val slices = workers.map(_.slice(items))
    assert(slices.flatten.sorted == items)
    assert(slices.flatten.distinct.size == items.size)
    assert(Worker(0, 1).slice(items) == items)
  }

  // ---------------------------------------------------------------- data

  private def tmp(name: String, content: String): Path = {
    val dir = Files.createTempDirectory("perf-test")
    val f = dir.resolve(name)
    Files.write(f, content.getBytes(StandardCharsets.UTF_8))
    f
  }

  test("csv: quotes, embedded commas, BOM, blank lines") {
    val f = tmp("u.csv", "﻿user,note\r\nalice,\"hello, world\"\n\nbob,\"say \"\"hi\"\"\"\n")
    val rows = CsvReader.read(f)
    assert(rows == IndexedSeq(Map("user" -> "alice", "note" -> "hello, world"), Map("user" -> "bob", "note" -> "say \"hi\"")))
  }

  test("record sources: queue runs out, circular wraps") {
    val recs = IndexedSeq(Map[String, Any]("a" -> 1), Map[String, Any]("a" -> 2))
    val q = new RecordSource("q", recs, "queue")
    assert(Seq(q.next(), q.next(), q.next()).map(_.map(_("a"))) == Seq(Some(1), Some(2), None))
    val c = new RecordSource("c", recs, "circular")
    assert(Seq(c.next(), c.next(), c.next()).map(_.map(_("a"))) == Seq(Some(1), Some(2), Some(1)))
    assert(RecordSource.recordsFrom(Seq("x"), "sr") == IndexedSeq(Map("sr" -> "x")))
    assert(RecordSource.recordsFrom(Seq(Map("id" -> 1)), "item").head("id") == 1)
  }

  // ---------------------------------------------------------------- plan loading

  private val samplePlan = Paths.get(getClass.getResource("/plans/sr-email-status.yaml").toURI)

  test("loader: the sample plan loads with includes, auth, setup, polling and SLAs") {
    val plan = PlanLoader.load(samplePlan)
    assert(plan.apis.keySet == Set("login", "loadFilterData", "getSrStatus", "getWaitTime"))
    assert(plan.apis("getSrStatus").method == "GET")
    assert(plan.auth.exists(_.isInstanceOf[AuthDef.Login]))
    assert(plan.setup.get.share.head == ShareDef("srQueue", "srList", "sr", "queue", splitAcrossWorkers = true))
    val sc = plan.scenarios.head
    assert(sc.feeders == List("users", "srQueue"))
    val poll = sc.steps.head.asInstanceOf[Step.Transaction].steps(1).asInstanceOf[Step.PollUntil]
    assert(poll.maxAttempts == 6 && poll.timeout.contains(1.minute))
    assert(plan.scenarios(1).auth.exists(_.isInstanceOf[AuthDef.Jwt]))
    assert(plan.sla.map(s => (s.scope, s.target, s.metric)) == List(
      ("global", None, "failedRequests.percent"),
      ("api", Some("getSrStatus"), "responseTime.p95"),
      ("transaction", Some("sr-end-to-end"), "failedRequests.percent")))
  }

  /** Writes a plan next to the sample's apis/ and data/ folders and returns the loader's error. */
  private def loadError(yaml: String): String = {
    val f = samplePlan.getParent.resolve(s"tmp-${System.nanoTime()}.yaml")
    Files.write(f, yaml.getBytes(StandardCharsets.UTF_8))
    try intercept[PlanException](PlanLoader.load(f)).getMessage
    finally Files.deleteIfExists(f)
  }

  private val minimal =
    """name: t
      |baseUrl: http://x
      |apis:
      |  ping: { request: GET /ping }
      |""".stripMargin

  test("loader: typos are caught with the YAML path") {
    val msg = loadError(minimal +
      """scenarios:
        |  - name: s
        |    steps:
        |      - pollUntil: { condition: "#{a} == 1", maxAtempts: 3, steps: [ { api: ping } ] }
        |    load: { profile: [ { atOnceUsers: 1 } ] }
        |""".stripMargin)
    assert(msg.contains("scenarios[0].steps[0].pollUntil") && msg.contains("maxAtempts"), msg)
  }

  test("loader: unknown API, unknown feeder, missing placeholder, endless poll, mixed models") {
    def sc(steps: String, load: String = "{ profile: [ { atOnceUsers: 1 } ] }", extra: String = "") =
      minimal + s"scenarios:\n  - name: s\n$extra    steps: $steps\n    load: $load\n"
    assert(loadError(sc("[ { api: nope } ]")).contains("API 'nope' which is not defined"))
    assert(loadError(sc("[ { api: ping } ]", extra = "    feeders: [ ghosts ]\n")).contains("feeder 'ghosts'"))
    assert(loadError(minimal.replace("http://x", "${NOT_SET_ANYWHERE}") + sc("[ { api: ping } ]").stripPrefix(minimal)).contains("NOT_SET_ANYWHERE"))
    assert(loadError(sc("""[ { pollUntil: { condition: "#{a} == 1", steps: [ { api: ping } ] } } ]""")).contains("never run forever"))
    assert(loadError(sc("[ { api: ping } ]", "{ profile: [ { atOnceUsers: 1 }, { constantConcurrentUsers: 2, during: 1m } ] }")).contains("cannot mix"))
  }

  test("loader: placeholders take defaults and system properties") {
    sys.props("PERF_TEST_URL") = "http://from-prop"
    try {
      val f = samplePlan.getParent.resolve(s"tmp-${System.nanoTime()}.yaml")
      Files.write(f, (minimal.replace("http://x", "${PERF_TEST_URL:http://default}") +
        "scenarios:\n  - name: s\n    steps: [ { api: ping } ]\n    load: { profile: [ { atOnceUsers: 1 } ] }\n").getBytes(StandardCharsets.UTF_8))
      try assert(PlanLoader.load(f).baseUrl == "http://from-prop") finally Files.deleteIfExists(f)
    } finally sys.props -= "PERF_TEST_URL"
  }

  // ---------------------------------------------------------------- jwt

  test("jwt: minted tokens carry exp, which the refresh logic can read") {
    val exp = System.currentTimeMillis() / 1000 + 60
    val token = Jwt.sign("HS256", "k".getBytes, Map("sub" -> "u1", "exp" -> exp))
    assert(token.split('.').length == 3)
    assert(Jwt.expiresAtMillis(token).contains(exp * 1000))
    assert(Jwt.expiresAtMillis("not-a-jwt").isEmpty)
  }
}
