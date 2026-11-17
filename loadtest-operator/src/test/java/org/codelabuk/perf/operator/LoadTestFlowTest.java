package org.codelabuk.perf.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.codelabuk.perf.operator.JobFactory.Role;
import org.codelabuk.perf.operator.LoadTestStatus.Phase;
import org.junit.jupiter.api.Test;

/** The whole lifecycle against an in-memory Kubernetes API; the test plays the cluster's part (Jobs finishing). */
@EnableKubernetesMockClient(crud = true)
class LoadTestFlowTest {

    static final String NS = "perf";
    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    KubernetesClient client;

    private LoadTestFlow flow() {
        return new LoadTestFlow(client, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private LoadTest loadTest(String name) {
        LoadTestSpec spec = new LoadTestSpec();
        spec.image = "registry/labuk-perf-runner:1.0.0";
        spec.plan = new LoadTestSpec.Plan();
        spec.plan.file = "plans/sr-email-status.yaml";
        spec.plan.git = new LoadTestSpec.Git();
        spec.plan.git.repo = "https://git.example.com/perf-tests.git";
        spec.plan.git.tokenSecret = new LoadTestSpec.SecretKey();
        spec.plan.git.tokenSecret.name = "git-creds";
        spec.workers = 3;
        spec.results = new LoadTestSpec.Results();
        spec.results.pvc = "perf-results";
        spec.env = Map.of("BASE_URL", "https://sit.api");
        spec.secretRefs = List.of("perf-db-creds");
        LoadTest lt = new LoadTest();
        lt.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).withUid("1a2b3c4d-0000-0000-0000-000000000000").build());
        lt.setSpec(spec);
        return lt;
    }

    private Job job(LoadTest lt, Role role) {
        return client.batch().v1().jobs().inNamespace(NS).withName(JobFactory.jobName(lt, role)).get();
    }

    /** Plays Kubernetes: marks the Job Complete/Failed and leaves a pod whose container wrote `facts`. */
    private void finish(LoadTest lt, Role role, boolean ok, String facts, int succeeded, int failed) {
        Job j = job(lt, role);
        j.setStatus(new JobStatusBuilder().withSucceeded(succeeded).withFailed(failed)
                .withConditions(new JobConditionBuilder().withType(ok ? "Complete" : "Failed").withStatus("True")
                        .withReason(ok ? null : "BackoffLimitExceeded").build()).build());
        client.batch().v1().jobs().inNamespace(NS).resource(j).updateStatus();
        if (facts != null) {
            client.pods().inNamespace(NS).resource(new PodBuilder()
                    .withNewMetadata().withName(j.getMetadata().getName() + "-pod").withLabels(j.getSpec().getTemplate().getMetadata().getLabels()).endMetadata()
                    .withNewStatus().withContainerStatuses(new ContainerStatusBuilder().withName("perf")
                            .withNewState().withNewTerminated().withExitCode(ok ? 0 : 1).withMessage(facts).endTerminated().endState().build())
                    .endStatus().build()).create();
        }
    }

    private static Map<String, String> env(Container c) {
        return c.getEnv().stream().filter(e -> e.getValue() != null).collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    }

    @Test
    void happyPathWithSetupEndsWithOneVerdict() {
        LoadTest lt = loadTest("orders-peak");
        LoadTestFlow f = flow();

        // 1. validate
        assertTrue(f.step(lt).statusChanged());
        assertEquals(Phase.Validating, lt.getStatus().phase);
        assertEquals("/results/orders-peak-1a2b3c4d", lt.getStatus().runDir);
        Job validate = job(lt, Role.validate);
        assertEquals(List.of("validate", "/tests/plans/sr-email-status.yaml", "--workers", "3", "--facts-file", "/dev/termination-log"),
                validate.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs());
        assertEquals(Phase.Validating, lt.getStatus().phase); // waits while the Job runs
        assertFalse(f.step(lt).statusChanged());

        finish(lt, Role.validate, true, "{\"valid\":true,\"plan\":\"sr-email-status\",\"hasSetup\":true,\"scenarios\":[\"sr-status-check\"]}", 1, 0);
        f.step(lt);
        assertEquals(Phase.Setup, lt.getStatus().phase);
        assertEquals(List.of("sr-status-check"), lt.getStatus().scenarios);
        assertEquals(List.of("setup", "/tests/plans/sr-email-status.yaml", "--out", "/results/orders-peak-1a2b3c4d/shared.json"),
                job(lt, Role.setup).getSpec().getTemplate().getSpec().getContainers().get(0).getArgs());

        // 2. setup done -> workers created with one common start time
        finish(lt, Role.setup, true, null, 1, 0);
        f.step(lt);
        assertEquals(Phase.Starting, lt.getStatus().phase);
        f.step(lt);
        assertEquals(Phase.Running, lt.getStatus().phase);
        assertEquals("2026-11-20T10:01:00Z", lt.getStatus().startAt);
        Job workers = job(lt, Role.workers);
        assertEquals("Indexed", workers.getSpec().getCompletionMode());
        assertEquals(3, workers.getSpec().getCompletions());
        assertEquals(3, workers.getSpec().getParallelism());
        assertEquals(0, workers.getSpec().getBackoffLimit());
        Map<String, String> env = env(workers.getSpec().getTemplate().getSpec().getContainers().get(0));
        assertEquals("2026-11-20T10:01:00Z", env.get("START_AT"));
        assertEquals("3", env.get("WORKER_COUNT"));
        assertEquals("/results/orders-peak-1a2b3c4d/shared.json", env.get("SHARED_IN"));
        assertEquals("/results/orders-peak-1a2b3c4d/logs", env.get("RESULTS_DIR"));
        assertEquals("https://sit.api", env.get("BASE_URL"));

        // 3. workers done -> report -> verdict
        finish(lt, Role.workers, true, null, 3, 0);
        f.step(lt);
        assertEquals(Phase.Aggregating, lt.getStatus().phase);
        assertEquals(3, lt.getStatus().workersSucceeded);
        finish(lt, Role.report, true, "{\"reportBuilt\":true,\"slaPassed\":false,\"exitCode\":2,\"report\":\"/results/orders-peak-1a2b3c4d/report/merged/index.html\"}", 1, 0);
        f.step(lt);
        assertEquals(Phase.Completed, lt.getStatus().phase);
        assertEquals("SLAFailed", lt.getStatus().result);
        assertEquals("/results/orders-peak-1a2b3c4d/report/merged/index.html", lt.getStatus().reportPath);
        assertNotNull(lt.getStatus().finishedAt);

        // finished tests are never touched again
        assertEquals(new LoadTestFlow.Result(false, 0), f.step(lt));
    }

    @Test
    void withoutSetupGoesStraightToWorkers() {
        LoadTest lt = loadTest("no-setup");
        LoadTestFlow f = flow();
        f.step(lt);
        finish(lt, Role.validate, true, "{\"valid\":true,\"plan\":\"p\",\"hasSetup\":false,\"scenarios\":[\"a\"]}", 1, 0);
        f.step(lt);
        f.step(lt);
        assertEquals(Phase.Running, lt.getStatus().phase);
        assertNull(job(lt, Role.setup));
        assertFalse(env(job(lt, Role.workers).getSpec().getTemplate().getSpec().getContainers().get(0)).containsKey("SHARED_IN"));
    }

    @Test
    void invalidPlanFailsWithTheReason() {
        LoadTest lt = loadTest("bad-plan");
        LoadTestFlow f = flow();
        f.step(lt);
        finish(lt, Role.validate, false, "{\"valid\":false,\"message\":\"orders.yaml: scenarios[0].steps[1]: unknown key 'maxAtempts'\"}", 0, 1);
        f.step(lt);
        assertEquals(Phase.Failed, lt.getStatus().phase);
        assertTrue(lt.getStatus().message.contains("unknown key 'maxAtempts'"), lt.getStatus().message);
        assertNull(job(lt, Role.workers));
    }

    @Test
    void failedWorkerFailsTheTest() {
        LoadTest lt = loadTest("worker-crash");
        LoadTestFlow f = flow();
        f.step(lt);
        finish(lt, Role.validate, true, "{\"valid\":true,\"hasSetup\":false}", 1, 0);
        f.step(lt);
        f.step(lt);
        finish(lt, Role.workers, false, null, 2, 1);
        f.step(lt);
        assertEquals(Phase.Failed, lt.getStatus().phase);
        assertEquals("1 of 3 worker(s) failed (BackoffLimitExceeded) - see the workers' pod logs", lt.getStatus().message);
        assertNull(job(lt, Role.report));
    }

    @Test
    void badSpecFailsBeforeCreatingAnything() {
        LoadTest lt = loadTest("no-source");
        lt.getSpec().plan.git = null;
        flow().step(lt);
        assertEquals(Phase.Failed, lt.getStatus().phase);
        assertEquals("spec.plan needs exactly one of: git, configMap, inImage", lt.getStatus().message);
        assertNull(job(lt, Role.validate));
    }

    @Test
    void stepsAreIdempotent() {
        LoadTest lt = loadTest("twice");
        LoadTestFlow f = flow();
        f.step(lt);
        // a restarted operator sees the same status and the existing Job: nothing new is created
        LoadTestStatus copy = lt.getStatus();
        lt.setStatus(null);
        lt.setStatus(copy);
        f.step(lt);
        f.step(lt);
        assertEquals(1, client.batch().v1().jobs().inNamespace(NS).list().getItems().size());
    }

    @Test
    void podsGetTestsSecretsAndSafeDefaults() {
        LoadTest lt = loadTest("details");
        flow().step(lt);
        Job j = job(lt, Role.validate);
        var pod = j.getSpec().getTemplate().getSpec();
        // tests cloned by an init container; the token comes from a Secret, never from the spec
        Container clone = pod.getInitContainers().get(0);
        assertEquals("alpine/git:2.47.2", clone.getImage());
        EnvVar token = clone.getEnv().stream().filter(e -> e.getName().equals("GIT_TOKEN")).findFirst().orElseThrow();
        assertEquals("git-creds", token.getValueFrom().getSecretKeyRef().getName());
        // secrets as environment, results volume, non-root, owned by the LoadTest
        Container perf = pod.getContainers().get(0);
        assertEquals("perf-db-creds", perf.getEnvFrom().get(0).getSecretRef().getName());
        assertTrue(pod.getVolumes().stream().anyMatch(v -> v.getPersistentVolumeClaim() != null && "perf-results".equals(v.getPersistentVolumeClaim().getClaimName())));
        assertTrue(pod.getSecurityContext().getRunAsNonRoot());
        assertEquals("details", j.getMetadata().getOwnerReferences().get(0).getName());
        assertTrue(j.getMetadata().getOwnerReferences().get(0).getController());
        assertEquals("Never", pod.getRestartPolicy());

        // ConfigMap variant: mounted read-only, no init container
        LoadTest cm = loadTest("cm");
        cm.getSpec().plan.git = null;
        cm.getSpec().plan.configMap = "orders-tests";
        flow().step(cm);
        var cmPod = job(cm, Role.validate).getSpec().getTemplate().getSpec();
        assertTrue(cmPod.getInitContainers().isEmpty());
        assertEquals("orders-tests", cmPod.getVolumes().stream().filter(v -> v.getConfigMap() != null).findFirst().orElseThrow().getConfigMap().getName());
    }
}
