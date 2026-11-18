package org.codelabuk.perf.runner

import java.lang.reflect.InvocationTargetException
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import scala.jdk.CollectionConverters._
import scala.util.Try

import org.codelabuk.perf.cli.{Merge, Validate}
import org.codelabuk.perf.data.Worker
import org.codelabuk.perf.load.{PlanException, PlanLoader, RunOptions}
import org.codelabuk.perf.metrics.RunSummary
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
      |  perf fetch    <s3://bucket/folder> [<local dir>]                   download a report / logs from S3 or MinIO
      |  perf convert-lr <VuGen script dir> [--out <dir>] [--iterations N]   LoadRunner web script -> YAML plan
      |  perf submit   <plan.yaml> --workers N ...                          start a distributed run on Kubernetes (perf submit --help)
      |  perf status   <run> [-n namespace] [--watch]                       follow it; with --watch exit 0 Passed, 2 SLAFailed, 1 Failed
      |
      |run / worker options:
      |  --scenario a,b           run only these scenarios                    (env SCENARIO)
      |  --results <dir>          where reports go (default: results)        (env RESULTS_DIR)
      |  --workers N --worker-index i   this machine's share of a distributed run  (env WORKER_COUNT, WORKER_INDEX)
      |  --start-at <time>        wait until then: epoch ms or 2026-11-20T10:00:00Z  (env START_AT)
      |  --shared-in <file>       skip setup, use lists saved by `perf setup` (env SHARED_IN)
      |  --no-reports             only write simulation.log (workers)
      |  --metrics-port <port>    live Prometheus metrics on http://<host>:<port>/metrics while running (env METRICS_PORT)
      |  --description <text>     run description shown in the report
      |  -DNAME=value             fills ${NAME} in the plan (environment variables work too)
      |
      |environment: PERF_HEAP (default 2g), PERF_JAVA_OPTS, JAVA_HOME
      |exit codes: 0 OK, 1 error or invalid plan, 2 SLAs failed""".stripMargin

  def main(args: Array[String]): Unit = {
    val code = relaunchIfSingleCpu(args).getOrElse(run(args.toList))
    System.out.flush()
    sys.exit(code)
  }

  /**
   * Gatling 3.13 deadlocks at start-up when the JVM sees 1 CPU: its actor pool has one thread per CPU and the
   * controller blocks that only thread while waiting for the result writers (crash after 5 s, then the run hangs).
   * 1-CPU containers and pods are common, so perf restarts itself once telling the JVM to see 2 CPUs. That only
   * changes thread counts - the real CPU limit (docker --cpus, Kubernetes limits) still applies.
   */
  private def relaunchIfSingleCpu(args: Array[String]): Option[Int] =
    if (Runtime.getRuntime.availableProcessors >= 2 || sys.props.contains("perf.relaunched")) None
    else {
      val javaExe = Paths.get(sys.props("java.home"), "bin", "java").toString
      val jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean.getInputArguments.asScala
        .filterNot(_.startsWith("-XX:ActiveProcessorCount")).toList
      // the class path goes through an argument file: on Windows the expanded lib/* list can exceed the command-line limit
      val cpFile = Files.createTempFile("perf-classpath", ".txt")
      Files.write(cpFile, List("-cp", "\"" + sys.props("java.class.path").replace("\\", "/") + "\"").asJava)
      cpFile.toFile.deleteOnExit()
      val cmd = (javaExe :: jvmArgs) ++ List("-XX:ActiveProcessorCount=2", "-Dperf.relaunched=true", s"@$cpFile", "org.codelabuk.perf.runner.Perf") ++ args
      System.err.println("perf: only 1 CPU visible - restarting with the JVM set to 2 CPUs (Gatling needs 2 threads; the CPU limit itself is unchanged)")
      Some(new ProcessBuilder(cmd.asJava).inheritIO().start().waitFor())
    }

  def run(args: List[String]): Int = args match {
    case Nil                                => println(Usage); 1
    case ("help" | "--help" | "-h") :: _    => println(Usage); 0
    case "validate" :: rest                 => validate(Args.parse(rest))
    case "run" :: rest                      => runPlan(Args.parse(rest))
    case "setup" :: rest                    => setup(Args.parse(rest))
    case "worker" :: rest                   => worker(Args.parse(rest))
    case "report" :: rest                   => report(rest)
    case "fetch" :: rest                    => fetch(rest)
    case "convert-lr" :: rest               => convertLr(Args.parse(rest))
    case "submit" :: rest                   => Kube.submit(rest)
    case "status" :: rest                   => Kube.status(rest)
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
    val input = a.opt("--input").getOrElse(return fail("usage: perf report --input <logs dir | s3://...> [--output <dir | s3://...>] [--name <run>]"))
    val name = a.opt("--name").getOrElse("merged")
    val s3 = Try(if (S3.isS3(input) || a.opt("--output").exists(S3.isS3)) Some(S3.fromEnv()) else None)
      .fold(e => return fail(s"perf report: ${e.getMessage}"), identity)

    // s3:// input: download the workers' logs first
    val localInput =
      if (!S3.isS3(input)) input
      else {
        val dir = Files.createTempDirectory("perf-report-logs")
        val n = Try(S3.downloadAll(s3.get, input, dir, ".log")).fold(e => return fail(s"perf report: ${e.getMessage}"), identity)
        if (n == 0) return fail(s"perf report: no .log files under $input")
        println(s"downloaded $n worker log(s) from $input")
        dir.toString
      }
    // s3:// output: build locally, upload afterwards
    val output = a.opt("--output")
    val localOutput = output.map(o => if (S3.isS3(o)) Files.createTempDirectory("perf-report-out").toString else o)

    val code = Merge.run(List("--input", localInput, "--name", name) ++ localOutput.toList.flatMap(o => List("--output", o)) ++
      a.passThrough("--description") ++ (if (a.has("--no-report")) List("--no-report") else Nil))
    val built = code == 0 || code == 2

    // final numbers of the whole run as JSON (summary.json next to the report folder) - read by the perf-dashboard
    val localOutDir = localOutput.map(Paths.get(_)).getOrElse { val in = Paths.get(localInput); in.resolveSibling(in.getFileName.toString + "-merged") }
    val mergedLog = localOutDir.resolve(name).resolve("simulation.log")
    val summary =
      if (!built || !Files.exists(mergedLog)) None
      else {
        val workers = Try(Files.list(Paths.get(localInput)).iterator().asScala.count(_.getFileName.toString.endsWith(".log"))).getOrElse(0)
        Try(RunSummary.write(RunSummary.fromLog(mergedLog, Map("workers" -> workers, "slaPassed" -> (code == 0))),
          localOutDir.resolve("summary.json"))).toOption
      }

    val reportPath = output match {
      case Some(o) if S3.isS3(o) =>
        if (built) {
          val n = Try(S3.uploadDir(s3.get, Paths.get(localOutput.get).resolve(name), s"$o/$name")).fold(e => return fail(s"perf report: ${e.getMessage}"), identity)
          summary.foreach(s => Try(S3.upload(s3.get, s, s"$o/summary.json")))
          println(s"uploaded the report ($n files) to $o/$name/")
        }
        s"$o/$name/index.html"
      case Some(o) => Paths.get(o).resolve(name).resolve("index.html").toString
      case None =>
        val in = Paths.get(localInput)
        in.resolveSibling(in.getFileName.toString + "-merged").resolve(name).resolve("index.html").toString
    }

    a.opt("--facts-file") match {
      case None => code
      case Some(f) =>
        Facts.write(f, Map("reportBuilt" -> built, "slaPassed" -> (code == 0), "exitCode" -> code, "report" -> reportPath))
        if (built) 0 else 1
    }
  }

  /** Downloads everything under an s3:// folder, keeping the folder structure (e.g. a report to open locally). */
  private def fetch(rest: List[String]): Int = {
    val a = Args.parse(rest)
    val url = a.positional.headOption.filter(S3.isS3).getOrElse(return fail("usage: perf fetch <s3://bucket/folder> [<local dir>] [--snapshot <s3://bucket/folder>]"))
    val snapshot = a.opt("--snapshot")
    if (snapshot.exists(s => !S3.isS3(s))) return fail("perf fetch: --snapshot must be an s3:// folder")
    val (bucket, prefix) = S3.parse(url)
    val target = Paths.get(a.positional.lift(1).getOrElse(prefix.split('/').filter(_.nonEmpty).lastOption.getOrElse(bucket)))
    Try {
      val s3 = S3.fromEnv()
      val keys = s3.list(bucket, if (prefix.isEmpty) "" else prefix + "/").filterNot(_.endsWith("/")) // skip folder markers
      keys.foreach(k => S3.download(s3, s"s3://$bucket/$k", target.resolve(k.stripPrefix(prefix).stripPrefix("/"))))
      // --snapshot: keep a copy of exactly these files elsewhere (the operator uses it to freeze a run's tests)
      if (keys.nonEmpty) snapshot.foreach { s => S3.uploadDir(s3, target, s); println(s"copied ${keys.size} file(s) to $s") }
      keys.size
    }.fold(e => fail(s"perf fetch: ${e.getMessage}"), n =>
      if (n == 0) fail(s"perf fetch: nothing under $url")
      else {
        println(s"downloaded $n file(s) to ${target.toAbsolutePath}")
        Option(target.resolve("index.html")).filter(Files.exists(_)).foreach(i => println(s"open ${i.toAbsolutePath}"))
        0
      })
  }

  /** LoadRunner VuGen web script -> YAML plan + API catalog + CSV data + conversion report. */
  private def convertLr(a: Args): Int = {
    val script = Paths.get(a.positional.headOption.getOrElse(return fail("usage: perf convert-lr <VuGen script dir> [--out <dir>] [--iterations N]")))
    if (!Files.exists(script)) return fail(s"perf convert-lr: not found: $script")
    val name = (if (Files.isDirectory(script)) script.toAbsolutePath.normalize().getFileName.toString else "script").replaceAll("[^A-Za-z0-9_-]", "-")
    val out = Paths.get(a.opt("--out").getOrElse(s"$name-perf"))
    val iterations = a.opt("--iterations").flatMap(_.toIntOption).getOrElse(1)
    Try(lr.VuGenConverter.convert(script, iterations)).fold(e => fail(s"perf convert-lr: ${e.getMessage}"), { r =>
      val plan = lr.VuGenConverter.write(r, name, out)
      println(s"converted ${r.requests} request(s) -> $plan")
      if (r.report.nonEmpty) {
        println(s"${r.report.size} note(s) - also in ${out.resolve(s"$name-conversion.txt")}:")
        r.report.foreach(n => println(s"  - $n"))
      }
      println(s"next: set `load` in the plan, then  perf validate $plan  and  perf run $plan")
      0
    })
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
    val localOut = if (S3.isS3(out)) Files.createTempDirectory("perf-setup-out").resolve("shared.json").toString else out
    sys.props ++= Map("plan" -> plan, "mode" -> "setup", "shared.out" -> localOut)
    if (!checkPlan(plan)) return 1
    val tmp = Files.createTempDirectory("perf-setup")
    val code = gatling(tmp, noReports = true, None)
    if (code == 0) {
      if (S3.isS3(out)) Try(S3.upload(S3.fromEnv(), Paths.get(localOut), out)).fold(e => return fail(s"perf setup: ${e.getMessage}"), identity)
      println(s"Shared lists saved to ${if (S3.isS3(out)) out else Paths.get(out).toAbsolutePath}")
    }
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
    val w = Worker.current
    val results = a.opt("--results").orElse(env("RESULTS_DIR")).getOrElse("results")
    val s3 = Try(if (S3.isS3(results) || RunOptions.setting("shared.in", "SHARED_IN").exists(S3.isS3)) Some(S3.fromEnv()) else None)
      .fold(e => return fail(s"perf worker: ${e.getMessage}"), identity)
    // shared lists from s3:// are fetched to local disk first
    RunOptions.setting("shared.in", "SHARED_IN").filter(S3.isS3).foreach { url =>
      val local = Files.createTempDirectory("perf-shared").resolve("shared.json")
      Try(S3.download(s3.get, url, local)).fold(e => return fail(s"perf worker: ${e.getMessage}"), identity)
      sys.props("shared.in") = local.toString
    }
    if (!checkPlan(plan)) return 1
    // Gatling's own output stays on local disk; only the final log goes to the (shared) results folder,
    // so `perf report` never sees partial or duplicate logs
    val runDir = Files.createTempDirectory(s"perf-worker-${w.index}-")
    val code = gatling(runDir, noReports = true, a.opt("--description"))
    val log = findLog(runDir)
    log.foreach { l =>
      // final numbers of this worker as JSON next to its log (read by the perf-dashboard after the pod is gone)
      val summary = Try(RunSummary.write(RunSummary.fromLog(l, Map("worker" -> w.index, "workers" -> w.count)),
        Files.createTempDirectory("perf-summary").resolve(s"worker-${w.index}.json"))).toOption
      if (S3.isS3(results)) {
        val target = s"${results.stripSuffix("/")}/worker-${w.index}.log"
        Try {
          S3.upload(s3.get, l, target)
          summary.foreach(s => S3.upload(s3.get, s, s"${results.stripSuffix("/")}/worker-${w.index}.json"))
        }.fold(e => return fail(s"perf worker: ${e.getMessage}"), identity)
        println(s"Worker ${w.index + 1}/${w.count}: results in $target")
      } else {
        val dir = Paths.get(results)
        val target = dir.resolve(s"worker-${w.index}.log")
        Files.createDirectories(dir)
        Files.copy(l, target, StandardCopyOption.REPLACE_EXISTING)
        summary.foreach(s => Files.copy(s, dir.resolve(s"worker-${w.index}.json"), StandardCopyOption.REPLACE_EXISTING))
        println(s"Worker ${w.index + 1}/${w.count}: results in $target")
      }
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
    a.opt("--metrics-port").foreach(sys.props("metrics.port") = _)
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
    // Gatling sends anonymous usage analytics by default - a company test tool sends nothing unless asked to
    if (!sys.props.contains("gatling.data.enableAnalytics")) sys.props("gatling.data.enableAnalytics") = "false"
    // live metrics follow the log of this run
    sys.props("metrics.results") = results.toAbsolutePath.toString
    if (RunOptions.setting("metrics.port", "METRICS_PORT").isDefined && !sys.props.contains("gatling.data.file.bufferSize"))
      sys.props("gatling.data.file.bufferSize") = "4096" // smaller write chunks = fresher live metrics
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
