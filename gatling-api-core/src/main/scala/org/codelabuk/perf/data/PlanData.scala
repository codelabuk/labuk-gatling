package org.codelabuk.perf.data

import java.nio.file.Path

import org.codelabuk.perf.load.PlanException
import org.codelabuk.perf.model.{DataSourceDef, ShareDef, TestPlan}
import org.codelabuk.perf.plugin.Plugins

/** Loads a plan's data for one worker. No Gatling runtime needed, so the validator uses it too. */
object PlanData {

  /** Every data source of the plan, already sliced for `worker` where splitAcrossWorkers is on. */
  def sources(plan: TestPlan, worker: Worker): Map[String, RecordSource] =
    plan.dataSources.toList.flatMap {
      case (name, DataSourceDef.Csv(_, file, strategy, split, sep)) =>
        val all =
          try CsvReader.read(file, sep)
          catch { case e: IllegalArgumentException => throw new PlanException(s"data source '$name': ${e.getMessage}") }
        Some(name -> new RecordSource(name, if (split) worker.slice(all) else all, strategy))
      case (name, DataSourceDef.Plugin(_, kind, node)) if Plugins.dataSources(kind).isFeeder(node) =>
        val records = Plugins.dataSources(kind).feederRecords(node, plan)
        val strategy = node.optStr("strategy").getOrElse("circular")
        val split = node.optBool("splitAcrossWorkers").getOrElse(true)
        // e.g. a DB query without ORDER BY: sort so every worker splits the same list
        val mine = if (!split) records else worker.slice(if (worker.count > 1) SharedFile.sorted(records).toIndexedSeq else records)
        Some(name -> new RecordSource(name, mine, strategy))
      case _ => None // a plugin definition used only by steps (e.g. a database connection)
    }.toMap

  /** Names usable in `feeders:` / `feed:` - CSV files and feeder-capable plugin data sources. */
  def feederNames(plan: TestPlan): Set[String] =
    plan.dataSources.collect {
      case (n, _: DataSourceDef.Csv)                                                   => n
      case (n, DataSourceDef.Plugin(_, kind, node)) if Plugins.dataSources(kind).isFeeder(node) => n
    }.toSet

  /** Normalises whatever an extraction produced into a list of items. */
  def asItems(value: Any): Seq[Any] = value match {
    case seq: Seq[_]                  => seq
    case col: java.util.Collection[_] => col.toArray.toSeq
    case single                       => Seq(single)
  }

  /**
   * Registers one shared list for this worker. Items are sorted first when the list is split, so every
   * worker slices an identical list even if the API returned it in a different order.
   */
  def registerShare(share: ShareDef, items: Seq[Any], worker: Worker): RecordSource = {
    val ordered = if (share.splitAcrossWorkers && worker.count > 1) SharedFile.sorted(items) else items
    val records = RecordSource.recordsFrom(ordered, share.as)
    val mine = if (share.splitAcrossWorkers) worker.slice(records) else records
    val source = new RecordSource(share.name, mine, share.strategy)
    SharedData.register(source)
    source
  }

  /** Registers all shares of the plan from a file written by mode=setup. */
  def registerSharesFromFile(plan: TestPlan, file: Path, worker: Worker): List[RecordSource] = {
    val shares = plan.setup.map(_.share).getOrElse(Nil)
    if (shares.isEmpty) throw new PlanException(s"shared.in was given but plan '${plan.name}' has no setup.share")
    val lists =
      try SharedFile.read(file)
      catch { case e: IllegalArgumentException => throw new PlanException(e.getMessage) }
    shares.map { share =>
      val items = lists.getOrElse(share.name, throw new PlanException(s"shared.in $file has no list '${share.name}' - found: ${lists.keys.mkString(", ")}"))
      registerShare(share, items, worker)
    }
  }
}
