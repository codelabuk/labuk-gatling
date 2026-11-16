package org.codelabuk.perf.it

import java.nio.file.Paths

import org.codelabuk.perf.load.RunOptions
import org.codelabuk.perf.sim.YamlSimulation

/**
 * Runs a YAML plan against [[MockApiServer]] (started before, stopped after the run).
 * Default plan: the sample SR flow; any other plan written for the mock can be given with -Dplan.
 *   mvn -pl gatling-api-core test-compile gatling:test -Dgatling.simulationClass=org.codelabuk.perf.it.MockedYamlSimulation
 */
class MockedYamlSimulation extends YamlSimulation {
  override protected def planPath: String =
    RunOptions.setting("plan", "PLAN").getOrElse(
      Paths.get(classOf[MockedYamlSimulation].getResource("/plans/sr-email-status.yaml").toURI).toString)

  before(MockApiServer.start(18080))
  after(MockApiServer.stop())
}
