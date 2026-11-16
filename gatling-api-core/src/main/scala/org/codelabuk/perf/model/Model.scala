package org.codelabuk.perf.model

import java.nio.file.Path

import scala.concurrent.duration.FiniteDuration

import org.codelabuk.perf.load.Node
import org.codelabuk.perf.expr.Condition

/**
 * In-memory form of a YAML test plan. Nothing in here knows about any business domain:
 * API names, variable names and conditions all come from the tester's YAML.
 */
final case class TestPlan(
    name: String,
    baseUrl: String,
    http: HttpDefaults,
    apis: Map[String, ApiDef],
    dataSources: Map[String, DataSourceDef],
    auth: Option[AuthDef],
    setup: Option[SetupDef],
    scenarios: List[ScenarioDef],
    sla: List[SlaDef],
    maxDuration: Option[FiniteDuration],
    baseDir: Path
)

final case class HttpDefaults(headers: Map[String, String], shareConnections: Boolean)

// ---------------------------------------------------------------- API catalog

final case class ApiDef(
    name: String,
    method: String,
    path: String,
    headers: Map[String, String],
    query: Map[String, String],
    body: Option[BodyDef],
    checks: List[CheckDef],
    extract: List[ExtractDef]
)

sealed trait BodyDef
object BodyDef {
  /** JSON (inline in YAML or loaded from a file); may contain #{var} placeholders. */
  final case class Json(template: String) extends BodyDef
  /** Any text body (XML, plain, ...); may contain #{var} placeholders. */
  final case class Raw(template: String, contentType: Option[String]) extends BodyDef
  final case class Form(fields: Map[String, String]) extends BodyDef
}

/** source: status | jsonPath | regex | header | bodyContains */
final case class CheckDef(source: String, expr: String, equals: Option[String], notExists: Boolean, statuses: List[Int])

/** source: jsonPath | regex | header ; kind: string | int | long | number | boolean | object | list | any */
final case class ExtractDef(saveAs: String, source: String, expr: String, all: Boolean, kind: String, optional: Boolean)

// ---------------------------------------------------------------- data

sealed trait DataSourceDef { def name: String }
object DataSourceDef {
  final case class Csv(name: String, file: Path, strategy: String, splitAcrossWorkers: Boolean, separator: Char) extends DataSourceDef
  /** A data source provided by a plugin module (e.g. jdbc from gatling-sql). */
  final case class Plugin(name: String, kind: String, node: Node) extends DataSourceDef
}

/** A list captured in `setup` and handed out to virtual users, one item each. */
final case class ShareDef(name: String, from: String, as: String, strategy: String, splitAcrossWorkers: Boolean)

// ---------------------------------------------------------------- auth

sealed trait AuthDef {
  def tokenVar: String
  def header: String
  def prefix: String
  def refreshBefore: Option[FiniteDuration]
}
object AuthDef {
  /** Calls a login API from the catalog and pulls the token out of the response. */
  final case class Login(
      api: String,
      tokenPath: String,
      tokenVar: String,
      header: String,
      prefix: String,
      refreshBefore: Option[FiniteDuration],
      ttl: Option[FiniteDuration]
  ) extends AuthDef

  /** Mints a signed JWT locally (HS256/384/512) - for environments that trust a shared test secret. */
  final case class Jwt(
      secret: String,
      secretBase64: Boolean,
      algorithm: String,
      claims: Map[String, String],
      validity: FiniteDuration,
      tokenVar: String,
      header: String,
      prefix: String,
      refreshBefore: Option[FiniteDuration]
  ) extends AuthDef

  /** A fixed token (e.g. an API key) - may reference #{var}. */
  final case class Static(token: String, tokenVar: String, header: String, prefix: String) extends AuthDef {
    override def refreshBefore: Option[FiniteDuration] = None
  }
}

// ---------------------------------------------------------------- flow

final case class SetupDef(vars: Map[String, String], auth: Option[AuthDef], steps: List[Step], share: List[ShareDef])

final case class ScenarioDef(
    name: String,
    feeders: List[String],
    auth: Option[AuthDef],
    steps: List[Step],
    load: LoadDef,
    throttle: List[ThrottleDef]
)

sealed trait Step
object Step {
  final case class Api(api: String, name: Option[String], extract: List[ExtractDef], checks: List[CheckDef]) extends Step
  final case class Transaction(name: String, steps: List[Step]) extends Step
  final case class Wait(expr: String, max: Option[FiniteDuration]) extends Step
  final case class Pace(expr: String) extends Step
  final case class Rendezvous(users: Int) extends Step
  final case class PollUntil(name: Option[String], condition: Condition, maxAttempts: Int, timeout: Option[FiniteDuration], steps: List[Step], failMessage: String)
      extends Step
  final case class ForEach(listVar: String, as: String, steps: List[Step]) extends Step
  final case class Repeat(times: String, steps: List[Step]) extends Step
  final case class Loop(during: FiniteDuration, steps: List[Step]) extends Step
  final case class If(condition: Condition, thenSteps: List[Step], elseSteps: List[Step]) extends Step
  final case class RandomSwitch(branches: List[(Double, List[Step])]) extends Step
  final case class Retry(times: Int, steps: List[Step]) extends Step
  final case class SetVars(values: Map[String, String]) extends Step
  final case class Log(message: String) extends Step
  case object StopUserIfFailed extends Step
  final case class Feed(source: String) extends Step
  /** Any step kind contributed by a plugin module (e.g. `sql` from gatling-sql). */
  final case class Plugin(kind: String, node: Node) extends Step
}

// ---------------------------------------------------------------- load

final case class LoadDef(model: String, profile: List[InjectionDef])

sealed trait InjectionDef
object InjectionDef {
  // open model (arrival rate)
  final case class AtOnce(users: Int) extends InjectionDef
  final case class RampUsers(users: Int, during: FiniteDuration) extends InjectionDef
  final case class ConstantRate(rate: Double, during: FiniteDuration, randomized: Boolean) extends InjectionDef
  final case class RampRate(from: Double, to: Double, during: FiniteDuration, randomized: Boolean) extends InjectionDef
  final case class StressPeak(users: Int, during: FiniteDuration) extends InjectionDef
  /** Stepped arrival rate (JMeter "stepping thread group"): startingFrom, +by per level, `times` levels. */
  final case class IncrementRate(by: Double, times: Int, levelLasting: FiniteDuration, rampLasting: FiniteDuration, startingFrom: Double)
      extends InjectionDef
  final case class Nothing(during: FiniteDuration) extends InjectionDef
  // closed model (concurrency)
  final case class ConstantConcurrent(users: Int, during: FiniteDuration) extends InjectionDef
  final case class RampConcurrent(from: Int, to: Int, during: FiniteDuration) extends InjectionDef
  /** Stepped concurrency: startingFrom users, +by per level, `times` levels. */
  final case class IncrementConcurrent(by: Int, times: Int, levelLasting: FiniteDuration, rampLasting: FiniteDuration, startingFrom: Int)
      extends InjectionDef
}

sealed trait ThrottleDef
object ThrottleDef {
  final case class ReachRps(target: Double, in: FiniteDuration) extends ThrottleDef
  final case class JumpToRps(target: Double) extends ThrottleDef
  final case class HoldFor(during: FiniteDuration) extends ThrottleDef
}

// ---------------------------------------------------------------- SLA

/** scope: global | api | transaction ; metric e.g. responseTime.p95, failedRequests.percent */
final case class SlaDef(raw: String, scope: String, target: Option[String], metric: String, op: String, value: Double)
