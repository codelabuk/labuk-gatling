package org.codelabuk.perf.build

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.FiniteDuration

import com.typesafe.scalalogging.StrictLogging
import io.gatling.commons.stats.assertion.Assertion
import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.core.assertion.{AssertionPathParts, AssertionWithPath, AssertionWithPathAndTarget}
import io.gatling.core.controller.inject.closed.ClosedInjectionStep
import io.gatling.core.controller.inject.open.OpenInjectionStep
import io.gatling.core.controller.throttle.ThrottleStep
import io.gatling.core.structure.{ChainBuilder, PopulationBuilder, ScenarioBuilder}
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder
import org.codelabuk.perf.data._
import org.codelabuk.perf.load.{LoadMath, PlanException, RunOptions}
import org.codelabuk.perf.model._

final case class AssembledSimulation(
    populations: List[PopulationBuilder],
    protocol: HttpProtocolBuilder,
    assertions: List[Assertion],
    maxDuration: Option[FiniteDuration],
    summary: String
)

/**
 * Turns a validated plan into Gatling populations for this worker.
 *
 *  - mode=run, no shared.in : setup (if any) runs on this worker, then the scenarios
 *  - mode=run, shared.in    : setup is skipped; its lists come from the file written by mode=setup
 *  - mode=setup             : only setup runs; its full lists are written to shared.out
 */
object SimulationAssembler extends StrictLogging {

  val SetupScenarioName = "plan-setup"

  def assemble(plan: TestPlan, opts: RunOptions = RunOptions(), worker: Worker = Worker.current): AssembledSimulation = {
    SharedData.clear()
    val unknown = opts.onlyScenarios -- plan.scenarios.map(_.name)
    if (unknown.nonEmpty) throw new PlanException(s"scenario: unknown scenario(s) ${unknown.mkString(", ")} - plan has ${plan.scenarios.map(_.name).mkString(", ")}")
    if (opts.setupOnly && plan.setup.forall(_.share.isEmpty)) throw new PlanException(s"mode=setup: plan '${plan.name}' has no setup.share to produce")

    val sources = PlanData.sources(plan, worker)
    val paths = new ReportPaths
    val baseCtx = new BuildContext(plan, None, Nil, paths, sources, worker, new AtomicInteger)

    def authFor(defn: Option[AuthDef]): Option[AuthStrategy] =
      defn.map(d => AuthStrategy.of(d, (api, extract) => ChainFactory.apiCall(api, None, extract, Nil, baseCtx.withAuth(None))))

    // ---- shared lists given up front: no setup on this worker
    val preloaded = opts.sharedIn.toList.flatMap(f => PlanData.registerSharesFromFile(plan, f, worker))
    preloaded.foreach(s => logger.info(s"shared.in: '${s.name}' has ${s.size} item(s) for worker ${worker.index + 1}/${worker.count}"))

    // ---- scenarios
    val selected = if (opts.setupOnly) Nil else plan.scenarios.filter(s => opts.onlyScenarios.isEmpty || opts.onlyScenarios(s.name))
    val mains: List[PopulationBuilder] = selected.map { sc =>
      val auth = authFor(sc.auth)
      val ctx = baseCtx.withAuth(auth)
      val chain: ChainBuilder =
        sc.feeders.map(ChainFactory.feedFrom(_, ctx)).foldLeft(exec(s => s): ChainBuilder)(_.exec(_))
          .exec(auth.map(_.setup).getOrElse(exec(s => s)))
          .exec(ChainFactory.steps(sc.steps, ctx))
      val pop = injectWith(scenario(sc.name).exec(chain), injection(sc.load, worker))
      warnOnSmallQueues(sc, sources, worker)
      LoadMath.throttleWarning(sc).foreach(w => logger.warn(w))
      if (sc.throttle.isEmpty) pop else pop.throttle(throttle(sc.throttle, worker): _*)
    }

    // ---- setup
    val populations = plan.setup match {
      case Some(setup) if opts.sharedIn.isEmpty =>
        val auth = authFor(setup.auth)
        val chain = exec(s => s.setAll(setup.vars.toList))
          .exec(auth.map(_.setup).getOrElse(exec(s => s)))
          .exec(ChainFactory.steps(setup.steps, baseCtx.withAuth(auth)))
          .crashLoadGeneratorIf("Setup failed (see the KO requests / errors above) - the test cannot start without its data", s => s.isFailed.success)
          .exec(shareChain(plan, setup.share, worker, opts))
        val setupPop = scenario(SetupScenarioName).exec(chain).inject(atOnceUsers(1))
        if (mains.isEmpty) List(setupPop) else List(setupPop.andThen(mains: _*))
      case _ => mains
    }

    val protocolBase = http.baseUrl(plan.baseUrl).acceptHeader("application/json").disableWarmUp.headers(plan.http.headers)
    val protocol = if (plan.http.shareConnections) protocolBase.shareConnections else protocolBase
    val assertions = if (opts.setupOnly) Nil else plan.sla.flatMap(sla(_, paths))

    AssembledSimulation(populations, protocol, assertions, plan.maxDuration, summary(plan, selected, worker, sources, opts))
  }

  private def shareChain(plan: TestPlan, shares: List[ShareDef], worker: Worker, opts: RunOptions): ChainBuilder =
    exec { session =>
      val found: Either[String, List[(ShareDef, Seq[Any])]] = shares.foldLeft[Either[String, List[(ShareDef, Seq[Any])]]](Right(Nil)) {
        case (Right(acc), share) =>
          session.attributes.get(share.from)
            .map(v => Right(acc :+ (share -> PlanData.asItems(v))))
            .getOrElse(Left(s"setup: variable '${share.from}' was never extracted, so '${share.name}' cannot be shared"))
        case (left, _) => left
      }
      found match {
        case Left(err) => err.failure
        case Right(lists) =>
          opts.sharedOut.foreach { file =>
            SharedFile.write(file, plan.name, lists.map { case (s, items) => s.name -> items }.toMap)
            logger.info(s"setup: wrote ${lists.map { case (s, i) => s"'${s.name}' (${i.size})" }.mkString(", ")} to ${file.toAbsolutePath}")
          }
          lists.foreach { case (share, items) =>
            val src = PlanData.registerShare(share, items, worker)
            logger.info(s"setup: shared '${share.name}' with ${src.size} item(s) for worker ${worker.index + 1}/${worker.count} (${items.size} in total)")
          }
          session.success
      }
    }.crashLoadGeneratorIf("Setup could not share its data (see errors above)", s => s.isFailed.success)

  // ------------------------------------------------------------ load

  private sealed trait PopulationInjection
  private object PopulationInjection {
    final case class Open(steps: List[OpenInjectionStep]) extends PopulationInjection
    final case class Closed(steps: List[ClosedInjectionStep]) extends PopulationInjection
  }

  private def injection(load: LoadDef, w: Worker): PopulationInjection =
    if (load.model == "closed")
      PopulationInjection.Closed(load.profile.collect {
        case InjectionDef.ConstantConcurrent(u, d) => constantConcurrentUsers(w.share(u)).during(d)
        case InjectionDef.RampConcurrent(f, t, d)  => rampConcurrentUsers(w.share(f)).to(w.share(t)).during(d)
        case InjectionDef.IncrementConcurrent(by, times, level, ramp, from) =>
          incrementConcurrentUsers(w.share(by)).times(times).eachLevelLasting(level).separatedByRampsLasting(ramp).startingFrom(w.share(from))
      })
    else
      PopulationInjection.Open(load.profile.collect {
        case InjectionDef.AtOnce(u)       => atOnceUsers(w.share(u))
        case InjectionDef.RampUsers(u, d) => rampUsers(w.share(u)).during(d)
        case InjectionDef.ConstantRate(r, d, rnd) =>
          val s = constantUsersPerSec(w.share(r)).during(d); if (rnd) s.randomized else s
        case InjectionDef.RampRate(f, t, d, rnd) =>
          val s = rampUsersPerSec(w.share(f)).to(w.share(t)).during(d); if (rnd) s.randomized else s
        case InjectionDef.StressPeak(u, d) => stressPeakUsers(w.share(u)).during(d)
        case InjectionDef.IncrementRate(by, times, level, ramp, from) =>
          incrementUsersPerSec(w.share(by)).times(times).eachLevelLasting(level).separatedByRampsLasting(ramp).startingFrom(w.share(from))
        case InjectionDef.Nothing(d)       => nothingFor(d)
      })

  private def injectWith(scn: ScenarioBuilder, i: PopulationInjection): PopulationBuilder = i match {
    case PopulationInjection.Open(steps)   => scn.inject(steps)
    case PopulationInjection.Closed(steps) => scn.inject(steps)
  }

  private def throttle(steps: List[ThrottleDef], w: Worker): List[ThrottleStep] = {
    def perWorker(total: Double): Int = {
      val share = w.share(total)
      if (share < 1 && w.count > 1)
        logger.warn(s"throttle: $total rps split over ${w.count} workers is ${"%.2f".format(share)} rps each - rounded up to 1 per worker")
      math.max(1, math.round(share).toInt)
    }
    steps.map {
      case ThrottleDef.ReachRps(t, in) => reachRps(perWorker(t)).in(in)
      case ThrottleDef.JumpToRps(t)    => jumpToRps(perWorker(t))
      case ThrottleDef.HoldFor(d)      => holdFor(d)
    }
  }

  private def warnOnSmallQueues(sc: ScenarioDef, sources: Map[String, RecordSource], w: Worker): Unit =
    LoadMath.plannedArrivals(sc.load, w).foreach { arrivals =>
      sc.feeders.flatMap(sources.get).filter(s => s.size < arrivals && s.strategy == "queue").foreach { s =>
        logger.warn(s"Scenario '${sc.name}': data '${s.name}' has ${s.size} record(s) on this worker but about $arrivals virtual users will start - " +
          "the extra users will stop immediately. Add rows or use strategy: circular.")
      }
    }

  // ------------------------------------------------------------ SLA

  private def sla(s: SlaDef, paths: ReportPaths): List[Assertion] = {
    val scopes: List[AssertionWithPath] = s.scope match {
      case "global"      => List(global)
      case "api"         => paths.requests.get(s.target.get).toList.flatten.map(p => details(AssertionPathParts(p)))
      case "transaction" => paths.groups.get(s.target.get).toList.flatten.map(p => details(AssertionPathParts(p)))
    }
    if (scopes.isEmpty) logger.warn(s"SLA '${s.raw}' does not match anything in the selected scenarios - skipped")

    def cmp[T](a: AssertionWithPathAndTarget[T], v: T): Assertion = s.op match {
      case "<"  => a.lt(v)
      case "<=" => a.lte(v)
      case ">"  => a.gt(v)
      case ">=" => a.gte(v)
      case "==" => a.is(v)
    }
    scopes.map { scope =>
      val rt = scope.responseTime
      val ms = math.round(s.value).toInt
      s.metric match {
        case "responseTime.min"    => cmp(rt.min, ms)
        case "responseTime.max"    => cmp(rt.max, ms)
        case "responseTime.mean"   => cmp(rt.mean, ms)
        case "responseTime.stdDev" => cmp(rt.stdDev, ms)
        case "responseTime.p999"   => cmp(rt.percentile(99.9), ms)
        case p if p.startsWith("responseTime.p") => cmp(rt.percentile(p.stripPrefix("responseTime.p").toDouble), ms)
        case "requestsPerSec"      => cmp(scope.requestsPerSec, s.value)
        case m =>
          val counts = m.takeWhile(_ != '.') match {
            case "failedRequests"     => scope.failedRequests
            case "successfulRequests" => scope.successfulRequests
            case _                    => scope.allRequests
          }
          if (m.endsWith(".percent")) cmp(counts.percent, s.value) else cmp(counts.count, math.round(s.value))
      }
    }
  }

  // ------------------------------------------------------------ summary

  private def summary(plan: TestPlan, selected: List[ScenarioDef], w: Worker, sources: Map[String, RecordSource], opts: RunOptions): String = {
    val sb = new StringBuilder
    sb ++= s"Plan '${plan.name}' against ${plan.baseUrl} - worker ${w.index + 1} of ${w.count}, mode=${opts.mode}\n"
    plan.setup.foreach { s =>
      if (opts.sharedIn.isDefined) sb ++= s"  setup: skipped - shared lists from ${opts.sharedIn.get}\n"
      else sb ++= s"  setup: ${s.steps.size} step(s), shares ${s.share.map(_.name).mkString(", ")}\n"
    }
    selected.foreach { sc =>
      sb ++= s"  scenario '${sc.name}' (${sc.load.model} model, ${sc.steps.size} top-level step(s)) ${LoadMath.describe(sc.load, w)} on this worker\n"
    }
    sources.values.foreach(s => sb ++= s"  data '${s.name}': ${s.size} record(s) on this worker\n")
    if (plan.sla.nonEmpty && !opts.setupOnly) {
      sb ++= s"  SLAs: ${plan.sla.map(_.raw).mkString("; ")}\n"
      if (w.count > 1) sb ++= "  NOTE: SLA results printed by this worker cover its share only - the merged report decides.\n"
    }
    opts.startAt.foreach(t => sb ++= s"  start at: ${java.time.Instant.ofEpochMilli(t)}\n")
    sb.result()
  }
}
