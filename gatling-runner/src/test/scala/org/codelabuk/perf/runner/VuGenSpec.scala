package org.codelabuk.perf.runner

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._

import org.codelabuk.perf.it.MockedYamlSimulation
import org.codelabuk.perf.load.PlanLoader
import org.codelabuk.perf.metrics.{LiveLogReader, LogEvent}
import org.codelabuk.perf.runner.lr.VuGenConverter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

/** Converts a recorded-style VuGen script (src/test/resources/lr/SR_Flow) and runs the result for real. */
class VuGenSpec extends AnyFunSuite with BeforeAndAfterAll {

  private val script = Paths.get(getClass.getResource("/lr/SR_Flow").toURI)
  private lazy val result = VuGenConverter.convert(script)
  private lazy val out: Path = {
    val dir = Files.createTempDirectory("lr-converted")
    VuGenConverter.write(result, "SR_Flow", dir)
    dir
  }
  private def read(p: Path) = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  test("C parsing: calls in order, comments ignored, control flow flagged") {
    val calls = VuGenConverter.parseCalls("Action.c", read(script.resolve("Action.c")))
    assert(calls.map(_.fn).take(4) == List("lr_start_transaction", "web_reg_save_param", "web_reg_save_param", "web_custom_request"))
    val ping = calls.find(c => c.fn == "web_url" && c.args.headOption.contains(VuGenConverter.Str("ping"))).get
    assert(ping.controlFlow)
    // escaped quotes inside the C strings
    val lb = calls(1).args.collectFirst { case VuGenConverter.Str(s) if s.startsWith("LB=") => s }.get
    assert(lb == "LB=\"srId\":\"")
  }

  test("conversion: requests, correlation, parameters, headers, transactions") {
    assert(result.requests == 4)
    val catalog = read(out.resolve("apis/SR_Flow.yaml"))
    val plan = read(out.resolve("SR_Flow.yaml"))
    // relative to the main host, which becomes baseUrl
    assert(plan.contains("http://localhost:18080"), plan)
    assert(catalog.contains("POST /auth/login") && catalog.contains("GET /sr/#{srId}/status"), catalog)
    // {UserName} is column 1 of users.dat ("user"), {Password} the column "password"
    assert(catalog.contains("#{user}") && catalog.contains("#{password}"), catalog)
    // auto header added after login, so it is on the later requests only
    assert(catalog.contains("Bearer #{token}"))
    // correlation and checks on the right requests
    assert(plan.contains("jsonPath: $.token") && plan.contains("all: true") && plan.contains("bodyContains"))
    assert(plan.contains("transaction: 01_Login") && plan.contains("transaction: 03_SR_Status"))
    assert(Files.exists(out.resolve("data/users.dat")))
  }

  test("the conversion report lists everything that was not converted") {
    val notes = result.report.mkString("\n")
    Seq("inside if/for/while", "lr_rendezvous", "printf formatting", "RunDate}: type Time is not converted",
      "web_set_max_html_param_len is a LoadRunner runtime setting").foreach(n => assert(notes.contains(n), s"missing note '$n' in\n$notes"))
    assert(Files.exists(out.resolve("SR_Flow-conversion.txt")))
  }

  test("the converted plan is valid YAML for the engine") {
    val plan = PlanLoader.load(out.resolve("SR_Flow.yaml"))
    assert(plan.apis.keySet == Set("login", "load_filter_data", "sr_status", "ping"))
    assert(plan.dataSources.keySet == Set("users"))
  }

  test("the converted script runs: every request and transaction succeeds against the mock API") {
    val planFile = out.resolve("SR_Flow.yaml")
    Files.write(planFile, read(planFile).replace("atOnceUsers: 1", "atOnceUsers: 3").getBytes(StandardCharsets.UTF_8))
    val results = Files.createTempDirectory("lr-run")
    sys.props ++= Map("plan" -> planFile.toString, "gatling.data.enableAnalytics" -> "false")
    val code =
      try {
        com.typesafe.config.ConfigFactory.invalidateCaches()
        val module = Class.forName("io.gatling.app.Gatling$").getField("MODULE$").get(null)
        module.getClass.getMethod("fromArgs", classOf[Array[String]]).invoke(module,
          Array("--simulation", classOf[MockedYamlSimulation].getName, "--results-folder", results.toString, "--no-reports")).asInstanceOf[Int]
      } finally sys.props -= "plan"
    assert(code == 0)
    val log = Files.walk(results).iterator().asScala.find(_.getFileName.toString == "simulation.log").get
    val r = new LiveLogReader(log)
    val events = try r.poll() finally r.close()
    val requests = events.collect { case q: LogEvent.Request => ((q.groups :+ q.name).mkString(" / "), q.ok) }
    Seq("01_Login / login", "02_Load_Filter_Data / load_filter_data", "03_SR_Status / sr_status", "ping").foreach { n =>
      assert(requests.count(_ == (n -> true)) == 3, s"$n: ${requests.filter(_._1 == n)}")
    }
    assert(!requests.exists(!_._2), requests.filter(!_._2))
    val groups = events.collect { case g: LogEvent.Group => (g.groups.mkString(" / "), g.ok) }
    assert(groups.count(_ == ("01_Login" -> true)) == 3 && groups.count(_ == ("03_SR_Status" -> true)) == 3)
  }
}
