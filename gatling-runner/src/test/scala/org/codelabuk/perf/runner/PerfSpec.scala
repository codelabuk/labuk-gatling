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

  test("validate and run reject a bad plan before any load, with one clear line") {
    val f = Files.createTempFile("bad", ".yaml")
    Files.write(f, "name: x\nbaseUrl: http://x\nscenarios: []\n".getBytes(StandardCharsets.UTF_8))
    assert(Perf.run(List("validate", f.toString)) == 1)
    assert(Perf.run(List("run", f.toString)) == 1)
    assert(Perf.run(List("run", "does/not/exist.yaml")) == 1)
  }
}
