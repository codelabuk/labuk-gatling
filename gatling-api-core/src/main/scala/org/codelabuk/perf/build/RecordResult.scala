package org.codelabuk.perf.build

import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.util.Clock
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.session.{Expression, Session}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext

/**
 * Writes one non-HTTP entry into the report, e.g. "pollUntil gave up" or "time until status changed".
 * The entry spans from the epoch-millis value stored in `startKey` until now.
 * A KO entry also marks the virtual user as failed (so the enclosing transaction turns KO too).
 */
final class RecordResultBuilder(entryName: String, startKey: String, success: Expression[Boolean], failMessage: String) extends ActionBuilder {
  override def build(ctx: ScenarioContext, next: Action): Action =
    new RecordResult(entryName, startKey, success, failMessage, ctx.coreComponents.statsEngine, ctx.coreComponents.clock, next)
}

final class RecordResult(
    entryName: String,
    startKey: String,
    success: Expression[Boolean],
    failMessage: String,
    val statsEngine: StatsEngine,
    clock: Clock,
    val next: Action
) extends ChainableAction {

  override val name: String = s"record-$entryName"

  override protected def execute(session: Session): Unit = {
    val end = clock.nowMillis
    val start = session.attributes.get(startKey).collect { case l: Long => l }.getOrElse(end)
    val ok = success(session).toOption.getOrElse(false)
    statsEngine.logResponse(session.scenario, session.groups, entryName, start, end, if (ok) OK else KO, None, if (ok) None else Some(failMessage))
    next ! (if (ok) session else session.markAsFailed)
  }
}
