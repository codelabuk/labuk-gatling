package org.codelabuk.perf.runner

import java.lang.reflect.InvocationTargetException
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import scala.jdk.CollectionConverters._
import scala.util.Try

import org.codelabuk.perf.cli.{Merge, Validate}
import org.codelabuk.perf.data.Worker
import org.codelabuk.perf.load.{PlanException, PlanLoader, RunOptions}
import org.codelabuk.perf.sim.YamlSimulation

/**
 * The `perf` command. Exit codes: 0 = OK, 1 = error / invalid plan, 2 = SLAs failed.
 */
object Perf {

  val Usage: String =
    """perf - run YAML performance test plans
      |
      |  perf validate <plan.yaml> [--workers N] [--scenario a,b]          check a plan, no traffic sent
      |  perf run      <plan.yaml> [options] [-DNAME=value ...]            run it on this machine
      |  perf setup    <plan.yaml> --out shared.json                        run only `setup`, save its shared lists
      |  perf worker   [options]                                            one worker of a distributed run (settings from env)
      |  perf report   --input <logs-dir> [--output <dir>] [--name <run>]   merge workers' logs -> one report + SLA verdict
      |
      |run / worker options:
      |  --scenario a,b           run only these scenarios                    (env SCENARIO)
      |  --results <dir>          where reports go (default: results)        (env RESULTS_DIR)
      |  --workers N --worker-index i   this machine's share of a distributed run  (env WORKER_COUNT, WORKER_INDEX)
      |  --start-at <time>        wait until then: epoch ms or 2026-11-20T10:00:00Z  (env START_AT)
      |  --shared-in <file>       skip setup, use lists saved by `perf setup` (env SHARED_IN)
      |  --no-reports             only write simulation.log (workers)
      |  --description <text>     run description shown in the report
      |  -DNAME=value             fills ${NAME} in the plan (environment variables work too)
      |
      |environment: PERF_HEAP (default 2g), PERF_JAVA_OPTS, JAVA_HOME
      |exit codes: 0 OK, 1 error or invalid plan, 2 SLAs failed""".stripMargin

  def main(args: Array[String]): Unit = {
    val code = run(args.toList)
    System.out.flush()
    sys.exit(code)
  }

  def run(args: List[String]): Int = args match {
    case Nil                                => println(Usage); 1
    case ("help" | "--help" | "-h") :: _    => println(Usage); 0
    case "validate" :: rest                 => validate(Args.parse(rest))
    case "run" :: rest                      => runPlan(Args.parse(rest))
    case "setup" :: rest                    => setup(Args.parse(rest))
    case "worker" :: rest                   => worker(Args.parse(rest))
    case "report" :: rest                   => report(rest)
    case other :: _ =>
      System.err.println(s"perf: unknown command '$other'\n"); System.err.println(Usage); 1
  }

  // ------------------------------------------------------------------ commands

  private def validate(a: Args): Int = {
    a.applyProps()
    val r = Validate.run(a.positional ++ a.passThrough("--workers", "--scenario"))
    (if (r.ok) System.out else System.err).println(r.report)
    // --facts-file: a small JSON for automation (the operator reads it from /dev/termination-log)
    a.opt("--facts-file").foreach { f =>
      val facts =
        if (!r.ok) Map("valid" -> false, "message" -> r.report.stripPrefix("INVALID: "))
        else {
          val plan = PlanLoader.load(Paths.get(a.positional.head))
          Map("valid" -> true, "plan" -> plan.name, "hasSetup" -> plan.setup.exists(_.share.nonEmpty), "scenarios" -> plan.scenarios.map(_.name))
        }
      Facts.write(f, facts)
    }
    if (r.ok) 0 else 1
  }

  /**
   * Merges workers' logs. With --facts-file the SLA verdict goes into the file and the exit code is 0 whenever a
   * report was built (so a Kubernetes Job succeeds); without it: 0 OK, 2 SLAs failed, 1 error.
   */
  private def report(rest: List[String]): Int = {
    val a = Args.parse(rest)
    a.opt("--facts-file") match {
      case None => Merge.run(rest)
      case Some(f) =>
        val name = a.opt("--name").getOrElse("merged")
        val withoutFacts = rest.patch(rest.indexOf("--facts-file"), Nil, 2)
        val code = Merge.run(if (a.has("--name")) withoutFacts else withoutFacts ++ List("--name", name))
        val output = a.opt("--output").map(Paths.get(_)).getOrElse {
          val in = Paths.get(a.opt("--input").getOrElse("."))
          in.resolveSibling(in.getFileName.toString + "-merged")
        }
        Facts.write(f, Map(
          "reportBuilt" -> (code == 0 || code == 2),
          "slaPassed" -> (code == 0),
          "exitCode" -> code,
          "report" -> output.resolve(name).resolve("index.html").toString
        ))
        if (code == 0 || code == 2) 0 else 1
    }
  }

  private def runPlan(a: Args): Int = {
    val plan = a.positional.headOption.getOrElse(return fail("perf run: which plan? e.g. perf run plans/orders.yaml"))
    a.applyProps()
    setPlanProps(plan, a)
    if (!checkPlan(plan)) return 1
    val code = gatling(Paths.get(a.opt("--results").getOrElse(env("RESULTS_DIR").getOrElse("results"))), a.has("--no-reports"), a.opt("--description"))
    if (code == 2) println("RESULT: SLAs FAILED") else if (code == 0) println("RESULT: OK")
    code
  }

  private def setup(a: Args): Int = {
    val plan = a.positional.headOption.getOrElse(return fail("perf setup: which plan?"))
    val out = a.opt("--out").getOrElse(return fail("perf setup: --out <file> is required (where to save the shared lists)"))
    a.applyProps()
    sys.props ++= Map("plan" -> plan, "mode" -> "setup", "shared.out" -> out)
    if (!checkPlan(plan)) return 1
    val tmp = Files.createTempDirectory("perf-setup")
    val code = gatling(tmp, noReports = true, None)
    if (code == 0) println(s"Shared lists saved to ${Paths.get(out).toAbsolutePath}")
    code
  }

  /**
   * One worker of a distributed run - the container entrypoint. Settings come from the environment
   * (PLAN, WORKER_INDEX or JOB_COMPLETION_INDEX, WORKER_COUNT, START_AT, SHARED_IN, SCENARIO, RESULTS_DIR),
   * CLI options override them. Writes RESULTS_DIR/worker-<index>.log for `perf report`.
   * A worker's own SLA result is informational (exit 0); only `perf report` judges SLAs.
   */
  private def worker(a: Args): Int = {
    val plan = a.positional.headOption.orElse(env("PLAN")).getOrElse(return fail("perf worker: set PLAN (or pass the plan path)"))
    a.applyProps()
    setPlanProps(plan, a)
    if (!checkPlan(plan)) return 1
    val w = Worker.current
    val results = Paths.get(a.opt("--results").orElse(env("RESULTS_DIR")).getOrElse("results"))
    // Gatling's own output stays on local disk; only the final log goes to the (shared) results folder,
    // so `perf report` never sees partial or duplicate logs
    val runDir = Files.createTempDirectory(s"perf-worker-${w.index}-")
    val code = gatling(runDir, noReports = true, a.opt("--description"))
    val log = findLog(runDir)
    log.foreach { l =>
      val target = results.resolve(s"worker-${w.index}.log")
      Files.createDirectories(results)
      Files.copy(l, target, StandardCopyOption.REPLACE_EXISTING)
      println(s"Worker ${w.index + 1}/${w.count}: results in $target")
    }
    code match {
      case 2 => println("NOTE: this worker's SLA check covers its share only - `perf report` decides."); 0
      case 0 if log.isEmpty => fail("worker finished but no simulation.log was written")
      case c => c
    }
  }

  // ------------------------------------------------------------------ helpers

  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  private def fail(msg: String): Int = { System.err.println(msg); 1 }

  /** Plan settings as system properties - read by YamlSimulation / RunOptions / Worker. */
  private def setPlanProps(plan: String, a: Args): Unit = {
    sys.props("plan") = plan
    a.opt("--scenario").foreach(sys.props("scenario") = _)
    a.opt("--start-at").foreach(sys.props("start.at") = _)
    a.opt("--shared-in").foreach(sys.props("shared.in") = _)
    a.opt("--workers").foreach(sys.props("worker.count") = _)
    a.opt("--worker-index").foreach(sys.props("worker.index") = _)
    if (a.has("--workers") && !a.has("--worker-index") && RunOptions.setting("worker.index", "WORKER_INDEX", "JOB_COMPLETION_INDEX").isEmpty)
      sys.props("worker.index") = "0"
  }

  /** Loads the plan up front so a mistake is one clear line, not a Gatling stack trace. */
  private def checkPlan(plan: String): Boolean =
    try { PlanLoader.load(Paths.get(plan)); RunOptions.fromSystem(); true }
    catch { case e: PlanException => System.err.println(s"INVALID: ${e.getMessage}"); false }

  /** Runs Gatling in this JVM with the generic simulation; returns its exit code (2 = SLAs failed). */
  private def gatling(results: Path, noReports: Boolean, description: Option[String]): Int = {
    Files.createDirectories(results)
    com.typesafe.config.ConfigFactory.invalidateCaches() // pick up -Dgatling.* settings made above
    val args = List("--simulation", classOf[YamlSimulation].getName, "--results-folder", results.toAbsolutePath.toString) ++
      (if (noReports) List("--no-reports") else Nil) ++
      description.toList.flatMap(d => List("--run-description", d))
    try {
      val module = Class.forName("io.gatling.app.Gatling$").getField("MODULE$").get(null)
      module.getClass.getMethod("fromArgs", classOf[Array[String]]).invoke(module, args.toArray).asInstanceOf[Int]
    } catch {
      case e: InvocationTargetException =>
        val root = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).toList.last
        System.err.println(s"ERROR: ${root.getMessage}")
        1
    }
  }

  private def findLog(dir: Path): Option[Path] =
    if (!Files.isDirectory(dir)) None
    else {
      val s = Files.walk(dir)
      try s.iterator().asScala.filter(_.getFileName.toString == "simulation.log").toList
        .sortBy(p => Try(Files.getLastModifiedTime(p).toMillis).getOrElse(0L)).lastOption
      finally s.close()
    }
}

/** Small JSON facts for automation. Kept under 4 KB - the limit of a Kubernetes termination message. */
object Facts {
  private val json = new com.fasterxml.jackson.databind.ObjectMapper()

  def write(file: String, facts: Map[String, Any]): Unit = {
    def javaValue(v: Any): Any = v match {
      case s: Seq[_] => s.map(javaValue).asJava
      case s: String if s.length > 1500 => s.take(1500) + "..."
      case other => other
    }
    val text = json.writeValueAsString(facts.map { case (k, v) => k -> javaValue(v) }.asJava)
    val path = Paths.get(file)
    Option(path.toAbsolutePath.getParent).filter(p => !Files.exists(p)).foreach(Files.createDirectories(_))
    Files.write(path, text.take(4000).getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}

/** Command-line arguments: positional values, `--option value`, `--switch`, and `-DNAME=value`. */
final case class Args(positional: List[String], options: Map[String, String], switches: Set[String], props: Map[String, String]) {
  def opt(name: String): Option[String] = options.get(name)
  def has(name: String): Boolean = switches(name) || options.contains(name)
  def applyProps(): Unit = sys.props ++= props
  def passThrough(names: String*): List[String] = names.toList.flatMap(n => options.get(n).toList.flatMap(v => List(n, v)))
}

object Args {
  private val Switches = Set("--no-reports")

  def parse(args: List[String]): Args = {
    @annotation.tailrec
    def loop(rest: List[String], acc: Args): Args = rest match {
      case Nil => acc
      case d :: tail if d.startsWith("-D") && d.contains("=") =>
        val (k, v) = d.drop(2).span(_ != '=')
        loop(tail, acc.copy(props = acc.props + (k -> v.drop(1))))
      case s :: tail if Switches(s) => loop(tail, acc.copy(switches = acc.switches + s))
      case o :: v :: tail if o.startsWith("--") && !v.startsWith("--") => loop(tail, acc.copy(options = acc.options + (o -> v)))
      case o :: tail if o.startsWith("--") => loop(tail, acc.copy(switches = acc.switches + o))
      case p :: tail => loop(tail, acc.copy(positional = acc.positional :+ p))
    }
    loop(args, Args(Nil, Map.empty, Set.empty, Map.empty))
  }
}
