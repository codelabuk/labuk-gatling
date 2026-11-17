package org.codelabuk.perf.metrics

import java.nio.file.{Files, Path}

import scala.collection.mutable

import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}

/**
 * Final numbers of a run (or of one worker) computed exactly from its simulation.log, as small JSON - so tools
 * that cannot read Gatling's binary log (the perf-dashboard) can show results after the pods are gone.
 */
object RunSummary {

  final case class Stats(count: Long, ok: Long, ko: Long, min: Long, mean: Long, p50: Long, p75: Long, p95: Long, p99: Long, max: Long)

  /**
   * Computed exactly like Gatling's HTML report (GeneralStatsBuffer in gatling-charts 3.13): a t-digest with
   * compression 100, values added in log order, percentiles rounded; mean rounded. So the dashboard and the
   * report always show the same numbers.
   */
  private def stats(durations: Seq[Long], ok: Long): Stats =
    if (durations.isEmpty) Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    else {
      val digest = new com.tdunning.math.stats.AVLTreeDigest(100.0)
      durations.foreach(d => digest.add(d.toDouble))
      def p(rank: Double): Long = math.round(digest.quantile(rank / 100.0))
      Stats(durations.size, ok, durations.size - ok, digest.quantile(0).toLong, math.round(durations.sum.toDouble / durations.size),
        p(50), p(75), p(95), p(99), digest.quantile(1).toLong)
    }

  /** Summary of a complete log, as a JSON-ready map. */
  def fromLog(log: Path, extra: Map[String, Any] = Map.empty): java.util.Map[String, Any] = {
    val reader = new LiveLogReader(log)
    val events = try reader.poll() finally reader.close()

    val reqDurations = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Long]]
    val reqOk = mutable.Map.empty[String, Long].withDefaultValue(0L)
    val txDurations = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Long]]
    val txOk = mutable.Map.empty[String, Long].withDefaultValue(0L)
    val errors = mutable.LinkedHashMap.empty[String, Long]
    val allDurations = mutable.ArrayBuffer.empty[Long]
    var allOk = 0L
    var users = 0L
    var start = Long.MaxValue
    var end = 0L

    events.foreach {
      case LogEvent.Run(s, _) => start = math.min(start, s)
      case LogEvent.User(_, isStart, ts) => if (isStart) users += 1; end = math.max(end, ts)
      case LogEvent.Request(groups, name, s, e, ok, msg) =>
        val key = (groups :+ name).mkString(" / ")
        reqDurations.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += (e - s)
        allDurations += (e - s)
        if (ok) { reqOk(key) += 1; allOk += 1 } else if (msg.nonEmpty) errors(msg) = errors.getOrElse(msg, 0L) + 1
        end = math.max(end, e)
      case LogEvent.Group(groups, s, e, _, ok) =>
        val key = groups.mkString(" / ")
        txDurations.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += (e - s)
        if (ok) txOk(key) += 1
        end = math.max(end, e)
      case LogEvent.Error(msg, _) => errors(msg) = errors.getOrElse(msg, 0L) + 1
    }

    def statsMap(st: Stats): java.util.Map[String, Any] = {
      val m = new java.util.LinkedHashMap[String, Any]()
      m.put("count", st.count); m.put("ok", st.ok); m.put("ko", st.ko); m.put("min", st.min); m.put("mean", st.mean)
      m.put("p50", st.p50); m.put("p75", st.p75); m.put("p95", st.p95); m.put("p99", st.p99); m.put("max", st.max)
      m
    }
    def named(src: mutable.LinkedHashMap[String, mutable.ArrayBuffer[Long]], okCounts: collection.Map[String, Long]): java.util.List[Any] = {
      val list = new java.util.ArrayList[Any]()
      src.foreach { case (k, d) => val m = statsMap(stats(d.toSeq, okCounts(k))); m.put("name", k); list.add(m) }
      list
    }

    val durationMs = if (start == Long.MaxValue || end <= start) 0L else end - start
    val total = stats(allDurations.toSeq, allOk)
    val out = new java.util.LinkedHashMap[String, Any]()
    extra.foreach { case (k, v) => out.put(k, v) }
    out.put("start", if (start == Long.MaxValue) null else java.time.Instant.ofEpochMilli(start).toString)
    out.put("durationSeconds", durationMs / 1000.0)
    out.put("users", users)
    out.put("total", statsMap(total))
    out.put("requestsPerSecond", if (durationMs == 0) 0.0 else math.round(total.count * 100000.0 / durationMs) / 100.0)
    out.put("errorPercent", if (total.count == 0) 0.0 else math.round(total.ko * 10000.0 / total.count) / 100.0)
    out.put("requests", named(reqDurations, reqOk))
    out.put("transactions", named(txDurations, txOk))
    val errs = new java.util.ArrayList[Any]()
    errors.toList.sortBy(-_._2).take(20).foreach { case (m, c) => val e = new java.util.LinkedHashMap[String, Any](); e.put("message", m); e.put("count", c); errs.add(e) }
    out.put("errors", errs)
    out
  }

  private val json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

  def write(summary: java.util.Map[String, Any], file: Path): Path = {
    Option(file.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    json.writeValue(file.toFile, summary)
    file
  }
}
