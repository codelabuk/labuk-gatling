package org.codelabuk.perf.sql

import com.typesafe.scalalogging.StrictLogging
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import org.codelabuk.perf.build.BuildContext
import org.codelabuk.perf.load.Node
import org.codelabuk.perf.model.TestPlan
import org.codelabuk.perf.plugin.{DataSourcePlugin, StepPlugin}

/** The `sql:` step. Registered via META-INF/services, so it is available whenever this module is on the classpath. */
final class SqlStepPlugin extends StepPlugin with StrictLogging {
  override val kind: String = "sql"

  override def validate(node: Node, plan: TestPlan): Unit = { SqlModel.step(node, plan); () }

  override def reportNames(node: Node): Set[String] = Set(SqlModel.stepName(node.get("sql")))

  override def build(node: Node, ctx: BuildContext): ChainBuilder = {
    val step = SqlModel.step(node, ctx.plan)
    val conn = SqlModel.connection(step.datasource, SqlModel.connectionNode(ctx.plan, step.datasource, node))
    ctx.paths.addRequest(ctx.groupPath, step.name) // so SLAs like api.<name>.responseTime.p95 find it
    conn.maxSessions.foreach { max =>
      val total = conn.poolSize * ctx.worker.count
      if (total > max)
        logger.warn(s"jdbc '${conn.name}': ${conn.poolSize} connections x ${ctx.worker.count} worker(s) = $total, above maxSessions $max")
    }
    exec(new SqlActionBuilder(step, conn))
  }
}

/** `type: jdbc` data sources: a connection (`url`) used by sql steps, or a feeder (`query`) giving test data. */
final class JdbcDataSourcePlugin extends DataSourcePlugin {
  override val kind: String = "jdbc"

  override def isFeeder(node: Node): Boolean = SqlModel.isFeeder(node)

  override def validate(node: Node, plan: TestPlan): Unit = {
    val name = node.path.split('.').last
    if (SqlModel.isFeeder(node)) SqlModel.feeder(name, node, plan) else SqlModel.connection(name, node)
    ()
  }

  override def feederRecords(node: Node, plan: TestPlan): IndexedSeq[Map[String, Any]] = {
    val name = node.path.split('.').last
    val f = SqlModel.feeder(name, node, plan)
    val conn = SqlModel.connection(f.connection, SqlModel.connectionNode(plan, f.connection, node))
    val step = SqlStep(s"feeder $name", f.connection, "query", f.query, f.params, Nil, List(SqlExtract("rows", "rows", None, optional = false)),
      None, Int.MaxValue, rollback = false, reportConnectionWait = false)
    val bound = f.params.map(p => SqlExecutor.BoundIn(p.literal.getOrElse(p.expr), p.sqlType))
    val c = Pools.get(conn).dataSource.getConnection
    try SqlExecutor.run(c, step, bound, Nil, conn.fetchSize) match {
      case Right(saved) => saved("rows").asInstanceOf[Vector[Map[String, Any]]]
      case Left(err)    => node.fail(s"feeder query failed: $err")
    }
    finally c.close()
  }
}
