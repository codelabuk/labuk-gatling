package org.codelabuk.perf.metrics

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

import com.sun.net.httpserver.HttpServer
import com.typesafe.scalalogging.StrictLogging

/**
 * Live counters built from the simulation.log records, rendered in Prometheus text format.
 * Because they come from the same records as the HTML report, the totals match the report exactly.
 *
 *   perf_requests_total{request,status}            requests (API calls, SQL steps, poll results) by OK/KO
 *   perf_response_time_ms_bucket{request,le}        response time histogram (p95 etc. via histogram_quantile)
 *   perf_transactions_total{transaction,status}      transactions (groups) by OK/KO
 *   perf_transaction_duration_ms_bucket{...}         transaction duration histogram
 *   perf_users_active{scenario} / perf_users_started_total{scenario}
 *   perf_errors_total{message}                       error messages (top 50, the rest as "other")
 * Every series also carries plan and worker labels, so a dashboard can sum across workers.
 */
final class LiveMetrics(plan: String, worker: String) {

  val Buckets: Vector[Double] = Vector(5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000).map(_.toDouble)
  private val MaxErrors = 50

  private final class Histogram {
    val counts = new Array[Long](Buckets.size)
    var count = 0L
    var sum = 0.0
    def add(ms: Double): Unit = {
      count += 1; sum += ms
      var i = 0
      while (i < Buckets.size) { if (ms <= Buckets(i)) counts(i) += 1; i += 1 }
    }
  }

  private val requests = mutable.LinkedHashMap.empty[(String, String), Long]
  private val requestTimes = mutable.LinkedHashMap.empty[String, Histogram]
  private val transactions = mutable.LinkedHashMap.empty[(String, String), Long]
  private val transactionTimes = mutable.LinkedHashMap.empty[String, Histogram]
  private val usersActive = mutable.LinkedHashMap.empty[String, Long]
  private val usersStarted = mutable.LinkedHashMap.empty[String, Long]
  private val errors = mutable.LinkedHashMap.empty[String, Long]
  @volatile private var lastEvent = 0L

  def record(events: Seq[LogEvent]): Unit = synchronized {
    events.foreach {
      case LogEvent.Request(groups, name, s, e, ok, msg) =>
        val r = (groups :+ name).mkString(" / ")
        requests((r, status(ok))) = requests.getOrElse((r, status(ok)), 0L) + 1
        requestTimes.getOrElseUpdate(r, new Histogram).add((e - s).toDouble)
        if (!ok && msg.nonEmpty) error(msg)
        lastEvent = e
      case LogEvent.Group(groups, s, e, _, ok) =>
        val t = groups.mkString(" / ")
        transactions((t, status(ok))) = transactions.getOrElse((t, status(ok)), 0L) + 1
        transactionTimes.getOrElseUpdate(t, new Histogram).add((e - s).toDouble)
        lastEvent = e
      case LogEvent.User(sc, start, ts) =>
        usersActive(sc) = usersActive.getOrElse(sc, 0L) + (if (start) 1 else -1)
        if (start) usersStarted(sc) = usersStarted.getOrElse(sc, 0L) + 1
        lastEvent = ts
      case LogEvent.Error(msg, ts) => error(msg); lastEvent = ts
      case LogEvent.Run(_, scenarios) => scenarios.foreach(s => usersActive.getOrElseUpdate(s, 0L))
    }
  }

  private def status(ok: Boolean) = if (ok) "OK" else "KO"

  private def error(msg: String): Unit = {
    val key = if (errors.contains(msg) || errors.size < MaxErrors) msg else "other"
    errors(key) = errors.getOrElse(key, 0L) + 1
  }

  private def esc(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
  private def labels(extra: (String, String)*): String =
    (Seq("plan" -> plan, "worker" -> worker) ++ extra).map { case (k, v) => s"""$k="${esc(v)}"""" }.mkString("{", ",", "}")
  private def num(d: Double): String = if (d == d.floor && !d.isInfinite) d.toLong.toString else d.toString

  def render(): String = synchronized {
    val sb = new StringBuilder
    def counterBlock(name: String, help: String, rows: Iterable[(Seq[(String, String)], Long)], kind: String = "counter"): Unit = {
      sb ++= s"# HELP $name $help\n# TYPE $name $kind\n"
      rows.foreach { case (l, v) => sb ++= s"$name${labels(l: _*)} $v\n" }
    }
    def histogramBlock(name: String, help: String, label: String, hs: Iterable[(String, Histogram)]): Unit = {
      sb ++= s"# HELP $name $help\n# TYPE $name histogram\n"
      hs.foreach { case (key, h) =>
        Buckets.indices.foreach(i => sb ++= s"${name}_bucket${labels(label -> key, "le" -> num(Buckets(i)))} ${h.counts(i)}\n")
        sb ++= s"${name}_bucket${labels(label -> key, "le" -> "+Inf")} ${h.count}\n"
        sb ++= s"${name}_sum${labels(label -> key)} ${num(h.sum)}\n"
        sb ++= s"${name}_count${labels(label -> key)} ${h.count}\n"
      }
    }
    counterBlock("perf_requests_total", "Requests by name and status (OK/KO)", requests.map { case ((r, s), v) => (Seq("request" -> r, "status" -> s), v) })
    histogramBlock("perf_response_time_ms", "Response time in milliseconds", "request", requestTimes)
    counterBlock("perf_transactions_total", "Transactions by name and status", transactions.map { case ((t, s), v) => (Seq("transaction" -> t, "status" -> s), v) })
    histogramBlock("perf_transaction_duration_ms", "Transaction duration in milliseconds (including waits)", "transaction", transactionTimes)
    counterBlock("perf_users_active", "Virtual users running now", usersActive.map { case (s, v) => (Seq("scenario" -> s), v) }, "gauge")
    counterBlock("perf_users_started_total", "Virtual users started", usersStarted.map { case (s, v) => (Seq("scenario" -> s), v) })
    counterBlock("perf_errors_total", "Errors by message", errors.map { case (m, v) => (Seq("message" -> m), v) })
    sb ++= s"# HELP perf_last_event_timestamp_seconds Time of the newest record seen\n# TYPE perf_last_event_timestamp_seconds gauge\n"
    sb ++= s"perf_last_event_timestamp_seconds${labels()} ${lastEvent / 1000.0}\n"
    sb.result()
  }
}

/**
 * Serves [[LiveMetrics]] on http://<host>:<port>/metrics and keeps them current by following the run's
 * simulation.log. Started by YamlSimulation when metrics.port / METRICS_PORT is set.
 */
final class LiveMetricsService(port: Int, resultsDir: Path, plan: String, worker: String, startedAfter: Long) extends StrictLogging {

  val metrics = new LiveMetrics(plan, worker)
  private val server = HttpServer.create(new InetSocketAddress(port), 16)
  private val poller = Executors.newSingleThreadScheduledExecutor { r => val t = new Thread(r, "perf-metrics"); t.setDaemon(true); t }
  @volatile private var reader: Option[LiveLogReader] = None

  def start(): Unit = {
    server.createContext("/metrics", ex => {
      val body = metrics.render().getBytes(StandardCharsets.UTF_8)
      ex.getResponseHeaders.set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
      ex.sendResponseHeaders(200, body.length.toLong)
      ex.getResponseBody.write(body)
      ex.close()
    })
    server.setExecutor(Executors.newSingleThreadExecutor { r => val t = new Thread(r, "perf-metrics-http"); t.setDaemon(true); t })
    server.start()
    poller.scheduleWithFixedDelay(() => pollOnce(), 0, 1, TimeUnit.SECONDS)
    logger.info(s"live metrics on http://localhost:$port/metrics (following the run log under $resultsDir)")
  }

  private def pollOnce(): Unit =
    try {
      if (reader.isEmpty) reader = findLog().map(new LiveLogReader(_))
      reader.foreach(r => metrics.record(r.poll()))
    } catch { case NonFatal(e) => logger.warn(s"live metrics: ${e.getMessage}") }

  /** This run's log: the newest simulation.log under the results folder created after the run started. */
  private def findLog(): Option[Path] =
    if (!Files.isDirectory(resultsDir)) None
    else {
      val s = Files.walk(resultsDir, 3)
      try s.iterator().asScala
        .filter(p => p.getFileName.toString == "simulation.log" && Try(Files.getLastModifiedTime(p).toMillis).getOrElse(0L) >= startedAfter - 2000)
        .toList.sortBy(p => Try(Files.getLastModifiedTime(p).toMillis).getOrElse(0L)).lastOption
      finally s.close()
    }

  /** Reads the last records, then keeps serving for `linger` so a final scrape sees the end totals. */
  def stop(lingerSeconds: Int): Unit = {
    poller.shutdown()
    Try(poller.awaitTermination(5, TimeUnit.SECONDS))
    pollOnce()
    if (lingerSeconds > 0) {
      logger.info(s"live metrics: serving final values for ${lingerSeconds}s")
      Thread.sleep(lingerSeconds * 1000L)
    }
    server.stop(0)
    reader.foreach(_.close())
  }
}
