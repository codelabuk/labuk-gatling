package org.codelabuk.perf.cli

import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import org.codelabuk.perf.results.LogMerger

/**
 * Merges the simulation.log files of all workers into one run, then lets Gatling build the HTML report
 * and evaluate the SLAs on the combined data.
 *
 *   Merge --input <dir> [--output <dir>] [--name <run-folder>] [--description <text>] [--no-report]
 *
 * --input  : a folder with the workers' logs (*.log directly in it, or simulation.log in sub-folders)
 * --output : results folder to create the merged run in (default: <input>-merged)
 *
 * Exit code: 0 = report built and SLAs met, 2 = SLAs failed, 1 = error.
 */
object Merge {

  def main(args: Array[String]): Unit = sys.exit(run(args.toList))

  def run(args: List[String]): Int = {
    def opt(name: String): Option[String] = args.sliding(2).collectFirst { case List(`name`, v) => v }
    val input = opt("--input").map(Paths.get(_).toAbsolutePath.normalize())
    if (input.isEmpty) {
      System.err.println("usage: Merge --input <dir> [--output <dir>] [--name <run-folder>] [--description <text>] [--no-report]")
      return 1
    }
    val in = input.get
    if (!Files.isDirectory(in)) { System.err.println(s"Merge: input folder not found: $in"); return 1 }

    val logs = LogMerger.findLogs(in)
    if (logs.isEmpty) { System.err.println(s"Merge: no .log files under $in"); return 1 }

    val output = opt("--output").map(Paths.get(_).toAbsolutePath.normalize()).getOrElse(in.resolveSibling(in.getFileName.toString + "-merged"))
    val name = opt("--name").getOrElse("merged-" + LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
    val target = output.resolve(name).resolve("simulation.log")
    if (Files.exists(target)) { System.err.println(s"Merge: $target already exists - pick another --name"); return 1 }

    val summary =
      try LogMerger.merge(logs, target, opt("--description").orElse(Some(s"merged from ${logs.size} worker log(s)")))
      catch {
        case e: java.io.IOException => System.err.println(s"Merge failed: ${e.getMessage}"); return 1
      }
    println(s"Merged ${summary.files} log(s): ${summary.users} users, ${summary.requests} request entries -> $target")
    logs.foreach(l => println(s"  - $l"))

    if (args.contains("--no-report")) 0 else gatlingReportsOnly(output, name)
  }

  /** Runs Gatling's own reports-only mode on the merged run; returns its exit code (2 = SLAs failed). */
  private def gatlingReportsOnly(resultsFolder: Path, runFolder: String): Int = {
    // Gatling.fromArgs returns the status code instead of exiting the JVM; it is package-private in Scala
    // but public in bytecode, so it is called reflectively.
    val module = Class.forName("io.gatling.app.Gatling$").getField("MODULE$").get(null)
    val fromArgs = module.getClass.getMethod("fromArgs", classOf[Array[String]])
    val code = fromArgs.invoke(module, Array("--reports-only", runFolder, "--results-folder", resultsFolder.toString)).asInstanceOf[Int]
    code match {
      case 0 => println("RESULT: report built, all SLAs met")
      case 2 => println("RESULT: report built, SLAs FAILED")
      case c => println(s"RESULT: Gatling reports-only failed (exit code $c)")
    }
    code
  }
}
