package org.codelabuk.perf.load

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.concurrent.duration._

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.codelabuk.perf.data.{PlanData, RecordSource}
import org.codelabuk.perf.expr.{Condition, Durations}
import org.codelabuk.perf.model._
import org.codelabuk.perf.plugin.Plugins

/** Reads a YAML test plan (plus its includes) into a validated [[TestPlan]]. */
object PlanLoader {

  private val mapper = new YAMLMapper()

  private val TopKeys = Seq("name", "baseUrl", "include", "http", "apis", "dataSources", "auth", "setup", "scenarios", "sla", "maxDuration")
  private val IncludeKeys = Seq("apis", "dataSources")
  private val HttpMethods = Set("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
  private val ExtractTypes = Set("string", "int", "long", "number", "boolean", "object", "list", "any")

  def load(planFile: Path): TestPlan = {
    val file = planFile.toAbsolutePath.normalize()
    if (!Files.isRegularFile(file)) throw new PlanException(s"Test plan not found: $file")
    val baseDir = file.getParent
    val root = read(file).allowOnly(TopKeys: _*)

    // apis + dataSources may come from the plan itself and from included files
    val includes = root.optList("include").map { n =>
      val f = baseDir.resolve(n.text).normalize()
      if (!Files.isRegularFile(f)) n.fail(s"included file not found: $f")
      read(f).allowOnly(IncludeKeys: _*)
    }
    val sources = includes :+ root

    val apis = mergeUnique(sources.flatMap(s => s.opt("apis").toList.flatMap(_.entries)), "API")
      .map { case (k, n) => k -> parseApi(k, n, baseDir) }
    val dataSourceNodes = mergeUnique(sources.flatMap(s => s.opt("dataSources").toList.flatMap(_.entries)), "data source")
    val dataSources = dataSourceNodes.map { case (k, n) => k -> parseDataSource(k, n, baseDir) }

    val http = root.opt("http").map { h =>
      h.allowOnly("headers", "shareConnections")
      HttpDefaults(h.optStringMap("headers"), h.optBool("shareConnections").getOrElse(false))
    }.getOrElse(HttpDefaults(Map.empty, shareConnections = false))

    val planAuth = root.opt("auth").flatMap(parseAuth)
    val setup = root.opt("setup").map(n => parseSetup(n, planAuth))
    val scenarioNodes = root.get("scenarios").list
    if (scenarioNodes.isEmpty) root.get("scenarios").fail("at least one scenario is required")
    val scenarios = scenarioNodes.map(n => parseScenario(n, planAuth))

    val plan = TestPlan(
      name = root.str("name"),
      baseUrl = root.str("baseUrl"),
      http = http,
      apis = apis.toMap,
      dataSources = dataSources.toMap,
      auth = planAuth,
      setup = setup,
      scenarios = scenarios,
      sla = root.optList("sla").map(parseSla),
      maxDuration = root.optDuration("maxDuration"),
      baseDir = baseDir
    )
    validate(plan, root)
    plan
  }

  private def read(file: Path): Node = {
    val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    val json: JsonNode =
      try mapper.readTree(text)
      catch { case e: Exception => throw new PlanException(s"$file: invalid YAML - ${e.getMessage.linesIterator.next()}") }
    val node = new Node(json, "", file.getFileName.toString)
    if (!node.isObject) throw new PlanException(s"$file: expected a YAML mapping at the top level")
    node
  }

  private def mergeUnique(entries: List[(String, Node)], what: String): List[(String, Node)] = {
    entries.groupBy(_._1).collectFirst { case (k, dups) if dups.size > 1 => dups.last._2.fail(s"$what '$k' is defined more than once") }
    entries
  }

  // ---------------------------------------------------------------- API catalog

  private def parseApi(name: String, n: Node, baseDir: Path): ApiDef = {
    n.allowOnly("request", "method", "path", "headers", "query", "body", "checks", "extract")
    val (method, path) = n.optStr("request") match {
      case Some(req) =>
        req.trim.split("\\s+", 2) match {
          case Array(m, p) => (m.toUpperCase, p.trim)
          case _           => n.get("request").fail("expected '<METHOD> <path>', e.g. 'GET /orders/#{id}'")
        }
      case None => (n.optStr("method").getOrElse("GET").toUpperCase, n.str("path"))
    }
    if (!HttpMethods(method)) n.fail(s"unsupported HTTP method '$method' - use one of ${HttpMethods.mkString(", ")}")
    ApiDef(
      name = name,
      method = method,
      path = path,
      headers = n.optStringMap("headers"),
      query = n.optStringMap("query"),
      body = n.opt("body").map(b => parseBody(b, baseDir)),
      checks = n.optList("checks").map(parseCheck),
      extract = n.opt("extract").map(parseExtracts).getOrElse(Nil)
    )
  }

  private def parseBody(n: Node, baseDir: Path): BodyDef = {
    n.allowOnly("json", "file", "raw", "form", "contentType")
    def readFile(f: Node): String = {
      val p = baseDir.resolve(f.text).normalize()
      if (!Files.isRegularFile(p)) f.fail(s"body file not found: $p")
      new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    }
    if (n.has("json")) {
      val j = n.get("json")
      BodyDef.Json(if (j.isText) j.text else j.jsonWithPlaceholders)
    } else if (n.has("file")) {
      val content = readFile(n.get("file"))
      val ct = n.optStr("contentType")
      if (ct.forall(_.contains("json")) && n.str("file").endsWith(".json")) BodyDef.Json(content) else BodyDef.Raw(content, ct)
    } else if (n.has("raw")) BodyDef.Raw(n.str("raw"), n.optStr("contentType"))
    else if (n.has("form")) BodyDef.Form(n.get("form").stringMap)
    else n.fail("expected one of: json, file, raw, form")
  }

  private def parseCheck(n: Node): CheckDef = {
    n.allowOnly("status", "jsonPath", "regex", "header", "bodyContains", "equals", "notExists")
    val equals = n.optStr("equals")
    val notExists = n.optBool("notExists").getOrElse(false)
    if (n.has("status")) CheckDef("status", "", None, notExists = false, n.get("status").stringList.map { s =>
        s.toIntOption.getOrElse(n.get("status").fail(s"'$s' is not an HTTP status code"))
      })
    else List("jsonPath", "regex", "header", "bodyContains").find(n.has) match {
      case Some(src) => CheckDef(src, n.str(src), equals, notExists, Nil)
      case None      => n.fail("expected one of: status, jsonPath, regex, header, bodyContains")
    }
  }

  /** `extract: { token: $.token, ids: { jsonPath: "$.items[*].id", all: true } }` */
  private def parseExtracts(n: Node): List[ExtractDef] =
    n.entries.map { case (saveAs, spec) =>
      if (spec.isText) ExtractDef(saveAs, "jsonPath", spec.text, all = false, "string", optional = false)
      else {
        spec.allowOnly("jsonPath", "regex", "header", "all", "type", "optional")
        val src = List("jsonPath", "regex", "header").find(spec.has).getOrElse(spec.fail("expected one of: jsonPath, regex, header"))
        val all = spec.optBool("all").getOrElse(false)
        val kind = spec.optStr("type").getOrElse("string").toLowerCase
        if (!ExtractTypes(kind)) spec.get("type").fail(s"unknown type '$kind' - use one of ${ExtractTypes.mkString(", ")}")
        if (src != "jsonPath" && kind != "string") spec.get("type").fail("'type' is only supported for jsonPath")
        ExtractDef(saveAs, src, spec.str(src), all, kind, spec.optBool("optional").getOrElse(false))
      }
    }

  // ---------------------------------------------------------------- data

  private def parseDataSource(name: String, n: Node, baseDir: Path): DataSourceDef = {
    val kind = n.optStr("type").getOrElse("csv").toLowerCase
    kind match {
      case "csv" =>
        n.allowOnly("type", "file", "strategy", "splitAcrossWorkers", "separator")
        val file = baseDir.resolve(n.str("file")).normalize()
        if (!Files.isRegularFile(file)) n.get("file").fail(s"CSV file not found: $file")
        val strategy = parseStrategy(n)
        val sep = n.optStr("separator").getOrElse(",")
        if (sep.length != 1) n.get("separator").fail("separator must be a single character")
        DataSourceDef.Csv(name, file, strategy, n.optBool("splitAcrossWorkers").getOrElse(true), sep.head)
      case other =>
        if (!Plugins.dataSources.contains(other))
          n.get("type").fail(s"unknown data source type '$other' - available: ${("csv" :: Plugins.dataSources.keys.toList).mkString(", ")}")
        DataSourceDef.Plugin(name, other, n)
    }
  }

  private def parseStrategy(n: Node): String = {
    val s = n.optStr("strategy").getOrElse("circular").toLowerCase
    if (!RecordSource.Strategies(s)) n.get("strategy").fail(s"unknown strategy '$s' - use one of ${RecordSource.Strategies.mkString(", ")}")
    s
  }

  // ---------------------------------------------------------------- auth

  /** Returns None for `auth: none`. */
  private def parseAuth(n: Node): Option[AuthDef] = {
    if (n.isText) {
      if (n.text.equalsIgnoreCase("none")) return None
      n.fail("expected 'none' or an auth mapping with a 'type'")
    }
    val header = n.optStr("header").getOrElse("Authorization")
    val prefix = n.optStr("prefix").getOrElse("Bearer ")
    val tokenVar = n.optStr("saveAs").getOrElse("token")
    n.str("type").toLowerCase match {
      case "none" => None
      case "login" =>
        n.allowOnly("type", "api", "token", "saveAs", "header", "prefix", "refreshBefore", "ttl")
        Some(AuthDef.Login(n.str("api"), n.optStr("token").getOrElse("$.token"), tokenVar, header, prefix, n.optDuration("refreshBefore"), n.optDuration("ttl")))
      case "jwt" =>
        n.allowOnly("type", "secret", "secretEncoding", "algorithm", "claims", "validity", "saveAs", "header", "prefix", "refreshBefore")
        val alg = n.optStr("algorithm").getOrElse("HS256").toUpperCase
        if (!Set("HS256", "HS384", "HS512")(alg)) n.get("algorithm").fail("supported algorithms: HS256, HS384, HS512")
        val enc = n.optStr("secretEncoding").getOrElse("text").toLowerCase
        if (!Set("text", "base64")(enc)) n.get("secretEncoding").fail("use 'text' or 'base64'")
        Some(AuthDef.Jwt(n.str("secret"), enc == "base64", alg, n.optStringMap("claims"), n.optDuration("validity").getOrElse(1.hour),
          tokenVar, header, prefix, n.optDuration("refreshBefore").orElse(Some(30.seconds))))
      case "static" =>
        n.allowOnly("type", "value", "saveAs", "header", "prefix")
        Some(AuthDef.Static(n.str("value"), tokenVar, header, n.optStr("prefix").getOrElse("")))
      case other => n.get("type").fail(s"unknown auth type '$other' - use login, jwt, static or none")
    }
  }

  // ---------------------------------------------------------------- setup / scenarios

  private def parseSetup(n: Node, planAuth: Option[AuthDef]): SetupDef = {
    n.allowOnly("vars", "auth", "steps", "share")
    val share = n.opt("share").map(_.entries).getOrElse(Nil).map { case (name, s) =>
      s.allowOnly("from", "as", "strategy", "splitAcrossWorkers")
      val from = stripEl(s.str("from"))
      ShareDef(name, from, s.optStr("as").getOrElse(from), parseStrategy(s) match { case "circular" if !s.has("strategy") => "queue"; case x => x },
        s.optBool("splitAcrossWorkers").getOrElse(true))
    }
    SetupDef(n.optStringMap("vars"), if (n.has("auth")) parseAuth(n.get("auth")) else planAuth, parseSteps(n.optList("steps")), share)
  }

  private def parseScenario(n: Node, planAuth: Option[AuthDef]): ScenarioDef = {
    n.allowOnly("name", "feeders", "auth", "steps", "load", "throttle")
    val steps = parseSteps(n.get("steps").list)
    if (steps.isEmpty) n.get("steps").fail("a scenario needs at least one step")
    ScenarioDef(
      name = n.str("name"),
      feeders = n.opt("feeders").map(_.stringList).getOrElse(Nil),
      auth = if (n.has("auth")) parseAuth(n.get("auth")) else planAuth,
      steps = steps,
      load = parseLoad(n.get("load")),
      throttle = n.optList("throttle").map(parseThrottle)
    )
  }

  private def stripEl(s: String): String = s.trim.stripPrefix("#{").stripSuffix("}").trim

  private def condition(n: Node): Condition =
    try Condition.parse(n.raw.asText())
    catch { case e: IllegalArgumentException => n.fail(e.getMessage) }

  private val CoreSteps = List("api", "transaction", "wait", "thinkTime", "pace", "rendezvous", "pollUntil", "forEach", "repeat", "loop",
    "if", "randomSwitch", "retry", "set", "log", "stopUserIfFailed", "feed")

  def parseSteps(nodes: List[Node]): List[Step] = nodes.map(parseStep)

  private def parseStep(n: Node): Step = {
    if (!n.isObject) n.fail(s"expected a step such as '- api: name' (known steps: ${(CoreSteps ++ Plugins.steps.keys).mkString(", ")})")
    val kind = n.keys.find(k => CoreSteps.contains(k) || Plugins.steps.contains(k)).getOrElse {
      n.fail(s"unknown step ${n.keys.mkString(", ")} - known steps: ${(CoreSteps ++ Plugins.steps.keys).mkString(", ")}")
    }
    val v = n.get(kind)
    kind match {
      case "api" =>
        n.allowOnly("api", "name", "extract", "checks")
        Step.Api(v.text, n.optStr("name"), n.opt("extract").map(parseExtracts).getOrElse(Nil), n.optList("checks").map(parseCheck))
      case "transaction" =>
        n.allowOnly("transaction", "steps")
        Step.Transaction(v.text, parseSteps(n.get("steps").list))
      case "wait" | "thinkTime" =>
        n.allowOnly(kind)
        if (v.isText) Step.Wait(checkDurationExpr(v, v.text), None)
        else {
          v.allowOnly("duration", "max")
          Step.Wait(checkDurationExpr(v.get("duration"), v.str("duration")), v.optDuration("max"))
        }
      case "pace" =>
        n.allowOnly("pace")
        Step.Pace(checkDurationExpr(v, v.text))
      case "rendezvous" =>
        n.allowOnly("rendezvous")
        Step.Rendezvous(v.int)
      case "pollUntil" =>
        n.allowOnly("pollUntil")
        v.allowOnly("name", "condition", "maxAttempts", "timeout", "steps", "failMessage")
        val c = condition(v.get("condition"))
        val max = v.optInt("maxAttempts").getOrElse(Int.MaxValue)
        if (!v.has("maxAttempts") && !v.has("timeout")) v.fail("set maxAttempts and/or timeout so a poll can never run forever")
        Step.PollUntil(v.optStr("name"), c, max, v.optDuration("timeout"), parseSteps(v.get("steps").list),
          v.optStr("failMessage").getOrElse(s"condition not met: ${c.source}"))
      case "forEach" =>
        n.allowOnly("forEach")
        v.allowOnly("list", "as", "steps")
        Step.ForEach(stripEl(v.str("list")), v.str("as"), parseSteps(v.get("steps").list))
      case "repeat" =>
        n.allowOnly("repeat")
        v.allowOnly("times", "steps")
        Step.Repeat(v.str("times"), parseSteps(v.get("steps").list))
      case "loop" =>
        n.allowOnly("loop")
        v.allowOnly("during", "steps")
        Step.Loop(v.get("during").duration, parseSteps(v.get("steps").list))
      case "if" =>
        n.allowOnly("if")
        v.allowOnly("condition", "then", "else")
        Step.If(condition(v.get("condition")), parseSteps(v.get("then").list), parseSteps(v.optList("else")))
      case "randomSwitch" =>
        n.allowOnly("randomSwitch")
        val branches = v.list.map { b =>
          b.allowOnly("percent", "steps")
          b.get("percent").double -> parseSteps(b.get("steps").list)
        }
        if (branches.map(_._1).sum > 100.0001) v.fail("percentages add up to more than 100")
        Step.RandomSwitch(branches)
      case "retry" =>
        n.allowOnly("retry")
        v.allowOnly("times", "steps")
        Step.Retry(v.get("times").int, parseSteps(v.get("steps").list))
      case "set" =>
        n.allowOnly("set")
        Step.SetVars(v.stringMap)
      case "log" =>
        n.allowOnly("log")
        Step.Log(v.text)
      case "stopUserIfFailed" =>
        n.allowOnly("stopUserIfFailed")
        Step.StopUserIfFailed
      case "feed" =>
        n.allowOnly("feed")
        Step.Feed(v.text)
      case plugin =>
        n.allowOnly((Plugins.steps(plugin).extraKeys + plugin).toSeq: _*)
        Step.Plugin(plugin, n)
    }
  }

  /** Durations may contain #{var}; only literal ones can be checked now. */
  private def checkDurationExpr(n: Node, s: String): String = {
    if (!s.contains("#{") && !Durations.isValid(s)) n.fail(s"invalid duration '$s' (examples: 500ms, 2s, 1m, 1s..3s, #{waitSeconds}s)")
    s
  }

  // ---------------------------------------------------------------- load

  /** kind -> (main key for the short form, all keys allowed in the long form) */
  private val InjectionKeys: Map[String, (String, Seq[String])] = Map(
    "atOnceUsers"              -> ("users", Seq("users")),
    "rampUsers"                -> ("users", Seq("users", "during")),
    "constantUsersPerSec"      -> ("rate", Seq("rate", "during", "randomized")),
    "rampUsersPerSec"          -> ("", Seq("from", "to", "during", "randomized")),
    "stressPeakUsers"          -> ("users", Seq("users", "during")),
    "nothingFor"               -> ("during", Seq("during")),
    "incrementUsersPerSec"     -> ("", Seq("by", "times", "eachLevelLasting", "separatedByRampsLasting", "startingFrom")),
    "constantConcurrentUsers"  -> ("users", Seq("users", "during")),
    "rampConcurrentUsers"      -> ("", Seq("from", "to", "during")),
    "incrementConcurrentUsers" -> ("", Seq("by", "times", "eachLevelLasting", "separatedByRampsLasting", "startingFrom"))
  )
  private val ClosedSteps = Set("constantConcurrentUsers", "rampConcurrentUsers", "incrementConcurrentUsers")
  private val OpenSteps = InjectionKeys.keySet -- ClosedSteps

  private def parseLoad(n: Node): LoadDef = {
    n.allowOnly("model", "profile")
    val items = n.get("profile").list
    if (items.isEmpty) n.get("profile").fail("at least one injection step is required")
    val parsed = items.map(parseInjection)
    val kinds = parsed.map(_._1)
    val model = if (kinds.forall(ClosedSteps)) "closed" else if (kinds.forall(k => OpenSteps(k))) "open" else
      n.get("profile").fail("cannot mix open steps (users/sec, rampUsers, ...) with closed steps (concurrent users) in one profile")
    n.optStr("model").foreach(m => if (m != model) n.get("model").fail(s"model is '$m' but the profile steps are $model-model steps"))
    LoadDef(model, parsed.map(_._2))
  }

  private def parseInjection(item: Node): (String, InjectionDef) = {
    val kind = item.keys.find(k => OpenSteps(k) || ClosedSteps(k)).getOrElse(
      item.fail(s"unknown injection step - use one of ${(OpenSteps ++ ClosedSteps).mkString(", ")}"))
    val v = item.get(kind)
    val (mainKey, allowed) = InjectionKeys(kind)
    // both `rampUsers: { users: 10, during: 1m }` and `rampUsers: 10` + `during: 1m` are accepted
    if (v.isObject) { item.allowOnly(kind); v.allowOnly(allowed: _*) }
    else {
      if (mainKey.isEmpty) v.fail(s"'$kind' needs a mapping: { ${allowed.mkString(", ")} }")
      item.allowOnly((kind +: allowed.filterNot(_ == mainKey)): _*)
    }
    def p(key: String): Node = if (v.isObject) v.get(key) else item.get(key)
    def pOpt(key: String): Option[Node] = if (v.isObject) v.opt(key) else item.opt(key)
    def main: Node = if (v.isObject) v.get(mainKey) else v
    def dur = p("during").duration
    def randomized = pOpt("randomized").exists(_.bool)
    def rampGap = pOpt("separatedByRampsLasting").map(_.duration).getOrElse(Duration.Zero)
    def positive(n: Node, value: Int): Int = if (value < 1) n.fail("must be at least 1") else value
    val d = kind match {
      case "atOnceUsers"             => InjectionDef.AtOnce(main.int)
      case "rampUsers"               => InjectionDef.RampUsers(main.int, dur)
      case "constantUsersPerSec"     => InjectionDef.ConstantRate(main.rate, dur, randomized)
      case "rampUsersPerSec"         => InjectionDef.RampRate(p("from").rate, p("to").rate, dur, randomized)
      case "stressPeakUsers"         => InjectionDef.StressPeak(main.int, dur)
      case "nothingFor"              => InjectionDef.Nothing(main.duration)
      case "incrementUsersPerSec" =>
        InjectionDef.IncrementRate(p("by").rate, positive(p("times"), p("times").int), p("eachLevelLasting").duration, rampGap,
          pOpt("startingFrom").map(_.rate).getOrElse(0.0))
      case "constantConcurrentUsers" => InjectionDef.ConstantConcurrent(main.int, dur)
      case "rampConcurrentUsers"     => InjectionDef.RampConcurrent(p("from").int, p("to").int, dur)
      case "incrementConcurrentUsers" =>
        InjectionDef.IncrementConcurrent(p("by").int, positive(p("times"), p("times").int), p("eachLevelLasting").duration, rampGap,
          pOpt("startingFrom").map(_.int).getOrElse(0))
    }
    kind -> d
  }

  private def parseThrottle(n: Node): ThrottleDef =
    if (n.has("reachRps")) { n.allowOnly("reachRps", "in"); ThrottleDef.ReachRps(n.get("reachRps").double, n.get("in").duration) }
    else if (n.has("jumpToRps")) { n.allowOnly("jumpToRps"); ThrottleDef.JumpToRps(n.get("jumpToRps").double) }
    else if (n.has("holdFor")) { n.allowOnly("holdFor"); ThrottleDef.HoldFor(n.get("holdFor").duration) }
    else n.fail("expected reachRps (+ in), jumpToRps or holdFor")

  // ---------------------------------------------------------------- SLA

  private val SlaPattern = """^\s*([A-Za-z]+)\.(.+?)\s*(<=|>=|==|<|>)\s*([0-9]+(?:\.[0-9]+)?)\s*(ms|s|%)?\s*$""".r
  private val MetricAliases = Map("failed" -> "failedRequests", "successful" -> "successfulRequests", "all" -> "allRequests")
  val SlaMetrics: Set[String] =
    Set("responseTime.min", "responseTime.max", "responseTime.mean", "responseTime.stdDev", "requestsPerSec") ++
      Set("p50", "p75", "p90", "p95", "p99", "p999").map("responseTime." + _) ++
      (for { r <- Seq("failedRequests", "successfulRequests", "allRequests"); m <- Seq("percent", "count") } yield s"$r.$m")

  /** written form -> canonical metric, longest first; e.g. "failed.percent" -> "failedRequests.percent" */
  private val MetricSuffixes: List[(String, String)] =
    (SlaMetrics.toList.map(m => m -> m) ++ SlaMetrics.toList.flatMap { m =>
      MetricAliases.collect { case (alias, full) if m.startsWith(full + ".") => (alias + m.stripPrefix(full)) -> m }
    }).sortBy(-_._1.length)

  private def parseSla(n: Node): SlaDef = {
    val raw = n.text
    def unknownMetric: Nothing = n.fail(s"cannot read SLA '$raw' - metrics: ${SlaMetrics.toList.sorted.mkString(", ")}")
    raw match {
      case SlaPattern(scopeRaw, rest, op, num, unit) =>
        val scope = scopeRaw.toLowerCase
        val (target, metric) = scope match {
          case "global" => (None, MetricSuffixes.collectFirst { case (w, m) if w == rest => m }.getOrElse(unknownMetric))
          case "api" | "transaction" =>
            // names may contain dots, so match the metric as a known suffix
            MetricSuffixes.collectFirst { case (w, m) if rest.endsWith("." + w) => (Some(rest.dropRight(w.length + 1)), m) }
              .getOrElse(unknownMetric)
          case _ => n.fail(s"SLA must start with global., api. or transaction. - got '$raw'")
        }
        val value = BigDecimal(num)
        val normalized = (metric, unit) match {
          case (m, "s") if m.startsWith("responseTime") => value * 1000
          case (m, "s" | "ms") if !m.startsWith("responseTime") => n.fail(s"'$unit' only makes sense for responseTime metrics")
          case (m, "%") if !m.endsWith(".percent") => n.fail("'%' only makes sense for .percent metrics")
          case _ => value
        }
        SlaDef(raw, scope, target, metric, op, normalized.toDouble)
      case _ => n.fail(s"cannot read SLA '$raw' - example: global.responseTime.p95 < 800ms")
    }
  }

  // ---------------------------------------------------------------- cross-checks

  private def validate(plan: TestPlan, root: Node): Unit = {
    def fail(msg: String): Nothing = root.fail(msg)

    val shareNames = plan.setup.toList.flatMap(_.share.map(_.name))
    shareNames.filter(plan.dataSources.contains).foreach(n => fail(s"'$n' is both a data source and a setup share - rename one"))
    val feedable = PlanData.feederNames(plan) ++ shareNames

    def walk(steps: List[Step], where: String): Unit = steps.foreach {
      case Step.Api(api, _, _, _) => if (!plan.apis.contains(api)) fail(s"$where: step uses API '$api' which is not defined - known APIs: ${plan.apis.keys.toList.sorted.mkString(", ")}")
      case Step.Feed(src)         => if (!feedable(src)) fail(s"$where: feed '$src' is not a data source or setup share")
      case Step.Transaction(_, s) => walk(s, where)
      case Step.PollUntil(_, _, _, _, s, _) => walk(s, where)
      case Step.ForEach(_, _, s)  => walk(s, where)
      case Step.Repeat(_, s)      => walk(s, where)
      case Step.Loop(_, s)        => walk(s, where)
      case Step.If(_, t, e)       => walk(t, where); walk(e, where)
      case Step.RandomSwitch(b)   => b.foreach(x => walk(x._2, where))
      case Step.Retry(_, s)       => walk(s, where)
      case Step.Plugin(kind, node) => Plugins.steps(kind).validate(node, plan)
      case _                      => ()
    }
    def checkAuth(a: Option[AuthDef], where: String): Unit = a.foreach {
      case l: AuthDef.Login if !plan.apis.contains(l.api) => fail(s"$where: auth uses API '${l.api}' which is not defined")
      case _ => ()
    }

    checkAuth(plan.auth, "auth")
    plan.setup.foreach { s => walk(s.steps, "setup"); checkAuth(s.auth, "setup") }
    val dupScenario = plan.scenarios.groupBy(_.name).collectFirst { case (n, l) if l.size > 1 => n }
    dupScenario.foreach(n => fail(s"scenario name '$n' is used more than once"))
    plan.scenarios.foreach { sc =>
      walk(sc.steps, s"scenario '${sc.name}'")
      checkAuth(sc.auth, s"scenario '${sc.name}'")
      sc.feeders.filterNot(feedable).foreach(f => fail(s"scenario '${sc.name}': feeder '$f' is not a data source or setup share - known: ${feedable.mkString(", ")}"))
    }
    plan.dataSources.values.foreach {
      case DataSourceDef.Plugin(_, kind, node) => Plugins.dataSources(kind).validate(node, plan)
      case _                                   => ()
    }

    // SLA targets must exist
    def names(steps: List[Step]): (Set[String], Set[String]) = steps.foldLeft((Set.empty[String], Set.empty[String])) {
      case ((a, t), Step.Api(api, name, _, _)) => (a + name.getOrElse(api), t)
      case ((a, t), Step.Plugin(kind, node))   => (a ++ Plugins.steps(kind).reportNames(node), t)
      case ((a, t), Step.Transaction(n, s))    => val (a2, t2) = names(s); (a ++ a2, t ++ t2 + n)
      case ((a, t), Step.PollUntil(Some(n), _, _, _, c, _)) => val (a2, t2) = names(c); (a ++ a2 + n, t ++ t2) // named polls are report entries
      case ((a, t), s) =>
        val children = s match {
          case Step.PollUntil(_, _, _, _, c, _) => c
          case Step.ForEach(_, _, c)         => c
          case Step.Repeat(_, c)             => c
          case Step.Loop(_, c)               => c
          case Step.If(_, x, y)              => x ++ y
          case Step.RandomSwitch(b)          => b.flatMap(_._2)
          case Step.Retry(_, c)              => c
          case _                             => Nil
        }
        val (a2, t2) = names(children); (a ++ a2, t ++ t2)
    }
    val (apiNames, txnNames) = names(plan.scenarios.flatMap(_.steps) ++ plan.setup.toList.flatMap(_.steps))
    val authApis = (plan.auth.toList ++ plan.scenarios.flatMap(_.auth) ++ plan.setup.flatMap(_.auth)).collect { case l: AuthDef.Login => l.api }
    plan.sla.foreach { s =>
      s.scope match {
        case "api" if !(apiNames ++ authApis).contains(s.target.get) => fail(s"SLA '${s.raw}': no step calls API '${s.target.get}'")
        case "transaction" if !txnNames.contains(s.target.get)        => fail(s"SLA '${s.raw}': no transaction named '${s.target.get}'")
        case _ => ()
      }
    }
  }
}
