package org.codelabuk.perf.runner

import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

class KubeSpec extends AnyFunSuite {

  private val envDefaults = Map("PERF_IMAGE" -> "reg/labuk-perf-runner:1.0.0", "PERF_PVC" -> "perf-results")

  test("submit: a Git-based LoadTest from options and team defaults") {
    val s = Kube.parseSubmit(List("plans/orders-peak.yaml", "--workers", "5", "--git-repo", "https://git/x/perf-tests.git",
      "--env", "BASE_URL=https://sit", "--env", "REGION=EU", "--secret", "perf-secrets", "--scenario", "checkout", "--memory", "4Gi",
      "--metrics", "--name", "orders-peak-1"), envDefaults.get).toOption.get
    val yaml = Kube.loadTestYaml(s)
    Seq("kind: LoadTest", "name: orders-peak-1", "namespace: perf", "image: \"reg/labuk-perf-runner:1.0.0\"", "workers: 5",
      "file: \"plans/orders-peak.yaml\"", "repo: \"https://git/x/perf-tests.git\"", "ref: \"main\"", "BASE_URL: \"https://sit\"",
      "REGION: \"EU\"", "secretRefs: [ \"perf-secrets\" ]", "scenarios: [ \"checkout\" ]", "memory: \"4Gi\"",
      "results: { pvc: \"perf-results\" }", "metrics: { enabled: true }").foreach(part => assert(yaml.contains(part), s"missing $part in\n$yaml"))
  }

  test("submit: a local folder becomes a ConfigMap; the plan path is the file inside it") {
    val dir = Files.createTempDirectory("tests")
    val s = Kube.parseSubmit(List(dir.resolve("sr.yaml").toString, "--from-dir", dir.toString, "--name", "run-2"), envDefaults.get).toOption.get
    val yaml = Kube.loadTestYaml(s)
    assert(yaml.contains("configMap: \"run-2-tests\"") && yaml.contains("file: \"sr.yaml\"") && !yaml.contains("git:"), yaml)
  }

  test("submit: missing or conflicting options are explained") {
    def err(args: String*) = Kube.parseSubmit(args.toList, envDefaults.get).swap.toOption.get
    assert(err("p.yaml").contains("where are the tests?"))
    assert(err("p.yaml", "--git-repo", "r", "--from-dir", ".").contains("not both"))
    assert(err("p.yaml", "--git-repo", "r", "--workers", "0").contains("positive number"))
    assert(err("p.yaml", "--git-repo", "r", "--env", "NOVALUE").contains("NAME=value"))
    assert(Kube.parseSubmit(List("p.yaml", "--git-repo", "r"), _ => None).swap.toOption.get.contains("PERF_IMAGE"))
    // default run name from the plan file + timestamp
    assert(Kube.parseSubmit(List("plans/Orders_Peak.yaml", "--git-repo", "r"), envDefaults.get).toOption.get.name.startsWith("orders-peak-"))
  }

  test("submit --dry-run prints the LoadTest and exits 0 without a cluster") {
    assert(Kube.submit(List("p.yaml", "--git-repo", "r", "--dry-run"), envDefaults.get) == 0)
  }
}
