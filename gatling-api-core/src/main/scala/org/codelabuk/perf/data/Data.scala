package org.codelabuk.perf.data

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.util.Random

import com.typesafe.scalalogging.StrictLogging

/**
 * Which slice of the load this JVM runs. Set by the k8s operator (or by hand for a multi-VM run):
 *   -Dworker.index / WORKER_INDEX (falls back to JOB_COMPLETION_INDEX from an Indexed Job), 0-based
 *   -Dworker.count / WORKER_COUNT
 * A single local run is index 0 of 1.
 */
final case class Worker(index: Int, count: Int) {
  require(count >= 1, s"worker count must be >= 1, got $count")
  require(index >= 0 && index < count, s"worker index must be in [0, $count), got $index")

  /** This worker's share of an integer total; the remainder goes to the lowest indexes. */
  def share(total: Int): Int = total / count + (if (index < total % count) 1 else 0)
  def share(rate: Double): Double = rate / count
  /** Keeps every count-th element starting at index, so workers never overlap. */
  def slice[T](items: IndexedSeq[T]): IndexedSeq[T] =
    if (count == 1) items else items.indices.collect { case i if i % count == index => items(i) }
}

object Worker {
  private def setting(names: String*): Option[String] =
    names.iterator.flatMap(n => sys.props.get(n).orElse(sys.env.get(n))).map(_.trim).find(_.nonEmpty)

  lazy val current: Worker = Worker(
    setting("worker.index", "WORKER_INDEX", "JOB_COMPLETION_INDEX").map(_.toInt).getOrElse(0),
    setting("worker.count", "WORKER_COUNT").map(_.toInt).getOrElse(1)
  )
}

/** Minimal RFC 4180 CSV reader: header row, quoted fields, escaped quotes, embedded separators/newlines. */
object CsvReader {
  def read(file: Path, separator: Char = ','): IndexedSeq[Map[String, String]] = {
    val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).stripPrefix("﻿")
    val rows = parse(text, separator).filterNot(r => r.forall(_.trim.isEmpty))
    if (rows.isEmpty) IndexedSeq.empty
    else {
      val header = rows.head.map(_.trim)
      rows.tail.zipWithIndex.map { case (r, i) =>
        if (r.size != header.size)
          throw new IllegalArgumentException(s"$file line ${i + 2}: expected ${header.size} columns but found ${r.size}")
        header.zip(r).toMap
      }.toIndexedSeq
    }
  }

  private def parse(text: String, sep: Char): Vector[Vector[String]] = {
    val rows = Vector.newBuilder[Vector[String]]
    var row = Vector.newBuilder[String]
    val field = new StringBuilder
    var inQuotes = false
    var i = 0
    def endField(): Unit = { row += field.toString; field.clear() }
    def endRow(): Unit = { endField(); rows += row.result(); row = Vector.newBuilder[String] }
    while (i < text.length) {
      val c = text.charAt(i)
      if (inQuotes) {
        if (c == '"' && i + 1 < text.length && text.charAt(i + 1) == '"') { field += '"'; i += 1 }
        else if (c == '"') inQuotes = false
        else field += c
      } else c match {
        case '"'                 => inQuotes = true
        case `sep`               => endField()
        case '\r'                => ()
        case '\n'                => endRow()
        case other               => field += other
      }
      i += 1
    }
    if (field.nonEmpty || text.nonEmpty && text.last != '\n') endRow()
    rows.result()
  }
}

/**
 * Hands out records to virtual users.
 *   queue    - each record once, in order; users exit when it runs out
 *   circular - in order, wrapping around
 *   random   - random record each time
 *   shuffle  - shuffled once, then like queue
 */
final class RecordSource(val name: String, records: IndexedSeq[Map[String, Any]], val strategy: String) extends StrictLogging {
  private val data = if (strategy == "shuffle") Random.shuffle(records) else records
  private val cursor = new AtomicLong(0)
  private val warned = new AtomicBoolean(false)

  def size: Int = data.size

  def next(): Option[Map[String, Any]] =
    if (data.isEmpty) exhausted()
    else strategy match {
      case "circular" => Some(data((cursor.getAndIncrement() % data.size).toInt))
      case "random"   => Some(data(ThreadLocalRandom.current().nextInt(data.size)))
      case _ =>
        val i = cursor.getAndIncrement()
        if (i < data.size) Some(data(i.toInt)) else exhausted()
    }

  private def exhausted(): Option[Map[String, Any]] = {
    if (warned.compareAndSet(false, true))
      logger.warn(s"Data '$name' has no more records ($size in total for this worker) - further virtual users using it will stop.")
    None
  }
}

object RecordSource {
  val Strategies: Set[String] = Set("queue", "circular", "random", "shuffle")

  /** Turns an extracted list into records: JSON objects keep their fields, plain values go under `as`. */
  def recordsFrom(items: Seq[Any], as: String): IndexedSeq[Map[String, Any]] =
    items.map {
      case m: Map[_, _]           => m.map { case (k, v) => k.toString -> v }.toMap[String, Any] + (as -> m)
      case m: java.util.Map[_, _] =>
        import scala.jdk.CollectionConverters._
        m.asScala.map { case (k, v) => k.toString -> (v: Any) }.toMap + (as -> m)
      case v => Map[String, Any](as -> v)
    }.toIndexedSeq
}

/** Lists captured by `setup` at runtime, keyed by share name. One instance per JVM (per worker). */
object SharedData {
  private val sources = new ConcurrentHashMap[String, RecordSource]()

  def register(source: RecordSource): Unit = sources.put(source.name, source)
  def get(name: String): Option[RecordSource] = Option(sources.get(name))
  def clear(): Unit = sources.clear()
}
