package org.codelabuk.perf.it

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._

import org.codelabuk.perf.metrics.{LiveLogReader, LogEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

/**
 * Runs plans/features.yaml through real Gatling (in this JVM) against the mock API, then checks the recorded
 * results request by request: every step type does exactly what the README says.
 */
class FeatureRunSpec extends AnyFunSuite with BeforeAndAfterAll {

  private var events: Vector[LogEvent] = Vector.empty
  private var exitCode = -1

  override def beforeAll(): Unit = {
    val plan = Paths.get(getClass.getResource("/plans/features.yaml").toURI)
    val results = Files.createTempDirectory("features-run")
    sys.props ++= Map("plan" -> plan.toString, "gatling.data.enableAnalytics" -> "false")
    try {
      com.typesafe.config.ConfigFactory.invalidateCaches()
      val module = Class.forName("io.gatling.app.Gatling$").getField("MODULE$").get(null)
      exitCode = module.getClass.getMethod("fromArgs", classOf[Array[String]]).invoke(module,
        Array("--simulation", classOf[MockedYamlSimulation].getName, "--results-folder", results.toString, "--no-reports")).asInstanceOf[Int]
    } finally sys.props -= "plan"
    val log = Files.walk(results).iterator().asScala.find(_.getFileName.toString == "simulation.log").getOrElse(fail("no simulation.log"))
    val r = new LiveLogReader(log)
    try events = r.poll() finally r.close()
  }

  private def requests(name: String): Vector[LogEvent.Request] =
    events.collect { case r: LogEvent.Request if (r.groups :+ r.name).mkString(" / ") == name => r }
  private def ok(name: String) = requests(name).count(_.ok)
  private def ko(name: String) = requests(name).count(!_.ok)
  private def usersOf(scenario: String) = events.collect { case u: LogEvent.User if u.scenario == scenario && u.start => u }.size

  test("the run itself completed") {
    assert(exitCode == 0, s"Gatling exit code $exitCode")
    assert(usersOf("steps") == 20)
  }

  test("forEach: one call per extracted item, each checked against its own id") {
    assert(ok("listItems") == 20)
    assert(ok("itemById") == 20 * MockApiServer.ItemCount && ko("itemById") == 0)
  }

  test("repeat runs its steps the given number of times") {
    assert(ok("ping-repeat") == 40)
  }

  test("if takes the then-branch when the condition holds, never the else-branch") {
    assert(ok("ping-if") == 20 && requests("ping-else").isEmpty)
  }

  test("randomSwitch sends every user down exactly one branch, both branches used") {
    val a = ok("ping-a"); val b = ok("ping-b")
    assert(a + b == 20 && a > 0 && b > 0, s"a=$a b=$b")
  }

  test("retry repeats the block until it succeeds (endpoint fails twice per user)") {
    assert(ko("flaky-retry") == 40, "two failed attempts per user are still recorded")
    assert(ok("flaky-retry") == 20, "then one success per user")
  }

  test("form, raw and file bodies are sent as written, inside their transaction") {
    Seq("bodies / echoForm", "bodies / echoRaw", "bodies / echoFile").foreach { n =>
      assert(ok(n) == 20 && ko(n) == 0, s"$n: ok=${ok(n)} ko=${ko(n)} ${requests(n).find(!_.ok).map(_.message).getOrElse("")}")
    }
    assert(events.collect { case g: LogEvent.Group if g.groups == List("bodies") && g.ok => g }.size == 20)
  }

  test("stopUserIfFailed ends the virtual user after a failure") {
    assert(ko("flaky-once") == 3)
    assert(requests("after-stop").isEmpty)
  }

  test("static auth puts the fixed value in the configured header") {
    assert(ok("secure") == 2 && ko("secure") == 0)
  }

  test("closed model never exceeds its concurrency; pace spaces the iterations") {
    var active = 0
    var peak = 0
    events.foreach {
      case LogEvent.User("closed", true, _)  => active += 1; peak = math.max(peak, active)
      case LogEvent.User("closed", false, _) => active -= 1
      case _                                 => ()
    }
    assert(peak <= 3, s"peak concurrency $peak")
    // each user loops for 2s with pace 500ms -> at most ~5 iterations per user
    val pings = ok("ping-closed")
    assert(pings > 0 && pings <= usersOf("closed") * 5, s"$pings pings for ${usersOf("closed")} users")
  }

  test("throttle caps the request rate - and Gatling stops the scenario when the throttle schedule ends") {
    val times = requests("ping-throttled").map(_.start).sorted
    // 120 users were planned, but the 4s throttle (1s ramp + 3s hold) ends the scenario: ~10 rps x 4s
    assert(times.size >= 30 && times.size <= 50, s"${times.size} requests")
    // while holding, requests are ~100ms apart (10 rps)
    val holding = times.filter(_ >= times.head + 1500)
    val gaps = holding.zip(holding.drop(1)).map { case (a, b) => b - a }
    val avg = gaps.sum.toDouble / gaps.size
    assert(avg >= 80 && avg <= 130, s"average gap ${avg}ms")
  }

  test("validate warns when a throttle is shorter than the load it throttles") {
    val plan = Paths.get(getClass.getResource("/plans/features.yaml").toURI).toString
    val r = org.codelabuk.perf.cli.Validate.run(List(plan))
    assert(r.ok && r.report.contains("Gatling stops the scenario when the throttle ends"), r.report)
  }
}
