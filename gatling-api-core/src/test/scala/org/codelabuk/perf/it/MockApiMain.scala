package org.codelabuk.perf.it

/**
 * Runs the mock API on its own (until stopped), e.g. to try the `perf` distribution, the Docker image or a cluster:
 *   mvn -pl gatling-api-core test-compile exec:exec@mock                      (localhost:18080)
 *   mvn -pl gatling-api-core test-compile exec:exec@mock -Dperf.args="18080 0.0.0.0"
 * As an image for a cluster:  mvn -pl gatling-api-core -Pmock-image test-compile jib:dockerBuild
 */
object MockApiMain {
  def main(args: Array[String]): Unit = {
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(18080)
    val host = args.lift(1).getOrElse("localhost")
    MockApiServer.start(port, host)
    sys.addShutdownHook(MockApiServer.stop())
    Thread.currentThread().join()
  }
}
