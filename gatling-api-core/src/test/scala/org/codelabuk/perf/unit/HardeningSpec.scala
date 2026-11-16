package org.codelabuk.perf.unit

import java.nio.file.{Files, Paths}

import org.codelabuk.perf.cli.Validate
import org.codelabuk.perf.data.{PlanData, SharedData, SharedFile, Worker}
import org.codelabuk.perf.load.{PlanException, PlanLoader, RunOptions}
import org.codelabuk.perf.model.{ShareDef, Step}
import org.scalatest.funsuite.AnyFunSuite

class HardeningSpec extends AnyFunSuite {

  private val samplePlan = Paths.get(getClass.getResource("/plans/sr-email-status.yaml").toURI)

  // ---------------------------------------------------------------- H3 start.at

  test("start.at accepts epoch millis and ISO-8601, rejects junk") {
    assert(RunOptions.parseInstant("1763400000000") == 1763400000000L)
    assert(RunOptions.parseInstant("2026-11-20T10:00:00Z") == java.time.Instant.parse("2026-11-20T10:00:00Z").toEpochMilli)
    assert(RunOptions.parseInstant("2026-11-20T15:30:00+05:30") == java.time.Instant.parse("2026-11-20T10:00:00Z").toEpochMilli)
    intercept[PlanException](RunOptions.parseInstant("tomorrow"))
  }

  // ---------------------------------------------------------------- H2 shared lists

  private val share = ShareDef("srQueue", "srList", "sr", "queue", splitAcrossWorkers = true)

  test("shared lists: workers get disjoint, complete slices even if each fetched the list in a different order") {
    val items = (1 to 30).map(i => f"SR-$i%04d")
    val orders = Seq(items, items.reverse, scala.util.Random.shuffle(items))
    val slices = (0 until 3).map { i =>
      val src = PlanData.registerShare(share, orders(i), Worker(i, 3)) // each worker saw a different order
      Iterator.continually(src.next()).takeWhile(_.isDefined).map(_.get("sr").toString).toList
    }
    assert(slices.flatten.sorted == items.sorted)
    assert(slices.flatten.distinct.size == items.size)
    SharedData.clear()
  }

  test("shared file: round-trips strings, numbers and objects") {
    val f = Files.createTempFile("shared", ".json")
    val lists = Map("ids" -> Seq("a", "b"), "objs" -> Seq(Map("id" -> 1L, "tags" -> Vector("x")), Map("id" -> 2L, "tags" -> Vector.empty)))
    SharedFile.write(f, "p", lists)
    assert(SharedFile.read(f) == lists)
  }

  test("shared file: plan shares are registered from it, missing lists are reported") {
    val plan = PlanLoader.load(samplePlan)
    val f = Files.createTempFile("shared", ".json")
    SharedFile.write(f, plan.name, Map("srQueue" -> (1 to 10).map(i => s"SR-$i")))
    val srcs = PlanData.registerSharesFromFile(plan, f, Worker(1, 2))
    assert(srcs.map(s => s.name -> s.size) == List("srQueue" -> 5))
    SharedFile.write(f, plan.name, Map("other" -> Seq("x")))
    assert(intercept[PlanException](PlanData.registerSharesFromFile(plan, f, Worker(0, 1))).getMessage.contains("no list 'srQueue'"))
    SharedData.clear()
  }

  // ---------------------------------------------------------------- H4 poll names

  test("pollUntil: optional name is loaded; failMessage is kept") {
    val poll = PlanLoader.load(samplePlan).scenarios.head.steps.head.asInstanceOf[Step.Transaction].steps(1).asInstanceOf[Step.PollUntil]
    assert(poll.name.isEmpty)
    assert(poll.failMessage == "SR did not leave PENDING")
  }

  // ---------------------------------------------------------------- H5 validate

  test("validate: a good plan passes and shows the per-worker split") {
    val r = Validate.run(List(samplePlan.toString, "--workers", "3"))
    assert(r.ok, r.report)
    assert(r.report.contains("VALID: plan 'sr-email-status'"))
    assert(r.report.contains("1/3") && r.report.contains("3/3"))
    // 40 users over 3 workers -> 14/13/13, 30 arrivals -> 10 each
    assert(r.report.linesIterator.exists(l => l.trim.startsWith("1/3") && l.contains("~10 users") && l.contains("14")), r.report)
  }

  test("validate: too little queue data is warned about, bad input fails with exit-worthy result") {
    val r = Validate.run(List(samplePlan.toString, "--workers", "1"))
    assert(r.ok && !r.report.contains("WARNINGS"), r.report) // 40 users >= 30 arrivals
    assert(!Validate.run(List("does/not/exist.yaml")).ok)
    assert(!Validate.run(List(samplePlan.toString, "--workers", "zero")).ok)
    assert(Validate.run(List(samplePlan.toString, "--scenario", "nope")).report.contains("unknown scenario"))
  }
}
