package org.codelabuk.perf.sim

import java.nio.file.Paths
import java.time.Instant

import io.gatling.core.Predef._
import org.codelabuk.perf.build.SimulationAssembler
import org.codelabuk.perf.load.{PlanException, PlanLoader, RunOptions}
import org.codelabuk.perf.metrics.LiveMetricsService

/**
 * The one generic simulation: it reads a YAML plan and runs it. Testers never write Scala.
 *
 *   -Dplan=plans/order-flow.yaml     (env PLAN)        which plan to run
 *   -Dscenario=a,b                   (env SCENARIO)    run only these scenarios
 *   -DANY_NAME=value                                   fills ${ANY_NAME} in the plan
 * Distributed runs (normally set by the operator):
 *   -Dworker.index / -Dworker.count  (env WORKER_INDEX or JOB_COMPLETION_INDEX / WORKER_COUNT)
 *   -Dstart.at=<epoch ms | ISO-8601> (env START_AT)    wait until then before starting load
 *   -Dmode=setup -Dshared.out=f.json                   run only setup, write its shared lists
 *   -Dshared.in=f.json               (env SHARED_IN)   skip setup, use those lists
 */
class YamlSimulation extends Simulation {

  /** Overridable so a test can point at a fixed plan. Must be a `def` (it is read during construction). */
  protected def planPath: String =
    RunOptions.setting("plan", "PLAN").getOrElse(YamlSimulation.missingPlan())

  private val plan = PlanLoader.load(Paths.get(planPath))
  private val opts = RunOptions.fromSystem()
  private val assembled = SimulationAssembler.assemble(plan, opts)
  println(assembled.summary)
  println(f"  load generator: ${Runtime.getRuntime.maxMemory / 1024.0 / 1024 / 1024}%.1f GB max heap, ${Runtime.getRuntime.availableProcessors} CPU(s)")

  // live metrics (Prometheus format) while the test runs: -Dmetrics.port=9464 / METRICS_PORT
  private val liveMetrics = RunOptions.setting("metrics.port", "METRICS_PORT").map { port =>
    val worker = org.codelabuk.perf.data.Worker.current
    new LiveMetricsService(
      port.toIntOption.getOrElse(throw new PlanException(s"metrics.port must be a number, got '$port'")),
      Paths.get(RunOptions.setting("metrics.results").getOrElse("results")),
      plan.name, s"${worker.index}", System.currentTimeMillis())
  }
  liveMetrics.foreach { m =>
    before(m.start())
    after(m.stop(RunOptions.setting("metrics.linger", "METRICS_LINGER_SECONDS").flatMap(_.toIntOption).getOrElse(15)))
  }

  // synchronised start: every worker waits for the same moment before injecting load
  opts.startAt.foreach { at =>
    before {
      val waitMs = at - System.currentTimeMillis()
      if (waitMs > 0) {
        println(s"Waiting ${waitMs / 1000.0}s for the synchronised start at ${Instant.ofEpochMilli(at)} ...")
        Thread.sleep(waitMs)
      } else if (waitMs < -5000)
        println(s"WARNING: start.at ${Instant.ofEpochMilli(at)} was ${-waitMs / 1000}s ago - starting now, this worker is late")
    }
  }

  private val run = setUp(assembled.populations)
    .protocols(assembled.protocol)
    .assertions(assembled.assertions: _*)
  assembled.maxDuration.foreach(run.maxDuration)
}

object YamlSimulation {
  private[sim] def missingPlan(): Nothing = {
    System.err.println(
      """
        |=====================================================================
        |  No test plan given. Tell the engine which YAML to run, e.g.
        |    mvn -pl gatling-api-core test-compile gatling:test -DskipTests -Dplan=path/to/plan.yaml
        |  (or set the PLAN environment variable).
        |  Built-in demo against a mock API:
        |    ... -Dgatling.simulationClass=org.codelabuk.perf.it.MockedYamlSimulation
        |=====================================================================
        |""".stripMargin)
    throw new PlanException("No test plan given - run with -Dplan=path/to/plan.yaml (or set PLAN)")
  }
}
