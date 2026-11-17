package org.codelabuk.perf.runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

class PerfSpec extends AnyFunSuite {

  test("arguments: positional, options, switches and -D properties") {
    val a = Args.parse(List("plans/x.yaml", "--scenario", "a,b", "--no-reports", "-DBASE_URL=http://h:1/p?q=1", "--workers", "3"))
    assert(a.positional == List("plans/x.yaml"))
    assert(a.opt("--scenario").contains("a,b") && a.opt("--workers").contains("3"))
    assert(a.has("--no-reports"))
    assert(a.props == Map("BASE_URL" -> "http://h:1/p?q=1"))
    assert(a.passThrough("--workers", "--missing") == List("--workers", "3"))
  }

  test("commands: help is 0, unknown or incomplete commands are 1") {
    assert(Perf.run(List("help")) == 0)
    assert(Perf.run(Nil) == 1)
    assert(Perf.run(List("bogus")) == 1)
    assert(Perf.run(List("run")) == 1)
    assert(Perf.run(List("setup", "x.yaml")) == 1) // --out missing
  }

  test("validate --facts-file writes machine-readable facts (what the operator reads)") {
    val planFile = Files.createTempFile("plan", ".yaml")
    Files.write(planFile, """name: facts-demo
                            |baseUrl: http://localhost:1
                            |apis: { list: { request: GET /items }, item: { request: "GET /items/#{id}" } }
                            |setup:
                            |  steps: [ { api: list, extract: { ids: { jsonPath: "$[*]", all: true } } } ]
                            |  share: { idQueue: { from: ids, as: id } }
                            |scenarios:
                            |  - name: sr-status-check
                            |    feeders: [ idQueue ]
                            |    steps: [ { api: item } ]
                            |    load: { profile: [ { atOnceUsers: 1 } ] }
                            |""".stripMargin.getBytes(StandardCharsets.UTF_8))
    val plan = planFile.toString
    val ok = Files.createTempFile("facts", ".json")
    assert(Perf.run(List("validate", plan, "--workers", "2", "--facts-file", ok.toString)) == 0)
    val facts = new String(Files.readAllBytes(ok), StandardCharsets.UTF_8)
    assert(facts.contains("\"valid\":true") && facts.contains("\"hasSetup\":true") && facts.contains("\"sr-status-check\""), facts)

    val bad = Files.createTempFile("bad", ".yaml")
    Files.write(bad, "name: x\nbaseUrl: http://x\nscenarios: []\n".getBytes(StandardCharsets.UTF_8))
    val badFacts = Files.createTempFile("facts", ".json")
    assert(Perf.run(List("validate", bad.toString, "--facts-file", badFacts.toString)) == 1)
    val bf = new String(Files.readAllBytes(badFacts), StandardCharsets.UTF_8)
    assert(bf.contains("\"valid\":false") && bf.contains("at least one scenario"), bf)
  }

  test("validate and run reject a bad plan before any load, with one clear line") {
    val f = Files.createTempFile("bad", ".yaml")
    Files.write(f, "name: x\nbaseUrl: http://x\nscenarios: []\n".getBytes(StandardCharsets.UTF_8))
    assert(Perf.run(List("validate", f.toString)) == 1)
    assert(Perf.run(List("run", f.toString)) == 1)
    assert(Perf.run(List("run", "does/not/exist.yaml")) == 1)
  }
}
