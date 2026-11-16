package org.codelabuk.perf.load

import java.nio.file.{Path, Paths}
import java.time.{Instant, OffsetDateTime}

import scala.util.Try

import org.codelabuk.perf.data.Worker
import org.codelabuk.perf.model.{InjectionDef, LoadDef}

/**
 * How this JVM should run a plan. Every option is a system property or an environment variable:
 *
 *   mode        / MODE        run (default) | setup  - setup runs only the plan's `setup` and writes shared lists
 *   scenario    / SCENARIO    comma-separated scenario names to run (default: all)
 *   shared.out  / SHARED_OUT  setup mode: file to write the shared lists to
 *   shared.in   / SHARED_IN   run mode: take shared lists from this file instead of running setup
 *   start.at    / START_AT    wait until this moment before starting load (epoch millis or ISO-8601)
 */
final case class RunOptions(
    mode: String = "run",
    onlyScenarios: Set[String] = Set.empty,
    sharedIn: Option[Path] = None,
    sharedOut: Option[Path] = None,
    startAt: Option[Long] = None
) {
  def setupOnly: Boolean = mode == "setup"
}

object RunOptions {
  def setting(names: String*): Option[String] =
    names.iterator.flatMap(n => sys.props.get(n).orElse(sys.env.get(n))).map(_.trim).find(_.nonEmpty)

  /** Epoch millis, or an ISO-8601 instant / offset date-time such as 2026-11-20T10:00:00Z. */
  def parseInstant(text: String): Long =
    text.toLongOption
      .orElse(Try(Instant.parse(text).toEpochMilli).toOption)
      .orElse(Try(OffsetDateTime.parse(text).toInstant.toEpochMilli).toOption)
      .getOrElse(throw new PlanException(s"start.at: cannot read '$text' - use epoch milliseconds or ISO-8601 like 2026-11-20T10:00:00Z"))

  def fromSystem(): RunOptions = {
    val mode = setting("mode", "MODE").getOrElse("run").toLowerCase
    if (!Set("run", "setup")(mode)) throw new PlanException(s"mode must be 'run' or 'setup', got '$mode'")
    val opts = RunOptions(
      mode = mode,
      onlyScenarios = setting("scenario", "SCENARIO").toList.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).toSet,
      sharedIn = setting("shared.in", "SHARED_IN").map(Paths.get(_)),
      sharedOut = setting("shared.out", "SHARED_OUT").map(Paths.get(_)),
      startAt = setting("start.at", "START_AT").map(parseInstant)
    )
    if (opts.setupOnly && opts.sharedOut.isEmpty) throw new PlanException("mode=setup needs -Dshared.out=<file> to write the shared lists to")
    if (opts.setupOnly && opts.sharedIn.nonEmpty) throw new PlanException("mode=setup and shared.in cannot be combined")
    opts
  }
}

/** Load arithmetic that needs no Gatling runtime - shared by the run, the validator and warnings. */
object LoadMath {

  /** Virtual users an open profile will start on this worker; None for closed models (users recycle). */
  def plannedArrivals(load: LoadDef, w: Worker): Option[Long] =
    if (load.model == "closed") None
    else Some(load.profile.map {
      case InjectionDef.AtOnce(u)             => w.share(u).toLong
      case InjectionDef.RampUsers(u, _)       => w.share(u).toLong
      case InjectionDef.StressPeak(u, _)      => w.share(u).toLong
      case InjectionDef.ConstantRate(r, d, _) => math.round(w.share(r) * d.toMillis / 1000.0)
      case InjectionDef.RampRate(f, t, d, _)  => math.round((w.share(f) + w.share(t)) / 2 * d.toMillis / 1000.0)
      case InjectionDef.IncrementRate(by, times, level, ramp, from) =>
        val rates = (0 until times).map(i => w.share(from + i * by))
        val levels = rates.map(_ * level.toMillis / 1000.0).sum
        val ramps = rates.sliding(2).collect { case Seq(a, b) => (a + b) / 2 * ramp.toMillis / 1000.0 }.sum
        math.round(levels + ramps)
      case _ => 0L
    }.sum)

  /** Highest number of concurrent users a closed profile asks for on this worker. */
  def peakConcurrency(load: LoadDef, w: Worker): Option[Int] =
    if (load.model != "closed") None
    else Some(load.profile.map {
      case InjectionDef.ConstantConcurrent(u, _) => w.share(u)
      case InjectionDef.RampConcurrent(f, t, _)  => math.max(w.share(f), w.share(t))
      case InjectionDef.IncrementConcurrent(by, times, _, _, from) => w.share(from) + (times - 1) * w.share(by)
      case _                                     => 0
    }.maxOption.getOrElse(0))

  def describe(load: LoadDef, w: Worker): String =
    plannedArrivals(load, w).map(n => s"~$n users").orElse(peakConcurrency(load, w).map(n => s"$n concurrent")).getOrElse("")
}
