package org.codelabuk.perf.sql

import java.nio.file.Paths

import org.codelabuk.perf.it.MockApiServer
import org.codelabuk.perf.load.RunOptions
import org.codelabuk.perf.sim.YamlSimulation

/**
 * Runs a plan with `sql` steps against in-memory Derby + the core's mock API.
 *   mvn -pl gatling-sql test-compile gatling:test -Dgatling.simulationClass=org.codelabuk.perf.sql.MockedSqlSimulation
 */
class MockedSqlSimulation extends YamlSimulation {
  // the database must exist before the plan is assembled (feeders query it at start-up)
  override protected def planPath: String = {
    DerbyFixture.init()
    RunOptions.setting("plan", "PLAN").getOrElse(Paths.get(classOf[MockedSqlSimulation].getResource("/plans/sql-and-api.yaml").toURI).toString)
  }

  before(MockApiServer.start(18080))
  after(MockApiServer.stop())
}
