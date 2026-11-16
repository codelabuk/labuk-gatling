package org.codelabuk.perf.it

/**
 * Runs the mock API on its own (until Ctrl+C), e.g. to try the `perf` distribution or the Docker image:
 *   mvn -pl gatling-api-core test-compile exec:exec@mock          (port 18080, or -Dperf.args=<port>)
 */
object MockApiMain {
  def main(args: Array[String]): Unit = {
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(18080)
    MockApiServer.start(port)
    sys.addShutdownHook(MockApiServer.stop())
    Thread.currentThread().join()
  }
}
