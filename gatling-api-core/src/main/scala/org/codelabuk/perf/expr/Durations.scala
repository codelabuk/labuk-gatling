package org.codelabuk.perf.expr

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

/**
 * Duration syntax used throughout the YAML:
 *   "500ms", "2s", "1.5s", "10m", "1h", "30" (= seconds), "1m30s", and ranges "1s..3s".
 */
object Durations {

  private val Part = """(\d+(?:\.\d+)?)\s*(ms|s|m|h)?""".r

  def parse(text: String): FiniteDuration = {
    val t = text.trim
    if (t.isEmpty) throw new IllegalArgumentException("empty duration")
    val parts = Part.findAllMatchIn(t).toList
    val consumed = parts.map(_.matched).mkString.replace(" ", "")
    if (parts.isEmpty || consumed != t.replace(" ", ""))
      throw new IllegalArgumentException(s"invalid duration '$text' (examples: 500ms, 2s, 10m, 1h, 1m30s)")
    parts.map { m =>
      val n = BigDecimal(m.group(1))
      val millis = Option(m.group(2)).getOrElse("s") match {
        case "ms" => n
        case "s"  => n * 1000
        case "m"  => n * 60000
        case "h"  => n * 3600000
      }
      millis.toLong.millis
    }.reduce(_ + _)
  }

  /** Parses a single duration or a "min..max" range and picks a uniformly random value for ranges. */
  def parseOrRange(text: String): FiniteDuration =
    text.split("\\.\\.", 2) match {
      case Array(min, max) =>
        val lo = parse(min).toMillis
        val hi = parse(max).toMillis
        if (hi < lo) throw new IllegalArgumentException(s"invalid range '$text': max < min")
        (if (hi == lo) lo else ThreadLocalRandom.current().nextLong(lo, hi + 1)).millis
      case _ => parse(text)
    }

  def isValid(text: String): Boolean =
    try { parseOrRange(text); true }
    catch { case _: IllegalArgumentException => false }
}
