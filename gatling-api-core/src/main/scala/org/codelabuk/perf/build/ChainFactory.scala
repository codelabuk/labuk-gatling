package org.codelabuk.perf.build

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.duration._

import com.typesafe.scalalogging.StrictLogging
import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.core.check.{CheckBuilder, CheckMaterializer}
import io.gatling.core.session.Expression
import io.gatling.core.session.el._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import io.gatling.http.check.HttpCheck
import io.gatling.http.request.builder.HttpRequestBuilder
import io.gatling.http.response.Response
import org.codelabuk.perf.data.{RecordSource, SharedData, Worker}
import org.codelabuk.perf.expr.{Condition, Durations}
import org.codelabuk.perf.load.Node
import org.codelabuk.perf.model._
import org.codelabuk.perf.plugin.Plugins

/** Where each request / transaction ends up in the report tree - needed to attach SLAs to them. */
final class ReportPaths {
  val requests: mutable.Map[String, mutable.LinkedHashSet[List[String]]] = mutable.Map.empty
  val groups: mutable.Map[String, mutable.LinkedHashSet[List[String]]] = mutable.Map.empty
  def addRequest(groupPath: List[String], name: String): Unit = requests.getOrElseUpdate(name, mutable.LinkedHashSet.empty) += (groupPath :+ name)
  def addGroup(path: List[String]): Unit = groups.getOrElseUpdate(path.last, mutable.LinkedHashSet.empty) += path
}

/** Everything a step (core or plugin) needs while being turned into a Gatling chain. */
final class BuildContext(
    val plan: TestPlan,
    val auth: Option[AuthStrategy],
    val groupPath: List[String],
    val paths: ReportPaths,
    val sources: Map[String, RecordSource],
    val worker: Worker,
    ids: AtomicInteger
) {
  def inGroup(name: String): BuildContext = new BuildContext(plan, auth, groupPath :+ name, paths, sources, worker, ids)
  def withAuth(a: Option[AuthStrategy]): BuildContext = new BuildContext(plan, a, groupPath, paths, sources, worker, ids)
  def nextId(prefix: String): String = s"__${prefix}${ids.incrementAndGet()}"
  /** Builds nested steps - plugins use this for their own child steps. */
  def chain(steps: List[Step]): ChainBuilder = ChainFactory.steps(steps, this)
  /** Data source or setup share by name, resolved at runtime. */
  def source(name: String): Option[RecordSource] = sources.get(name).orElse(SharedData.get(name))
}

object ChainFactory extends StrictLogging {

  val Exhausted = "__dataExhausted"

  // ------------------------------------------------------------ expressions

  private val elCache = new ConcurrentHashMap[String, Expression[Any]]()
  private def elAny(body: String): Expression[Any] = elCache.computeIfAbsent(body, b => s"#{$b}".el[Any])

  /** Evaluates a parsed YAML condition against the session. */
  def condition(c: Condition): Expression[Boolean] = session => c.eval(el => elAny(el)(session).toOption).success

  private def durationExpr(text: String, max: Option[FiniteDuration]): Expression[FiniteDuration] = {
    val cap: FiniteDuration => FiniteDuration = d => max.fold(d)(m => if (d > m) m else d)
    if (!text.contains("#{")) {
      if (text.contains("..")) _ => cap(Durations.parseOrRange(text)).success
      else { val d = cap(Durations.parse(text)); _ => d.success }
    } else {
      val e = text.el[String]
      session =>
        e(session).flatMap { resolved =>
          try cap(Durations.parseOrRange(resolved)).success
          catch { case ex: IllegalArgumentException => s"'$text' resolved to '$resolved': ${ex.getMessage}".failure }
        }
    }
  }

  private val noop: ChainBuilder = exec(session => session)

  def steps(list: List[Step], ctx: BuildContext): ChainBuilder =
    list.map(step(_, ctx)).reduceOption(_.exec(_)).getOrElse(noop)

  // ------------------------------------------------------------ steps

  def step(s: Step, ctx: BuildContext): ChainBuilder = s match {

    case Step.Api(api, name, extract, checks) => apiCall(api, name, extract, checks, ctx)

    case Step.Transaction(name, inner) =>
      val gctx = ctx.inGroup(name)
      ctx.paths.addGroup(gctx.groupPath)
      group(name)(steps(inner, gctx))

    case Step.Wait(expr, max) => pause(durationExpr(expr, max))

    case Step.Pace(expr) => pace(durationExpr(expr, None))

    case Step.Rendezvous(users) => rendezVous(math.max(1, ctx.worker.share(users)))

    case Step.PollUntil(entryName, cond, maxAttempts, timeout, inner, failMessage) =>
      val id = ctx.nextId("poll")
      val attempts = s"${id}_attempts"
      val started = s"${id}_start"
      val done = condition(cond)
      val keepGoing: Expression[Boolean] = session =>
        done(session).map { ok =>
          !ok &&
          session(attempts).as[Int] < maxAttempts &&
          timeout.forall(t => System.currentTimeMillis() - session(started).as[Long] < t.toMillis)
        }
      exec(_.set(attempts, 0).set(started, System.currentTimeMillis()))
        .asLongAs(keepGoing, s"${id}_i", exitASAP = false)(
          exec(session => session.set(attempts, session(attempts).as[Int] + 1)).exec(steps(inner, ctx))
        )
        .exec(entryName match {
          // named: always an entry in the report - OK timing how long the poll took, or KO with failMessage
          case Some(n) =>
            ctx.paths.addRequest(ctx.groupPath, n)
            exec(new RecordResultBuilder(n, started, done, failMessage))
          // unnamed: only a poll that gives up shows up, as a KO "pollUntil" entry with failMessage
          case None =>
            doIf(session => done(session).map(!_))(exec(new RecordResultBuilder("pollUntil", started, done, failMessage)))
        })
        .exec(_.remove(attempts).remove(started))

    case Step.ForEach(listVar, as, inner) =>
      val list = elAny(listVar)
      val asList: Expression[Seq[Any]] = session =>
        list(session).flatMap {
          case s: Seq[_]                => s.success
          case j: java.util.Collection[_] => j.toArray.toSeq.success
          case other                    => s"forEach: '$listVar' is not a list (got ${other.getClass.getSimpleName})".failure
        }
      foreach(asList, as, ctx.nextId("forEach"))(steps(inner, ctx))

    case Step.Repeat(times, inner) =>
      val count: Expression[Int] = times.toIntOption match {
        case Some(n) => _ => n.success
        case None    => times.el[Int]
      }
      repeat(count, ctx.nextId("repeat"))(steps(inner, ctx))

    case Step.Loop(d, inner) =>
      during(_ => d.success, ctx.nextId("loop"), exitASAP = false)(steps(inner, ctx))

    case Step.If(cond, thenSteps, elseSteps) =>
      if (elseSteps.isEmpty) doIf(condition(cond))(steps(thenSteps, ctx))
      else doIfOrElse(condition(cond))(steps(thenSteps, ctx))(steps(elseSteps, ctx))

    case Step.RandomSwitch(branches) =>
      randomSwitch(branches.map { case (pct, inner) => pct -> (steps(inner, ctx): io.gatling.core.action.builder.Executable) }: _*)

    case Step.Retry(times, inner) => tryMax(_ => times.success, ctx.nextId("retry"))(steps(inner, ctx))

    case Step.SetVars(values) =>
      val exprs = values.toList.map { case (k, v) => k -> v.el[Any] }
      exec { session =>
        exprs.foldLeft(session.success) { case (acc, (k, e)) => acc.flatMap(s => e(s).map(v => s.set(k, v))) }
      }

    case Step.Log(message) =>
      val e = message.el[String]
      exec { session =>
        e(session) match {
          case Success(m)   => logger.info(s"[${session.scenario} #${session.userId}] $m")
          case Failure(err) => logger.info(s"[${session.scenario} #${session.userId}] (log failed: $err)")
        }
        session
      }

    case Step.StopUserIfFailed => exitHereIfFailed

    case Step.Feed(name) => feedFrom(name, ctx)

    case Step.Plugin(kind, node) => Plugins.steps(kind).build(node, ctx)
  }

  /** Takes the next record of a data source / setup share; the virtual user stops when there are none left. */
  def feedFrom(name: String, ctx: BuildContext): ChainBuilder =
    exec { session =>
      ctx.source(name).flatMap(_.next()) match {
        case Some(record) => session.setAll(record)
        case None         => session.set(Exhausted, name)
      }
    }.exitHereIf(s => s.contains(Exhausted).success)

  // ------------------------------------------------------------ HTTP

  def apiCall(apiName: String, stepName: Option[String], extraExtract: List[ExtractDef], extraChecks: List[CheckDef], ctx: BuildContext): ChainBuilder = {
    val api = ctx.plan.apis(apiName)
    val requestName = stepName.getOrElse(apiName)
    ctx.paths.addRequest(ctx.groupPath, requestName)

    var req: HttpRequestBuilder = http(requestName.el[String]).httpRequest(api.method, api.path.el[String])
    if (api.headers.nonEmpty) req = req.headers(api.headers)
    api.query.foreach { case (k, v) => req = req.queryParam(k.el[String], v.el[Any]) }
    api.body.foreach {
      case BodyDef.Json(t)    => req = req.body(StringBody(t)).asJson
      case BodyDef.Raw(t, ct) => req = req.body(StringBody(t)); ct.foreach(c => req = req.header("Content-Type", c))
      case BodyDef.Form(f)    => f.foreach { case (k, v) => req = req.formParam(k.el[String], v.el[Any]) }
    }

    // a step-level extract with the same variable name replaces the catalog one
    val extracts = api.extract.filterNot(e => extraExtract.exists(_.saveAs == e.saveAs)) ++ extraExtract
    val checks = (api.checks ++ extraChecks).map(toCheck) ++ extracts.map(toExtract)
    if (checks.nonEmpty) req = req.check(checks: _*)

    val auth = ctx.auth.filterNot(_.skips(apiName))
    auth.foreach(a => req = a.decorate(req))
    auth.flatMap(_.refresh) match {
      case Some(refresh) => refresh.exec(req)
      case None          => exec(req)
    }
  }

  private def toCheck(c: CheckDef): HttpCheck = {
    def finish[T, P](b: CheckBuilder.MultipleFind[T, P, String])(implicit m: CheckMaterializer[T, HttpCheck, Response, P]): HttpCheck =
      if (c.notExists) checkBuilder2HttpCheck(b.notExists)
      else c.equals.fold(checkBuilder2HttpCheck(b.exists))(v => checkBuilder2HttpCheck(b.is(v.el[String])))
    c.source match {
      case "status"       => status.in(c.statuses: _*)
      case "jsonPath"     => finish(jsonPath(c.expr.el[String]))
      case "regex"        => finish(regex(c.expr.el[String]))
      case "header"       => finish(header(c.expr.el[String]))
      case "bodyContains" => if (c.notExists) substring(c.expr.el[String]).notExists else substring(c.expr.el[String]).exists
    }
  }

  private def save[T, P, X](b: CheckBuilder.MultipleFind[T, P, X], e: ExtractDef)(implicit m: CheckMaterializer[T, HttpCheck, Response, P]): HttpCheck =
    checkBuilder2HttpCheck(
      if (e.all) { if (e.optional) b.findAll.optional.saveAs(e.saveAs) else b.findAll.saveAs(e.saveAs) }
      else { if (e.optional) b.find.optional.saveAs(e.saveAs) else b.find.saveAs(e.saveAs) }
    )

  private def toExtract(e: ExtractDef): HttpCheck = e.source match {
    case "jsonPath" =>
      val b = jsonPath(e.expr.el[String])
      e.kind match {
        case "string"  => save(b, e)
        case "int"     => save(b.ofType[Int], e)
        case "long"    => save(b.ofType[Long], e)
        case "number"  => save(b.ofType[Double], e)
        case "boolean" => save(b.ofType[Boolean], e)
        case "object"  => save(b.ofType[Map[String, Any]], e)
        case "list"    => save(b.ofType[Seq[Any]], e)
        case _         => save(b.ofType[Any], e)
      }
    case "regex"  => save(regex(e.expr.el[String]), e)
    case "header" => save(header(e.expr.el[String]), e)
  }
}
