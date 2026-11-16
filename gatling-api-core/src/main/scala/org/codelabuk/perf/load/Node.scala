package org.codelabuk.perf.load

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

import com.fasterxml.jackson.databind.JsonNode
import org.codelabuk.perf.expr.Durations

/** Thrown for any problem in a plan; the message always says where (file + YAML path). */
final class PlanException(message: String) extends RuntimeException(message)

/** Resolves ${NAME} and ${NAME:default} from system properties, then environment variables. */
object Placeholders {
  private val Pattern = """\$\{([A-Za-z0-9_.\-]+)(?::([^}]*))?}""".r

  def resolve(text: String, where: => String): String =
    Pattern.replaceAllIn(
      text,
      m => {
        val name = m.group(1)
        val value = sys.props.get(name).orElse(sys.env.get(name)).orElse(Option(m.group(2))).getOrElse {
          throw new PlanException(s"$where: \"$${$name}\" is not set - pass -D$name=... or set environment variable $name")
        }
        java.util.regex.Matcher.quoteReplacement(value)
      }
    )
}

/** Path-aware, typo-catching view over a YAML node. */
final class Node(val raw: JsonNode, val path: String, file: String) {

  def fail(msg: String): Nothing = throw new PlanException(s"$file: $path: $msg")
  private def child(key: String): String = if (path.isEmpty) key else s"$path.$key"

  def isObject: Boolean = raw != null && raw.isObject
  def isArray: Boolean = raw != null && raw.isArray
  def isText: Boolean = raw != null && raw.isValueNode
  def has(key: String): Boolean = isObject && raw.has(key) && !raw.get(key).isNull

  def keys: List[String] = if (isObject) raw.fieldNames().asScala.toList else Nil

  def get(key: String): Node = {
    if (!isObject) fail("expected a mapping (key: value)")
    if (!has(key)) fail(s"'$key' is required")
    new Node(raw.get(key), child(key), file)
  }
  def opt(key: String): Option[Node] = if (has(key)) Some(get(key)) else None

  /** Fails on keys that are not allowed here - catches typos such as `maxAtempts`. */
  def allowOnly(allowed: String*): Node = {
    val unknown = keys.filterNot(allowed.contains)
    if (unknown.nonEmpty)
      fail(s"unknown key${if (unknown.size > 1) "s" else ""} ${unknown.map("'" + _ + "'").mkString(", ")} - allowed: ${allowed.mkString(", ")}")
    this
  }

  def text: String = {
    if (!isText) fail("expected a single value")
    Placeholders.resolve(raw.asText(), s"$file: $path")
  }
  def int: Int = {
    val t = text
    t.toIntOption.getOrElse(fail(s"expected a whole number but found '$t'"))
  }
  def double: Double = {
    val t = text
    t.toDoubleOption.getOrElse(fail(s"expected a number but found '$t'"))
  }
  def bool: Boolean = text.toLowerCase match {
    case "true" | "yes" | "on"  => true
    case "false" | "no" | "off" => false
    case other                  => fail(s"expected true or false but found '$other'")
  }
  /** A rate per second; accepts "4.5", "4.5/s", "266/min", "16000/h" (JMeter-style figures). */
  def rate: Double = {
    val t = text.replace(" ", "")
    val (num, per) = t.split("/", 2) match {
      case Array(n, u) => (n, u.toLowerCase)
      case Array(n)    => (n, "s")
    }
    val value = num.toDoubleOption.getOrElse(fail(s"expected a rate like 5, 5/s, 266/min or 16000/h but found '$t'"))
    per match {
      case "s" | "sec" | "second"            => value
      case "m" | "min" | "minute"            => value / 60
      case "h" | "hr" | "hour"               => value / 3600
      case other                             => fail(s"unknown rate unit '/$other' - use /s, /min or /h")
    }
  }

  def duration: FiniteDuration =
    try Durations.parse(text)
    catch { case e: IllegalArgumentException => fail(e.getMessage) }

  def str(key: String): String = get(key).text
  def optStr(key: String): Option[String] = opt(key).map(_.text)
  def optInt(key: String): Option[Int] = opt(key).map(_.int)
  def optDouble(key: String): Option[Double] = opt(key).map(_.double)
  def optBool(key: String): Option[Boolean] = opt(key).map(_.bool)
  def optDuration(key: String): Option[FiniteDuration] = opt(key).map(_.duration)

  def list: List[Node] =
    if (raw == null || raw.isNull) Nil
    else if (!isArray) fail("expected a list (- item)")
    else raw.elements().asScala.zipWithIndex.map { case (n, i) => new Node(n, s"$path[$i]", file) }.toList

  def optList(key: String): List[Node] = opt(key).map(_.list).getOrElse(Nil)

  /** Ordered key -> node pairs of a mapping. */
  def entries: List[(String, Node)] = {
    if (!isObject) fail("expected a mapping (key: value)")
    keys.map(k => k -> new Node(raw.get(k), child(k), file))
  }

  /** A mapping of scalar values, with ${...} placeholders resolved. */
  def stringMap: Map[String, String] = entries.map { case (k, v) => k -> v.text }.toMap
  def optStringMap(key: String): Map[String, String] = opt(key).map(_.stringMap).getOrElse(Map.empty)

  /** A string, or a list of strings. */
  def stringList: List[String] = if (isArray) list.map(_.text) else List(text)

  /** The raw YAML subtree as JSON text, with ${...} placeholders resolved in every value. */
  def jsonWithPlaceholders: String = {
    def walk(n: JsonNode): JsonNode = n match {
      case o: com.fasterxml.jackson.databind.node.ObjectNode =>
        val copy = o.objectNode()
        o.fields().asScala.foreach(e => copy.set[JsonNode](e.getKey, walk(e.getValue)))
        copy
      case a: com.fasterxml.jackson.databind.node.ArrayNode =>
        val copy = a.arrayNode()
        a.elements().asScala.foreach(e => copy.add(walk(e)))
        copy
      case t: com.fasterxml.jackson.databind.node.TextNode =>
        com.fasterxml.jackson.databind.node.TextNode.valueOf(Placeholders.resolve(t.asText(), s"$file: $path"))
      case other => other
    }
    walk(raw).toString
  }
}
