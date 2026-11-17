package org.codelabuk.perf.operator;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * One distributed load test run:
 * <pre>
 * apiVersion: perf.codelabuk.org/v1alpha1
 * kind: LoadTest
 * spec:
 *   image: registry/labuk-perf-runner:1.0.0
 *   plan: { git: { repo: https://git/perf-tests.git, ref: main }, file: plans/orders.yaml }
 *   workers: 5
 *   results: { pvc: perf-results }
 * </pre>
 * The operator validates the plan, runs its setup once, starts the workers together, then merges their results
 * into one report with one SLA verdict. Create a new LoadTest per run; a finished one is never re-run.
 */
@Group("perf.codelabuk.org")
@Version("v1alpha1")
@ShortNames("lt")
// columns for `kubectl get loadtests`
@AdditionalPrinterColumn(name = "Phase", jsonPath = ".status.phase", type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(name = "Result", jsonPath = ".status.result", type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(name = "Workers", jsonPath = ".spec.workers", type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(name = "Done", jsonPath = ".status.workersSucceeded", type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(name = "Failed", jsonPath = ".status.workersFailed", type = AdditionalPrinterColumn.Type.INTEGER, priority = 1)
@AdditionalPrinterColumn(name = "Message", jsonPath = ".status.message", type = AdditionalPrinterColumn.Type.STRING, priority = 1)
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp", type = AdditionalPrinterColumn.Type.DATE)
public class LoadTest extends CustomResource<LoadTestSpec, LoadTestStatus> implements Namespaced {
}
