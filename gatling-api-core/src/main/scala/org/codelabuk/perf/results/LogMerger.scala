package org.codelabuk.perf.results

import java.io._
import java.nio.file.{Files, Path}
import java.util.{Arrays => JArrays}

/**
 * Merges the `simulation.log` files of several workers into one, so Gatling's reports-only mode can build
 * a single report and evaluate the SLAs on all the data.
 *
 * Gatling 3.13 writes a binary log and its reports-only mode reads exactly one file, so this is needed for
 * distributed runs. The format below mirrors Gatling 3.13.1's LogFileDataWriter / LogFileReader:
 *
 *   Run      (0): version, simulation class, start (long), description, scenarios[], assertions[] (opaque bytes)
 *   Request  (1): groups[] (cached), name (cached), start (int), end (int), ok (bool), message (cached)
 *   User     (2): scenario index (int), start (bool), timestamp (int)
 *   Group    (3): groups[] (cached), start (int), end (int), cumulated response time (int), ok (bool)
 *   Error    (4): message (cached), timestamp (int)
 *
 * Timestamps are relative to the run start; they are re-based onto the earliest worker start.
 * Strings are copied byte-for-byte (Java's internal value + coder), so no re-encoding happens.
 * The format is tied to the Gatling version - [[merge]] refuses logs from a different version.
 */
object LogMerger {

  val SupportedGatlingVersion = "3.13.1"

  private val Run: Byte = 0
  private val Request: Byte = 1
  private val User: Byte = 2
  private val Group: Byte = 3
  private val Error: Byte = 4

  /** A string as stored in the log: Java's internal byte value + coder (LATIN1=0, UTF16=1). */
  final class RawString(val bytes: Array[Byte], val coder: Byte) {
    override def equals(o: Any): Boolean = o match {
      case r: RawString => r.coder == coder && JArrays.equals(r.bytes, bytes)
      case _            => false
    }
    override def hashCode: Int = JArrays.hashCode(bytes) * 31 + coder
    /** Decoded value, for messages and checks only. */
    def text: String =
      if (bytes.isEmpty) ""
      else if (coder == 0) new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
      else new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE) // HotSpot stores UTF16 little-endian on x86/ARM
  }
  private val Empty = new RawString(Array.emptyByteArray, 0)

  final case class Header(
      gatlingVersion: RawString,
      simulationClass: RawString,
      start: Long,
      description: RawString,
      scenarios: Vector[RawString],
      assertions: Vector[Array[Byte]]
  )

  final case class Summary(files: Int, records: Long, users: Long, requests: Long, start: Long, output: Path)

  final class LogFormatException(msg: String) extends IOException(msg)

  // ------------------------------------------------------------------ reading

  private final class Reader(file: Path) extends AutoCloseable {
    private val in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))
    private val cache = new java.util.HashMap[Int, RawString]

    def read(): Int = in.read()
    def int(): Int = in.readInt()
    def long(): Long = in.readLong()
    def bool(): Boolean = in.readBoolean()
    def bytes(): Array[Byte] = in.readNBytes(int())
    def raw(): RawString = {
      val len = int()
      if (len == 0) Empty
      else {
        val b = in.readNBytes(len)
        if (b.length != len) throw new EOFException(s"$file: truncated string")
        new RawString(b, in.readByte())
      }
    }
    def cached(): RawString = {
      val idx = int()
      if (idx >= 0) { val s = raw(); cache.put(idx, s); s }
      else Option(cache.get(-idx)).getOrElse(throw new LogFormatException(s"$file: unknown cached string #${-idx}"))
    }
    def groups(): List[RawString] = List.fill(int())(cached())

    def header(): Header = {
      if (read() != Run) throw new LogFormatException(s"$file: not a Gatling simulation.log (no run header)")
      Header(raw(), raw(), long(), raw(), Vector.fill(int())(raw()), Vector.fill(int())(bytes()))
    }
    override def close(): Unit = in.close()
  }

  // ------------------------------------------------------------------ writing

  private final class Writer(file: Path) extends AutoCloseable {
    private val out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))
    private val cache = new java.util.HashMap[RawString, Integer]
    private var next = 1 // Gatling starts at 1: index 0 cannot be negated to signal a cache hit

    def byte(b: Byte): Unit = out.writeByte(b)
    def int(i: Int): Unit = out.writeInt(i)
    def long(l: Long): Unit = out.writeLong(l)
    def bool(b: Boolean): Unit = out.writeBoolean(b)
    def bytes(b: Array[Byte]): Unit = { out.writeInt(b.length); out.write(b) }
    def raw(s: RawString): Unit =
      if (s.bytes.isEmpty) out.writeInt(0)
      else { out.writeInt(s.bytes.length); out.write(s.bytes); out.writeByte(s.coder) }
    def cached(s: RawString): Unit = {
      val idx = cache.get(s)
      if (idx == null) { int(next); raw(s); cache.put(s, next); next += 1 }
      else int(-idx.intValue)
    }
    def groups(g: List[RawString]): Unit = { int(g.size); g.foreach(cached) }

    def header(h: Header): Unit = {
      byte(Run); raw(h.gatlingVersion); raw(h.simulationClass); long(h.start); raw(h.description)
      int(h.scenarios.size); h.scenarios.foreach(raw)
      int(h.assertions.size); h.assertions.foreach(bytes)
    }
    override def close(): Unit = out.close()
  }

  // ------------------------------------------------------------------ merge

  /** Reads only the header of a log - used to validate before merging. */
  def readHeader(file: Path): Header = { val r = new Reader(file); try r.header() finally r.close() }

  /**
   * Merges `inputs` into `output` (a `simulation.log` path). Returns what was merged.
   * @param description run description shown in the report; defaults to the first log's.
   */
  def merge(inputs: Seq[Path], output: Path, description: Option[String] = None): Summary = {
    require(inputs.nonEmpty, "no simulation.log files to merge")
    val headers = inputs.map(readHeader)
    val first = headers.head

    headers.zip(inputs).foreach { case (h, f) =>
      if (h.gatlingVersion.text != SupportedGatlingVersion)
        throw new LogFormatException(s"$f was written by Gatling ${h.gatlingVersion.text}; this merger supports $SupportedGatlingVersion only")
      if (h.simulationClass != first.simulationClass)
        throw new LogFormatException(s"$f comes from simulation ${h.simulationClass.text}, expected ${first.simulationClass.text}")
      if (h.assertions.size != first.assertions.size || h.assertions.zip(first.assertions).exists { case (a, b) => !JArrays.equals(a, b) })
        throw new LogFormatException(s"$f has different SLAs from ${inputs.head} - were the workers running the same plan?")
    }

    // scenarios may differ in order or (with -Dscenario) in content between workers
    val scenarios: Vector[RawString] = headers.flatMap(_.scenarios).distinct.toVector
    val scenarioIndex: Map[RawString, Int] = scenarios.zipWithIndex.toMap
    val start = headers.map(_.start).min

    // stored as LATIN1, so only plain text can replace the original description
    val desc = description.filter(d => d.nonEmpty && d.forall(_ < 256))
      .map(d => new RawString(d.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0))
      .getOrElse(first.description)

    Files.createDirectories(output.getParent)
    var records = 0L
    var users = 0L
    var requests = 0L
    val w = new Writer(output)
    try {
      w.header(Header(first.gatlingVersion, first.simulationClass, start, desc, scenarios, first.assertions))
      inputs.zip(headers).foreach { case (file, h) =>
        val shift = (h.start - start).toInt
        val localScenarios = h.scenarios.map(scenarioIndex)
        val r = new Reader(file)
        try {
          r.header()
          var kind = r.read()
          while (kind != -1) {
            kind.toByte match {
              case Request =>
                val g = r.groups(); val name = r.cached(); val s = r.int(); val e = r.int(); val ok = r.bool(); val msg = r.cached()
                w.byte(Request); w.groups(g); w.cached(name); w.int(s + shift); w.int(e + shift); w.bool(ok); w.cached(msg)
                requests += 1
              case User =>
                val sc = r.int(); val isStart = r.bool(); val ts = r.int()
                w.byte(User); w.int(localScenarios(sc)); w.bool(isStart); w.int(ts + shift)
                if (isStart) users += 1
              case Group =>
                val g = r.groups(); val s = r.int(); val e = r.int(); val cumulated = r.int(); val ok = r.bool()
                w.byte(Group); w.groups(g); w.int(s + shift); w.int(e + shift); w.int(cumulated); w.bool(ok)
              case Error =>
                val msg = r.cached(); val ts = r.int()
                w.byte(Error); w.cached(msg); w.int(ts + shift)
              case other => throw new LogFormatException(s"$file: unknown record type $other")
            }
            records += 1
            kind = r.read()
          }
        } finally r.close()
      }
    } finally w.close()
    Summary(inputs.size, records, users, requests, start, output)
  }

  /** All simulation logs under `dir`: `*.log` files directly in it, or `simulation.log` in its sub-folders. */
  def findLogs(dir: Path): Seq[Path] = {
    import scala.jdk.CollectionConverters._
    val stream = Files.walk(dir, 3)
    try stream.iterator().asScala.filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".log")).toList.sortBy(_.toString)
    finally stream.close()
  }
}
