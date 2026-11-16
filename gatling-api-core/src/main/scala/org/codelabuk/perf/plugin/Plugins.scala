package org.codelabuk.perf.plugin

import java.util.ServiceLoader

import scala.jdk.CollectionConverters._

import io.gatling.core.structure.ChainBuilder
import org.codelabuk.perf.build.BuildContext
import org.codelabuk.perf.load.Node
import org.codelabuk.perf.model.TestPlan

/**
 * Extension point for new step kinds (e.g. `sql`, `kafka`, `jms`).
 * A module registers implementations in
 * META-INF/services/org.codelabuk.perf.plugin.StepPlugin and they become usable in YAML
 * just by being on the classpath - no change to the core.
 */
trait StepPlugin {
  /** The YAML key that selects this step, e.g. "sql". */
  def kind: String
  /** Other keys allowed next to `kind` in the same step. */
  def extraKeys: Set[String] = Set.empty
  /** Fail fast with node.fail(...) on bad YAML. Called while loading the plan. */
  def validate(node: Node, plan: TestPlan): Unit = ()
  /** Names this step shows up under in the report - lets SLAs such as `api.<name>...` target it. */
  def reportNames(node: Node): Set[String] = Set.empty
  def build(node: Node, ctx: BuildContext): ChainBuilder
}

/** Extension point for new data source types (e.g. `jdbc`). */
trait DataSourcePlugin {
  /** The value of `type:` that selects this plugin, e.g. "jdbc". */
  def kind: String
  def validate(node: Node, plan: TestPlan): Unit = ()
  /** Whether this definition supplies records to virtual users (`feeders:` / `feed:`), or is only used by steps. */
  def isFeeder(node: Node): Boolean = false
  /** Records to hand out to virtual users; called once per worker at start-up, only when [[isFeeder]]. */
  def feederRecords(node: Node, plan: TestPlan): IndexedSeq[Map[String, Any]] = IndexedSeq.empty
}

object Plugins {
  private def load[T](cls: Class[T], key: T => String): Map[String, T] = {
    val all = ServiceLoader.load(cls, Thread.currentThread().getContextClassLoader).asScala.toList
    all.groupBy(key).map {
      case (k, List(single)) => k -> single
      case (k, many) => throw new IllegalStateException(s"Several plugins claim '$k': ${many.map(_.getClass.getName).mkString(", ")}")
    }
  }

  lazy val steps: Map[String, StepPlugin] = load(classOf[StepPlugin], (_: StepPlugin).kind)
  lazy val dataSources: Map[String, DataSourcePlugin] = load(classOf[DataSourcePlugin], (_: DataSourcePlugin).kind)
}
