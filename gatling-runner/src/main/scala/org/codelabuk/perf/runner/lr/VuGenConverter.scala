package org.codelabuk.perf.runner.lr

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{LinkedHashMap => JMap}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.Try

import com.fasterxml.jackson.dataformat.yaml.{YAMLGenerator, YAMLMapper}

/**
 * Converts a LoadRunner VuGen web (HTTP/HTML) script to a YAML plan + API catalog + CSV data.
 * Everything it cannot convert is listed in the conversion report with file and line - never silently dropped.
 */
object VuGenConverter {

  final case class Result(plan: String, catalog: String, dataFiles: Map[String, Path], report: List[String], requests: Int)

  // ------------------------------------------------------------------ C source -> calls

  sealed trait Arg
  final case class Str(value: String) extends Arg
  final case class Ident(name: String) extends Arg
  final case class Call(file: String, line: Int, fn: String, args: List[Arg], controlFlow: Boolean)

  /** Removes // and /* */ comments, keeping string literals and line numbers intact. */
  private def stripComments(src: String): String = {
    val sb = new StringBuilder
    var i = 0
    var inStr = false
    while (i < src.length) {
      val c = src.charAt(i)
      if (inStr) {
        sb += c
        if (c == '\\' && i + 1 < src.length) { sb += src.charAt(i + 1); i += 1 }
        else if (c == '"') inStr = false
        i += 1
      } else if (c == '"') { inStr = true; sb += c; i += 1 }
      else if (src.startsWith("//", i)) { while (i < src.length && src.charAt(i) != '\n') i += 1 }
      else if (src.startsWith("/*", i)) {
        val end = src.indexOf("*/", i + 2)
        val stop = if (end < 0) src.length else end + 2
        sb ++= src.substring(i, stop).filter(_ == '\n') // keep line numbers
        i = stop
      } else { sb += c; i += 1 }
    }
    sb.result()
  }

  private val ControlFlow = """^(if|else|for|while|do|switch)\b""".r

  /** Splits a C file into call statements, in order. Control-flow wrappers are removed but flagged. */
  def parseCalls(file: String, source: String): List[Call] = {
    val src = stripComments(source)
    val calls = ListBuffer.empty[Call]
    var i = 0
    var start = 0
    var depth = 0
    var inStr = false
    def lineAt(pos: Int) = src.substring(0, pos).count(_ == '\n') + 1
    def flush(end: Int): Unit = {
      var stmt = src.substring(start, end)
      var offset = start
      // drop function headers `Action()\n{`, block braces and blank space before the call
      var changed = true
      var control = false
      while (changed) {
        changed = false
        val trimmed = stmt.dropWhile(c => c.isWhitespace || c == '{' || c == '}')
        offset += stmt.length - trimmed.length
        stmt = trimmed
        """^\w+\s*\(\s*\)\s*\{""".r.findPrefixMatchOf(stmt).foreach { m => stmt = stmt.substring(m.end); offset += m.end; changed = true }
        ControlFlow.findPrefixMatchOf(stmt).foreach { m =>
          control = true
          var j = m.end
          while (j < stmt.length && stmt.charAt(j).isWhitespace) j += 1
          if (j < stmt.length && stmt.charAt(j) == '(') { // skip the (condition)
            var d = 0; var k = j; var s = false
            while (k < stmt.length && !(d == 1 && stmt.charAt(k) == ')' && !s)) {
              val ch = stmt.charAt(k)
              if (s) { if (ch == '\\') k += 1 else if (ch == '"') s = false }
              else if (ch == '"') s = true else if (ch == '(') d += 1 else if (ch == ')') d -= 1
              k += 1
            }
            j = math.min(k + 1, stmt.length)
          }
          stmt = stmt.substring(j); offset += j; changed = true
        }
      }
      """(?s)^(\w+)\s*\((.*)\)\s*$""".r.findFirstMatchIn(stmt.trim).foreach { m =>
        if (m.group(1) != "return") calls += Call(file, lineAt(offset), m.group(1), splitArgs(m.group(2)), control)
      }
    }
    while (i < src.length) {
      val c = src.charAt(i)
      if (inStr) { if (c == '\\') i += 1 else if (c == '"') inStr = false }
      else c match {
        case '"'               => inStr = true
        case '('               => depth += 1
        case ')'               => depth -= 1
        case ';' if depth == 0 => flush(i); start = i + 1
        case _                 => ()
      }
      i += 1
    }
    calls.toList
  }

  /** Top-level comma split; adjacent string literals are joined, escapes resolved. */
  private def splitArgs(s: String): List[Arg] = {
    val parts = ListBuffer.empty[String]
    val cur = new StringBuilder
    var depth = 0; var inStr = false; var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (inStr) { cur += c; if (c == '\\' && i + 1 < s.length) { cur += s.charAt(i + 1); i += 1 } else if (c == '"') inStr = false }
      else c match {
        case '"'               => inStr = true; cur += c
        case '(' | '['         => depth += 1; cur += c
        case ')' | ']'         => depth -= 1; cur += c
        case ',' if depth == 0 => parts += cur.result(); cur.clear()
        case _                 => cur += c
      }
      i += 1
    }
    if (cur.toString.trim.nonEmpty) parts += cur.result()
    parts.toList.map(_.trim).map { p =>
      if (p.startsWith("\"")) Str("\"((?:[^\"\\\\]|\\\\.)*)\"".r.findAllMatchIn(p).map(m => unescape(m.group(1))).mkString)
      else Ident(p)
    }
  }

  private def unescape(s: String): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < s.length) {
      if (s.charAt(i) == '\\' && i + 1 < s.length) {
        sb += (s.charAt(i + 1) match { case 'n' => '\n'; case 't' => '\t'; case 'r' => '\r'; case o => o }); i += 2
      } else { sb += s.charAt(i); i += 1 }
    }
    sb.result()
  }

  // ------------------------------------------------------------------ parameters (.prm / .dat)

  final case class Table(name: String, file: Path, columns: List[String], strategy: String)

  /** Parses the script's .prm file: File/Table parameters become CSV data sources. Returns (tables, param -> column). */
  private def parameters(dir: Path, report: ListBuffer[String]): (List[Table], Map[String, String]) = {
    val prms = Try(Files.list(dir).iterator().asScala.filter(_.toString.toLowerCase.endsWith(".prm")).toList).getOrElse(Nil)
    val tables = mutable.LinkedHashMap.empty[String, Table]
    val mapping = mutable.LinkedHashMap.empty[String, String]
    prms.foreach { prm =>
      val text = new String(Files.readAllBytes(prm), StandardCharsets.ISO_8859_1)
      """(?s)\[parameter:([^\]]+)\](.*?)(?=\n\[|\z)""".r.findAllMatchIn(text).foreach { m =>
        val param = m.group(1).trim
        val props = """(\w+)="([^"]*)"""".r.findAllMatchIn(m.group(2)).map(p => p.group(1) -> p.group(2)).toMap
        props.get("Type") match {
          case Some("Table") | Some("File") =>
            val tableFile = dir.resolve(props.getOrElse("Table", s"$param.dat"))
            if (!Files.isRegularFile(tableFile)) report += s"parameter {$param}: table ${tableFile.getFileName} not found - not converted"
            else {
              val delimiter = props.getOrElse("Delimiter", ",").headOption.getOrElse(',')
              val header = Files.readAllLines(tableFile, StandardCharsets.ISO_8859_1).asScala.headOption.getOrElse("")
                .split(java.util.regex.Pattern.quote(delimiter.toString)).map(_.trim).toList
              val strategy = props.getOrElse("SelectNextRow", "Sequential").toLowerCase match {
                case "random" => "random"
                case "unique" => "queue"
                case _        => "circular"
              }
              val name = tableFile.getFileName.toString.replaceAll("\\.[^.]+$", "").replaceAll("[^A-Za-z0-9_]", "_")
              tables.getOrElseUpdate(name, Table(name, tableFile, header, strategy))
              val column = props.get("ColumnName") match {
                case Some(c) if c.startsWith("Col ") => c.stripPrefix("Col ").trim.toIntOption.flatMap(n => header.lift(n - 1)).getOrElse(param)
                case Some(c)                         => c
                case None                            => param
              }
              mapping(param) = column
              if (delimiter != ',') report += s"parameter {$param}: table uses delimiter '$delimiter' - set `separator` on data source '$name' if needed"
            }
          case other =>
            report += s"parameter {$param}: type ${other.getOrElse("?")} is not converted - replace {$param} by hand (e.g. #{randomUuid()}, #{currentDate(yyyy-MM-dd)})"
        }
      }
    }
    (tables.values.toList, mapping.toMap)
  }

  // ------------------------------------------------------------------ calls -> plan

  private final class Pending {
    val extracts = new JMap[String, Any]()
    val checks = new java.util.ArrayList[Any]()
    val headers = new JMap[String, String]()
  }

  private def kv(args: List[Arg]): Map[String, String] =
    args.collect { case Str(s) if s.contains("=") => s.split("=", 2) match { case Array(k, v) => k.trim -> v } }.toMap

  def convert(scriptDir: Path, iterations: Int = 1): Result = {
    val report = ListBuffer.empty[String]
    val files: List[Path] =
      if (Files.isRegularFile(scriptDir)) List(scriptDir)
      else {
        val cs = Files.list(scriptDir).iterator().asScala.filter(_.toString.toLowerCase.endsWith(".c")).toList
        def named(n: String) = cs.filter(_.getFileName.toString.equalsIgnoreCase(n))
        val actions = cs.filter(_.getFileName.toString.toLowerCase.startsWith("action")).sortBy(f => (f.getFileName.toString.length, f.getFileName.toString))
        named("vuser_init.c") ++ actions ++ named("vuser_end.c")
      }
    if (files.isEmpty) throw new IllegalArgumentException(s"no VuGen .c files in $scriptDir")
    val dir = if (Files.isDirectory(scriptDir)) scriptDir else scriptDir.toAbsolutePath.getParent
    val (tables, paramColumns) = parameters(dir, report)

    val savedParams = mutable.LinkedHashSet.empty[String]
    val catalog = new JMap[String, Any]()
    val hosts = mutable.LinkedHashMap.empty[String, Int]
    val requestUrls = mutable.LinkedHashMap.empty[String, (String, Option[String], String)]
    val autoHeaders = new JMap[String, String]()
    var pending = new Pending
    var requests = 0

    /** {Param} -> #{column}; unknown {Name} stays as is (LoadRunner does the same) and is reported. */
    def params(s: String, where: String): String =
      """\{([A-Za-z_][A-Za-z0-9_]*)\}""".r.replaceAllIn(s, m => {
        val p = m.group(1)
        val target = paramColumns.get(p).orElse(Option.when(savedParams(p))(p))
        target match {
          case Some(t) => java.util.regex.Matcher.quoteReplacement(s"#{$t}")
          case None =>
            report += s"$where: {$p} is not a known parameter - left as text"
            java.util.regex.Matcher.quoteReplacement(m.matched)
        }
      })

    def apiName(raw: String): String = {
      val base = raw.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_").stripPrefix("_").stripSuffix("_") match { case "" => "request"; case b => b }
      if (!catalog.containsKey(base)) base else Iterator.from(2).map(i => s"${base}_$i").find(n => !catalog.containsKey(n)).get
    }

    // a stack of open transactions; each holds the step list it fills
    val root = new java.util.ArrayList[Any]()
    val stack = mutable.Stack[(String, java.util.ArrayList[Any])](("", root))
    def add(step: JMap[String, Any]): Unit = stack.top._2.add(step)
    def step(kvs: (String, Any)*): JMap[String, Any] = { val m = new JMap[String, Any](); kvs.foreach { case (k, v) => m.put(k, v) }; m }

    // sections: init steps, action steps (repeated), end steps
    val sections = mutable.LinkedHashMap.empty[String, java.util.ArrayList[Any]]

    files.foreach { f =>
      val fileName = f.getFileName.toString
      val section = fileName.toLowerCase match {
        case "vuser_init.c" => "init"
        case "vuser_end.c"  => "end"
        case _              => "action"
      }
      val target = sections.getOrElseUpdate(section, new java.util.ArrayList[Any]())
      stack.clear(); stack.push(("", target))
      parseCalls(fileName, new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1)).foreach { c =>
        val where = s"${c.file}:${c.line}"
        if (c.controlFlow) report += s"$where: ${c.fn}(...) is inside if/for/while - control flow is not converted, the call is kept in sequence"
        val strs = c.args.collect { case Str(s) => s }
        c.fn match {
          case "web_url" | "web_custom_request" | "web_submit_data" =>
            val a = kv(c.args)
            val rawUrl = a.getOrElse("URL", a.getOrElse("Action", ""))
            if (rawUrl.isEmpty) report += s"$where: ${c.fn} without URL/Action - skipped"
            else {
              val url = params(rawUrl, where)
              val host = """^(https?://[^/?#]+)""".r.findFirstMatchIn(url).map(_.group(1))
              host.foreach(h => hosts(h) = hosts.getOrElse(h, 0) + 1)
              val name = apiName(strs.headOption.getOrElse("request"))
              val api = new JMap[String, Any]()
              val method = a.getOrElse("Method", if (c.fn == "web_submit_data") "POST" else "GET").toUpperCase
              api.put("request", s"$method $url") // made relative to baseUrl once all hosts are known
              requestUrls(name) = (method, host, url)
              val headers = new JMap[String, String]()
              autoHeaders.forEach((k, v) => headers.put(k, v))
              pending.headers.forEach((k, v) => headers.put(k, v))
              if (!headers.isEmpty) api.put("headers", headers)
              if (c.fn == "web_submit_data") {
                // ITEMDATA, "Name=x", "Value=y", ENDITEM, ...
                val form = new JMap[String, String]()
                var name: Option[String] = None
                strs.drop(1).foreach { s =>
                  if (s.startsWith("Name=")) name = Some(s.stripPrefix("Name="))
                  else if (s.startsWith("Value=")) name.foreach { n => form.put(n, params(s.stripPrefix("Value="), where)); name = None }
                }
                if (!form.isEmpty) api.put("body", step("form" -> form))
              } else a.get("Body").foreach { b =>
                val body = params(b, where)
                val enc = a.getOrElse("EncType", "")
                if (enc.contains("json") || body.trim.startsWith("{") || body.trim.startsWith("["))
                  api.put("body", step("json" -> body))
                else api.put("body", step("raw" -> body, "contentType" -> (if (enc.isEmpty) "text/plain" else enc)))
              }
              if (a.contains("BodyBinary")) report += s"$where: BodyBinary is not converted - add the body by hand"
              if (a.get("Resource").contains("1")) report += s"$where: '${strs.headOption.getOrElse("")}' is a static resource (Resource=1) - converted as a normal request"
              if (strs.exists(_ == "EXTRARES") || c.args.exists { case Ident("EXTRARES") => true; case _ => false })
                report += s"$where: EXTRARES (embedded resources) are not converted - Gatling only sends the main request"
              val stepMap = step("api" -> name)
              if (!pending.extracts.isEmpty) stepMap.put("extract", pending.extracts)
              if (!pending.checks.isEmpty) stepMap.put("checks", pending.checks)
              catalog.put(name, api)
              add(stepMap)
              pending = new Pending
              requests += 1
            }

          case "web_reg_save_param" | "web_reg_save_param_ex" | "web_reg_save_param_json" | "web_reg_save_param_regexp" =>
            val a = kv(c.args)
            val name = a.get("ParamName").orElse(strs.headOption.filterNot(_.contains("="))).getOrElse("value")
            val all = a.get("Ord").exists(_.equalsIgnoreCase("ALL"))
            a.get("Ord").filter(o => !o.equalsIgnoreCase("ALL") && o != "1").foreach(o => report += s"$where: Ord=$o for {$name} - the first match is used")
            val spec = new JMap[String, Any]()
            if (c.fn == "web_reg_save_param_json") spec.put("jsonPath", a.getOrElse("QueryString", "$"))
            else if (c.fn == "web_reg_save_param_regexp") spec.put("regex", a.getOrElse("RegExp", "(.*)"))
            else {
              val lb = a.getOrElse("LB", ""); val rb = a.getOrElse("RB", "")
              spec.put("regex", s"${java.util.regex.Pattern.quote(lb)}(.*?)${java.util.regex.Pattern.quote(rb)}")
            }
            if (all) spec.put("all", true)
            if (a.get("NotFound").exists(_.equalsIgnoreCase("warning"))) spec.put("optional", true)
            pending.extracts.put(name, spec)
            savedParams += name

          case "web_reg_find" =>
            val a = kv(c.args)
            a.get("Text").foreach { t =>
              val check = new JMap[String, Any](); check.put("bodyContains", params(t, where))
              if (a.get("Fail").exists(_.equalsIgnoreCase("Found"))) check.put("notExists", true)
              pending.checks.add(check)
            }
            if (a.contains("TextPfx") || a.contains("TextSfx")) report += s"$where: web_reg_find TextPfx/TextSfx not converted"

          case "web_add_header"      => if (strs.size >= 2) pending.headers.put(strs(0), params(strs(1), where))
          case "web_add_auto_header" => if (strs.size >= 2) autoHeaders.put(strs(0), params(strs(1), where))
          case "web_revert_auto_header" => strs.headOption.foreach(autoHeaders.remove)

          case "lr_start_transaction" | "lr_start_sub_transaction" =>
            val t = new java.util.ArrayList[Any]()
            add(step("transaction" -> strs.headOption.getOrElse("transaction"), "steps" -> t))
            stack.push((strs.headOption.getOrElse(""), t))

          case "lr_end_transaction" | "lr_end_sub_transaction" =>
            if (stack.size > 1) {
              val (open, _) = stack.pop()
              if (strs.headOption.exists(_ != open)) report += s"$where: lr_end_transaction(\"${strs.head}\") closes \"$open\" - transactions overlap in the script"
            } else report += s"$where: lr_end_transaction without a start - ignored"

          case "lr_think_time" =>
            c.args.headOption.foreach {
              case Ident(n) if n.toDoubleOption.isDefined => add(step("wait" -> s"${n}s"))
              case other                                  => report += s"$where: lr_think_time($other) - not a number, not converted"
            }

          case "lr_rendezvous" =>
            // a rendezvous waits forever until N users arrive, so the placeholder must never block: 1 = no waiting
            add(step("rendezvous" -> 1))
            report += s"$where: lr_rendezvous(\"${strs.headOption.getOrElse("")}\") - set how many users to wait for " +
              "(LoadRunner keeps it in the Controller scenario); 1 is a placeholder that does not wait"

          case "lr_save_string" =>
            if (strs.size >= 2) { add(step("set" -> step(strs(1) -> params(strs(0), where)))); savedParams += strs(1) }

          case "lr_output_message" | "lr_log_message" | "lr_message" =>
            strs.headOption.foreach { msg =>
              if (msg.contains("%")) report += s"$where: ${c.fn} uses printf formatting - logged as text"
              add(step("log" -> params(msg, where)))
            }

          case "web_set_max_html_param_len" | "web_cache_cleanup" | "web_cleanup_cookies" | "web_set_sockets_option" | "web_set_user" |
               "web_set_timeout" | "lr_set_debug_message" | "web_global_verification" | "web_reg_add_cookie" =>
            report += s"$where: ${c.fn} is a LoadRunner runtime setting - not needed / not converted"

          case other =>
            report += s"$where: $other(...) is not converted"
        }
      }
      if (stack.size > 1) report += s"${f.getFileName}: transaction \"${stack.top._1}\" is never ended - closed at the end of the file"
    }

    // ---- assemble the plan: the most used host becomes baseUrl, its requests become relative paths
    val mainHost = hosts.toList.sortBy(-_._2).headOption.map(_._1)
    if (hosts.size > 1) report += s"requests go to ${hosts.size} hosts - baseUrl is ${mainHost.get}, the others keep absolute URLs"
    requestUrls.foreach { case (name, (method, host, url)) =>
      if (host.isDefined && host == mainHost)
        catalog.get(name).asInstanceOf[JMap[String, Any]].put("request", s"$method ${url.stripPrefix(host.get) match { case "" => "/"; case p => p }}")
    }

    val steps = new java.util.ArrayList[Any]()
    sections.get("init").foreach(steps.addAll)
    sections.get("action").foreach { a =>
      if (iterations > 1) steps.add(step("repeat" -> step("times" -> iterations, "steps" -> a))) else steps.addAll(a)
    }
    sections.get("end").foreach(steps.addAll)

    val scriptName = (if (Files.isDirectory(scriptDir)) scriptDir.getFileName.toString else "script").replaceAll("[^A-Za-z0-9_-]", "-")
    val plan = new JMap[String, Any]()
    plan.put("name", scriptName)
    plan.put("baseUrl", mainHost.fold("${BASE_URL}")(h => s"$${BASE_URL:$h}"))
    plan.put("include", java.util.List.of(s"apis/$scriptName.yaml"))
    if (tables.nonEmpty) {
      val ds = new JMap[String, Any]()
      tables.foreach(t => ds.put(t.name, step("file" -> s"data/${t.file.getFileName}", "strategy" -> t.strategy)))
      plan.put("dataSources", ds)
    }
    val scenario = new JMap[String, Any]()
    scenario.put("name", scriptName)
    if (tables.nonEmpty) scenario.put("feeders", tables.map(_.name).asJava)
    scenario.put("steps", steps)
    scenario.put("load", step("profile" -> java.util.List.of(step("atOnceUsers" -> 1))))
    plan.put("scenarios", java.util.List.of(scenario))

    val yaml = new YAMLMapper().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
      .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
    val header =
      s"""# Converted from LoadRunner script '$scriptName' by `perf convert-lr`.
         |# Load is not part of a VuGen script (it lives in the Controller scenario): set `load` below.
         |# Review the conversion report (${report.size} note(s)) before running.
         |""".stripMargin
    val catalogDoc = new JMap[String, Any](); catalogDoc.put("apis", catalog)
    Result(
      plan = header + yaml.writeValueAsString(plan),
      catalog = s"# API catalog converted from LoadRunner script '$scriptName'\n" + yaml.writeValueAsString(catalogDoc),
      dataFiles = tables.map(t => s"data/${t.file.getFileName}" -> t.file).toMap,
      report = report.toList,
      requests = requests
    )
  }

  /** Writes the plan, catalog, data and report under `out`; returns the plan path. */
  def write(r: Result, scriptName: String, out: Path): Path = {
    Files.createDirectories(out.resolve("apis"))
    val plan = out.resolve(s"$scriptName.yaml")
    Files.write(plan, r.plan.getBytes(StandardCharsets.UTF_8))
    Files.write(out.resolve("apis").resolve(s"$scriptName.yaml"), r.catalog.getBytes(StandardCharsets.UTF_8))
    r.dataFiles.foreach { case (rel, src) =>
      val dst = out.resolve(rel); Files.createDirectories(dst.getParent); Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    Files.write(out.resolve(s"$scriptName-conversion.txt"),
      (s"Conversion report for $scriptName: ${r.requests} request(s), ${r.report.size} note(s)\n" + r.report.map("- " + _).mkString("\n") + "\n")
        .getBytes(StandardCharsets.UTF_8))
    plan
  }
}
