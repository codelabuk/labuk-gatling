package org.codelabuk.perf.operator;

import java.util.ArrayList;
import java.util.List;

/** What `kubectl get loadtest x -o yaml` shows while and after the test runs. */
public class LoadTestStatus {

    public enum Phase { Validating, Setup, Starting, Running, Aggregating, Completed, Failed }

    public Phase phase;
    public String message;

    /** From validation: plan name, its scenarios, whether it has a setup step to run once. */
    public String plan;
    public List<String> scenarios = new ArrayList<>();
    public Boolean hasSetup;

    /** Folder of this run on the results volume, e.g. /results/orders-peak-1a2b3c4d. */
    public String runDir;
    /** Common start time of all workers (ISO-8601). */
    public String startAt;

    public int workersActive;
    public int workersSucceeded;
    public int workersFailed;

    /** Passed / SLAFailed once the merged report exists. */
    public String result;
    public Boolean slaPassed;
    public String reportPath;

    public String startedAt;
    public String finishedAt;

    public boolean terminal() {
        return phase == Phase.Completed || phase == Phase.Failed;
    }
}
