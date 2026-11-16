package org.codelabuk.perf.unit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.concurrent.duration._

import org.codelabuk.perf.data.Worker
import org.codelabuk.perf.load.{LoadMath, PlanException, PlanLoader}
import org.codelabuk.perf.model.{InjectionDef, LoadDef}
import org.scalatest.funsuite.AnyFunSuite

class LoadSpec extends AnyFunSuite {

  private val dir = Paths.get(getClass.getResource("/plans/sr-email-status.yaml").toURI).getParent

  private def loadProfile(profile: String): LoadDef = {
    val f = dir.resolve(s"tmp-${System.nanoTime()}.yaml")
    Files.write(f, s"""name: t
                      |baseUrl: http://x
                      |apis: { ping: { request: GET /ping } }
                      |scenarios:
                      |  - name: s
                      |    steps: [ { api: ping } ]
                      |    load:
                      |      profile:
                      |$profile""".stripMargin.getBytes(StandardCharsets.UTF_8))
    try PlanLoader.load(f).scenarios.head.load finally Files.deleteIfExists(f)
  }

  private def loadError(profile: String): String = intercept[PlanException](loadProfile(profile)).getMessage

  test("rates accept /s, /min and /h") {
    val l = loadProfile(
      """        - constantUsersPerSec: { rate: 266/min, during: 1h }
        |        - rampUsersPerSec: { from: 1/s, to: 16000/h, during: 1m }
        |""".stripMargin)
    val c = l.profile.head.asInstanceOf[InjectionDef.ConstantRate]
    assert(math.abs(c.rate - 266.0 / 60) < 1e-9)
    assert(l.profile(1) == InjectionDef.RampRate(1.0, 16000.0 / 3600, 1.minute, randomized = false))
    // 266/min for an hour = 15,960 virtual users (= requests for a one-request flow)
    assert(LoadMath.plannedArrivals(l.copy(profile = List(c)), Worker(0, 1)).contains(15960L))
    assert(loadError("        - constantUsersPerSec: { rate: 5/day, during: 1m }\n").contains("unknown rate unit"))
  }

  test("stepped load: incrementUsersPerSec and incrementConcurrentUsers") {
    val open = loadProfile(
      "        - incrementUsersPerSec: { by: 60/min, times: 3, eachLevelLasting: 10m, separatedByRampsLasting: 1m, startingFrom: 60/min }\n")
    assert(open.model == "open")
    assert(open.profile.head == InjectionDef.IncrementRate(1.0, 3, 10.minutes, 1.minute, 1.0))
    // levels 1, 2, 3 users/s for 600s each = 3600; ramps (1.5 + 2.5) * 60 = 240
    assert(LoadMath.plannedArrivals(open, Worker(0, 1)).contains(3840L))

    val closed = loadProfile("        - incrementConcurrentUsers: { by: 50, times: 4, eachLevelLasting: 5m, startingFrom: 100 }\n")
    assert(closed.model == "closed")
    assert(LoadMath.peakConcurrency(closed, Worker(0, 1)).contains(250))
    assert(LoadMath.peakConcurrency(closed, Worker(0, 2)).contains(125))
  }

  test("load steps reject typos and missing parts") {
    assert(loadError("        - rampUsers: { users: 10, durin: 1m }\n").contains("durin"))
    assert(loadError("        - constantUsersPerSec: 5\n          durng: 1m\n").contains("durng"))
    assert(loadError("        - incrementUsersPerSec: 5\n").contains("needs a mapping"))
    assert(loadError("        - incrementUsersPerSec: { by: 1, times: 0, eachLevelLasting: 1m }\n").contains("at least 1"))
  }
}
