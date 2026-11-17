package org.codelabuk.perf.operator;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.codelabuk.perf.operator.JobFactory.Role;
import org.codelabuk.perf.operator.LoadTestStatus.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lifecycle of one LoadTest, one step per call. Idempotent: every step looks at what already exists, so the
 * operator can restart at any time and simply continue.
 *
 *   Validating -> (Setup) -> Starting -> Running -> Aggregating -> Completed
 *        any step can end in Failed, with the reason in status.message
 */
public final class LoadTestFlow {

    private static final Logger log = LoggerFactory.getLogger(LoadTestFlow.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param requeueSeconds when to look again (0 = done) */
    public record Result(boolean statusChanged, int requeueSeconds) {}

    private final KubernetesClient client;
    private final Clock clock;

    public LoadTestFlow(KubernetesClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public Result step(LoadTest lt) {
        if (lt.getStatus() == null) lt.setStatus(new LoadTestStatus());
        LoadTestStatus st = lt.getStatus();
        if (st.terminal()) return new Result(false, 0);
        String before = snapshot(st);
        try {
            advance(lt, st);
        } catch (RuntimeException e) {
            log.error("LoadTest {}: {}", lt.getMetadata().getName(), e.getMessage(), e);
            fail(st, "operator error: " + e.getMessage());
        }
        return new Result(!before.equals(snapshot(st)), st.terminal() ? 0 : 5);
    }

    private void advance(LoadTest lt, LoadTestStatus st) {
        if (st.phase == null) {
            String problem = JobFactory.specProblem(lt.getSpec());
            if (problem != null) { fail(st, problem); return; }
            st.startedAt = now().toString();
            st.runDir = JobFactory.runDir(lt);
            ensure(lt, Role.validate, () -> JobFactory.validate(lt));
            st.phase = Phase.Validating;
            st.message = "validating the plan";
            return;
        }
        switch (st.phase) {
            case Validating -> onDone(lt, Role.validate, st, () -> {
                JsonNode facts = facts(lt, Role.validate).orElse(null);
                if (facts == null || !facts.path("valid").asBoolean(false)) {
                    fail(st, "plan is invalid: " + (facts != null ? facts.path("message").asText() : "no validation result - see the validate pod's log"));
                    return;
                }
                st.plan = facts.path("plan").asText(null);
                st.hasSetup = facts.path("hasSetup").asBoolean(false);
                st.scenarios = new ArrayList<>();
                facts.path("scenarios").forEach(s -> st.scenarios.add(s.asText()));
                if (st.hasSetup) {
                    ensure(lt, Role.setup, () -> JobFactory.setup(lt, st.runDir));
                    st.phase = Phase.Setup;
                    st.message = "running the plan's setup once";
                } else st.phase = Phase.Starting;
            }, "validation failed - see the validate pod's log");

            case Setup -> onDone(lt, Role.setup, st, () -> st.phase = Phase.Starting, "setup failed - see the setup pod's log");

            case Starting -> {
                Instant startAt = now().plusSeconds(lt.getSpec().startDelaySeconds);
                st.startAt = startAt.toString();
                ensure(lt, Role.workers, () -> JobFactory.workers(lt, st.runDir, st.startAt, Boolean.TRUE.equals(st.hasSetup)));
                st.phase = Phase.Running;
                st.message = lt.getSpec().workers + " worker(s) start together at " + st.startAt;
            }

            case Running -> {
                Job job = job(lt, Role.workers);
                if (job == null) { fail(st, "the workers Job disappeared"); return; }
                var js = job.getStatus();
                if (js != null) {
                    st.workersActive = nz(js.getActive());
                    st.workersSucceeded = nz(js.getSucceeded());
                    st.workersFailed = nz(js.getFailed());
                }
                if (condition(job, "Failed")) {
                    fail(st, st.workersFailed + " of " + lt.getSpec().workers + " worker(s) failed"
                            + reason(job).map(r -> " (" + r + ")").orElse("") + " - see the workers' pod logs");
                } else if (condition(job, "Complete")) {
                    ensure(lt, Role.report, () -> JobFactory.report(lt, st.runDir));
                    st.phase = Phase.Aggregating;
                    st.message = "merging " + lt.getSpec().workers + " worker log(s) into one report";
                }
            }

            case Aggregating -> onDone(lt, Role.report, st, () -> {
                JsonNode facts = facts(lt, Role.report).orElse(null);
                if (facts == null || !facts.path("reportBuilt").asBoolean(false)) {
                    fail(st, "could not build the report - see the report pod's log");
                    return;
                }
                st.slaPassed = facts.path("slaPassed").asBoolean(false);
                st.result = st.slaPassed ? "Passed" : "SLAFailed";
                st.reportPath = facts.path("report").asText(null);
                st.phase = Phase.Completed;
                st.finishedAt = now().toString();
                st.message = st.slaPassed ? "all SLAs met" : "SLAs failed - see the report";
            }, "report failed - see the report pod's log");

            default -> { }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs `next` when the role's Job completed, fails the test when it failed; waits otherwise. */
    private void onDone(LoadTest lt, Role role, LoadTestStatus st, Runnable next, String failure) {
        Job job = job(lt, role);
        if (job == null) { fail(st, "the " + role + " Job disappeared"); return; }
        if (condition(job, "Complete")) next.run();
        else if (condition(job, "Failed")) {
            if (role == Role.validate) next.run(); // the facts explain why the plan is invalid
            else fail(st, failure);
        }
    }

    private void ensure(LoadTest lt, Role role, java.util.function.Supplier<Job> build) {
        if (job(lt, role) == null) {
            client.batch().v1().jobs().inNamespace(lt.getMetadata().getNamespace()).resource(build.get()).create();
            log.info("LoadTest {}: created {} Job", lt.getMetadata().getName(), role);
        }
    }

    private Job job(LoadTest lt, Role role) {
        return client.batch().v1().jobs().inNamespace(lt.getMetadata().getNamespace()).withName(JobFactory.jobName(lt, role)).get();
    }

    private static boolean condition(Job job, String type) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) return false;
        return job.getStatus().getConditions().stream().anyMatch(c -> type.equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private static Optional<String> reason(Job job) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) return Optional.empty();
        return job.getStatus().getConditions().stream().filter(c -> "Failed".equals(c.getType()))
                .map(JobCondition::getReason).filter(r -> r != null && !r.isBlank()).findFirst();
    }

    /** The JSON a perf container wrote to /dev/termination-log (perf validate/report --facts-file). */
    private Optional<JsonNode> facts(LoadTest lt, Role role) {
        List<Pod> pods = client.pods().inNamespace(lt.getMetadata().getNamespace())
                .withLabels(Map.of(JobFactory.LABEL_LOADTEST, lt.getMetadata().getName(), JobFactory.LABEL_ROLE, role.name()))
                .list().getItems();
        for (Pod p : pods) {
            if (p.getStatus() == null || p.getStatus().getContainerStatuses() == null) continue;
            for (ContainerStatus cs : p.getStatus().getContainerStatuses()) {
                if (!"perf".equals(cs.getName()) || cs.getState() == null || cs.getState().getTerminated() == null) continue;
                String msg = cs.getState().getTerminated().getMessage();
                if (msg == null || msg.isBlank()) continue;
                try {
                    return Optional.of(JSON.readTree(msg));
                } catch (Exception e) {
                    // FallbackToLogsOnError: not JSON but the log tail - keep it as the message
                    return Optional.of(JSON.createObjectNode().put("valid", false).put("message", msg.strip()));
                }
            }
        }
        return Optional.empty();
    }

    private void fail(LoadTestStatus st, String message) {
        st.phase = Phase.Failed;
        st.message = message;
        st.finishedAt = now().toString();
    }

    private Instant now() {
        return clock.instant();
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static String snapshot(LoadTestStatus st) {
        try {
            return JSON.writeValueAsString(st);
        } catch (Exception e) {
            return String.valueOf(System.nanoTime());
        }
    }
}
