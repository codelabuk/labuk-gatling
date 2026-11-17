package org.codelabuk.perf.runner

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * `perf submit` and `perf status`: start and follow a distributed run on Kubernetes without writing LoadTest YAML.
 * They use the tester's own `kubectl` (and its current context, or --context), so access rules stay with the cluster.
 *
 * Defaults can come from the environment so a team sets them once:
 *   PERF_IMAGE, PERF_NAMESPACE, PERF_PVC, PERF_GIT_REPO, PERF_GIT_REF, PERF_KUBE_CONTEXT
 */
object Kube {

  val SubmitUsage: String =
    """perf submit <plan.yaml> --workers N [options]
      |  tests from Git:    --git-repo <url> [--git-ref main] [--git-token-secret <secret>]   plan path relative to the repo
      |  or a local folder: --from-dir <dir>   uploaded as a ConfigMap (flat folder, 1 MB limit); plan path relative to it
      |  --image <runner image>     (env PERF_IMAGE)
      |  results on a volume:  --pvc <results claim>                                      (env PERF_PVC)
      |  or in S3 / MinIO:     --s3 s3://bucket/prefix --s3-secret <secret> [--s3-endpoint http://minio:9000]
      |                        (env PERF_S3_URL, PERF_S3_SECRET, PERF_S3_ENDPOINT; the secret holds AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)
      |  --namespace <ns>           (env PERF_NAMESPACE)    --context <kube ctx>    (env PERF_KUBE_CONTEXT)
      |  --name <run name>          default: <plan>-<yyyyMMdd-HHmmss>
      |  --env NAME=value ...  --secret <name> ...  --scenario a,b  --cpu 2  --memory 4Gi  --start-delay 60  --metrics
      |  --dry-run                  print the LoadTest instead of applying it""".stripMargin

  final case class Submit(
      plan: String,
      workers: Int,
      image: String,
      results: Either[String, (String, Option[String], String)], // pvc | (s3 url, endpoint, credentials secret)
      namespace: String,
      name: String,
      gitRepo: Option[String],
      gitRef: String,
      gitTokenSecret: Option[String],
      fromDir: Option[Path],
      env: List[(String, String)],
      secrets: List[String],
      scenarios: List[String],
      cpu: Option[String],
      memory: Option[String],
      startDelay: Option[Int],
      metrics: Boolean
  )

  /** Reads `perf submit` arguments (and env defaults); Left = what is missing or wrong. */
  def parseSubmit(raw: List[String], env: String => Option[String]): Either[String, Submit] = {
    // --env and --secret may repeat, so collect them before the generic parser keeps only the last one
    def repeated(flag: String): List[String] = raw.sliding(2).collect { case List(`flag`, v) => v }.toList
    val a = Args.parse(raw)
    def opt(name: String, envName: String) = a.opt(name).orElse(env(envName)).map(_.trim).filter(_.nonEmpty)
    for {
      plan <- a.positional.headOption.toRight("which plan? perf submit plans/orders.yaml --workers 3 ...")
      workers <- a.opt("--workers").map(w => w.toIntOption.filter(_ >= 1).toRight(s"--workers must be a positive number, got '$w'"))
        .getOrElse(Right(1))
      image <- opt("--image", "PERF_IMAGE").toRight("--image (or PERF_IMAGE) is required - the perf runner image")
      results <- opt("--s3", "PERF_S3_URL") match {
        case Some(url) if !url.startsWith("s3://") => Left(s"--s3 must look like s3://bucket/prefix, got '$url'")
        case Some(url) =>
          opt("--s3-secret", "PERF_S3_SECRET").toRight("--s3-secret (or PERF_S3_SECRET) is required with --s3 - a Secret with AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY")
            .map(secret => Right((url, opt("--s3-endpoint", "PERF_S3_ENDPOINT"), secret)))
        case None =>
          opt("--pvc", "PERF_PVC").map(Left(_)).toRight("results need --pvc (or PERF_PVC) or --s3 (or PERF_S3_URL)")
      }
      source <- (opt("--git-repo", "PERF_GIT_REPO"), a.opt("--from-dir")) match {
        case (Some(_), Some(_)) => Left("use either --git-repo or --from-dir, not both")
        case (None, None)       => Left("where are the tests? --git-repo <url> or --from-dir <dir>")
        case (g, d)             => Right((g, d.map(Paths.get(_))))
      }
      envVars <- repeated("--env").foldLeft[Either[String, List[(String, String)]]](Right(Nil)) {
        case (Right(acc), kv) =>
          kv.split("=", 2) match {
            case Array(k, v) if k.nonEmpty => Right(acc :+ (k -> v))
            case _                         => Left(s"--env expects NAME=value, got '$kv'")
          }
        case (left, _) => left
      }
      _ <- source._2.filterNot(Files.isDirectory(_)).map(d => s"--from-dir: not a folder: $d").toLeft(())
    } yield {
      val stamp = java.time.LocalDateTime.now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      val base = Paths.get(plan).getFileName.toString.stripSuffix(".yaml").stripSuffix(".yml").toLowerCase.replaceAll("[^a-z0-9-]", "-")
      Submit(
        plan = plan,
        workers = workers,
        image = image,
        results = results,
        namespace = opt("--namespace", "PERF_NAMESPACE").getOrElse("perf"),
        name = a.opt("--name").getOrElse(s"${base.take(40)}-$stamp"),
        gitRepo = source._1,
        gitRef = opt("--git-ref", "PERF_GIT_REF").getOrElse("main"),
        gitTokenSecret = a.opt("--git-token-secret"),
        fromDir = source._2,
        env = envVars,
        secrets = repeated("--secret"),
        scenarios = a.opt("--scenario").toList.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty),
        cpu = a.opt("--cpu"),
        memory = a.opt("--memory"),
        startDelay = a.opt("--start-delay").flatMap(_.toIntOption),
        metrics = a.has("--metrics")
      )
    }
  }

  private def q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  def configMapName(s: Submit): String = s"${s.name.take(50)}-tests"

  /** The LoadTest resource for a submit. */
  def loadTestYaml(s: Submit): String = {
    val sb = new StringBuilder
    sb ++= "apiVersion: perf.codelabuk.org/v1alpha1\nkind: LoadTest\n"
    sb ++= s"metadata:\n  name: ${s.name}\n  namespace: ${s.namespace}\n"
    sb ++= s"spec:\n  image: ${q(s.image)}\n  workers: ${s.workers}\n  plan:\n    file: ${q(s.fromDir.fold(s.plan)(_ => Paths.get(s.plan).getFileName.toString))}\n"
    s.gitRepo.foreach { repo =>
      sb ++= s"    git:\n      repo: ${q(repo)}\n      ref: ${q(s.gitRef)}\n"
      s.gitTokenSecret.foreach(t => sb ++= s"      tokenSecret: { name: ${q(t)} }\n")
    }
    s.fromDir.foreach(_ => sb ++= s"    configMap: ${q(configMapName(s))}\n")
    if (s.scenarios.nonEmpty) sb ++= s"  scenarios: [ ${s.scenarios.map(q).mkString(", ")} ]\n"
    if (s.env.nonEmpty) { sb ++= "  env:\n"; s.env.foreach { case (k, v) => sb ++= s"    $k: ${q(v)}\n" } }
    if (s.secrets.nonEmpty) sb ++= s"  secretRefs: [ ${s.secrets.map(q).mkString(", ")} ]\n"
    if (s.cpu.isDefined || s.memory.isDefined) {
      val limits = (s.cpu.map(c => s"cpu: ${q(c)}") ++ s.memory.map(m => s"memory: ${q(m)}")).mkString(", ")
      sb ++= s"  resources: { requests: { $limits }, limits: { $limits } }\n"
    }
    s.results match {
      case Left(pvc) => sb ++= s"  results: { pvc: ${q(pvc)} }\n"
      case Right((url, endpoint, secret)) =>
        sb ++= s"  results:\n    s3: { url: ${q(url)}, credentialsSecret: ${q(secret)}${endpoint.map(e => s", endpoint: ${q(e)}").getOrElse("")} }\n"
    }
    s.startDelay.foreach(d => sb ++= s"  startDelaySeconds: $d\n")
    if (s.metrics) sb ++= "  metrics: { enabled: true }\n"
    sb.result()
  }

  // ------------------------------------------------------------------ kubectl

  final case class Result(code: Int, out: String)

  def kubectl(context: Option[String], args: Seq[String], stdin: Option[String] = None): Result = {
    val cmd = Seq("kubectl") ++ context.toSeq.flatMap(c => Seq("--context", c)) ++ args
    val p = Try(new ProcessBuilder(cmd.asJava).redirectErrorStream(true).start())
      .getOrElse(return Result(127, "kubectl not found - install it and make sure it is on the PATH"))
    stdin.foreach { s => p.getOutputStream.write(s.getBytes(StandardCharsets.UTF_8)) }
    p.getOutputStream.close()
    val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    Result(p.waitFor(), out.trim)
  }

  def submit(raw: List[String], env: String => Option[String] = sys.env.get): Int =
    parseSubmit(raw, env) match {
      case Left(err) => System.err.println(s"perf submit: $err\n\n$SubmitUsage"); 1
      case Right(s) =>
        val yaml = loadTestYaml(s)
        if (raw.contains("--dry-run")) { println(yaml); return 0 }
        val ctx = Args.parse(raw).opt("--context").orElse(env("PERF_KUBE_CONTEXT"))
        s.fromDir.foreach { dir =>
          val cm = kubectl(ctx, Seq("-n", s.namespace, "create", "configmap", configMapName(s), s"--from-file=${dir.toAbsolutePath}", "--dry-run=client", "-o", "yaml"))
          if (cm.code != 0) { System.err.println(s"perf submit: could not package $dir as a ConfigMap:\n${cm.out}"); return 1 }
          val applied = kubectl(ctx, Seq("apply", "-f", "-"), Some(cm.out))
          if (applied.code != 0) { System.err.println(s"perf submit: could not upload the tests:\n${applied.out}"); return 1 }
          println(s"tests from $dir uploaded as ConfigMap ${configMapName(s)}")
        }
        val r = kubectl(ctx, Seq("apply", "-f", "-"), Some(yaml))
        if (r.code != 0) { System.err.println(s"perf submit: kubectl apply failed:\n${r.out}"); 1 }
        else {
          println(r.out)
          println(s"follow it:  perf status ${s.name} -n ${s.namespace} --watch${ctx.map(c => s" --context $c").getOrElse("")}")
          0
        }
    }

  private val Fields = Seq("phase", "message", "result", "workersSucceeded", "workersFailed", "startAt", "reportPath")

  /** perf status <name> [-n ns] [--watch]; with --watch the exit code is the verdict: 0 Passed, 2 SLAFailed, 1 Failed. */
  def status(raw: List[String], env: String => Option[String] = sys.env.get): Int = {
    val a = Args.parse(raw.map { case "-n" => "--namespace"; case x => x })
    val name = a.positional.headOption.getOrElse { System.err.println("perf status <name> [-n namespace] [--watch]"); return 1 }
    val ns = a.opt("--namespace").orElse(env("PERF_NAMESPACE")).getOrElse("perf")
    val ctx = a.opt("--context").orElse(env("PERF_KUBE_CONTEXT"))
    val json = new com.fasterxml.jackson.databind.ObjectMapper()
    var last = ""
    while (true) {
      // -o json and parse here: quoting a jsonpath template does not survive Windows process arguments
      val r = kubectl(ctx, Seq("-n", ns, "get", "loadtest", name, "-o", "json"))
      if (r.code != 0) { System.err.println(r.out); return 1 }
      val st = Try(json.readTree(r.out).path("status")).getOrElse(json.createObjectNode())
      val values = Fields.map(f => f -> Option(st.get(f)).filterNot(_.isNull).map(_.asText).getOrElse("")).toMap
      val line =
        if (values("phase").isEmpty) "Pending     not picked up yet - is the loadtest-operator running?"
        else s"${values("phase").padTo(11, ' ')} ${values("message")}"
      if (line != last) { println(s"${java.time.LocalTime.now.withNano(0)}  $line"); last = line }
      values("phase") match {
        case "Completed" | "Failed" =>
          println(s"result: ${Option(values("result")).filter(_.nonEmpty).getOrElse("-")}  workers ok/failed: ${values("workersSucceeded")}/${values("workersFailed")}")
          val report = values("reportPath")
          if (report.startsWith("s3://")) println(s"report: $report\n  download: perf fetch ${report.stripSuffix("/index.html")}")
          else if (report.nonEmpty) println(s"report: $report (on the results volume)")
          return if (values("phase") == "Failed") 1 else if (values("result") == "Passed") 0 else 2
        case _ if !a.has("--watch") => return 0
        case _ => Thread.sleep(5000)
      }
    }
    1
  }
}
