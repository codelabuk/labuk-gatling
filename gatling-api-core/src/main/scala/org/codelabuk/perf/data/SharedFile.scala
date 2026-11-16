package org.codelabuk.perf.data

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper, SerializationFeature}

/**
 * The lists a plan's `setup` shares with virtual users, as a JSON file:
 *   { "plan": "...", "shares": { "srQueue": [ "SR-0001", ... ] } }
 *
 * Lets setup run ONCE for a distributed test (mode=setup writes it, every worker reads it with shared.in),
 * so all workers slice the very same list.
 */
object SharedFile {
  private val json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

  def write(file: Path, plan: String, shares: Map[String, Seq[Any]]): Unit = {
    Option(file.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    val root = new java.util.LinkedHashMap[String, Any]()
    root.put("plan", plan)
    val s = new java.util.LinkedHashMap[String, Any]()
    shares.toList.sortBy(_._1).foreach { case (k, items) => s.put(k, toJava(items)) }
    root.put("shares", s)
    json.writeValue(file.toFile, root)
  }

  def read(file: Path): Map[String, Seq[Any]] = {
    if (!Files.isRegularFile(file)) throw new IllegalArgumentException(s"shared.in: file not found: ${file.toAbsolutePath}")
    val root = json.readTree(file.toFile)
    val shares = Option(root.get("shares")).filter(_.isObject)
      .getOrElse(throw new IllegalArgumentException(s"$file: no 'shares' object - was it written by mode=setup?"))
    shares.fields().asScala.map(e => e.getKey -> fromJson(e.getValue).asInstanceOf[Seq[Any]]).toMap
  }

  /** Deterministic order, so every worker slices identically even if the API returned items in a different order. */
  def sorted[T](items: Seq[T]): Seq[T] = items.sortBy(i => json.writeValueAsString(toJava(i)))

  private def toJava(v: Any): Any = v match {
    case m: scala.collection.Map[_, _] =>
      val out = new java.util.LinkedHashMap[String, Any](); m.foreach { case (k, x) => out.put(k.toString, toJava(x)) }; out
    case s: Iterable[_]                => s.map(toJava).toList.asJava
    case m: java.util.Map[_, _]        => toJava(m.asScala)
    case c: java.util.Collection[_]    => toJava(c.asScala)
    case other                         => other
  }

  private def fromJson(n: JsonNode): Any =
    if (n.isArray) n.elements().asScala.map(fromJson).toVector
    else if (n.isObject) n.fields().asScala.map(e => e.getKey -> fromJson(e.getValue)).toMap
    else if (n.isIntegralNumber) n.asLong()
    else if (n.isNumber) n.asDouble()
    else if (n.isBoolean) n.asBoolean()
    else if (n.isNull) null
    else n.asText()
}
