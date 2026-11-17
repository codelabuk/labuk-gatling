package org.codelabuk.perf.operator;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

/**
 * Hands each LoadTest to {@link LoadTestFlow} and re-checks every few seconds until it finishes.
 * Polling keeps the operator simple; a load test runs for minutes, so 5 s of latency does not matter.
 */
@ControllerConfiguration(name = "loadtest")
public class LoadTestReconciler implements Reconciler<LoadTest> {

    private final Clock clock;

    public LoadTestReconciler() {
        this(Clock.systemUTC());
    }

    LoadTestReconciler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UpdateControl<LoadTest> reconcile(LoadTest loadTest, Context<LoadTest> context) {
        LoadTestFlow.Result r = new LoadTestFlow(context.getClient(), clock).step(loadTest);
        UpdateControl<LoadTest> control = r.statusChanged() ? UpdateControl.patchStatus(loadTest) : UpdateControl.noUpdate();
        return r.requeueSeconds() > 0 ? control.rescheduleAfter(r.requeueSeconds(), TimeUnit.SECONDS) : control;
    }
}
