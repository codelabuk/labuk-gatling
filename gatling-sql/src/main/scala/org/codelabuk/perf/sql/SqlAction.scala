package org.codelabuk.perf.sql

import java.util.concurrent.RejectedExecutionException

import scala.util.control.NonFatal

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.util.Clock
import io.gatling.commons.validation._
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.session.el._
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext

final class SqlActionBuilder(step: SqlStep, conn: JdbcConnection) extends ActionBuilder {
  override def build(ctx: ScenarioContext, next: Action): Action =
    new SqlAction(step, Pools.get(conn), ctx.coreComponents.statsEngine, ctx.coreComponents.clock, next)
}

/**
 * Runs one `sql` step for a virtual user:
 *  1. resolves #{...} parameters on the Gatling thread,
 *  2. runs the statement on the connection's own thread pool (JDBC blocks - never on Gatling's threads),
 *  3. records the timing as a request named after the step, then resumes the user on its event loop.
 * The time measured includes waiting for a free connection, as a real application would see it.
 */
final class SqlAction(step: SqlStep, pool: Pool, val statsEngine: StatsEngine, clock: Clock, val next: Action) extends ChainableAction {

  override val name: String = s"sql-${step.name}"

  private val paramExprs: List[Either[(Expression[Any], Option[String]), SqlExecutor.BoundOut]] = step.params.map {
    case SqlParam.In(_, Some(lit), t) => Left(((_: Session) => lit.success, t))
    case SqlParam.In(expr, None, t)   => Left((expr.el[Any], t))
    case SqlParam.Out(t, saveAs)      => Right(SqlExecutor.BoundOut(t, saveAs))
  }
  private val expectedExprs: List[Expression[String]] = step.checks.map(c => if (c.kind == "column") c.expected.el[String] else (_: Session) => c.expected.success)

  override protected def execute(session: Session): Unit = {
    val start = clock.nowMillis
    val resolved: Validation[(List[SqlExecutor.Bound], List[String])] = for {
      bound <- paramExprs.foldLeft[Validation[List[SqlExecutor.Bound]]](Nil.success) {
        case (acc, Left((e, t))) => acc.flatMap(l => e(session).map(v => l :+ SqlExecutor.BoundIn(v, t)))
        case (acc, Right(out))   => acc.map(_ :+ out)
      }
      expected <- expectedExprs.foldLeft[Validation[List[String]]](Nil.success)((acc, e) => acc.flatMap(l => e(session).map(l :+ _)))
    } yield (bound, expected)

    resolved match {
      case Failure(err) => finish(session, start, Left(s"parameters: $err"), None)
      case Success((bound, expected)) =>
        try {
          pool.executor.execute { () =>
            val waitStart = clock.nowMillis
            var connectedAt = waitStart
            val result =
              try {
                val c = pool.dataSource.getConnection
                connectedAt = clock.nowMillis
                try SqlExecutor.run(c, step, bound, expected, pool.conn.fetchSize)
                finally c.close()
              } catch {
                case e: java.sql.SQLException => Left(SqlExecutor.sqlMessage(e))
                case NonFatal(e)              => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
              }
            finish(session, start, result, if (step.reportConnectionWait) Some(waitStart -> connectedAt) else None)
          }
        } catch {
          case _: RejectedExecutionException => finish(session, start, Left("SQL pool is shut down"), None)
        }
    }
  }

  private def finish(session: Session, start: Long, result: Either[String, Map[String, Any]], connectionWait: Option[(Long, Long)]): Unit = {
    val end = clock.nowMillis
    connectionWait.foreach { case (s, e) => statsEngine.logResponse(session.scenario, session.groups, s"${step.name} (connection)", s, e, OK, None, None) }
    val next2 = result match {
      case Right(saved) =>
        statsEngine.logResponse(session.scenario, session.groups, step.name, start, end, OK, None, None)
        session.setAll(saved)
      case Left(err) =>
        statsEngine.logResponse(session.scenario, session.groups, step.name, start, end, KO, None, Some(err))
        session.markAsFailed
    }
    // back onto the virtual user's own event loop before continuing its scenario
    session.eventLoop.execute(() => next ! next2)
  }
}
